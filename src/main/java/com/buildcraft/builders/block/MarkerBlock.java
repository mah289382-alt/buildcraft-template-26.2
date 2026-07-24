package com.buildcraft.builders.block;

import com.mojang.serialization.MapCodec;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jspecify.annotations.Nullable;

import com.buildcraft.builders.BuildersContent;
import com.buildcraft.builders.blockentity.MarkerBlockEntity;

/**
 * Placed in pairs to define a custom rectangular mining area for a Quarry, instead of the fixed default size.
 * Two markers automatically link to their nearest unlinked partner (within range) when placed.
 */
public class MarkerBlock extends Block implements EntityBlock {
    public static final MapCodec<MarkerBlock> CODEC = simpleCodec(MarkerBlock::new);

    private static final VoxelShape SHAPE = Block.box(6, 0, 6, 10, 10, 10);

    public MarkerBlock(BlockBehaviour.Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<MarkerBlock> codec() {
        return CODEC;
    }

    @Override
    protected VoxelShape getShape(BlockState state, net.minecraft.world.level.BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPE;
    }

    @Override
    public @Nullable BlockEntity newBlockEntity(BlockPos worldPosition, BlockState blockState) {
        return new MarkerBlockEntity(worldPosition, blockState);
    }

    @Override
    public <T extends BlockEntity> @Nullable BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        if (type != BuildersContent.MARKER_BLOCK_ENTITY.get()) {
            return null;
        }
        return (lvl, pos, blockState, be) -> MarkerBlockEntity.tick(lvl, pos, blockState, (MarkerBlockEntity) be);
    }
}
