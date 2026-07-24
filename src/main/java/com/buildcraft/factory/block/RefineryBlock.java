package com.buildcraft.factory.block;

import com.mojang.serialization.MapCodec;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.phys.BlockHitResult;
import org.jspecify.annotations.Nullable;

import com.buildcraft.factory.blockentity.RefineryBlockEntity;

/**
 * Ports {@code BlockRefinery}/{@code TileRefinery} (the classic simple Refinery, real source at
 * {@code src_old_license/buildcraft/factory/BlockRefinery.java} - the modern "Neptune" tree only has stub
 * assets for this block with no matching Java class, so the old-license tree is the real source of truth
 * here, not the modern {@code BlockDistiller} thermal-distillation machine). Real source IS 4-way horizontally
 * directional (confirmed via the real blockstate JSON's {@code facing: east/west/north/south} variants and
 * {@code RenderRefinery} reading {@code BuildCraftProperties.BLOCK_FACING} to rotate the whole model - an
 * earlier pass wrongly assumed "no facing" without checking). No static baked model at all (real source:
 * {@code getRenderType()==2}/{@code isOpaqueCube()==false}, modern model file is {@code builtin/entity}) - like
 * {@code EngineBlock}, {@link com.buildcraft.factory.client.render.RefineryBlockEntityRenderer} draws
 * literally everything, including the real 3-tank + 2-sliding-magnet moving parts (see that renderer's javadoc).
 */
public class RefineryBlock extends Block implements EntityBlock {
    private final MapCodec<RefineryBlock> codec = MapCodec.unit(() -> this);

    public RefineryBlock(BlockBehaviour.Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH));
    }

    @Override
    protected MapCodec<RefineryBlock> codec() {
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
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.INVISIBLE;
    }

    @Override
    public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new RefineryBlockEntity(pos, state);
    }

    @Override
    public @Nullable <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        return (lvl, pos, st, be) -> RefineryBlockEntity.tick(lvl, pos, st, (RefineryBlockEntity) be);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hitResult) {
        if (level.getBlockEntity(pos) instanceof MenuProvider provider && player instanceof ServerPlayer serverPlayer) {
            serverPlayer.openMenu(provider, pos);
            return InteractionResult.SUCCESS;
        }
        return InteractionResult.PASS;
    }

    /** Real source has {@code BlockRefinery.onBlockActivated} try this BEFORE opening the GUI: holding a FILLED
     * container fills the tile, holding an EMPTY container drains it - ported here rather than a strict "no
     * direct interaction" port, per direct user request. The item-type check (not the actual tank mutation)
     * MUST run identically on both client and server - same real dupe-bug shape already found and fixed for
     * {@code EngineBlock.useItemOn}: the client runs this same dispatch locally for prediction, and gating the
     * whole check behind server-only would let the client's own bucket-placement prediction run unchecked,
     * producing a permanent duplicate since the server never touches the world position that got "placed". */
    @Override
    protected InteractionResult useItemOn(ItemStack itemStack, BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hitResult) {
        if (level.getBlockEntity(pos) instanceof RefineryBlockEntity refinery && refinery.isRelevantBucket(itemStack)) {
            if (!level.isClientSide()) {
                refinery.tryFillOrDrainFromHand(player, hand);
            }
            return InteractionResult.SUCCESS;
        }
        return InteractionResult.TRY_WITH_EMPTY_HAND;
    }
}
