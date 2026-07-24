package com.buildcraft.factory.block;

import com.mojang.serialization.MapCodec;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Ports real {@code BlockTube} (real source: {@code common/buildcraft/factory/block/BlockTube.java}) - the
 * physical filler segment a Mining Well/Pump places down its own shaft as it works, real bounding box
 * (4/16..12/16 horizontal, full height) matched originally.
 * <p>
 * DEVIATION FROM SOURCE (explicit user request, after several rounds of visual iteration): this block is now a
 * pure, invisible marker - no model geometry (see its {@code mining_well_tube.json}/{@code pump_tube.json}, both
 * emptied of elements), no collision, no selection outline. It still exists as a real placed block purely so
 * {@code MinerBlockEntity}'s own obstruction-avoidance scans ("have I already dug/reached this position") have
 * something concrete to check against - the ENTIRE visible shaft is now one continuous cosmetic beam drawn by
 * {@code MinerBlockEntityRenderer}, sourced from the machine and growing smoothly with no per-block "pop" or
 * seam. Making the real blocks visible (an earlier iteration) caused repeated z-fighting/flicker/stutter bugs
 * against that cosmetic beam - see {@code MinerBlockEntityRenderer}'s class javadoc for the full history.
 * <p>
 * Real source makes this block player-unbreakable while its owning miner still exists ({@code removedByPlayer}
 * walks upward looking for a live {@code TileMiner}) - simplified here to flatly unbreakable
 * ({@code strength(-1, 3600000)}, the same convention vanilla uses for Bedrock/Barrier), since only the owning
 * {@link com.buildcraft.factory.blockentity.MinerBlockEntity} itself ever places/removes these blocks directly
 * via {@code Level.setBlock} (which bypasses normal breakability entirely) - a player was never going to be
 * able to legitimately obtain or need to manually clear one either way.
 */
public class TubeBlock extends Block {
    private final MapCodec<TubeBlock> codec = MapCodec.unit(() -> this);

    public TubeBlock(BlockBehaviour.Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<TubeBlock> codec() {
        return codec;
    }

    /** No outline/selection - matches the empty model and empty collision shape (see below); this is a pure
     * invisible marker now. */
    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return Shapes.empty();
    }

    @Override
    protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return Shapes.empty();
    }
}
