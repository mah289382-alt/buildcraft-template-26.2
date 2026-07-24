package com.buildcraft.builders.block;

import com.mojang.serialization.MapCodec;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.ScheduledTickAccess;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.item.context.BlockPlaceContext;
import org.jspecify.annotations.Nullable;

import com.buildcraft.builders.BuildersContent;
import com.buildcraft.builders.blockentity.QuarryBlockEntity;

public class QuarryBlock extends HorizontalDirectionalBlock implements EntityBlock {
    public static final MapCodec<QuarryBlock> CODEC = simpleCodec(QuarryBlock::new);

    public QuarryBlock(BlockBehaviour.Properties properties) {
        super(properties);
        BlockState defaultState = this.stateDefinition.any().setValue(FACING, Direction.NORTH);
        for (Direction dir : Direction.values()) {
            defaultState = defaultState.setValue(ConnectedTextures.propertyFor(dir), false);
        }
        this.registerDefaultState(defaultState);
    }

    @Override
    protected MapCodec<QuarryBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<net.minecraft.world.level.block.Block, BlockState> builder) {
        builder.add(FACING);
        ConnectedTextures.addProperties(builder);
    }

    @Override
    public @Nullable BlockState getStateForPlacement(BlockPlaceContext context) {
        BlockState state = this.defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
        return ConnectedTextures.initial(state, context.getLevel(), context.getClickedPos(), ConnectedTextures::isFrameOrQuarry);
    }

    @Override
    protected BlockState updateShape(BlockState state, LevelReader level, ScheduledTickAccess ticks, BlockPos pos,
            Direction direction, BlockPos neighborPos, BlockState neighborState, RandomSource random) {
        return ConnectedTextures.updateOne(state, direction, neighborState, ConnectedTextures::isFrameOrQuarry);
    }

    @Override
    public @Nullable BlockEntity newBlockEntity(BlockPos worldPosition, BlockState blockState) {
        return new QuarryBlockEntity(worldPosition, blockState);
    }

    @Override
    public <T extends BlockEntity> @Nullable BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        if (type != BuildersContent.QUARRY_BLOCK_ENTITY.get()) {
            return null;
        }
        return (lvl, pos, blockState, be) -> QuarryBlockEntity.tick(lvl, pos, blockState, (QuarryBlockEntity) be);
    }
}
