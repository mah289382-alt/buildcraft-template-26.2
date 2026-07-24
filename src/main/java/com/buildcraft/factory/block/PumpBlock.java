package com.buildcraft.factory.block;

import com.mojang.serialization.MapCodec;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import org.jspecify.annotations.Nullable;

import com.buildcraft.factory.blockentity.MinerBlockEntity;
import com.buildcraft.factory.blockentity.PumpBlockEntity;

/**
 * Ports {@code BlockPump} (real source: {@code common/buildcraft/factory/block/BlockPump.java} - NOT directional,
 * confirmed via the real blockstate JSON having no facing variants at all, unlike the Mining Well). A real baked
 * JSON block model is used (plain textured cube, `"parent": "block/cube"`, same as the Mining Well) - all real
 * behaviour lives in {@link PumpBlockEntity}. No GUI (real source has none).
 */
public class PumpBlock extends Block implements EntityBlock {
    private final MapCodec<PumpBlock> codec = MapCodec.unit(() -> this);

    public PumpBlock(BlockBehaviour.Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<PumpBlock> codec() {
        return codec;
    }

    @Override
    public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new PumpBlockEntity(pos, state);
    }

    @Override
    public @Nullable <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        return (lvl, pos, st, be) -> MinerBlockEntity.tick(lvl, pos, st, (MinerBlockEntity) be);
    }
}
