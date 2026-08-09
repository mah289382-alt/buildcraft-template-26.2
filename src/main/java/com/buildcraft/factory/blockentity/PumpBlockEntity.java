package com.buildcraft.factory.blockentity;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.fluids.FluidType;
import net.neoforged.neoforge.transfer.ResourceHandler;
import net.neoforged.neoforge.transfer.fluid.FluidResource;
import net.neoforged.neoforge.transfer.fluid.FluidStacksResourceHandler;
import net.neoforged.neoforge.transfer.transaction.Transaction;
import org.jspecify.annotations.Nullable;

import com.buildcraft.BuildCraft;
import com.buildcraft.Config;
import com.buildcraft.factory.FactoryContent;

/**
 * Ports real {@code TilePump} (real source: {@code common/buildcraft/factory/tile/TilePump.java}, also extending
 * the shared {@link MinerBlockEntity}). Unlike the Mining Well, a Pump never breaks blocks - once its shaft's
 * growth reaches a fluid position, it flood-fills outward from there (real {@code buildQueue}/{@code buildQueue0}:
 * liquids search {@code UP,NORTH,SOUTH,WEST,EAST} - never {@code DOWN}, matching real source; gaseous fluids
 * search the opposite set, not ported since this project has no gaseous fluids) up to
 * {@link Config#PUMP_MAX_DISTANCE} blocks, collecting every connected SOURCE block of the same fluid into a
 * drain queue. Draining one source block costs a real FIXED FE amount (not hardness-based - draining doesn't
 * have a "hardness" concept), fills the Pump's own internal buffer (real: 16 buckets), and pauses once that
 * buffer is over half full (real: {@code tank.getFluidAmount() > tank.getCapacity() / 2}) to leave room for
 * outgoing flow.
 * <p>
 * DEVIATION FROM SOURCE (explicit user request - see {@link MinerBlockEntity}'s class javadoc for the full
 * reasoning): real source's {@code buildQueue} scans straight down for the first fluid BEFORE any shaft growth
 * happens at all. Here, the downward scan is gone entirely - the shaft grows one probe at a time (driven purely
 * by power, via the base class), and only checks for fluid once growth is ABOUT to reach a new position
 * ({@link MinerBlockEntity#getTipPos}, real untouched world state): air lets growth continue past it untouched,
 * a solid obstruction halts permanently (a Pump can't dig through rock), and fluid pauses growth right there to
 * flood-fill and drain it - {@link #buildQueueAt} is the same real flood-fill algorithm, just seeded from the
 * already-known tip position instead of also performing the downward search itself.
 * <p>
 * <b>Real bug fixed</b> (see {@link MinerBlockEntity}'s own bug-fix note for the full root cause): the shaft's
 * growth used to unconditionally overwrite whatever real block occupied a newly-reached depth with its own
 * invisible tube marker BEFORE this class ever got to inspect it, so the "halt at a solid obstruction" check
 * above could never actually see a real obstruction (only ever its own already-placed tube, which reads as
 * passable) - the Pump tunnelled straight through solid rock (and would have gone through bedrock too) hunting
 * for fluid, instead of stopping the instant the ground in front of it wasn't air or a fluid. Fixed at the base
 * class level - {@code mine} now always sees genuine, unmolested world state.
 * <p>
 * Real source's infinite-water-source detection is ported in simplified form: real source additionally checks
 * whether the position directly below a 2-source-neighbour point is itself water or solid (the exact same check
 * vanilla's own {@code LiquidBlock} uses to decide if a water source regenerates); this port simplifies that to
 * "2 or more matching-fluid neighbours found during the flood-fill", which is close to vanilla's actual
 * infinite-source heuristic without needing to re-derive the "solid below" check outside a block's own tick.
 * Real source's Oil Spring integration ({@code ITileOilSpring}) is NOT ported - this project has no Oil Spring
 * block (see [[project-status]] memory - explicitly listed as not-started).
 * <p>
 * Doesn't actively push its collected fluid outward (real source's {@code FluidUtilBC.pushFluidAround} every
 * tick) - instead just exposes {@link #getFluidHandler()} as a real {@code Capabilities.Fluid.BLOCK} target,
 * relying on a consumer (a fluid pipe's own passive self-pull - see
 * {@code com.buildcraft.transport.blockentity.FluidPipeBlockEntity}'s documented deviation - or a future Tank/
 * right-click) to pull from it. Simpler, and this pump's own tank is genuinely just storage either way.
 */
public class PumpBlockEntity extends MinerBlockEntity {
    public static boolean DEBUG_LOG = true;
    private long debugFeAccum = 0;
    private int debugBucketsAccum = 0;
    private long debugTotalBucketsFilled = 0;
    private long debugTotalWorldBlocksRemoved = 0;

    private static final Direction[] SEARCH_DIRECTIONS =
            { Direction.UP, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST };

    private final FluidStacksResourceHandler tank = new FluidStacksResourceHandler(1, Config.PUMP_TANK_CAPACITY.get()) {
        @Override
        protected void onContentsChanged(int index, net.neoforged.neoforge.fluids.FluidStack previousContents) {
            setChanged();
        }
    };

    private boolean queueBuilt = false;
    private @Nullable BlockPos currentPos;
    private final Set<BlockPos> reachable = new HashSet<>();
    private final Deque<BlockPos> queue = new ArrayDeque<>();
    private boolean isInfiniteWaterSource = false;
    private long nextRebuildTick = 0;

    public PumpBlockEntity(BlockPos pos, BlockState state) {
        super(FactoryContent.PUMP_BLOCK_ENTITY.get(), pos, state,
                Config.PUMP_ENERGY_CAPACITY.get(), Config.PUMP_MAX_FE_PER_TICK.get());
    }

    public ResourceHandler<FluidResource> getFluidHandler() {
        return tank;
    }

    private void logPumpDebug(Level level, BlockPos pos) {
        if (DEBUG_LOG && level.getGameTime() % 100 == 0) {
            BuildCraft.LOGGER.info(
                    "[PUMP_DEBUG] pos={} stored={}/{} FE consumed last 100t={} FE ({}/t avg) bucketsDrained last 100t={} tank={}/{} mB",
                    pos, energy.getAmountAsLong(), Config.PUMP_ENERGY_CAPACITY.get(), debugFeAccum, debugFeAccum / 100.0,
                    debugBucketsAccum, tank.getAmountAsLong(0), Config.PUMP_TANK_CAPACITY.get());
            debugFeAccum = 0;
            debugBucketsAccum = 0;
        }
    }

    @Override
    protected void mine(Level level, BlockPos pos) {
        if (isComplete()) {
            return;
        }

        if (!queueBuilt) {
            BlockPos tip = getTipPos(pos);
            if (level.isOutsideBuildHeight(tip)) {
                stopped = true;
                return;
            }
            FluidState fs = level.getFluidState(tip);
            if (fs.isEmpty()) {
                // getTipPos now always points at real, untouched world state (see MinerBlockEntity's own
                // bug-fix note) - growth hasn't been allowed to overwrite this position with a tube marker yet,
                // so an air/non-air check here is genuinely reliable: a real solid obstruction (not fluid, not
                // air) halts the Pump permanently instead of silently tunnelling through it.
                BlockState tipState = level.getBlockState(tip);
                if (!tipState.isAir()) {
                    BuildCraft.LOGGER.info("Pump at {}: STOPPED at {} - solid obstruction ({})", pos, tip, tipState);
                    stopped = true; // real solid obstruction - a Pump can't dig through it
                }
                return; // air: nothing here yet, growth commits it for free this tick, keep probing next tick
            }
            paused = true;
            buildQueueAt(level, tip, fs.getType());
            queueBuilt = true;
            nextPos();
            BuildCraft.LOGGER.info("Pump at {}: PAUSED at {} - found fluid {}, queue size={}", pos, tip, fs.getType(), queue.size());
        }

        if (tank.getAmountAsLong(0) > (long) Config.PUMP_TANK_CAPACITY.get() / 2) {
            logPumpDebug(level, pos);
            return;
        }

        if (currentPos != null && reachable.contains(currentPos)) {
            long target = Config.PUMP_ENERGY_PER_DRAIN.get();
            try (Transaction tx = Transaction.openRoot()) {
                int extracted = energy.extract((int) Math.min(Integer.MAX_VALUE, target - progress), tx);
                if (extracted > 0) {
                    tx.commit();
                }
                progress += extracted;
                debugFeAccum += extracted;
            }
            if (progress < target) {
                logPumpDebug(level, pos);
                return;
            }
            FluidState fluidState = level.getFluidState(currentPos);
            if (fluidState.isSource() && canDrain(fluidState)) {
                FluidResource resource = FluidResource.of(fluidState.getType());
                boolean tankGained = false;
                try (Transaction tx = Transaction.openRoot()) {
                    int filled = tank.insert(0, resource, FluidType.BUCKET_VOLUME, tx);
                    if (filled > 0) {
                        tx.commit();
                        debugBucketsAccum++;
                        debugTotalBucketsFilled++;
                        tankGained = true;
                    }
                }
                progress = 0;
                logPumpDebug(level, pos);
                if (isInfiniteWaterSource && Config.PUMP_CONSUMES_WATER.get()) {
                    isInfiniteWaterSource = false;
                }
                boolean worldBlockRemoved = false;
                if (!isInfiniteWaterSource) {
                    level.setBlock(currentPos, Blocks.AIR.defaultBlockState(), 3);
                    worldBlockRemoved = true;
                    debugTotalWorldBlocksRemoved++;
                    reachable.remove(currentPos);
                    nextPos();
                }
                if (DEBUG_LOG) {
                    BuildCraft.LOGGER.info(
                            "[PUMP_DUPE_CHECK] pos={} drainedAt={} fluid={} tankGained={} worldBlockRemoved={} isInfiniteWaterSource={} totalBucketsFilled={} totalWorldBlocksRemoved={}",
                            pos, currentPos, fluidState.getType(), tankGained, worldBlockRemoved, isInfiniteWaterSource,
                            debugTotalBucketsFilled, debugTotalWorldBlocksRemoved);
                }
                return;
            }
            // The fluid here changed since the queue was built (drained by something else, or flowed away) -
            // fall through to a rebuild below.
        } else if (currentPos != null || level.getGameTime() < nextRebuildTick) {
            return;
        }
        nextRebuildTick = level.getGameTime() + 30;

        // Rebuild the queue at the SAME tip depth we're paused at (no downward re-scan - the shaft is already
        // there). If the pool is genuinely gone, resume probing deeper instead of staying stuck forever.
        BlockPos tip = getTipPos(pos);
        FluidState fs = level.getFluidState(tip);
        if (fs.isEmpty()) {
            queueBuilt = false;
            paused = false;
            return;
        }
        buildQueueAt(level, tip, fs.getType());
        nextPos();
        if (currentPos == null) {
            queueBuilt = false;
            paused = false;
        }
    }

    @Override
    protected Block getTubeBlock() {
        return FactoryContent.PUMP_TUBE_BLOCK.get();
    }

    private boolean canDrain(FluidState fluidState) {
        FluidResource stored = tank.getResource(0);
        return stored.isEmpty() || stored.getFluid() == fluidState.getType();
    }

    private void nextPos() {
        currentPos = queue.pollLast();
    }

    /** Real {@code buildQueue0}: flood-fills outward from an ALREADY-KNOWN seed position (the shaft's own tip,
     * once its growth reaches fluid - see class javadoc for why this no longer also performs the downward
     * search itself), collecting every connected same-fluid SOURCE block within {@link Config#PUMP_MAX_DISTANCE}. */
    private void buildQueueAt(Level level, BlockPos seedPos, Fluid queueFluid) {
        queue.clear();
        reachable.clear();
        isInfiniteWaterSource = false;

        Set<BlockPos> checked = new HashSet<>();
        checked.add(seedPos);
        reachable.add(seedPos);
        if (level.getFluidState(seedPos).isSource()) {
            queue.add(seedPos);
        }
        boolean isWater = !Config.PUMP_CONSUMES_WATER.get() && queueFluid.isSame(Fluids.WATER);
        long maxDistSq = (long) Config.PUMP_MAX_DISTANCE.get() * Config.PUMP_MAX_DISTANCE.get();
        boolean hitSearchLimit = false;

        List<BlockPos> frontier = new ArrayList<>();
        frontier.add(seedPos);
        outer:
        while (!frontier.isEmpty()) {
            List<BlockPos> next = new ArrayList<>();
            for (BlockPos current : frontier) {
                int matches = 0;
                for (Direction dir : SEARCH_DIRECTIONS) {
                    BlockPos neighbor = current.relative(dir);
                    if (neighbor.distSqr(seedPos) > maxDistSq) {
                        // The flood-fill would have kept expanding past PUMP_MAX_DISTANCE here - the lake
                        // genuinely continues beyond the pump's search radius, not just "ran out of oil".
                        if (!level.getFluidState(neighbor).isEmpty() && level.getFluidState(neighbor).getType().isSame(queueFluid)) {
                            hitSearchLimit = true;
                        }
                        continue;
                    }
                    if (!checked.add(neighbor)) {
                        matches++;
                        continue;
                    }
                    FluidState fs = level.getFluidState(neighbor);
                    if (!fs.isEmpty() && fs.getType().isSame(queueFluid)) {
                        reachable.add(neighbor);
                        if (fs.isSource()) {
                            queue.add(neighbor);
                        }
                        next.add(neighbor);
                        matches++;
                    }
                }
                if (isWater && matches >= 2) {
                    isInfiniteWaterSource = true;
                    break outer;
                }
            }
            frontier = next;
        }
        if (DEBUG_LOG) {
            BuildCraft.LOGGER.info(
                    "[PUMP_FLOODFILL_DEBUG] pos={} seed={} fluid={} reachableTiles={} realSourcesFound={} maxDistance={} hitSearchLimit={} isInfiniteWaterSource={}",
                    worldPosition, seedPos, queueFluid, reachable.size(), queue.size(), Config.PUMP_MAX_DISTANCE.get(),
                    hitSearchLimit, isInfiniteWaterSource);
        }
    }
}
