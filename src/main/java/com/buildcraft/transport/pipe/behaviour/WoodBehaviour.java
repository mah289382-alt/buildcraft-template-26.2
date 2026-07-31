package com.buildcraft.transport.pipe.behaviour;

import java.util.EnumSet;
import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.transfer.ResourceHandler;
import net.neoforged.neoforge.transfer.energy.EnergyHandler;
import net.neoforged.neoforge.transfer.fluid.FluidResource;
import net.neoforged.neoforge.transfer.item.ItemResource;
import net.neoforged.neoforge.transfer.transaction.Transaction;
import net.neoforged.neoforge.transfer.transaction.TransactionContext;
import org.jspecify.annotations.Nullable;

import com.buildcraft.Config;
import com.buildcraft.transport.block.FluidPipeBlock;
import com.buildcraft.transport.block.PipeBlock;
import com.buildcraft.transport.blockentity.FluidPipeBlockEntity;
import com.buildcraft.transport.blockentity.PipeBlockEntity;
import com.buildcraft.transport.pipe.PipeBehaviour;
import com.buildcraft.transport.pipe.PulsedEnergyReceiver;

/**
 * Ports {@code PipeBehaviourWood} (which extends {@code PipeBehaviourDirectional}): a single facing, restricted
 * to a TILE (real inventory) neighbor only - never another pipe, matching {@code canFaceDirection}'s
 * {@code ConnectedType.TILE} check. {@code canConnect(face, PipeBehaviourWood)} always returning false in the
 * original (two Wood pipes can never connect to each other) is ported as {@link #canConnectToPipe}.
 * <p>
 * <b>No persistent power buffer</b> - ports source's real mechanic exactly: {@code PipeBehaviourWood} has no
 * battery/energy field at all. Power only ever exists for the single instant it's delivered:
 * {@code receivePower(microJoules, simulate)} calls straight into {@code extract()}, which synchronously
 * extracts {@code power / mjPerItem} items right then and returns whatever wasn't spent - nothing is ever
 * stored between calls. This port's first pass got this wrong (gave Wood pipes their own
 * {@code SimpleEnergyHandler} buffer, filled by an Engine's push and drained separately on tick) - a real,
 * user-reported "it's storing energy like a battery" bug: an Engine could keep depositing FE into an idle
 * buffer (nothing to extract, or between pulses) and it would bank up over many pump cycles, then dump a much
 * larger burst than any single pulse once conditions allowed extraction again. Fixed by making
 * {@link #getEnergyHandler()} return a handler whose {@code insert()} IS the extraction trigger itself -
 * {@link DemandEnergyHandler} below - {@code getAmountAsLong()} always 0, nothing carried between calls.
 * <p>
 * Not {@code final}: {@link WoodDiamondBehaviour} extends this to add a 9-slot item filter on top of the same
 * extraction loop, via the {@link #canExtract}/{@link #onExtracted} hooks - mirroring the original's own
 * {@code PipeBehaviourWoodDiamond extends PipeBehaviourWood} relationship.
 */
public class WoodBehaviour implements PipeBehaviour {
    private @Nullable Direction currentDir;
    private final DemandEnergyHandler energy = new DemandEnergyHandler();

    // Refreshed every tick (see #tick) so DemandEnergyHandler.insert - which fires from an Engine's own tick,
    // not from this pipe's - always has an up to date Level/BlockPos/PipeBlockEntity to work with.
    private @Nullable Level cachedLevel;
    private @Nullable BlockPos cachedPos;
    private @Nullable PipeBlockEntity cachedPipe;

    @Override
    public boolean connectsToContainers() {
        return true;
    }

    @Override
    public boolean canConnectToPipe(PipeBehaviour other) {
        return !(other instanceof WoodBehaviour);
    }

    /**
     * Real click-target precision found via testing: aiming at a specific thin connector arm is unreliable
     * (a plain click easily raytraces to a face - e.g. the central post's own top - that isn't a valid tile
     * face at all, previously a silent no-op). Falls back to cycling (same as a center/sneak click) whenever
     * the clicked face isn't a usable target, so a wrench click is never a dead end.
     */
    @Override
    public boolean onWrenchClick(Level level, BlockPos pipePos, @Nullable Direction clickedFace) {
        if (clickedFace != null && clickedFace != currentDir && isTileFace(level, pipePos, clickedFace)) {
            currentDir = clickedFace;
        } else {
            advanceFacing(level, pipePos);
        }
        return true;
    }

    private void advanceFacing(Level level, BlockPos pipePos) {
        Direction[] all = Direction.values();
        int start = currentDir == null ? -1 : currentDir.ordinal();
        for (int i = 1; i <= all.length; i++) {
            Direction candidate = all[(start + i) % all.length];
            if (isTileFace(level, pipePos, candidate)) {
                currentDir = candidate;
                return;
            }
        }
        currentDir = null;
    }

    /**
     * Wood only ever faces a real inventory - never another pipe, matching {@code canFaceDirection}. Dispatches
     * to the item or fluid pipe connectivity check depending on which concrete block entity is actually placed at
     * {@code pipePos}, since a single {@code WoodBehaviour} instance only ever backs one flavour for its whole
     * lifetime (either an item {@link PipeBlockEntity} or a fluid {@link FluidPipeBlockEntity}, never both) - the
     * same real bug/fix already applied to {@link IronBehaviour#isValidFacing}.
     */
    private static boolean isTileFace(Level level, BlockPos pipePos, Direction dir) {
        if (level.getBlockEntity(pipePos) instanceof FluidPipeBlockEntity) {
            return FluidPipeBlock.isConnectable(level, pipePos, dir) && !isFluidPipeNeighbor(level, pipePos, dir);
        }
        return PipeBlock.isConnectable(level, pipePos, dir) && !isPipeNeighbor(level, pipePos, dir);
    }

    private static boolean isPipeNeighbor(Level level, BlockPos pipePos, Direction dir) {
        return level.getBlockEntity(pipePos.relative(dir)) instanceof PipeBlockEntity;
    }

    private static boolean isFluidPipeNeighbor(Level level, BlockPos pipePos, Direction dir) {
        return level.getBlockEntity(pipePos.relative(dir)) instanceof FluidPipeBlockEntity;
    }

    /**
     * Ports {@code PipeBehaviourDirectional.onTick()}: auto-advances to a valid tile-connected face whenever the
     * current one is missing or no longer valid - the wrench is only for CHANGING which face is active, not for
     * enabling extraction in the first place. Extraction itself no longer happens here (see class javadoc) -
     * only ever synchronously inside {@link DemandEnergyHandler#insert}, driven by an Engine's own tick.
     */
    @Override
    public void tick(Level level, BlockPos pipePos, PipeBlockEntity pipe) {
        cachedLevel = level;
        cachedPos = pipePos;
        cachedPipe = pipe;
        if (currentDir == null || !isTileFace(level, pipePos, currentDir)) {
            advanceFacing(level, pipePos);
        }
    }

    /** Fluid-pipe parallel to {@link #tick}: a fluid Wood/Diamond-Wood pipe has no power-driven extraction (see
     * {@link #filterFluidDestinations}'s javadoc), but still needs the same lazy facing auto-advance so
     * {@code currentDir} - and therefore the valve texture and the input-only-face restriction - actually
     * activates, rather than staying permanently unset (this hook is the fluid pipe's only per-tick entry point;
     * {@link #tick} above is never invoked for a {@link FluidPipeBlockEntity}). */
    @Override
    public void fluidTick(Level level, BlockPos pipePos, FluidPipeBlockEntity pipe) {
        if (currentDir == null || !isTileFace(level, pipePos, currentDir)) {
            advanceFacing(level, pipePos);
        }
    }

    /** Which items this pipe is willing to extract - {@link WoodDiamondBehaviour} narrows this by its filter. */
    protected boolean canExtract(ItemStack stack) {
        return true;
    }

    /**
     * The colour tag to stamp on an item as it's extracted - {@link EmzuliBehaviour} reads this before
     * {@link #onExtracted} advances its round-robin slot, so the tag matches whichever slot the item actually
     * came from rather than whatever slot is current by the time the item later reaches the pipe's center.
     */
    protected @Nullable DyeColor colourForExtraction() {
        return null;
    }

    /** Called after a successful extraction - {@link WoodDiamondBehaviour} uses this to advance round-robin. */
    protected void onExtracted(ItemStack extracted) {}

    protected @Nullable Direction getCurrentDir() {
        return currentDir;
    }

    /** Ports {@code PipeBehaviourWood.getTextureData}: the extraction face shows the "valve" texture. */
    @Override
    public @Nullable Direction getValveDirection() {
        return currentDir;
    }

    /**
     * Ports real {@code PipeBehaviourWood.fluidSideCheck}: the wrench-set extraction face is input-only for fluid
     * too - fluid may never flow back OUT through it, matching the same asymmetric-face concept as item
     * extraction. Real source's fluid intake through that face is ALSO power-gated (its {@code extract()} spends
     * the same MJ budget on either items or fluid, whichever {@code IFlowItems}/{@code IFlowFluid} the pipe has) -
     * NOT ported here, since this whole fluid-pipe module already has every tier passively self-serve fluid from
     * any adjacent source (see {@code FluidPipeBlockEntity}'s documented deviation) rather than requiring an
     * Engine's power at all; porting the output-face restriction alone is a real, cheap, faithful piece of that
     * behaviour without pulling in the full power-gated-extraction machinery for uncertain benefit.
     */
    @Override
    public Set<Direction> filterFluidDestinations(Level level, BlockPos pipePos, Direction from, FluidResource fluid,
            Set<Direction> candidates) {
        if (currentDir == null || !candidates.contains(currentDir)) {
            return candidates;
        }
        Set<Direction> allowed = EnumSet.noneOf(Direction.class);
        allowed.addAll(candidates);
        allowed.remove(currentDir);
        return allowed;
    }

    /** Real {@code BCTransportConfig}: Wood's fluid pipe transfers {@code baseFlowRate}=10 mB/tick, delay 10 -
     * overridden by {@link WoodDiamondBehaviour} with Diamond-Wood's own real (different) fluid rate. */
    @Override
    public int fluidTransferPerTick() {
        return 10;
    }

    @Override
    public @Nullable EnergyHandler getEnergyHandler() {
        return energy;
    }

    @Override
    public void save(ValueOutput output) {
        if (currentDir != null) {
            output.store("currentDir", Direction.CODEC, currentDir);
        }
    }

    @Override
    public void load(ValueInput input) {
        currentDir = input.read("currentDir", Direction.CODEC).orElse(null);
    }

    /**
     * Ports {@code PipeBehaviourWood.receivePower}/{@code extract}: {@code insert()} IS the extraction - up to
     * {@code amount / feCost} items are pulled out and sent on their way in this single synchronous call, and
     * only the FE actually "spent" on real extractions is reported as accepted. No storage between calls.
     */
    private final class DemandEnergyHandler implements EnergyHandler, PulsedEnergyReceiver {
        @Override
        public long getAmountAsLong() {
            return 0;
        }

        @Override
        public long getCapacityAsLong() {
            return Config.PIPE_POWERED_MAX_FE_PER_TICK.get();
        }

        @Override
        public int insert(int amount, TransactionContext transaction) {
            Level level = cachedLevel;
            BlockPos pipePos = cachedPos;
            PipeBlockEntity pipe = cachedPipe;
            if (level == null || pipePos == null || pipe == null || currentDir == null) {
                return 0;
            }
            int feCost = Config.PIPE_WOOD_FE_PER_ITEM.get();
            int maxItems = amount / feCost;
            if (maxItems <= 0 || !isTileFace(level, pipePos, currentDir)) {
                return 0;
            }
            BlockPos neighborPos = pipePos.relative(currentDir);
            ResourceHandler<ItemResource> handler =
                    level.getCapability(Capabilities.Item.BLOCK, neighborPos, currentDir.getOpposite());
            if (handler == null) {
                return 0;
            }
            int itemsExtracted = 0;
            for (int i = 0; i < handler.size() && itemsExtracted < maxItems; i++) {
                ItemResource resource = handler.getResource(i);
                if (resource.isEmpty() || !canExtract(resource.toStack(1))) {
                    continue;
                }
                int extracted;
                try (Transaction tx = Transaction.open(transaction)) {
                    extracted = handler.extract(i, resource, 1, tx);
                    if (extracted > 0) {
                        tx.commit();
                    }
                }
                if (extracted > 0) {
                    ItemStack extractedStack = resource.toStack(extracted);
                    pipe.acceptItem(extractedStack, currentDir, colourForExtraction());
                    onExtracted(extractedStack);
                    itemsExtracted++;
                }
            }
            return itemsExtracted * feCost;
        }

        @Override
        public int extract(int amount, TransactionContext transaction) {
            return 0;
        }
    }
}
