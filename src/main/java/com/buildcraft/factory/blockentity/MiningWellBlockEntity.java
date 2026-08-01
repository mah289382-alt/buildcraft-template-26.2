package com.buildcraft.factory.blockentity;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.neoforged.neoforge.capabilities.BlockCapabilityCache;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.transfer.ResourceHandler;
import net.neoforged.neoforge.transfer.item.ItemResource;
import net.neoforged.neoforge.transfer.transaction.Transaction;

import com.buildcraft.BuildCraft;
import com.buildcraft.Config;
import com.buildcraft.factory.FactoryContent;
import com.buildcraft.transport.pipe.PipeConnectable;

/**
 * Ports real {@code TileMiningWell} (real source: {@code common/buildcraft/factory/tile/TileMiningWell.java},
 * extending the shared {@link MinerBlockEntity}). Digs a single column straight down from its own position,
 * breaking one block at a time at real FE cost {@code = energyPerHardnessPoint * (hardness + 1)} (the exact same
 * formula as the Quarry - {@code BlockUtil.computeBlockBreakPower} in source), routing drops to an adjacent
 * inventory exactly like the Quarry's own {@code routeDrop}. Real per-position rule
 * (real {@code canBreak()}, re-derived from intent rather than a 1:1 port of an unread private helper): air is
 * skipped over (not an obstruction, just nothing there yet); a fluid position is passable only if its viscosity
 * is low enough (real threshold 1000 - water passes, this mod's own Oil and vanilla Lava don't - matches real
 * {@code fluid.getViscosity() <= 1000}); any other non-air, unbreakable-or-blocked position halts the well
 * entirely (matches real: hits an obstruction, stops scanning further down).
 * <p>
 * DEVIATION FROM SOURCE (explicit user request - see {@link MinerBlockEntity}'s class javadoc for the full
 * reasoning): the well no longer scans AHEAD to find its next breakable position before growing toward it. It
 * grows one probe at a time (driven purely by power, via the base class), and only checks {@link #canBreak} once
 * the shaft's growth is ABOUT to reach a new position ({@link MinerBlockEntity#getTipPos}, real untouched world
 * state - see that class's own bug-fix note) - air just lets growth continue past it untouched, a breakable
 * block/fluid pauses growth right there to work on it, and an unbreakable obstruction halts permanently. No more
 * upfront detection before extending at all.
 */
public class MiningWellBlockEntity extends MinerBlockEntity implements PipeConnectable {
    public MiningWellBlockEntity(BlockPos pos, BlockState state) {
        super(FactoryContent.MINING_WELL_BLOCK_ENTITY.get(), pos, state,
                Config.MINING_WELL_ENERGY_CAPACITY.get(), Config.MINING_WELL_MAX_FE_PER_TICK.get());
    }

    /** Real perf fix (2026-07-31 FPS/TPS audit): {@link #routeDrop} used to call the raw, uncached
     * {@code level.getCapability(...)} for up to 6 directions per mined-block drop - see
     * {@code FluidPipeBlockEntity}'s own equivalent field for the full reasoning (NeoForge's own
     * {@link BlockCapabilityCache}, no caching in the raw call). One shared, per-direction cache. */
    private final Map<Direction, BlockCapabilityCache<ResourceHandler<ItemResource>, Direction>> itemCapCache = new EnumMap<>(Direction.class);

    @Override
    protected void mine(Level level, BlockPos pos) {
        if (isComplete()) {
            return;
        }
        BlockPos tip = getTipPos(pos);
        if (!paused) {
            if (level.isOutsideBuildHeight(tip)) {
                stopped = true;
                return;
            }
            if (!canBreak(level, tip)) {
                BlockState tipState = level.getBlockState(tip);
                if (tipState.isAir()) {
                    return; // nothing here - growth commits it for free this tick, keep probing next tick
                }
                BuildCraft.LOGGER.info("MiningWell at {}: STOPPED at {} - unbreakable ({})", pos, tip, tipState);
                stopped = true; // real obstruction (e.g. bedrock) - halt forever
                return;
            }
            paused = true;
            progress = 0;
            BuildCraft.LOGGER.info("MiningWell at {}: PAUSED at {} to break {} (cost={}, energy stored={})", pos, tip,
                    level.getBlockState(tip), breakCost(level.getBlockState(tip).getDestroySpeed(level, tip)), energy.getAmountAsLong());
        }

        long target = breakCost(level.getBlockState(tip).getDestroySpeed(level, tip));
        try (Transaction tx = Transaction.openRoot()) {
            int extracted = energy.extract((int) Math.min(Integer.MAX_VALUE, target - progress), tx);
            if (extracted > 0) {
                tx.commit();
            }
            progress += extracted;
        }
        if (progress >= target) {
            progress = 0;
            mineAndRouteDrops(level, tip);
            BuildCraft.LOGGER.info("MiningWell at {}: RESUMED past {}", pos, tip);
            paused = false; // resume probing deeper past it
        }
    }

    private static long breakCost(float hardness) {
        return Math.round(Config.MINING_WELL_ENERGY_PER_HARDNESS_POINT.get() * (hardness + 1.0));
    }

    private boolean canBreak(Level level, BlockPos target) {
        FluidState fluidState = level.getFluidState(target);
        if (!fluidState.isEmpty()) {
            return fluidState.getType().getFluidType().getViscosity() <= 1000;
        }
        BlockState state = level.getBlockState(target);
        if (state.isAir()) {
            return false;
        }
        float hardness = state.getDestroySpeed(level, target);
        return hardness >= 0;
    }

    @Override
    protected Block getTubeBlock() {
        return FactoryContent.MINING_WELL_TUBE_BLOCK.get();
    }

    /** Ports the Quarry's own {@code mineAndRouteDrops}/{@code routeDrop} exactly (same real
     * {@code InventoryUtil.addToBestAcceptor} intent: push drops into any adjacent inventory, falling back to
     * spawning an item entity for anything that doesn't fit). */
    private void mineAndRouteDrops(Level level, BlockPos target) {
        if (!(level instanceof ServerLevel serverLevel)) {
            level.destroyBlock(target, true, null, 512);
            return;
        }
        BlockState state = level.getBlockState(target);
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
}
