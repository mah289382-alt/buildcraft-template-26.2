package com.buildcraft.factory.block;

import com.mojang.serialization.MapCodec;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.phys.BlockHitResult;
import org.jspecify.annotations.Nullable;

import com.buildcraft.factory.blockentity.AutoWorkbenchBlockEntity;

/**
 * Ports {@code BlockAutoWorkbenchItems}/{@code TileAutoWorkbenchItems} - a "ghost recipe" auto-crafter, see
 * {@link AutoWorkbenchBlockEntity} for the real crafting/tag-matching logic. Real source's model is a plain
 * static textured cube (confirmed via the real blockstate/model JSON - a {@code cube_all}-shaped block with a
 * distinct top texture, non-directional) - unlike the Engine/Refinery's fully custom-rendered blocks, no
 * facing property and no custom {@code BlockEntityRenderer} are needed here.
 */
public class AutoWorkbenchBlock extends Block implements EntityBlock {
    private final MapCodec<AutoWorkbenchBlock> codec = MapCodec.unit(() -> this);

    public AutoWorkbenchBlock(BlockBehaviour.Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<AutoWorkbenchBlock> codec() {
        return codec;
    }

    @Override
    public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new AutoWorkbenchBlockEntity(pos, state);
    }

    @Override
    public @Nullable <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        return (lvl, pos, st, be) -> AutoWorkbenchBlockEntity.tick(lvl, pos, st, (AutoWorkbenchBlockEntity) be);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hitResult) {
        if (level.getBlockEntity(pos) instanceof MenuProvider provider) {
            if (player instanceof ServerPlayer serverPlayer) {
                serverPlayer.openMenu(provider, pos);
            }
            return InteractionResult.SUCCESS;
        }
        return InteractionResult.PASS;
    }
}
