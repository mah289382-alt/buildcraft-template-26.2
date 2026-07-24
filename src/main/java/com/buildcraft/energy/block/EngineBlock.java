package com.buildcraft.energy.block;

import java.util.Optional;
import java.util.function.BiFunction;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.ScheduledTickAccess;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.neoforge.capabilities.Capabilities;
import org.jspecify.annotations.Nullable;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;

import com.buildcraft.energy.blockentity.CombustionEngineBlockEntity;
import com.buildcraft.energy.blockentity.EngineBlockEntity;

import com.mojang.serialization.MapCodec;

/**
 * A single-face engine block, shared across all tiers (one {@code EngineBlock} instance per material, mirroring
 * {@code PipeBlock}'s pattern) - ports {@code BlockEngineBase_BC8}/{@code BlockEngine_BC8}, which in source is
 * one metadata-variant block for every tier; this port uses one distinct block per tier instead, matching how
 * every other module here already handles per-material variants (idiomatic in modern Minecraft, where
 * metadata-based blocks are deprecated in favour of one blockstate identity per block).
 * <p>
 * Collision shape is a plain full cube (a deliberate simplification vs. source's stepped base+trunk+piston
 * model - the real geometry is reproduced visually by the renderer, not the collision box, matching how solid
 * modded machines conventionally just use a full-cube hitbox regardless of a non-cube visual model).
 */
public class EngineBlock extends Block implements EntityBlock {
    private final BiFunction<BlockPos, BlockState, EngineBlockEntity> blockEntityFactory;

    public EngineBlock(BlockBehaviour.Properties properties, BiFunction<BlockPos, BlockState, EngineBlockEntity> blockEntityFactory) {
        super(properties);
        this.blockEntityFactory = blockEntityFactory;
        registerDefaultState(stateDefinition.any().setValue(BlockStateProperties.FACING, Direction.NORTH));
    }

    private final MapCodec<EngineBlock> codec = MapCodec.unit(() -> this);

    @Override
    protected MapCodec<EngineBlock> codec() {
        return codec;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(BlockStateProperties.FACING);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return Shapes.block();
    }

    /**
     * No static baked model at all - the real source geometry (a 4-element assembly with a runtime-animated
     * piston and stage-dependent trunk texture) has no vanilla-model equivalent, so
     * {@link com.buildcraft.energy.client.render.EngineBlockEntityRenderer} draws literally everything every
     * frame instead (matching how {@code QuarryBlock} lets its renderer draw the dynamic gantry/drill parts,
     * just taken further here since there's no static portion left to bake at all).
     */
    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.INVISIBLE;
    }

    /**
     * Ports {@code TileEngineBase_BC8.onPlacedBy}/{@code rotateIfInvalid()}: prefer a face that actually has a
     * power receiver on it (a real gap in this port's first pass - only wrench-triggered rotation was
     * implemented, so an engine placed while looking the "wrong" way relative to a Quarry/powered pipe would
     * never auto-correct, needing a manual wrench click the player might not think to do). Falls back to facing
     * away from the player - matching a furnace-style placement convention - only if no neighbor can receive
     * power at all yet.
     */
    @Override
    public @Nullable BlockState getStateForPlacement(BlockPlaceContext context) {
        Direction fallback = context.getNearestLookingDirection().getOpposite();
        Direction facing = findReceiverFacing(context.getLevel(), context.getClickedPos()).orElse(fallback);
        return defaultBlockState().setValue(BlockStateProperties.FACING, facing);
    }

    /**
     * Ports {@code rotateIfInvalid()}'s other trigger - re-checks facing whenever a neighbor changes, so an
     * engine placed BEFORE its target (e.g. before the Quarry next to it exists yet) still ends up correctly
     * facing it once that neighbor appears, without needing a manual wrench click.
     */
    @Override
    protected BlockState updateShape(BlockState state, LevelReader level, ScheduledTickAccess ticks, BlockPos pos,
            Direction direction, BlockPos neighborPos, BlockState neighborState, RandomSource random) {
        Direction currentFacing = state.getValue(BlockStateProperties.FACING);
        if (level instanceof Level realLevel && EngineBlockEntity.hasReceiver(realLevel, pos, currentFacing)) {
            return state;
        }
        if (level instanceof Level realLevel) {
            return findReceiverFacing(realLevel, pos).map(f -> state.setValue(BlockStateProperties.FACING, f)).orElse(state);
        }
        return state;
    }

    private static Optional<Direction> findReceiverFacing(Level level, BlockPos pos) {
        for (Direction dir : Direction.values()) {
            if (EngineBlockEntity.hasReceiver(level, pos, dir)) {
                return Optional.of(dir);
            }
        }
        return Optional.empty();
    }

    @Override
    public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return blockEntityFactory.apply(pos, state);
    }

    @Override
    public @Nullable <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        return (lvl, pos, st, be) -> EngineBlockEntity.tick(lvl, pos, st, (EngineBlockEntity) be);
    }

    /**
     * Wrench right-click rotates (delegates to {@link EngineBlockEntity#onWrenchClick}); any other right-click
     * opens the tier's GUI if it has one (Stirling/Combustion do, Redstone/Creative don't - matching source's
     * {@code onActivated} being entirely absent on the Redstone tile and Creative cycling output instead of
     * opening a menu, handled by that tier's own block entity rather than here).
     */
    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hitResult) {
        if (level.getBlockEntity(pos) instanceof MenuProvider provider && player instanceof ServerPlayer serverPlayer) {
            serverPlayer.openMenu(provider, pos);
            return InteractionResult.SUCCESS;
        }
        return InteractionResult.PASS;
    }

    /** Real source has no direct right-click-with-bucket interaction at all - a deliberate, disclosed addition
     * per direct user request: right-clicking a Combustion Engine with a Water/Oil/Fuel bucket fills the
     * matching tank straight away, with no GUI needed. Falls through to {@link #useWithoutItem} (opening the
     * GUI) via {@link InteractionResult#TRY_WITH_EMPTY_HAND} whenever the held item isn't a relevant bucket, or
     * for every other engine tier (only Combustion has fluid tanks at all).
     * <p>
     * The item-type check (not the actual tank mutation) MUST run identically on both sides - a real dupe bug,
     * traced via {@code ServerPlayerGameMode.useItemOn}: that method only skips vanilla's own
     * {@code itemStack.useOn(context)} (the bucket's normal place-fluid-in-world behavior) when this override's
     * result {@code consumesAction()}. The client runs this SAME dispatch locally for prediction, and an earlier
     * version gated the whole check behind {@code !level.isClientSide()}, so the client always fell through to
     * {@code TRY_WITH_EMPTY_HAND} and let the bucket's own predictive placement run - showing a real fluid block
     * appear at the clicked face. Since the server-side tank fill never touches that world position at all, the
     * server has nothing to correct there, so the client's phantom block was never un-placed: one bucket-worth
     * of fluid ended up existing in both the tank AND as a real block - the dupe. Fixed by making the ITEM-TYPE
     * check (cheap, no server-only state needed) run on both sides so both consistently return SUCCESS and
     * block the vanilla placement fallback; only the actual fluid transfer stays server-only. */
    @Override
    protected InteractionResult useItemOn(ItemStack itemStack, BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hitResult) {
        if (level.getBlockEntity(pos) instanceof CombustionEngineBlockEntity engine && engine.isFillableBucket(itemStack)) {
            if (!level.isClientSide()) {
                engine.tryFillFromHand(player, hand);
            }
            return InteractionResult.SUCCESS;
        }
        return InteractionResult.TRY_WITH_EMPTY_HAND;
    }
}
