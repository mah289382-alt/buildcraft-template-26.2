package com.buildcraft.transport.blockentity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;
import java.util.function.Supplier;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.fluids.FluidType;
import net.neoforged.neoforge.transfer.ResourceHandler;
import net.neoforged.neoforge.transfer.fluid.FluidResource;
import net.neoforged.neoforge.transfer.transaction.Transaction;
import net.neoforged.neoforge.transfer.transaction.TransactionContext;
import org.jspecify.annotations.Nullable;

import com.buildcraft.transport.block.FluidPipeBlock;
import com.buildcraft.transport.block.PipeBlock;
import com.buildcraft.transport.pipe.PipeBehaviour;

/**
 * A real, faithful port of {@code PipeFlowFluids} - NOT the earlier single-tank stand-in this class used to be.
 * Real source (confirmed by reading {@code common/buildcraft/transport/pipe/flow/PipeFlowFluids.java} directly,
 * per the user's explicit "read extremely into it" request) gives each pipe SEVEN independent buffers
 * ("{@code Section}"s) - one per {@link Direction} face plus a CENTER section ({@code null} face here) - each with
 * its own {@link Section#amount}, a delayed-insertion ring buffer ({@link Section#incoming}) that rate-limits how
 * much can move through that section per {@link PipeBehaviour#fluidTransferDelay()}-tick window independent of
 * total capacity, and a signed direction-lock cooldown ({@link Section#ticksInDirection}, real 60-tick
 * {@link #DIRECTION_COOLDOWN}) that prevents a side from being used for both input and output in quick succession
 * (a real anti-oscillation mechanism, not this class's old ad-hoc "exclude last fill side" rule). Movement is a
 * real 3-phase per-tick simulation, ported near-exactly: {@link #moveFromPipe} (push out of OUTPUT-locked/neutral
 * face sections into real neighbouring capabilities), {@link #moveFromCenter} (distribute the CENTER section out
 * to eligible output faces, proportionally by available flow rate), {@link #moveToCenter} (pull eligible INPUT
 * faces into the CENTER, proportionally).
 * <p>
 * <b>Real, deliberate behaviour change from this class's own earlier design</b>: a {@link Section}'s capability
 * exposure is now FILL-ONLY, matching real {@code Section.drain(...)} always returning {@code null}/nothing - a
 * fluid pipe can no longer be externally drained via capability (only the pipe's own {@link #moveFromPipe} ever
 * removes fluid from a section to push it elsewhere). The OLD bidirectional design was an explicit, disclosed
 * deviation from source; this is the source-accurate correction.
 * <p>
 * <b>One deviation kept, unchanged from before (still disclosed)</b>: real BuildCraft fluid pipes never actively
 * pull fluid from a neighbour - only a dedicated Wood pipe (MJ-powered) or a Pump/Tank's own active push
 * ({@code FluidUtilBC.pushFluidAround}) moves fluid INTO a pipe from outside. This port's Pump/Tank don't
 * implement that active-push side (a separate, larger undertaking), so every tier here still passively
 * self-serves fluid from an adjacent real source via {@link #pullIn} - otherwise a pipe network would have no way
 * to ever receive fluid from a Pump/Tank at all. Kept exactly as before, just adapted to fill a specific
 * {@link Section} instead of one flat tank.
 */
public class FluidPipeBlockEntity extends BlockEntity {
    private static final int DIRECTION_COOLDOWN = 60;

    /** Ticks for the visual fill-wave to cross one full block, shared with {@code FluidPipeBlockEntityRenderer}
     * (which reads this exact constant rather than keeping its own copy) - the single source of truth for the
     * "how long does the front take to visually cross one pipe" rate that {@link #propagateFlowAnchors} chains
     * across block boundaries. Server-visible on purpose: {@link Section#flowStartTick} is purely cosmetic state
     * (never read by the real simulation), but the CHAINING math that keeps a whole connected run on one shared
     * timeline has to run in {@link #tick} (server-side), so it needs to know the same rate the renderer uses.
     * <p>
     * Reverted back to {@code 40L} (user: "the movement is no longer smooth so revert changes") - a same-day
     * attempt at 2x speed (halving this to {@code 20L}) combined with a separate per-arm rate change to read as
     * too fast/choppy rather than smooth. Left at the original, confirmed-smooth rate; revisit speed separately
     * (on its own, one change at a time) if still wanted later. */
    public static final long VISUAL_CROSS_TICKS = 40L;

    private final PipeBehaviour behaviour;
    private final Map<Direction, Section> faceSections = new EnumMap<>(Direction.class);
    private final Section centerSection = new Section(null);
    private @Nullable FluidResource currentFluid;
    private int currentDelay = 1;

    public FluidPipeBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state, Supplier<PipeBehaviour> behaviourFactory) {
        super(type, pos, state);
        this.behaviour = behaviourFactory.get();
        for (Direction dir : Direction.values()) {
            faceSections.put(dir, new Section(dir));
        }
    }

    public PipeBehaviour getBehaviour() {
        return behaviour;
    }

    public FluidResource getFluidResource() {
        return currentFluid == null ? FluidResource.EMPTY : currentFluid;
    }

    /** Per-section capacity - real {@code capacity = max(BUCKET_VOLUME, transferPerTick*10)}, applied to EVERY
     * section independently (real source's own field name is misleadingly singular - it's a per-section ceiling,
     * not a shared total budget, so a fully-utilised pipe can genuinely hold up to 7x this much at once). */
    private int sectionCapacity() {
        return Math.max(FluidType.BUCKET_VOLUME, behaviour.fluidTransferPerTick() * 10);
    }

    /** 0-100 fill of one section, for the renderer - {@code null} face means CENTER. */
    public int getSectionFillPercent(@Nullable Direction face) {
        int capacity = sectionCapacity();
        if (capacity <= 0) {
            return 0;
        }
        return (int) (100L * sectionFor(face).amount / capacity);
    }

    /** Real {@link Section#ticksInDirection} sign - positive = actively pushing OUT that face this cooldown
     * window, negative = actively receiving IN, 0 = neutral. Drives the renderer's flow-offset animation. */
    public int getDirectionTicks(@Nullable Direction face) {
        return sectionFor(face).ticksInDirection;
    }

    /** The absolute game tick this section's CURRENT continuous flow (in its current direction) began - see
     * {@link Section#markFlowing} - lets the renderer anchor a one-shot "fills in, then stays full" animation to
     * a real starting point instead of an endlessly-repeating cycle. Meaningless (and unused by the renderer)
     * when {@link #getDirectionTicks} is 0. */
    public long getFlowStartTick(@Nullable Direction face) {
        return sectionFor(face).flowStartTick;
    }

    /**
     * The absolute game tick THIS CLIENT first observed this section holding real content ({@code -1} if it
     * isn't observed as holding any right now) - see {@link Section#firstObservedTick}.
     * <p>
     * <b>Real bug fixed (user: "cant flow smoothly if new pipes are added while its flowing... blinks forward
     * like a server-side check")</b>: {@link #getFlowStartTick} alone is the wrong thing to render a fresh reveal
     * from for a BRAND NEW pipe joining an already-running network. It's computed by {@code propagateFlowAnchors}
     * as "the true origin's own start time, plus {@code hops * VISUAL_CROSS_TICKS}" - correct and necessary for
     * an already-converged network (see that method's own javadoc, and the dormancy-survival fix it depends on),
     * but for a pipe that's JUST NOW joining a flow that's already been running far longer than {@code
     * hops * VISUAL_CROSS_TICKS}, that formula lands in the PAST relative to "now" - so the very first frame this
     * new pipe is rendered, {@code elapsed = now - flowStartTick} is ALREADY past {@code CROSS_TICKS}, and it
     * renders as instantly, fully done - a visible pop-in ("blinks forward") instead of a fill-in animation, with
     * no wrong VALUE anywhere, just a value that's honestly already elapsed by the time this client ever sees it.
     * <p>
     * This accessor gives the renderer an independent, PURELY CLIENT-SIDE floor for how far along an animation is
     * allowed to appear: {@code effectiveStart = max(getFlowStartTick(...), getFirstObservedTick(...))}. For an
     * already-established, long-converged section (including one merely resuming after a dormant pause - see
     * {@code resolveFlowStartTick}'s own dormancy-survival fix), the client has ALSO been observing real content
     * there for just as long, so {@link #getFirstObservedTick} is equally ancient and the {@code max} picks
     * whichever authoritative value {@code getFlowStartTick} already correctly provides - completely unaffected,
     * no regression to the existing (working) straight/corner/dormancy behaviour. For a section a client is
     * seeing hold real content for the very FIRST time (a brand new pipe just placed and just connected), this is
     * necessarily "now" - forcing {@code effectiveStart} to be recent, guaranteeing a real, visible fill-in over
     * {@code CROSS_TICKS} instead of a backdated instant-full pop, with zero change needed to the underlying
     * server-authoritative chaining logic at all.
     */
    public long getFirstObservedTick(@Nullable Direction face) {
        return sectionFor(face).firstObservedTick;
    }

    /**
     * Sticky version of {@link #getDirectionTicks} - the last real flow direction this face was moving, {@code
     * -1}/{@code 0}/{@code 1}, that does NOT reset to 0 the moment real per-tick movement genuinely pauses
     * (unlike {@link #getDirectionTicks}, which does, by design, decay to 0 - see {@link Section#markFlowing}).
     * <p>
     * Real bug fixed (user: "it goes smoothly perfectly then after a certain amount of blocks it changes its
     * behaviour to be a different flicker blink type... audit all the code to see if there are multiple
     * behaviour states"): the renderer used to gate BOTH "should this arm render at all" and "which way should
     * it be oriented" on the live {@code ticksInDirection} sign - but real per-tick delivery isn't guaranteed to
     * succeed literally every single tick (proportional splitting across competing branches, capacity/backpressure
     * once a long or heavily-loaded network's downstream sections start filling up), and EVERY tick that a live
     * transfer doesn't happen, {@code ticksInDirection} can read as 0 between successes even while the section is
     * genuinely still full of fluid mid-flow - which the OLD gate rendered as NOTHING AT ALL (see the confirmed,
     * self-documented root cause already written into {@link Section#save}'s comment for the earlier
     * {@code flowStartTick}-sync bug: "What looked like motion was just the render's direction!=0 gate flickering
     * on/off as sync packets arrived irregularly"). This is structurally more likely to happen with distance from
     * the true source (more hops = more opportunities for a single tick's real delivery to miss), which lines up
     * with "smooth near the start, flickers further along a long run." The renderer now gates on real content
     * ({@code amount > 0}) instead, using THIS sticky sign (not the live one) purely to orient which way an arm
     * should visually fill from - a section that's genuinely full doesn't blink out just because one tick's real
     * transfer happened to land on a value of exactly 0.
     */
    public int getLastKnownSign(@Nullable Direction face) {
        return sectionFor(face).lastKnownSign;
    }

    public int getSectionCapacity() {
        return sectionCapacity();
    }

    /** Client-interpolated fill amount for one section - mirrors {@code MinerBlockEntity.getInterpolatedLength}'s
     * lerp-between-last-and-this-tick technique, fed by {@link Section#tickClient}. */
    public float getInterpolatedAmount(@Nullable Direction face, float partialTicks) {
        Section s = sectionFor(face);
        return s.clientAmountLast + (s.clientAmountThis - s.clientAmountLast) * partialTicks;
    }

    private Section sectionFor(@Nullable Direction face) {
        return face == null ? centerSection : faceSections.get(face);
    }

    /** Fluid-pipe parallel to {@code PipeBlockEntity.onWrenchClick} - lets Iron/Wood fluid pipes have their
     * forced facing manually set/cycled, same as their item-pipe counterparts. */
    public boolean onWrenchClick(@Nullable Direction clickedFace) {
        boolean handled = behaviour.onWrenchClick(level, worldPosition, clickedFace);
        if (handled) {
            setChanged();
            if (level != null) {
                syncToClients(level);
                PipeBlock.syncValveState(level, worldPosition, getBlockState(), behaviour);
            }
        }
        return handled;
    }

    public static void tick(Level level, BlockPos pos, BlockState state, FluidPipeBlockEntity be) {
        if (level.isClientSide()) {
            be.centerSection.tickClient();
            for (Section s : be.faceSections.values()) {
                s.tickClient();
            }
            return;
        }
        be.behaviour.fluidTick(level, pos, be);
        PipeBlock.syncValveState(level, pos, state, be.behaviour);

        boolean changed = false;
        if (be.currentFluid != null) {
            int totalFluid = 0;
            boolean canOutput = false;
            for (Section s : be.allSections()) {
                s.currentTime = (s.currentTime + 1) % be.currentDelay;
                s.advanceForMovement();
                totalFluid += s.amount;
                if (s.canOutput()) {
                    canOutput = true;
                }
            }
            if (totalFluid == 0) {
                be.setFluid(null);
                changed = true;
            } else {
                if (canOutput) {
                    changed |= be.moveFromPipe(level, pos);
                }
                changed |= be.moveFromCenter(level, pos);
                changed |= be.moveToCenter();
            }
            for (Section s : be.allSections()) {
                if (s.ticksInDirection > 0) {
                    s.ticksInDirection--;
                    if (s.ticksInDirection == 0) {
                        s.currentSign = 0;
                    }
                } else if (s.ticksInDirection < 0) {
                    s.ticksInDirection++;
                    if (s.ticksInDirection == 0) {
                        s.currentSign = 0;
                    }
                }
            }
        }

        changed |= be.pullIn(level, pos);
        changed |= be.propagateFlowAnchors(level, pos);

        if (changed) {
            be.setChanged();
            be.syncToClients(level);
        }
        if (DEBUG_LOG && changed) {
            be.logSectionState(pos);
        }
    }

    // TEMPORARY diagnostic aid (per direct user request after several rounds of blind visual iteration didn't
    // land) - flip to true, have the user reproduce the issue, then read run/logs/latest.log for real per-tick
    // per-section data instead of guessing further from static code reading alone. Matches this project's own
    // established precedent for exactly this situation (see [[engines-status]] Round 2 item F). Already did its
    // job once (2026-07-26): real log data confirmed section amounts freeze at steady-state, which directly
    // informed the travelling-marker fix in FluidPipeBlockEntityRenderer.
    // Flipped back ON (2026-07-29) - user reports the animation "gets glitchy" the longer a run or the more
    // complex a fork gets, and flickers specifically at a terminal pipe, after several rounds of code-reading-only
    // fixes to FluidPipeBlockEntityRenderer's shared-timeline design didn't fully resolve it. Now also logs
    // flowStartTick/lastKnownSign (the renderer's own timing anchors, not logged by the previous round that only
    // needed amt/inc/dir) so a real per-tick trace can show whether flowStartTick is jumping/drifting for
    // downstream pipes in a long chain, or whether a terminal pipe's sign/amount is genuinely oscillating.
    private static final boolean DEBUG_LOG = true;

    private void logSectionState(BlockPos pos) {
        StringBuilder sb = new StringBuilder();
        sb.append("FluidPipe@").append(pos).append(" fluid=")
                .append(currentFluid == null ? "none" : currentFluid.getFluid())
                .append(" center=").append(sectionDebug(centerSection));
        for (Direction dir : Direction.values()) {
            sb.append(' ').append(dir.getSerializedName()).append('=').append(sectionDebug(faceSections.get(dir)));
        }
        com.buildcraft.BuildCraft.LOGGER.info(sb.toString());
    }

    private static String sectionDebug(Section s) {
        return "[amt=" + s.amount + ",inc=" + s.incomingTotalCache + ",dir=" + s.ticksInDirection
                + ",flowStart=" + s.flowStartTick + ",lastSign=" + s.lastKnownSign + "]";
    }

    private List<Section> allSections() {
        List<Section> all = new ArrayList<>(7);
        all.add(centerSection);
        all.addAll(faceSections.values());
        return all;
    }

    private void setFluid(@Nullable FluidResource fluid) {
        currentFluid = fluid == null || fluid.isEmpty() ? null : fluid;
        currentDelay = Math.max(1, behaviour.fluidTransferDelay());
        for (Section s : allSections()) {
            s.incoming = new int[currentDelay];
            s.incomingTotalCache = 0;
            s.currentTime = 0;
            s.ticksInDirection = 0;
            s.currentSign = 0;
        }
    }

    /**
     * Real {@code moveFromPipe}: for every face section that isn't INPUT-locked, drain up to
     * {@code fluidTransferPerTick()} (simulated first) and try to push it into whatever's on that side - another
     * fluid pipe's own {@link Section#fill} (reached the exact same way as any other tile, matching real source
     * never special-casing a neighbouring pipe) or a real {@code Capabilities.Fluid.BLOCK} handler. A push that
     * lands anything locks that section OUTPUT for {@link #DIRECTION_COOLDOWN} ticks.
     */
    private boolean moveFromPipe(Level level, BlockPos pos) {
        boolean changed = false;
        int rate = behaviour.fluidTransferPerTick();
        for (Direction dir : Direction.values()) {
            Section section = faceSections.get(dir);
            if (!section.canOutput()) {
                continue;
            }
            int maxDrain = section.drainInternal(rate, false);
            if (maxDrain <= 0) {
                continue;
            }
            Set<Direction> allowed = behaviour.filterFluidDestinations(level, pos, null, currentFluid, Set.of(dir));
            if (!allowed.contains(dir)) {
                continue;
            }
            int pushed = pushToNeighbor(level, pos, dir, maxDrain);
            if (pushed > 0) {
                section.drainInternal(pushed, true);
                section.markFlowing(1);
                changed = true;
            }
        }
        return changed;
    }

    private int pushToNeighbor(Level level, BlockPos pos, Direction dir, int amount) {
        BlockPos neighborPos = pos.relative(dir);
        ResourceHandler<FluidResource> handler = level.getCapability(Capabilities.Fluid.BLOCK, neighborPos, dir.getOpposite());
        if (handler == null) {
            return 0;
        }
        try (Transaction tx = Transaction.openRoot()) {
            int inserted = handler.insert(currentFluid, amount, tx);
            if (inserted > 0) {
                tx.commit();
            }
            return inserted;
        }
    }

    /**
     * Real {@code moveFromCenter}: splits the CENTER section's available fluid across every eligible output face
     * (connected, output-capable, has real room) proportionally by flow-rate share, shuffled for fairness across
     * ties - ported near-exactly, including the real "round up to at least 1" rule so a slow trickle still
     * eventually moves rather than perpetually truncating to 0.
     */
    private boolean moveFromCenter(Level level, BlockPos pos) {
        int totalAvailable = centerSection.getMaxDrained(behaviour.fluidTransferPerTick());
        if (totalAvailable < 1) {
            return false;
        }
        int flowRate = behaviour.fluidTransferPerTick();
        List<Direction> real = new ArrayList<>();
        for (Direction dir : Direction.values()) {
            Section section = faceSections.get(dir);
            if (section.canOutput() && section.getMaxFilled(flowRate) > 0 && FluidPipeBlock.isConnectable(level, pos, dir)) {
                real.add(dir);
            }
        }
        if (real.isEmpty()) {
            return false;
        }
        Set<Direction> allowedSet = behaviour.filterFluidDestinations(level, pos, null, currentFluid, Set.copyOf(real));
        real.removeIf(dir -> !allowedSet.contains(dir));
        if (real.isEmpty()) {
            return false;
        }
        Collections.shuffle(real);

        boolean changed = false;
        float min = Math.min(flowRate * real.size(), totalAvailable) / (float) flowRate / real.size();
        for (Direction dir : real) {
            Section section = faceSections.get(dir);
            int available = section.fill(flowRate, false);
            int amountToPush = (int) (available * min);
            if (amountToPush < 1) {
                amountToPush++;
            }
            amountToPush = centerSection.drainInternal(amountToPush, false);
            if (amountToPush <= 0) {
                continue;
            }
            int filled = section.fill(amountToPush, true);
            if (filled > 0) {
                centerSection.drainInternal(filled, true);
                section.markFlowing(1);
                changed = true;
            }
        }
        return changed;
    }

    /**
     * Real {@code moveToCenter}: pulls fluid from every INPUT-capable face proportionally into the CENTER
     * section. Ports real {@code PipeBehaviourVoid.moveFluidToCentre}'s exact mechanic for {@link
     * PipeBehaviour#destroysFluids}: the side sections still drain normally (so a Void pipe's arms visibly fill
     * with incoming fluid) but the drained amount is discarded instead of entering the centre - the fluid
     * disappears exactly at the moment it would reach the middle, not before.
     */
    private boolean moveToCenter() {
        int spaceAvailable = sectionCapacity() - centerSection.amount;
        int flowRate = behaviour.fluidTransferPerTick();
        boolean destroys = behaviour.destroysFluids();
        // A Void pipe (destroys=true) has no real space limit on what it can swallow - it never actually stores
        // anything past the side section (see the discard logic further down) - so the normal centre-capacity
        // gate is skipped entirely for it, matching real source's OnMoveToCentre handler unconditionally
        // discarding fluidEnteringCentre regardless of how "full" the centre nominally looks.
        if (!destroys && (spaceAvailable <= 0 || centerSection.getMaxFilled(flowRate) <= 0)) {
            return false;
        }

        List<Direction> faces = new ArrayList<>(List.of(Direction.values()));
        Collections.shuffle(faces);

        Map<Direction, Integer> inputPerTick = new EnumMap<>(Direction.class);
        int transferInCount = 0;
        for (Direction dir : faces) {
            Section section = faceSections.get(dir);
            int amount = section.canInput() ? section.drainInternal(flowRate, false) : 0;
            inputPerTick.put(dir, amount);
            if (amount > 0) {
                transferInCount++;
            }
        }
        if (transferInCount == 0) {
            return false;
        }

        int left = Math.min(flowRate, Math.max(spaceAvailable, 0));
        float min = Math.min(flowRate * transferInCount, Math.max(spaceAvailable, 0)) / (float) flowRate / transferInCount;
        if (destroys) {
            left = flowRate;
            min = 1f;
        }

        Map<Direction, Integer> leaving = new EnumMap<>(Direction.class);
        for (Direction dir : Direction.values()) {
            int input = inputPerTick.get(dir);
            if (input <= 0) {
                continue;
            }
            int amountToDrain = (int) (input * min);
            if (amountToDrain < 1) {
                amountToDrain++;
            }
            if (amountToDrain > left) {
                amountToDrain = left;
            }
            Section section = faceSections.get(dir);
            int amountToPush = section.drainInternal(amountToDrain, false);
            if (amountToPush > 0) {
                leaving.put(dir, amountToPush);
                left -= amountToPush;
            }
        }

        boolean changed = false;
        for (Direction dir : Direction.values()) {
            Integer amountObj = leaving.get(dir);
            if (amountObj == null || amountObj <= 0) {
                continue;
            }
            int amount = amountObj;
            Section section = faceSections.get(dir);
            section.drainInternal(amount, true);
            section.markFlowing(-1);
            changed = true;
            if (!destroys) {
                centerSection.fill(amount, true);
            }
        }
        return changed;
    }

    /** See class javadoc's "one deviation kept" note - passively self-serves EVERY eligible face section from an
     * adjacent real fluid-source neighbour, every tick, since no machine in this port actively pushes into a pipe
     * yet.
     * <p>
     * <b>Real bug fixed</b>: this used to skip whichever direction last succeeded ({@code lastPullSide}), meant
     * as one-tick fairness across multiple sources - but since a single call only ever fills ONE section (this
     * loop, not a per-tick cursor), that exclusion was never cleared and permanently blacklisted a pipe's only
     * real source after its very first successful pull, ever - a genuinely severe bug (a pipe fed by exactly one
     * Pump/Tank would take on a single trickle and then never receive fluid again), and the direct cause of "still
     * doesn't add to a tank correctly" - most test setups only have one real source, so this fired on literally
     * the first tick. Fixed by dropping the exclusion and every-direction cursor entirely: every eligible face is
     * tried, independently, every tick (matching how {@link #moveFromPipe} already loops every direction
     * unconditionally with no artificial single-success-then-stop limit).
     * <p>
     * Never pulls from another {@link FluidPipeBlockEntity} (those receive fluid via {@link #moveFromPipe}
     * pushing INTO their {@link Section#fill}, matching real source never having a pull path at all). */
    private boolean pullIn(Level level, BlockPos pos) {
        boolean changed = false;
        for (Direction dir : Direction.values()) {
            BlockPos neighborPos = pos.relative(dir);
            if (level.getBlockEntity(neighborPos) instanceof FluidPipeBlockEntity) {
                continue;
            }
            Section section = faceSections.get(dir);
            if (!section.canInput()) {
                continue;
            }
            ResourceHandler<FluidResource> handler = level.getCapability(Capabilities.Fluid.BLOCK, neighborPos, dir.getOpposite());
            if (handler == null) {
                continue;
            }
            int room = section.getMaxFilled(behaviour.fluidTransferPerTick());
            if (room <= 0) {
                continue;
            }
            FluidResource resource = currentFluid != null ? currentFluid : firstNonEmptyResource(handler);
            if (resource == null || resource.isEmpty()) {
                continue;
            }
            try (Transaction tx = Transaction.openRoot()) {
                int simulatedExtract;
                try (Transaction sim = Transaction.open(tx)) {
                    simulatedExtract = handler.extract(resource, Math.min(room, behaviour.fluidTransferPerTick()), sim);
                }
                if (simulatedExtract <= 0) {
                    continue;
                }
                boolean wasEmpty = currentFluid == null;
                if (wasEmpty) {
                    setFluid(resource);
                }
                int filled = section.fill(simulatedExtract, true);
                int actuallyExtracted = handler.extract(resource, filled, tx);
                if (filled > 0 && actuallyExtracted == filled) {
                    tx.commit();
                    section.markFlowing(-1);
                    changed = true;
                } else if (wasEmpty) {
                    // Roll back the speculative setFluid if nothing actually moved and the pipe was empty before.
                    setFluid(null);
                }
            }
        }
        return changed;
    }

    private static @Nullable FluidResource firstNonEmptyResource(ResourceHandler<FluidResource> handler) {
        for (int i = 0; i < handler.size(); i++) {
            FluidResource r = handler.getResource(i);
            if (!r.isEmpty()) {
                return r;
            }
        }
        return null;
    }

    /**
     * The earliest {@link Section#flowStartTick} among this pipe's own faces that genuinely hold fluid ({@code
     * amount > 0}), or empty if this pipe is completely empty. This is the pipe's own view of "when did the
     * front, walked all the way back to wherever it truly originated, first arrive at ME" - the one quantity
     * {@link #propagateFlowAnchors} needs from a neighbour to keep chaining exact instead of approximate.
     * <p>
     * <b>Real bug fixed (user: "if i add additional pipes it reverts back to the flicker jump... instead of
     * smooth like it is when all pipes are there by default")</b>: this used to require {@link
     * Section#ticksInDirection} to be LIVE (nonzero) - but {@code ticksInDirection} decays back to 0 the moment
     * real per-tick movement genuinely stalls (steady-state equilibrium, or a run backed up behind a dead end
     * with nowhere further to go), which can easily happen well before the fluid itself is gone - a dormant,
     * still-full section just sits there with {@code amount > 0} and a perfectly valid, already-converged {@code
     * flowStartTick}, but {@code ticksInDirection == 0}. Extending a backed-up run (adding a pipe that finally
     * gives the dead end somewhere to push into) makes real movement resume, which is a GENUINE local sign
     * transition on the resuming face - {@link Section#markFlowing} correctly stamps a fresh "now" for it - but
     * with the OLD {@code ticksInDirection}-gated version of this method, every OTHER face on the same
     * now-dormant-for-a-while pipe (including the one that should have anchored the resuming face back to the
     * true, long-ago origin) had ALSO gone dormant, so there was nothing valid left to chain from - the whole
     * resumed run reverted to fresh, ungrounded timestamps, i.e. exactly the pre-chaining flicker/jump behaviour.
     * {@code amount > 0} survives dormancy completely intact (only {@code ticksInDirection}/{@code currentSign}
     * decay - {@link Section#flowStartTick} itself is never touched by the decay loop), so this now correctly
     * keeps finding the true original origin through however long a dormant stretch, ready the instant movement
     * (and rendering, which is separately gated on live direction) resumes.
     * <p>
     * <b>Second real bug found via debug logging and fixed (2026-07-29, same category as {@link
     * #resolveFlowStartTick}'s own sign-blind-recursion fix, missed the first time round)</b>: real log data
     * showed a pipe with ONLY an active OUTPUT face and no genuine input at all (its centre draining pre-existing
     * content out with nothing currently feeding it - a real, ordinary scenario, not an edge case) had that
     * SAME output face's {@code flowStartTick} climb by {@code VISUAL_CROSS_TICKS} every tick for 400+
     * consecutive ticks straight, never settling. Root cause: this method only checked {@code amount > 0}, with
     * no sign check - so with no genuine input face active, it found the OUTPUT face ITSELF as the "earliest"
     * candidate (its {@code amount} is real and positive too), and {@link #propagateFlowAnchors}'s intra-pipe
     * relay then fed that value straight back into the SAME face plus {@code VISUAL_CROSS_TICKS} - a genuine
     * self-referential feedback loop, climbing forever with no real input to anchor it. Fixed the same way as
     * {@link #resolveFlowStartTick}: only a face with {@code lastKnownSign < 0} (a genuine, sticky, real input)
     * can be a candidate here - an output face can never be "where this pipe's content came from," even for
     * itself.
     */
    public OptionalLong earliestFaceFlowStartTick() {
        long earliest = Long.MAX_VALUE;
        for (Direction dir : Direction.values()) {
            Section face = faceSections.get(dir);
            if (face.amount > 0 && face.lastKnownSign < 0 && face.flowStartTick < earliest) {
                earliest = face.flowStartTick;
            }
        }
        return earliest == Long.MAX_VALUE ? OptionalLong.empty() : OptionalLong.of(earliest);
    }

    /**
     * Resolves this pipe's own correct {@code flowStartTick} by walking backward through connected, fluid-
     * holding {@link FluidPipeBlockEntity} neighbours in ONE pass, all the way to the true source, instead of
     * trusting each pipe's own already-stored value (which {@link #propagateFlowAnchors}'s old per-tick
     * relaxation could take up to one real game tick PER HOP to fully settle onto).
     * <p>
     * <b>Real, severe bug fixed (user: "something wrong it lags like crazy to open world and then everything is
     * gone")</b>: the first version of this method recursed into ANY neighbour reachable through a face with
     * {@code amount > 0}, with no restriction on direction - but in a genuinely flowing pipe, BOTH the face
     * receiving fluid AND the face pushing it onward have {@code amount > 0}, so pipe A recursing into B would
     * immediately have B recurse right back into A (A's own face touching B is just as full), which recursed
     * into B again, and so on, bounded only by a 256-deep cap - for EVERY active input face, on EVERY pipe,
     * EVERY tick. On any real network this is either a catastrophic tick-time blowup (the reported lag/hang) or
     * an outright {@code StackOverflowError} - and if a server watchdog force-kills the process mid-tick or
     * mid-save as a result, that can plausibly cost real, unrecoverable world state (the reported "everything is
     * gone"). Real fix: track the set of block positions already visited on THIS walk and refuse to re-enter one
     * ({@code visited.add(...)} returning {@code false}) - this guarantees each pipe is visited AT MOST ONCE per
     * top-level call, giving a hard bound of "the size of the connected network," not an exponential one, and
     * makes termination unconditional (no reliance on a depth counter racing against branching factor) for ANY
     * topology - a straight line, a branching tree, or a genuine physical ring of pipes.
     * <p>
     * Pure read - never mutates any {@link Section} (including on neighbours), so it's safe to call from
     * anywhere, any number of times, in any order, with no cross-instance ordering hazard.
     * <p>
     * <b>Real bug found via debug logging and fixed (user: "the longer it goes in a pipe or more complex turns
     * and forks the more glitchy it gets")</b>: real log data showed a face's own {@code flowStart} climbing by
     * exactly {@code VISUAL_CROSS_TICKS} EVERY tick for 20+ consecutive ticks (never settling), even though {@code
     * ticksInDirection} stayed rock-steady the whole time (no real transition happening at all) - the timeline
     * anchor itself was a moving target. Root cause: this loop only checked {@code face.amount > 0}, with NO check
     * on the face's SIGN - so it recursed through OUTPUT faces (and sideways faces at a junction) just as readily
     * as genuine INPUT ones, letting the "walk backward to the true origin" wander downstream or sideways through
     * the network instead of strictly backward. At a junction with multiple faces reciprocally reading each
     * other's just-mutated {@code flowStartTick} every tick, that produces exactly this kind of monotonic drift
     * instead of a stable, correct-on-first-read value - and it gets worse with more forks/hops since there are
     * more alternate (wrong) paths for the walk to wander through. Fixed by requiring {@code lastKnownSign < 0}
     * (the sticky, real-server-side input signal - not the live {@code currentSign}, for the same "don't lose a
     * genuine input just because of a one-tick live gap" reason the renderer's own gates were switched to sticky
     * signals earlier this session) - a face can only ever be considered a candidate "where did this come from"
     * source if it's actually known to be an input, never an output or neutral face.
     */
    private OptionalLong resolveFlowStartTick(Set<BlockPos> visited) {
        if (!visited.add(worldPosition)) {
            return OptionalLong.empty();
        }
        long best = Long.MAX_VALUE;
        for (Direction dir : Direction.values()) {
            Section face = faceSections.get(dir);
            if (face.amount <= 0 || face.lastKnownSign >= 0) {
                continue;
            }
            long candidate = face.flowStartTick;
            if (level != null && level.getBlockEntity(worldPosition.relative(dir)) instanceof FluidPipeBlockEntity neighbor) {
                OptionalLong upstream = neighbor.resolveFlowStartTick(visited);
                if (upstream.isPresent()) {
                    candidate = upstream.getAsLong() + VISUAL_CROSS_TICKS;
                }
            }
            if (candidate < best) {
                best = candidate;
            }
        }
        return best == Long.MAX_VALUE ? OptionalLong.empty() : OptionalLong.of(best);
    }

    /**
     * Real structural fix for "each pipe animates independently, causing a visible restart at every pipe-to-pipe
     * boundary" (user: "unlike item pipes with fluid pipes all pipes should somehow combine to act as one pipe...
     * currently ur treating the animation as a transition from 1 pipe to the next"). {@link Section#markFlowing}
     * only knows about its OWN local transition and stamps "now" with zero cross-block awareness - a straight run
     * of N connected+flowing pipes independently produced N different {@link Section#flowStartTick} values, each
     * restarting its own fill-in the instant flow reached it.
     * <p>
     * Why the earlier "adopt the neighbour's raw timestamp if it's older" version (see memory) was still only an
     * APPROXIMATION: it copied a neighbour's OUTPUT-face stamp verbatim, but that stamp is set by
     * {@link #moveFromPipe}'s real per-tick simulation timing (delayed-insertion buffers, {@code
     * DIRECTION_COOLDOWN}), which has NO fixed relationship to the purely-cosmetic {@code
     * FluidPipeBlockEntityRenderer}'s visual wave speed ({@link #VISUAL_CROSS_TICKS} per block) - so even with a
     * synced START time, a downstream pipe's wave could still start growing before the upstream pipe's own wave
     * had visually finished crossing it, an inconsistent rate, not a true single timeline.
     * <p>
     * <b>Second real bug fixed, same user follow-up ("make it act the exact same no matter how many pipes are
     * left or how far its gone")</b>: this METHOD derives every downstream face's {@link Section#flowStartTick}
     * exactly, by pure addition from the true origin, but it originally did so by reading each neighbour's own
     * ALREADY-COMMITTED value ({@code earliestFaceFlowStartTick()}) - which, for a long run, only becomes fully
     * correct gradually, one hop further per real game tick (since that neighbour's own value depended on ITS
     * neighbour's, and so on) - so a pipe far down a long run could visibly still be "catching up" for several
     * ticks, exactly the length-dependent slowdown reported. Now uses {@link #resolveFlowStartTick} instead -
     * a one-pass recursive walk all the way back to the true source, computed fresh every call rather than
     * trusting anyone's possibly-still-converging cached value - so the result is exactly correct on the very
     * first tick it's asked, regardless of chain length.
     * <p>
     * The exact-addition idea itself is the same trick {@code TravellingItem} uses for smooth cross-block motion
     * (position as a pure function of absolute time), just applied to a moving FRONT instead of a moving POINT -
     * the actual "act as one pipe" behaviour, without literally merging block entities the way {@code Tank} does
     * (fluid pipes branch, so a literal merge doesn't fit as cleanly as it does for Tank's simple vertical
     * stacking).
     * <p>
     * Two passes: (1) across a block boundary, an active INPUT face is set to {@code
     * neighbour.resolveFlowStartTick(visited) + VISUAL_CROSS_TICKS} whenever the neighbour has ANY established
     * front to resolve back to a true source (see that method's own javadoc for why this survives dormancy, not
     * just live movement, and for the visited-set that replaced an earlier, dangerously unbounded version) - if
     * it doesn't (the neighbour IS the true origin, e.g. fed directly by
     * a Pump/Tank, not another pipe), this face's own locally-stamped {@link Section#markFlowing} value is left
     * untouched, since that raw stamp already correctly marks the true start of the whole chain; (2) within this
     * pipe, relay {@code earliestFaceFlowStartTick() + VISUAL_CROSS_TICKS} straight onto every active OUTPUT face
     * - matches {@code renderArmWave}'s standalone-branch-output origin ("when did fluid become available at MY
     * centre for this branch"), and by running AFTER (1) this tick, correctly reflects whatever this pipe's own
     * input faces were JUST resolved to, with no cross-block recursion needed for this half (it only looks at
     * this same pipe's own faces).
     */
    private boolean propagateFlowAnchors(Level level, BlockPos pos) {
        boolean changed = false;

        for (Direction dir : Direction.values()) {
            Section face = faceSections.get(dir);
            if (face.ticksInDirection >= 0) {
                continue;
            }
            if (!(level.getBlockEntity(pos.relative(dir)) instanceof FluidPipeBlockEntity neighbor)) {
                continue;
            }
            Set<BlockPos> visited = new HashSet<>();
            visited.add(pos);
            OptionalLong neighborResolved = neighbor.resolveFlowStartTick(visited);
            if (neighborResolved.isEmpty()) {
                continue;
            }
            long candidate = neighborResolved.getAsLong() + VISUAL_CROSS_TICKS;
            if (candidate != face.flowStartTick) {
                face.flowStartTick = candidate;
                changed = true;
            }
        }

        OptionalLong myEarliest = earliestFaceFlowStartTick();
        if (myEarliest.isPresent()) {
            long throughTick = myEarliest.getAsLong() + VISUAL_CROSS_TICKS;
            for (Direction dir : Direction.values()) {
                Section face = faceSections.get(dir);
                if (face.ticksInDirection > 0 && face.flowStartTick != throughTick) {
                    face.flowStartTick = throughTick;
                    changed = true;
                }
            }
        }
        return changed;
    }

    /** Exposes each face's own {@link Section} as a real {@code Capabilities.Fluid.BLOCK} target - FILL-ONLY
     * (see class javadoc), matching real {@code Section} always returning {@code null}/0 for any drain attempt. */
    public ResourceHandler<FluidResource> getFluidHandler(@Nullable Direction side) {
        return new SidedFluidHandler(side);
    }

    private final class SidedFluidHandler implements ResourceHandler<FluidResource> {
        private final Section section;

        SidedFluidHandler(@Nullable Direction side) {
            this.section = sectionFor(side);
        }

        @Override
        public int size() {
            return 1;
        }

        @Override
        public FluidResource getResource(int index) {
            return currentFluid == null ? FluidResource.EMPTY : currentFluid;
        }

        @Override
        public long getAmountAsLong(int index) {
            return section.amount;
        }

        @Override
        public long getCapacityAsLong(int index, FluidResource resource) {
            return sectionCapacity();
        }

        @Override
        public boolean isValid(int index, FluidResource resource) {
            return currentFluid == null || currentFluid.equals(resource);
        }

        @Override
        public int insert(int index, FluidResource resource, int amount, TransactionContext transaction) {
            return doFill(resource, amount, transaction);
        }

        @Override
        public int extract(int index, FluidResource resource, int amount, TransactionContext transaction) {
            return 0;
        }

        private int doFill(FluidResource resource, int amount, TransactionContext transaction) {
            if (resource.isEmpty() || amount <= 0) {
                return 0;
            }
            if (!section.canInput() || section.face != null && !FluidPipeBlock.isConnectable(level, worldPosition, section.face)) {
                return 0;
            }
            if (currentFluid != null && !currentFluid.equals(resource)) {
                return 0;
            }
            if (currentFluid == null) {
                setFluid(resource);
            }
            int filled = section.fill(amount, true);
            if (filled > 0) {
                section.markFlowing(-1);
            }
            return filled;
        }
    }

    private void syncToClients(Level level) {
        BlockState state = getBlockState();
        level.sendBlockUpdated(worldPosition, state, state, 2);
    }

    @Override
    public @Nullable Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        return saveCustomOnly(registries);
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        output.store("currentFluid", FluidResource.OPTIONAL_CODEC, currentFluid == null ? FluidResource.EMPTY : currentFluid);
        centerSection.save(output.child("center"));
        for (Direction dir : Direction.values()) {
            faceSections.get(dir).save(output.child(dir.getSerializedName()));
        }
        behaviour.save(output.child("behaviour"));
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        currentFluid = input.read("currentFluid", FluidResource.OPTIONAL_CODEC).filter(r -> !r.isEmpty()).orElse(null);
        currentDelay = Math.max(1, behaviour.fluidTransferDelay());
        input.child("center").ifPresent(centerSection::load);
        for (Direction dir : Direction.values()) {
            input.child(dir.getSerializedName()).ifPresent(faceSections.get(dir)::load);
        }
        input.child("behaviour").ifPresent(behaviour::load);
    }

    /** One of a pipe's 7 real buffers ({@code null} {@link #face} = CENTER) - ports real {@code
     * PipeFlowFluids.Section} directly: {@link #incoming} is a delayed-insertion ring buffer (one slot per tick
     * of {@link #currentDelay}) that rate-limits movement independent of {@link #amount}/capacity, and {@link
     * #ticksInDirection} is the real signed direction lock (negative = input-locked, positive = output-locked,
     * decaying toward 0 by 1/tick - see {@link #tick}). */
    private final class Section {
        final @Nullable Direction face;
        int amount = 0;
        int[] incoming = new int[1];
        int incomingTotalCache = 0;
        int currentTime = 0;
        int ticksInDirection = 0;

        // Client-only interpolation (not persisted) - mirrors real source's clientAmountThis/Last, driving the
        // renderer's per-section fill height. No flow-offset/animation field any more - see
        // FluidPipeBlockEntityRenderer's class javadoc for why manual flow animation was dropped entirely after
        // two different approaches both had real, user-reported visual problems.
        int clientAmountLast, clientAmountThis;

        /** Client-only, not persisted (see {@link #tickClient}) - the absolute game tick THIS CLIENT first
         * observed this section holding real content ({@code -1} = not currently observed). Exists purely to cap
         * the renderer's reveal animation - see {@code FluidPipeBlockEntityRenderer}'s "pipes added while
         * flowing" fix for why {@link #flowStartTick} alone isn't safe to render from directly. */
        long firstObservedTick = -1L;

        /** Client-only, not persisted - whether {@link #tickClient} has ever run for THIS Section instance yet.
         * See the real "resets to not filled" bug fixed in {@link #tickClient}. */
        boolean everTickedClient = false;

        /** Not persisted (purely cosmetic, see {@link #markFlowing}) - the absolute game tick this section's
         * CURRENT continuous flow began, and the sign ({@code -1}/{@code 0}/{@code 1}) that flow started as. */
        long flowStartTick;
        int currentSign;
        /** Sticky version of {@link #currentSign} - set on every {@link #markFlowing} call and NEVER reset back
         * to 0 by the decay loop (unlike {@link #currentSign}, which deliberately DOES reset so {@link
         * #markFlowing} can tell a genuine restart from a mere continuation - see class javadoc's "flicker after
         * a certain amount of blocks" bug for why the renderer needs a signal that survives that reset). */
        int lastKnownSign;

        Section(@Nullable Direction face) {
            this.face = face;
        }

        /** Real bug fixed (user asked to remove the fill-then-drain CYCLE entirely - "just let it fill no
         * reset"): the renderer needs to know WHEN a section's current flow began (not just its current sign,
         * which {@link #ticksInDirection} already reports) so it can animate a one-shot "fills in, then stays
         * full" instead of endlessly repeating. Records {@link #flowStartTick} only on a genuine transition
         * (was neutral, or was flowing the OPPOSITE way) - repeated same-direction calls while already flowing
         * (the normal case, called nearly every tick while a pipe is actively moving fluid) do NOT reset it, so
         * the fill-in animation only plays once per continuous flow episode, not every single tick. {@link
         * #currentSign} is reset to 0 whenever {@link #ticksInDirection} decays back to exactly 0 (see {@link
         * #tick}), so a later restart in the SAME direction still correctly counts as a fresh episode.
         * <p>
         * <b>Real root cause found and fixed (2026-07-29) for "save the world and reload, the water renders
         * gone... like I just placed the pipes again"</b>: {@link #currentSign} was NEVER included in {@link
         * #save}/{@link #load} - so after ANY reload (world load or even a routine network re-sync), every
         * section's {@code currentSign} snapped back to its Java default (0), regardless of what it genuinely was
         * before. The very next tick a STILL-actively-flowing face calls this method again with its real,
         * unchanged sign (e.g. {@code -1}, still receiving from the same source it always was), the check above
         * ({@code currentSign(0) != sign(-1)}) reads as a GENUINE transition - because the post-reload {@code 0}
         * looks identical to a real fresh start - and {@link #flowStartTick} gets wrongly stamped to "now",
         * overwriting the correctly-loaded, correctly-aged value with a fake fresh one. This exactly explains BOTH
         * halves of the report: it looks like "just placed again" because the reveal genuinely restarts from a
         * real code path (not a rendering illusion), and pipes closer to the source (continuously, successfully
         * transferring nearly every tick) hit this on literally the FIRST tick after reload, while pipes further
         * down (subject to more per-tick rate-limiting/backpressure, not always succeeding on any given tick)
         * escape it for however many ticks pass before their own next successful transfer - matching "further
         * down still retain water" exactly. Fixed by persisting {@link #currentSign} same as {@link #lastKnownSign}
         * - a reload now correctly recognises "this is the SAME sign as before, not a new episode" immediately. */
        void markFlowing(int sign) {
            if (sign != 0 && currentSign != sign) {
                flowStartTick = level == null ? 0L : level.getGameTime();
            }
            currentSign = sign;
            if (sign != 0) {
                lastKnownSign = sign;
            }
            ticksInDirection = sign * DIRECTION_COOLDOWN;
        }

        boolean canOutput() {
            return ticksInDirection >= 0;
        }

        boolean canInput() {
            return ticksInDirection <= 0;
        }

        int getMaxFilled(int transferPerTick) {
            int availableTotal = sectionCapacity() - amount;
            int availableThisTick = transferPerTick - incoming[currentTime];
            return Math.min(availableTotal, availableThisTick);
        }

        int getMaxDrained(int transferPerTick) {
            return Math.min(amount - incomingTotalCache, transferPerTick);
        }

        int fill(int maxFill, boolean doFill) {
            int amountToFill = Math.min(getMaxFilled(behaviour.fluidTransferPerTick()), maxFill);
            if (amountToFill <= 0) {
                return 0;
            }
            if (doFill) {
                incoming[currentTime] += amountToFill;
                incomingTotalCache += amountToFill;
                amount += amountToFill;
            }
            return amountToFill;
        }

        int drainInternal(int maxDrain, boolean doDrain) {
            maxDrain = Math.min(maxDrain, getMaxDrained(behaviour.fluidTransferPerTick()));
            if (maxDrain <= 0) {
                return 0;
            }
            if (doDrain) {
                amount -= maxDrain;
            }
            return maxDrain;
        }

        void advanceForMovement() {
            incomingTotalCache -= incoming[currentTime];
            incoming[currentTime] = 0;
        }

        /** Real bug fixed (user-reported "teleporting/flickering"): this used to ease {@code clientAmountThis}
         * toward {@code amount} by a quarter of the remaining distance per tick - a SECOND smoothing layer
         * stacked on top of the real per-tick rate-limiting already in {@link #fill}/{@link #drainInternal},
         * fighting it: the real engine already changes {@code amount} in small, correctly-paced per-tick steps
         * (that IS the real animation), so easing on top of an already-paced value only adds lag and can make a
         * big already-real jump (e.g. after several rate-limited ticks with no visible change, followed by one
         * that finally clears the incoming-delay window) look like it teleport-then-snaps into place over a
         * couple of frames instead of reading as one clean, real transition. Ports {@code MinerBlockEntity}'s own
         * PROVEN client-smoothing pattern instead (confirmed working, used by the Quarry drill and the Mining
         * Well/Pump shaft beam already in this project): snapshot the REAL current value directly, every client
         * tick, with NO manual easing - all frame-to-frame smoothness comes from lerping between this tick's and
         * last tick's snapshot via {@code partialTicks} at render time, exactly like {@code
         * PipeBlockEntity.TravellingItem}'s own absolute-time render-position technique (a pure function of two
         * known ticks, never an accumulator that can drift or double-smooth). */
        /**
         * <b>Real bug found via user report + log comparison (user: "the water resets to not filled... didnt
         * save in the world") and fixed (2026-07-29)</b>: confirmed via a real before/after log diff across a
         * world save+reload that the underlying {@link #amount} genuinely DOES persist correctly (e.g. a section
         * logged {@code amt=932} right before save, {@code amt=940} on the very next tick after reload - real,
         * continued data, nothing lost). The visual "resets to not filled" was the RENDER treating an
         * already-fully-established network as brand new: {@link #firstObservedTick} is deliberately never
         * persisted (see its own javadoc - it exists to catch a genuinely NEW pipe joining an already-flowing
         * network), but on a full world/chunk reload EVERY section becomes newly-observed by the client
         * simultaneously, at the exact same instant its already-correct, already-aged {@link #flowStartTick} gets
         * loaded too - {@code effectiveFlowStart = max(flowStart, firstObserved)} then picked the freshly-stamped
         * {@code firstObserved} (≈"now") over the correctly-aged {@code flowStart}, forcing the WHOLE network to
         * replay its ~2s fill-in reveal from empty on every reload, even though nothing was actually empty.
         * <p>
         * Fixed by distinguishing "this client-side {@link Section} object was just created because the world/
         * chunk (re)loaded" (should trust the persisted {@link #flowStartTick} as-is, no fresh reveal) from "this
         * section genuinely just started receiving mid-session" (should still get the fresh-reveal floor, per the
         * original "pipes added while flowing" fix). {@link #everTickedClient} makes that distinction: on the
         * FIRST ever client tick for this instance, if it's already holding content, {@link #firstObservedTick}
         * is seeded to {@link #flowStartTick} itself (so {@code max()} is a no-op and the real, correctly-aged
         * value wins) instead of "now"; every SUBSEQUENT genuine 0-to-positive transition still stamps "now" as
         * before, unchanged.
         */
        void tickClient() {
            clientAmountLast = clientAmountThis;
            clientAmountThis = amount;
            if (amount <= 0) {
                firstObservedTick = -1L;
            } else if (firstObservedTick < 0L) {
                firstObservedTick = everTickedClient ? (level == null ? 0L : level.getGameTime()) : flowStartTick;
            }
            everTickedClient = true;
        }

        void save(ValueOutput output) {
            output.putInt("amount", amount);
            output.putInt("ticksInDirection", ticksInDirection);
            output.putIntArray("incoming", incoming);
            // Real bug fixed (user-reported "blink moving instead of smooth"): flowStartTick was never included
            // here, so it was NEVER synced to the client at all - the client's own copy stayed frozen at its
            // Java default (0) forever, meaning the renderer's "elapsed = clientTime - flowStartTick" was always
            // some huge, ever-growing number, pinning headFrac at 1 (always "already fully filled") - no sliding
            // fill animation was EVER actually happening. What looked like motion was just the render's
            // direction!=0 gate flickering on/off as sync packets arrived irregularly. Since this same
            // getUpdateTag/saveCustomOnly path IS the network sync (not just world-save), adding it here fixes
            // both at once.
            output.putLong("flowStartTick", flowStartTick);
            output.putInt("lastKnownSign", lastKnownSign);
            // Real bug fixed (user: "save the world and reload, the water renders gone... like I just placed the
            // pipes again") - see markFlowing's own javadoc for the full mechanism. currentSign must survive a
            // reload/re-sync so an already-flowing face isn't mistaken for a brand new one on the next tick.
            output.putInt("currentSign", currentSign);
        }

        void load(ValueInput input) {
            amount = input.getIntOr("amount", 0);
            ticksInDirection = input.getIntOr("ticksInDirection", 0);
            incoming = input.getIntArray("incoming").orElse(new int[currentDelay]);
            incomingTotalCache = 0;
            for (int v : incoming) {
                incomingTotalCache += v;
            }
            flowStartTick = input.getLongOr("flowStartTick", 0L);
            lastKnownSign = input.getIntOr("lastKnownSign", 0);
            currentSign = input.getIntOr("currentSign", 0);
        }
    }
}
