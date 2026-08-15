package com.buildcraft.builders.blockentity;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

import net.neoforged.neoforge.capabilities.BlockCapabilityCache;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.transfer.ResourceHandler;
import net.neoforged.neoforge.transfer.energy.EnergyHandler;
import net.neoforged.neoforge.transfer.energy.SimpleEnergyHandler;
import net.neoforged.neoforge.transfer.item.ItemResource;
import net.neoforged.neoforge.transfer.transaction.Transaction;

import com.buildcraft.BuildCraft;
import com.buildcraft.Config;
import com.buildcraft.builders.BuildersContent;
import com.buildcraft.builders.block.FrameBlock;
import com.buildcraft.builders.block.QuarryBlock;
import com.buildcraft.transport.pipe.PipeConnectable;

/**
 * Mines out a rectangular area below/around the block it's placed against, one block at a time, powered by FE.
 * <p>
 * Ports the original's real state machine as closely as the FE/NeoForge context allows: a single
 * {@link MiningTask} runs at a time (breaking a block, placing a Frame block, or moving the drill), each with
 * its own FE cost, fed from the energy buffer at a capped rate per tick (see {@link #getMaxDrawThisTick()}).
 * The interior is walked with a {@link QuarryBoxIterator} - a faithful port of the original's
 * {@code BoxIterator}: a randomized-direction, zig-zagging (boustrophedon) sweep of each Y layer from the top
 * of the mining box down, so the drill never has to double back across a whole layer it already covered.
 */
public class QuarryBlockEntity extends BlockEntity implements PipeConnectable {
    public static boolean DEBUG_LOG = true;
    private long debugFeAccum = 0;
    private int debugBlocksAccum = 0;

    private static final int MAX_INSERT = 10_000;
    private static final int MAX_EXTRACT = 10_000;

    private final SimpleEnergyHandler energy = new SimpleEnergyHandler(Config.QUARRY_ENERGY_CAPACITY.get(), MAX_INSERT, MAX_EXTRACT) {
        @Override
        protected void onEnergyChanged(int previousAmount) {
            setChanged();
        }
    };

    private boolean areaInitialized = false;
    private boolean finished = false;
    private @Nullable BlockPos frameMin;
    private @Nullable BlockPos frameMax;
    private @Nullable BlockPos mineMin;
    private @Nullable BlockPos mineMax;
    private Deque<BlockPos> frameQueue = new ArrayDeque<>();
    private boolean chunksForced = false;
    // Every position where this Quarry has placed a Frame block, so they can be cleaned up if the Quarry is broken.
    private final Set<BlockPos> placedFramePositions = new HashSet<>();
    /** Real bug fixed (2026-08-08): {@code drillPos} (below) gets assigned the moment {@link #selectNextTask}
     * FIRST runs, which only requires {@code budget > 0} in {@link #runTasks} - i.e. the instant ANY nonzero FE
     * is stored, well before enough has accumulated to actually complete a single task. That made the renderer
     * (which shows the "idle" wireframe box only while {@code drillPos == null}) switch to the "actively
     * drilling" gantry view within the first few ticks of ever receiving power, even while genuinely starved
     * and making zero real progress for a long time afterward - exactly what looked like "no wireframe when
     * idle/unpowered" once engine output was correctly nerfed and that starved period got much longer. This
     * flag only flips true the first time a task actually SPENDS real power (in {@link #runTasks}), and gates
     * what the client-synced drill position shows - {@code drillPos} itself is untouched, still needed
     * immediately for the box iterator's own internal bookkeeping. */
    private boolean hasStartedWork = false;

    /** Real perf fix (2026-07-31 FPS/TPS audit): {@link #routeDrop} used to call the raw, uncached
     * {@code level.getCapability(...)} for up to 6 directions per mined-block drop - see
     * {@code FluidPipeBlockEntity}'s own equivalent field for the full reasoning (NeoForge's own
     * {@link BlockCapabilityCache}, no caching in the raw call). One shared, per-direction cache. */
    private final Map<Direction, BlockCapabilityCache<ResourceHandler<ItemResource>, Direction>> itemCapCache = new EnumMap<>(Direction.class);

    // Real mining state, mirroring TileQuarry's boxIterator/drillPos/currentTask trio.
    private @Nullable QuarryBoxIterator boxIterator;
    // Server-authoritative continuous position of the drill. Null whenever there's no active mining position to
    // show - before mining has started, and every time frame work (obstacle clearing / placement) interrupts it,
    // exactly like the original nulling TileQuarry.drillPos before TaskBreakBlock/TaskAddFrame.
    private @Nullable Vec3 drillPos;
    private @Nullable MiningTask currentTaskObj;

    // Client-render-only interpolation buffers (not persisted), shifted once per CLIENT tick - the same 1-tick
    // buffer technique the original uses for prevClientDrillPos/clientDrillPos and each Task's clientPower.
    private @Nullable Vec3 clientDrillPos;
    private @Nullable Vec3 prevClientDrillPos;
    private long clientTaskPower;
    private long prevClientTaskPower;

    public QuarryBlockEntity(BlockPos pos, BlockState state) {
        super(BuildersContent.QUARRY_BLOCK_ENTITY.get(), pos, state);
    }

    public static void tick(Level level, BlockPos pos, BlockState state, QuarryBlockEntity be) {
        if (level.isClientSide()) {
            be.prevClientDrillPos = be.clientDrillPos;
            be.clientDrillPos = be.hasStartedWork ? be.drillPos : null;
            be.prevClientTaskPower = be.clientTaskPower;
            be.clientTaskPower = be.currentTaskObj != null ? be.currentTaskObj.power : 0;
            return;
        }
        if (!be.areaInitialized) {
            be.initArea(level, pos, state);
        }
        if (be.finished) {
            return;
        }
        // TEMPORARY: no Engine exists yet to power the Quarry. Remove once a real FE-producing machine exists.
        // A modest per-tick trickle, not a full top-off - see Config.QUARRY_DEBUG_POWER_PER_TICK's javadoc for why.
        if (Config.QUARRY_DEBUG_INFINITE_POWER.get()) {
            try (Transaction tx = Transaction.openRoot()) {
                if (be.energy.insert(Config.QUARRY_DEBUG_POWER_PER_TICK.get(), tx) > 0) {
                    tx.commit();
                }
            }
        }
        be.runTasks(level);
    }

    public EnergyHandler getEnergyHandler(@Nullable Direction side) {
        return energy;
    }

    public @Nullable BlockPos getFrameMin() {
        return frameMin;
    }

    public @Nullable BlockPos getFrameMax() {
        return frameMax;
    }

    /** The deepest Y the drill/connector rod can ever reach ({@code mineMin}'s own Y, not the frame posts' -
     * see {@link com.buildcraft.builders.client.render.QuarryBlockEntityRenderer#getRenderBoundingBox} for why
     * this needs to be exposed separately from {@link #getFrameMin()}: the frame's own Y-range is just the
     * (short) post height, while the drill can dig far below it. */
    public @Nullable BlockPos getMineMin() {
        return mineMin;
    }

    /**
     * The drill's interpolated world position for rendering, or {@code null} if the gantry/drill shouldn't be
     * shown at all right now (still building the frame, or mid-interruption to fix it) - the renderer falls
     * back to the wireframe box or targeting laser in that case, matching the original's
     * {@code tile.clientDrillPos != null} check.
     */
    public @Nullable Vec3 getInterpolatedDrillPos(float partialTicks) {
        if (clientDrillPos == null) {
            return null;
        }
        if (prevClientDrillPos == null) {
            return clientDrillPos;
        }
        return prevClientDrillPos.lerp(clientDrillPos, partialTicks);
    }

    /** Non-null while lasering an obstacle out of the frame's own footprint from a distance (no gantry there yet). */
    public @Nullable BlockPos getTargetingObstacle() {
        return currentTaskObj instanceof BreakBlockTask task && !task.isMining ? task.breakPos : null;
    }

    /** Non-null only while actively breaking a block during real mining (drives the drill's plunge/retract bob). */
    public @Nullable BlockPos getBreakTarget() {
        return currentTaskObj instanceof BreakBlockTask task && task.isMining ? task.breakPos : null;
    }

    /** 0..1 fraction of the current mining break's power target, interpolated for smooth rendering. */
    public float getInterpolatedBreakProgress(float partialTicks) {
        long target = currentTaskObj != null ? currentTaskObj.getTarget() : 0;
        if (target <= 0) {
            return 1.0F;
        }
        double power = Mth.lerp(partialTicks, (double) prevClientTaskPower, (double) clientTaskPower);
        return (float) Mth.clamp(power / target, 0.0, 1.0);
    }

    private void initArea(Level level, BlockPos pos, BlockState state) {
        Direction facing = state.getValue(QuarryBlock.FACING);
        Direction into = facing.getOpposite();
        BlockPos min;
        BlockPos max;

        BlockPos markerAreaPos = pos.relative(into);
        BlockPos[] markerArea = tryGetMarkerArea(level, markerAreaPos);
        if (markerArea != null) {
            min = markerArea[0].offset(-1, 0, -1);
            max = new BlockPos(markerArea[1].getX() + 1, markerArea[0].getY() + Config.QUARRY_FRAME_HEIGHT.get(), markerArea[1].getZ() + 1);
            level.removeBlock(markerAreaPos, false);
            level.removeBlock(markerArea[2], false);
        } else {
            int height = Config.QUARRY_FRAME_HEIGHT.get();
            switch (into) {
                case WEST -> {
                    min = pos.offset(-11, 0, -5);
                    max = pos.offset(-1, height, 5);
                }
                case SOUTH -> {
                    min = pos.offset(-5, 0, 1);
                    max = pos.offset(5, height, 11);
                }
                case NORTH -> {
                    min = pos.offset(-5, 0, -11);
                    max = pos.offset(5, height, -1);
                }
                default -> {
                    // EAST, and the two vertical directions which shouldn't occur since FACING is horizontal-only
                    min = pos.offset(1, 0, -5);
                    max = pos.offset(11, height, 5);
                }
            }
        }
        this.frameMin = min;
        this.frameMax = max;

        int minY = Math.max(level.getMinY(), max.getY() - 1 - Config.QUARRY_MAX_MINE_DEPTH.get());
        this.mineMin = new BlockPos(min.getX() + 1, minY, min.getZ() + 1);
        this.mineMax = new BlockPos(max.getX() - 1, max.getY() - 1, max.getZ() - 1);

        List<BlockPos> edges = new ArrayList<>();
        for (BlockPos p : BlockPos.betweenClosed(min, max)) {
            if (!p.equals(pos) && isBoxEdge(p, min, max)) {
                edges.add(p.immutable());
            }
        }
        edges.sort(Comparator.comparingDouble(p -> p.distSqr(pos)));
        this.frameQueue = new ArrayDeque<>(edges);

        this.areaInitialized = true;
        this.boxIterator = null;
        this.drillPos = null;
        this.currentTaskObj = null;

        boolean degenerate = mineMin.getX() > mineMax.getX() || mineMin.getZ() > mineMax.getZ() || mineMin.getY() > mineMax.getY();
        if (degenerate) {
            this.finished = true;
        }
        if (level instanceof ServerLevel serverLevel) {
            forceChunks(serverLevel);
        }

        BuildCraft.LOGGER.info(
                "Quarry at {}: area initialized (source={}). frame=[{} .. {}] size={}x{}x{} | mine=[{} .. {}] size={}x{}x{} | frameBlocksQueued={}{}",
                pos, markerArea != null ? "markers" : "default",
                min, max, max.getX() - min.getX() + 1, max.getY() - min.getY() + 1, max.getZ() - min.getZ() + 1,
                mineMin, mineMax, mineMax.getX() - mineMin.getX() + 1, mineMax.getY() - mineMin.getY() + 1, mineMax.getZ() - mineMin.getZ() + 1,
                frameQueue.size(), degenerate ? " | WARNING: degenerate mining box, nothing to mine" : "");
        setChanged();
        // Real bug fixed (2026-08-08): setChanged() alone only marks this dirty for disk SAVING - it doesn't
        // send anything over the network. frameMin/frameMax (which the renderer needs for both its bounding box
        // and the pre-drilling wireframe) were computed here but never actually reached the client until
        // something else happened to trigger a sync later - runTasks()'s own sync is conditional on real task
        // progress, which (now that engines are correctly weak) can be a long time coming. Reloading the world
        // "fixed" it only because a fresh chunk load re-reads the by-then-already-saved NBT from disk, not
        // because anything was actually wrong with the data - the initial placement just never told the client
        // it existed. A real network sync right here, the moment the area is known, closes that gap.
        if (level instanceof ServerLevel) {
            syncToClients(level);
        }
    }

    private void forceChunks(ServerLevel level) {
        if (chunksForced || frameMin == null || frameMax == null) {
            return;
        }
        for (int x = frameMin.getX() >> 4; x <= frameMax.getX() >> 4; x++) {
            for (int z = frameMin.getZ() >> 4; z <= frameMax.getZ() >> 4; z++) {
                BuildersContent.QUARRY_TICKET_CONTROLLER.forceChunk(level, worldPosition, x, z, true, false);
            }
        }
        chunksForced = true;
    }

    private void releaseChunks(ServerLevel level) {
        if (!chunksForced || frameMin == null || frameMax == null) {
            return;
        }
        for (int x = frameMin.getX() >> 4; x <= frameMax.getX() >> 4; x++) {
            for (int z = frameMin.getZ() >> 4; z <= frameMax.getZ() >> 4; z++) {
                BuildersContent.QUARRY_TICKET_CONTROLLER.forceChunk(level, worldPosition, x, z, false, false);
            }
        }
        chunksForced = false;
    }

    @Override
    public void setRemoved() {
        super.setRemoved();
        if (level instanceof ServerLevel serverLevel) {
            releaseChunks(serverLevel);
        }
    }

    /** When the Quarry itself is broken/replaced, clean up every Frame block it placed, matching the original. */
    @Override
    public void preRemoveSideEffects(BlockPos pos, BlockState state) {
        super.preRemoveSideEffects(pos, state);
        if (level != null && !level.isClientSide()) {
            for (BlockPos framePos : placedFramePositions) {
                if (level.getBlockState(framePos).is(BuildersContent.FRAME_BLOCK.get())) {
                    level.removeBlock(framePos, false);
                }
            }
            placedFramePositions.clear();
        }
    }

    /** Pushes the current state (drill target, frame progress, etc) to nearby clients for rendering. */
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

    /**
     * If a linked pair of Markers sits at {@code areaPos} (directly in front of the Quarry), returns
     * {@code {min, max, otherMarkerPos}} describing the area they define. Returns {@code null} otherwise,
     * in which case the caller should fall back to the fixed default area.
     */
    private static BlockPos @Nullable [] tryGetMarkerArea(Level level, BlockPos areaPos) {
        if (!(level.getBlockEntity(areaPos) instanceof MarkerBlockEntity marker) || !marker.isLinked()) {
            return null;
        }
        BlockPos other = marker.getLinkedPos();
        if (other == null || !(level.getBlockEntity(other) instanceof MarkerBlockEntity)) {
            return null;
        }
        int minX = Math.min(areaPos.getX(), other.getX());
        int maxX = Math.max(areaPos.getX(), other.getX());
        int minZ = Math.min(areaPos.getZ(), other.getZ());
        int maxZ = Math.max(areaPos.getZ(), other.getZ());
        if (maxX - minX < 2 || maxZ - minZ < 2) {
            return null;
        }
        return new BlockPos[] {
                new BlockPos(minX, areaPos.getY(), minZ),
                new BlockPos(maxX, areaPos.getY(), maxZ),
                other
        };
    }

    private static boolean isBoxEdge(BlockPos p, BlockPos min, BlockPos max) {
        int extremes = 0;
        if (p.getX() == min.getX() || p.getX() == max.getX()) extremes++;
        if (p.getY() == min.getY() || p.getY() == max.getY()) extremes++;
        if (p.getZ() == min.getZ() || p.getZ() == max.getZ()) extremes++;
        return extremes >= 2;
    }

    /**
     * Matches the original's power ramp-up: full rate once the battery is over half full, scaling down
     * linearly toward 0 as it approaches empty, rather than always drawing at the flat cap.
     */
    private int getMaxDrawThisTick() {
        int maxPerTick = Config.QUARRY_MAX_FE_PER_TICK.get();
        long stored = energy.getAmountAsLong();
        long halfCapacity = Config.QUARRY_ENERGY_CAPACITY.get() / 2;
        if (stored >= halfCapacity) {
            return maxPerTick;
        }
        long scaled = (long) maxPerTick * stored / halfCapacity;
        return (int) Math.max(0, Math.min(maxPerTick, scaled));
    }

    private static long breakCost(float hardness) {
        // Matches BlockUtil.computeBlockBreakPower's shape: cost = C * (hardness + 1), so even a hardness-0
        // block costs something.
        return Math.round(Config.QUARRY_ENERGY_PER_HARDNESS_POINT.get() * (hardness + 1.0));
    }

    /**
     * Runs up to a power-scaled number of task-completions this tick, capped by how much FE can be drawn this
     * tick (see {@link #getMaxDrawThisTick()}). Mirrors TileQuarry.update()'s power_loop: keep feeding the
     * current task; once it completes, immediately try to start (and possibly finish) another, until the tick's
     * power budget or task-count cap runs out.
     */
    private void runTasks(Level level) {
        int budget = getMaxDrawThisTick();
        // Matches the original's maxTasks = max(1, floor(max * quarryMaxTasksPerTick / MAX_POWER_PER_TICK)): the
        // task-completion cap itself scales down with the power ramp-up, not just the power budget each task
        // draws from - so a nearly-empty battery allows fewer task completions per tick, not just slower ones.
        // A no-op while Config.QUARRY_DEBUG_INFINITE_POWER keeps the battery pinned at full (budget always equals
        // the flat max, so this always resolves to QUARRY_MAX_TASKS_PER_TICK exactly) - only matters once a real
        // power source makes the buffer run low.
        int maxTasks = Math.max(1, (int) ((long) budget * Config.QUARRY_MAX_TASKS_PER_TICK.get() / Config.QUARRY_MAX_FE_PER_TICK.get()));
        boolean changed = false;
        for (int i = 0; i < maxTasks && budget > 0 && !finished; i++) {
            if (currentTaskObj == null) {
                selectNextTask(level);
                if (currentTaskObj == null) {
                    break;
                }
            }
            long needed = currentTaskObj.getRequiredPowerThisTick();
            // Real source (TileQuarry.java:561-570, quarryTaskPowerDivisor, confirmed 2026-08-11 via the actual
            // BuildCraft GitHub source): each ADDITIONAL task completed within the same tick (i>0) costs
            // progressively more raw power for the same effective progress - extracting nNeeded from the
            // battery only actually counts as `added` toward the task, where added < nNeeded once i>0. This is
            // real source's actual speed-limiting mechanic for a well-powered Quarry (a genuine omission in this
            // port before now, not a deliberate deviation) - without it, abundant power alone lets
            // QUARRY_MAX_TASKS_PER_TICK tasks complete every tick at full efficiency; with it, completing more
            // than one task per tick gets progressively less power-efficient. divisor=0 disables it (added=extracted).
            int divisor = Config.QUARRY_TASK_POWER_DIVISOR.get();
            long nNeeded = divisor > 0 ? needed * (divisor + i) / divisor : needed;
            long leftover = divisor > 0 ? (needed * (divisor + i)) % divisor : 0;
            int want = (int) Math.max(0, Math.min(budget, nNeeded));
            if (want <= 0) {
                break;
            }
            int extracted;
            try (Transaction tx = Transaction.openRoot()) {
                extracted = energy.extract(want, tx);
                if (extracted > 0) {
                    tx.commit();
                }
            }
            if (extracted <= 0) {
                break;
            }
            budget -= extracted;
            changed = true;
            hasStartedWork = true;
            debugFeAccum += extracted;
            long added = divisor > 0 ? (long) extracted * divisor / (divisor + i) : extracted;
            if (leftover > 0) {
                added++;
            }
            MiningTask finishedTask = currentTaskObj;
            boolean done = currentTaskObj.addPower(added);
            if (done) {
                if (finishedTask instanceof BreakBlockTask task && !task.isMining) {
                    frameQueue.pollFirst();
                } else if (finishedTask instanceof BreakBlockTask task) {
                    debugBlocksAccum++;
                } else if (finishedTask instanceof AddFrameTask) {
                    frameQueue.pollFirst();
                }
                currentTaskObj = null;
            } else {
                break;
            }
        }
        if (changed) {
            setChanged();
            syncToClients(level);
        }
        if (DEBUG_LOG && level.getGameTime() % 100 == 0) {
            BuildCraft.LOGGER.info(
                    "[QUARRY_DEBUG] pos={} stored={}/{} FE consumed last 100t={} FE ({}/t avg) blocksMined last 100t={}",
                    getBlockPos(), energy.getAmountAsLong(), Config.QUARRY_ENERGY_CAPACITY.get(), debugFeAccum,
                    debugFeAccum / 100.0, debugBlocksAccum);
            debugFeAccum = 0;
            debugBlocksAccum = 0;
        }
    }

    /** Picks the next task to work on, following the original's strict priority: frame repair before mining. */
    private void selectNextTask(Level level) {
        if (currentTaskObj != null) {
            return;
        }

        if (!frameQueue.isEmpty()) {
            BlockPos framePos = frameQueue.peekFirst();
            BlockState existing = level.getBlockState(framePos);
            if (existing.getFluidState().is(FluidTags.LAVA)) {
                // Never destroy lava (or, in future, other fuel fluids) - just leave a gap in the frame here.
                frameQueue.pollFirst();
                return;
            }
            if (!existing.isAir()) {
                float hardness = existing.getDestroySpeed(level, framePos);
                if (hardness < 0) {
                    // Unbreakable (e.g. bedrock) - can't clear it, leave a gap.
                    frameQueue.pollFirst();
                    return;
                }
                drillPos = null;
                currentTaskObj = new BreakBlockTask(framePos, false);
            } else {
                drillPos = null;
                currentTaskObj = new AddFrameTask(framePos);
            }
            return;
        }

        if (mineMin == null || mineMax == null) {
            return;
        }
        if (boxIterator == null || drillPos == null) {
            if (boxIterator == null) {
                boxIterator = createBoxIterator();
            }
            skipUnminable(level);
            drillPos = closestInsideMiningBox(worldPosition);
        }
        skipUnminable(level);
        BlockPos current = boxIterator.current;
        if (current == null) {
            // Real bug fixed (2026-08-11, user-reported: Quarry "finished" at a cave/mineshaft without reaching
            // bedrock). Confirmed via real TileQuarry.java: canMoveDownTo requires the ENTIRE column above a
            // candidate position to be air/water - any exposed lava (or other non-air/water obstruction) in a
            // natural cave permanently fails that check for everything below it in that column, forever, since
            // there's no obstacle-clearing task for the mining area's interior (only the frame's own border has
            // one). That's real, faithfully-ported BuildCraft behavior, not a port bug on its own - confirmed
            // real source has the exact same canMine/canMoveThrough/canMoveDownTo trio and can get stuck the
            // same way. The actual regression was here: real source's boxIterator running out of positions
            // (BoxIterator.hasFinished()) just silently skips that tick's work and keeps re-checking forever -
            // it has NO "finished" concept at all. This port added one anyway (persisted, chunk-releasing,
            // logged as a clean completion) keyed off the exact same "iterator exhausted" signal that can't
            // tell "genuinely mined everything" apart from "permanently stuck on an obstruction it can never
            // clear on its own." A stalled Quarry was falsely announcing success and releasing its chunks
            // while most of its area was still untouched. Matching source exactly: do nothing this tick, no
            // terminal state, chunks stay force-loaded (same tradeoff real BuildCraft always had) - if the
            // iterator later gets any further positions via {@link #initArea}/frame changes, work resumes
            // normally; a genuinely fully-mined Quarry likewise just idles harmlessly forever, same as source.
            return;
        }
        Vec3 currentVec = Vec3.atLowerCornerOf(current);
        if (drillPos.distanceToSqr(currentVec) >= 1.0) {
            currentTaskObj = new MoveDrillTask(drillPos, currentVec);
        } else if (canMine(level, current)) {
            currentTaskObj = new BreakBlockTask(current, true);
        }
        // Else: nothing to do this pass - skipUnminable should already have filtered this out. Leave
        // currentTaskObj null; the tick simply makes no progress until the world state around it changes.
    }

    private void skipUnminable(Level level) {
        if (boxIterator == null) {
            return;
        }
        BlockPos current = boxIterator.current;
        while (current != null
                && (canMoveThrough(level, current) || !canMine(level, current) || !canMoveDownTo(level, current))) {
            current = boxIterator.advance();
        }
    }

    private QuarryBoxIterator createBoxIterator() {
        long seed = worldPosition.asLong() ^ ((long) level.dimension().hashCode() << 1);
        RandomSource rand = RandomSource.create(seed);
        boolean xIsFirst = rand.nextBoolean();
        boolean xPositive = rand.nextBoolean();
        boolean zPositive = rand.nextBoolean();
        return new QuarryBoxIterator(mineMin, mineMax, xIsFirst, xPositive, zPositive);
    }

    private boolean canMine(Level level, BlockPos pos) {
        float hardness = level.getBlockState(pos).getDestroySpeed(level, pos);
        if (hardness < 0) {
            return false;
        }
        FluidState fluid = level.getFluidState(pos);
        return fluid.isEmpty() || fluid.is(FluidTags.WATER);
    }

    private boolean canMoveThrough(Level level, BlockPos pos) {
        if (level.getBlockState(pos).isAir()) {
            return true;
        }
        FluidState fluid = level.getFluidState(pos);
        return !fluid.isEmpty() && fluid.is(FluidTags.WATER);
    }

    private boolean canMoveDownTo(Level level, BlockPos pos) {
        if (mineMax == null) {
            return true;
        }
        for (int y = mineMax.getY(); y > pos.getY(); y--) {
            if (!canMoveThrough(level, new BlockPos(pos.getX(), y, pos.getZ()))) {
                return false;
            }
        }
        return true;
    }

    /** Clamps a position into the mining box - where the drill "spawns" the first time it starts mining, or
     * re-spawns after frame work interrupts it, matching the original's {@code Box.closestInsideTo}. */
    private Vec3 closestInsideMiningBox(BlockPos pos) {
        if (mineMin == null || mineMax == null) {
            return Vec3.atLowerCornerOf(pos);
        }
        double x = Mth.clamp(pos.getX(), mineMin.getX(), mineMax.getX());
        double y = Mth.clamp(pos.getY(), mineMin.getY(), mineMax.getY());
        double z = Mth.clamp(pos.getZ(), mineMin.getZ(), mineMax.getZ());
        return new Vec3(x, y, z);
    }

    /**
     * Breaks the block and routes its drops to an adjacent inventory if one is attached to the Quarry, matching
     * the original's {@code InventoryUtil.addToBestAcceptor}. Anything that doesn't fit falls back to spawning
     * as an item entity above the Quarry, same as breaking it normally would.
     */
    private void mineAndRouteDrops(Level level, BlockPos target, BlockState state) {
        if (!(level instanceof ServerLevel serverLevel)) {
            level.destroyBlock(target, true, null, 512);
            return;
        }
        List<ItemStack> drops = Block.getDrops(state, serverLevel, target, level.getBlockEntity(target));
        level.destroyBlock(target, false, null, 512);
        for (ItemStack drop : drops) {
            routeDrop(serverLevel, drop);
        }
    }

    private void routeDrop(ServerLevel level, ItemStack stack) {
        if (stack.isEmpty()) {
            return;
        }
        for (Direction dir : Direction.values()) {
            if (stack.isEmpty()) {
                return;
            }
            BlockCapabilityCache<ResourceHandler<ItemResource>, Direction> cache = itemCapCache.computeIfAbsent(dir,
                    d -> BlockCapabilityCache.create(Capabilities.Item.BLOCK, level, worldPosition.relative(d), d.getOpposite(),
                            () -> !isRemoved(), () -> {}));
            ResourceHandler<ItemResource> handler = cache.getCapability();
            if (handler == null) {
                continue;
            }
            ItemResource resource = ItemResource.of(stack);
            int inserted;
            try (Transaction tx = Transaction.openRoot()) {
                inserted = handler.insert(resource, stack.getCount(), tx);
                if (inserted > 0) {
                    tx.commit();
                }
            }
            if (inserted > 0) {
                stack.shrink(inserted);
            }
        }
        if (!stack.isEmpty()) {
            Block.popResource(level, worldPosition.above(), stack);
        }
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        energy.serialize(output.child("energy"));
        output.putBoolean("areaInitialized", areaInitialized);
        output.putBoolean("finished", finished);
        output.putBoolean("chunksForced", chunksForced);
        output.putBoolean("hasStartedWork", hasStartedWork);
        if (frameMin != null) output.store("frameMin", BlockPos.CODEC, frameMin);
        if (frameMax != null) output.store("frameMax", BlockPos.CODEC, frameMax);
        if (mineMin != null) output.store("mineMin", BlockPos.CODEC, mineMin);
        if (mineMax != null) output.store("mineMax", BlockPos.CODEC, mineMax);
        ValueOutput.TypedOutputList<BlockPos> list = output.list("frameQueue", BlockPos.CODEC);
        for (BlockPos p : frameQueue) {
            list.add(p);
        }
        ValueOutput.TypedOutputList<BlockPos> placedList = output.list("placedFramePositions", BlockPos.CODEC);
        for (BlockPos p : placedFramePositions) {
            placedList.add(p);
        }

        output.putBoolean("hasBoxIterator", boxIterator != null);
        if (boxIterator != null) {
            output.putBoolean("boxIteratorXIsFirst", boxIterator.xIsFirst);
            output.putInt("boxIteratorXDir", boxIterator.xDir);
            output.putInt("boxIteratorZDir", boxIterator.zDir);
            if (boxIterator.current != null) {
                output.store("boxIteratorCurrent", BlockPos.CODEC, boxIterator.current);
            }
        }
        if (drillPos != null) {
            output.store("drillPos", Vec3.CODEC, drillPos);
        }
        if (currentTaskObj instanceof BreakBlockTask task) {
            output.putString("taskKind", task.isMining ? "break_mine" : "break_frame");
            output.putLong("taskPower", task.power);
            output.store("taskBreakPos", BlockPos.CODEC, task.breakPos);
        } else if (currentTaskObj instanceof AddFrameTask task) {
            output.putString("taskKind", "add_frame");
            output.putLong("taskPower", task.power);
            output.store("taskFramePos", BlockPos.CODEC, task.framePos);
        } else if (currentTaskObj instanceof MoveDrillTask task) {
            output.putString("taskKind", "move_drill");
            output.putLong("taskPower", task.power);
            output.store("taskMoveFrom", Vec3.CODEC, task.from);
            output.store("taskMoveTo", Vec3.CODEC, task.to);
        }
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        input.child("energy").ifPresent(energy::deserialize);
        areaInitialized = input.getBooleanOr("areaInitialized", false);
        finished = input.getBooleanOr("finished", false);
        chunksForced = input.getBooleanOr("chunksForced", false);
        hasStartedWork = input.getBooleanOr("hasStartedWork", false);
        frameMin = input.read("frameMin", BlockPos.CODEC).orElse(null);
        frameMax = input.read("frameMax", BlockPos.CODEC).orElse(null);
        mineMin = input.read("mineMin", BlockPos.CODEC).orElse(null);
        mineMax = input.read("mineMax", BlockPos.CODEC).orElse(null);
        frameQueue = new ArrayDeque<>();
        input.list("frameQueue", BlockPos.CODEC).ifPresent(l -> l.stream().forEach(frameQueue::add));
        placedFramePositions.clear();
        input.list("placedFramePositions", BlockPos.CODEC).ifPresent(l -> l.stream().forEach(placedFramePositions::add));

        boxIterator = null;
        if (input.getBooleanOr("hasBoxIterator", false) && mineMin != null && mineMax != null) {
            boolean xIsFirst = input.getBooleanOr("boxIteratorXIsFirst", true);
            int xDir = input.getIntOr("boxIteratorXDir", 1);
            int zDir = input.getIntOr("boxIteratorZDir", 1);
            boxIterator = new QuarryBoxIterator(mineMin, mineMax, xIsFirst, xDir > 0, zDir > 0);
            boxIterator.xDir = xDir;
            boxIterator.zDir = zDir;
            boxIterator.current = input.read("boxIteratorCurrent", BlockPos.CODEC).orElse(null);
        }
        drillPos = input.read("drillPos", Vec3.CODEC).orElse(null);

        currentTaskObj = null;
        String taskKind = input.getStringOr("taskKind", "");
        long taskPower = input.getLongOr("taskPower", 0);
        switch (taskKind) {
            case "break_mine" -> input.read("taskBreakPos", BlockPos.CODEC).ifPresent(p -> {
                currentTaskObj = new BreakBlockTask(p, true);
                currentTaskObj.power = taskPower;
            });
            case "break_frame" -> input.read("taskBreakPos", BlockPos.CODEC).ifPresent(p -> {
                currentTaskObj = new BreakBlockTask(p, false);
                currentTaskObj.power = taskPower;
            });
            case "add_frame" -> input.read("taskFramePos", BlockPos.CODEC).ifPresent(p -> {
                currentTaskObj = new AddFrameTask(p);
                currentTaskObj.power = taskPower;
            });
            case "move_drill" -> {
                Vec3 from = input.read("taskMoveFrom", Vec3.CODEC).orElse(null);
                Vec3 to = input.read("taskMoveTo", Vec3.CODEC).orElse(null);
                if (from != null && to != null) {
                    currentTaskObj = new MoveDrillTask(from, to);
                    currentTaskObj.power = taskPower;
                }
            }
            default -> {
            }
        }
    }

    /** Ports TileQuarry's abstract {@code Task}: accumulates FE toward {@link #getTarget()}, then either
     * completes it (consuming the power) or silently cancels and refunds it. */
    private abstract class MiningTask {
        long power;

        abstract long getTarget();

        long getRequiredPowerThisTick() {
            return Math.max(0, getTarget() - power);
        }

        /** Called each tick power is added but the target hasn't been reached yet. Return {@code true} to force
         * the task to end early without calling {@link #finish} (e.g. the block was already destroyed some
         * other way while we were powering it). */
        boolean onReceivePower(long added, long target) {
            return false;
        }

        /** Called once {@code power >= target}. Return {@code true} if it actually accomplished its goal
         * (consuming the power spent), or {@code false} to cancel and refund it. */
        abstract boolean finish(long added, long target);

        /** @return {@code true} once this task is done (finished or force-ended) and should be cleared. */
        final boolean addPower(long amount) {
            power += amount;
            long target = getTarget();
            if (power >= target) {
                if (!finish(amount, target)) {
                    try (Transaction tx = Transaction.openRoot()) {
                        int refund = (int) Math.min(power, Integer.MAX_VALUE);
                        if (energy.insert(refund, tx) > 0) {
                            tx.commit();
                        }
                    }
                }
                return true;
            }
            return onReceivePower(amount, target);
        }
    }

    private final class BreakBlockTask extends MiningTask {
        final BlockPos breakPos;
        /** True for a real mining break (drops routed to an inventory); false for clearing a frame obstacle
         * (drops discarded - matches the original discarding drops when {@code drillPos == null}). */
        final boolean isMining;

        BreakBlockTask(BlockPos breakPos, boolean isMining) {
            this.breakPos = breakPos;
            this.isMining = isMining;
        }

        @Override
        long getTarget() {
            return breakCost(level.getBlockState(breakPos).getDestroySpeed(level, breakPos));
        }

        @Override
        boolean onReceivePower(long added, long target) {
            if (level.getBlockState(breakPos).isAir()) {
                return true;
            }
            level.destroyBlockProgress(breakPos.hashCode(), breakPos, Math.min(9, (int) (power * 9 / Math.max(1, target))));
            return false;
        }

        @Override
        boolean finish(long added, long target) {
            level.destroyBlockProgress(breakPos.hashCode(), breakPos, -1);
            BlockState state = level.getBlockState(breakPos);
            if (state.isAir()) {
                return true;
            }
            if (isMining) {
                BuildCraft.LOGGER.debug("Quarry at {}: mined {} (block={}, cost={} FE)",
                        worldPosition, breakPos, state.getBlock(), target);
                mineAndRouteDrops(level, breakPos, state);
            } else {
                BuildCraft.LOGGER.debug("Quarry at {}: cleared obstruction {} at {} for frame ({} FE)",
                        worldPosition, state.getBlock(), breakPos, target);
                level.destroyBlock(breakPos, false, null, 512);
            }
            return true;
        }
    }

    private final class AddFrameTask extends MiningTask {
        final BlockPos framePos;

        AddFrameTask(BlockPos framePos) {
            this.framePos = framePos;
        }

        @Override
        long getTarget() {
            return Config.QUARRY_FRAME_BLOCK_COST.get();
        }

        @Override
        boolean finish(long added, long target) {
            // Real bug fixed: this used to bail out (refund, leave a permanent gap) if something re-occupied
            // framePos between task creation and completion - e.g. water/oil flowing back into a spot a
            // BreakBlockTask had just cleared, several ticks earlier, while THIS task was still accumulating
            // power. Since runTasks() unconditionally polls frameQueue once this task reports "done" regardless
            // of finish()'s return value, that bail-out silently dropped the position forever - the exact
            // "missing frame piece where something was in the way" symptom. A frame position must always end up
            // with a real Frame block, so force-clear whatever's there now (matching BreakBlockTask's own
            // obstruction-clearing pattern) instead of giving up.
            BlockState blocking = level.getBlockState(framePos);
            if (!blocking.isAir()) {
                if (DEBUG_LOG) {
                    BuildCraft.LOGGER.info("[QUARRY_DEBUG] pos={} frame slot {} was re-occupied by {} before placement - force-clearing",
                            getBlockPos(), framePos, blocking.getBlock());
                }
                level.destroyBlock(framePos, false, null, 512);
            }
            BlockState frameState = FrameBlock.computeState(BuildersContent.FRAME_BLOCK.get().defaultBlockState(), level, framePos);
            level.setBlockAndUpdate(framePos, frameState);
            placedFramePositions.add(framePos);
            BuildCraft.LOGGER.debug("Quarry at {}: placed frame block at {} ({} remaining in queue)",
                    worldPosition, framePos, frameQueue.size() - 1);
            return true;
        }
    }

    private final class MoveDrillTask extends MiningTask {
        final Vec3 from;
        final Vec3 to;

        MoveDrillTask(Vec3 from, Vec3 to) {
            this.from = from;
            this.to = to;
        }

        @Override
        long getTarget() {
            return Math.round(from.distanceTo(to) * Config.QUARRY_MOVE_COST_PER_BLOCK.get());
        }

        @Override
        boolean onReceivePower(long added, long target) {
            double t = target <= 0 ? 1.0 : Mth.clamp((double) power / target, 0.0, 1.0);
            drillPos = from.lerp(to, t);
            return false;
        }

        @Override
        boolean finish(long added, long target) {
            drillPos = to;
            return true;
        }
    }

    /**
     * A faithful port of the original's {@code BoxIterator} for the one shape TileQuarry actually uses it in:
     * X and Z (randomly ordered and randomly directed) sweep each Y layer in a zig-zag (boustrophedon) pattern -
     * flipping direction every time a row runs off the box instead of snapping back to the same side - and Y
     * always descends one layer at a time from the top, with no wraparound.
     */
    private static final class QuarryBoxIterator {
        final BlockPos min;
        final BlockPos max;
        final boolean xIsFirst;
        int xDir;
        int zDir;
        @Nullable BlockPos current;

        QuarryBoxIterator(BlockPos min, BlockPos max, boolean xIsFirst, boolean xPositive, boolean zPositive) {
            this.min = min;
            this.max = max;
            this.xIsFirst = xIsFirst;
            this.xDir = xPositive ? 1 : -1;
            this.zDir = zPositive ? 1 : -1;
            int startX = xPositive ? min.getX() : max.getX();
            int startZ = zPositive ? min.getZ() : max.getZ();
            this.current = new BlockPos(startX, max.getY(), startZ);
        }

        @Nullable
        BlockPos advance() {
            if (current == null) {
                return null;
            }
            int x = current.getX();
            int y = current.getY();
            int z = current.getZ();
            if (xIsFirst) {
                x += xDir;
                if (x > max.getX() || x < min.getX()) {
                    xDir = -xDir;
                    x = xDir > 0 ? min.getX() : max.getX();
                    z += zDir;
                    if (z > max.getZ() || z < min.getZ()) {
                        zDir = -zDir;
                        z = zDir > 0 ? min.getZ() : max.getZ();
                        y -= 1;
                    }
                }
            } else {
                z += zDir;
                if (z > max.getZ() || z < min.getZ()) {
                    zDir = -zDir;
                    z = zDir > 0 ? min.getZ() : max.getZ();
                    x += xDir;
                    if (x > max.getX() || x < min.getX()) {
                        xDir = -xDir;
                        x = xDir > 0 ? min.getX() : max.getX();
                        y -= 1;
                    }
                }
            }
            current = y < min.getY() ? null : new BlockPos(x, y, z);
            return current;
        }
    }
}
