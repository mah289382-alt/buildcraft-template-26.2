package com.buildcraft.factory.block;

import com.mojang.serialization.MapCodec;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import org.jspecify.annotations.Nullable;

import com.buildcraft.factory.blockentity.MinerBlockEntity;
import com.buildcraft.factory.blockentity.MiningWellBlockEntity;

/**
 * Ports {@code BlockMiningWell} (real source: {@code common/buildcraft/factory/block/BlockMiningWell.java},
 * real {@code IBlockWithFacing} - confirmed genuinely directional via the real blockstate JSON's
 * {@code facing: east/south/west/north} variants, each just rotating the same real cube model). A real baked
 * JSON block model is used (unlike the Refinery/Engine/Tank's custom-rendered frames) - real source's own model
 * is a plain textured cube (`"parent": "block/cube"`), nothing to custom-render here; all the real behaviour
 * lives in {@link MiningWellBlockEntity}. No GUI (real source has none for this block).
 */
public class MiningWellBlock extends Block implements EntityBlock {
    private final MapCodec<MiningWellBlock> codec = MapCodec.unit(() -> this);

    public MiningWellBlock(BlockBehaviour.Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH));
    }

    @Override
    protected MapCodec<MiningWellBlock> codec() {
        return codec;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(BlockStateProperties.HORIZONTAL_FACING);
    }

    @Override
    public @Nullable BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, context.getHorizontalDirection().getOpposite());
    }

    @Override
    public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new MiningWellBlockEntity(pos, state);
    }

    @Override
    public @Nullable <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        return (lvl, pos, st, be) -> MinerBlockEntity.tick(lvl, pos, st, (MinerBlockEntity) be);
    }
}
