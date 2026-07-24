package com.buildcraft.factory.block;

import com.mojang.serialization.MapCodec;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.ScheduledTickAccess;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jspecify.annotations.Nullable;

import com.buildcraft.factory.blockentity.TankBlockEntity;

/**
 * Ports {@code BlockTank}/{@code TileTank} (real source: {@code common/buildcraft/factory/block/BlockTank.java} +
 * {@code tile/TileTank.java} - unlike the Refinery, the modern "Neptune" tree here IS the complete, current
 * implementation, with a matching old-license version kept only for historical comparison, not used). A generic
 * fluid-storage block (any single fluid, unlike the Refinery's fixed Oil/Fuel) whose standout feature is real
 * vertical stacking: placing Tanks on top of each other merges them into one combined store (see
 * {@link TankBlockEntity#getTanks()}/{@link TankBlockEntity#getFluidHandler()}), with the touching cap faces
 * visually merging too (real: {@code shouldSideBeRendered}, ported here as {@link #skipRendering} - the modern
 * equivalent hook, confirmed present on this version's {@code BlockBehaviour}).
 * <p>
 * Real bounding box ({@code BlockTank.BOUNDING_BOX}): inset 2px on X/Z, full height.
 * <p>
 * The frame is now (2026-07-21) fully custom-rendered, like the Refinery/Engine, NOT via the real baked JSON
 * block model this was originally ported with ({@code models/block/tank.json}/{@code tank_joined_below.json} -
 * kept only as a stub for the particle-break texture, matching how {@code EngineBlock}'s own kept model works;
 * {@link #getRenderShape} returns {@link RenderShape#INVISIBLE}). 2 real bugs drove this switch: (1) a static
 * JSON model's quads are single-sided (backface-culled) - looking through the tank's own transparent "window"
 * texture toward a far wall, that far wall's quad faces AWAY from the viewer and gets culled, so nothing was
 * there ("east wall looking inward toward west" reported as literally empty) - there is no JSON-model-level way
 * to make a quad visible from both sides, only hand-built geometry with explicit reversed-winding duplicate
 * quads can (see {@code TankBlockEntityRenderer}). (2) the item's icon was rendering with a missing texture -
 * root-caused by comparing against this project's own working precedents: {@code RefineryBlock}'s and
 * {@code EngineBlock}'s item models are self-contained ({@code "parent": "block/block"} with their own embedded
 * {@code elements}), never parented directly to their OWN block model - the original {@code models/item/tank.json}
 * broke that pattern (parented straight to {@code buildcraft:block/tank}), which is what real source's OWN item
 * model also does - but real source's block model is a genuine top-level JSON model with no custom renderer
 * layered on it, unlike this port now.
 * <p>
 * Real {@code getExtension} (a pipe-connection visual-extension hook from {@code ICustomPipeConnection}) isn't
 * ported - this project's Pipe system has no equivalent "variable connection extension" concept for any other
 * block, so there's nothing real to hook it into (a documented scope line, not an oversight).
 * <p>
 * No GUI (real source has one, {@code GuiTank}/{@code ContainerTank}) - removed per direct user request. Right-
 * click-with-a-bucket fill/drain ({@link #useItemOn}) is the only interaction; an empty-hand/non-bucket right-
 * click ({@link #useWithoutItem}, not overridden here - default no-op) does nothing.
 */
public class TankBlock extends Block implements EntityBlock {
    public static final BooleanProperty JOINED_BELOW = BooleanProperty.create("joined_below");
    private static final VoxelShape SHAPE = Shapes.box(2 / 16D, 0 / 16D, 2 / 16D, 14 / 16D, 16 / 16D, 14 / 16D);

    private final MapCodec<TankBlock> codec = MapCodec.unit(() -> this);

    public TankBlock(BlockBehaviour.Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(JOINED_BELOW, false));
    }

    @Override
    protected MapCodec<TankBlock> codec() {
        return codec;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(JOINED_BELOW);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPE;
    }

    @Override
    public @Nullable BlockState getStateForPlacement(BlockPlaceContext context) {
        boolean below = context.getLevel().getBlockState(context.getClickedPos().below()).getBlock() instanceof TankBlock;
        return defaultBlockState().setValue(JOINED_BELOW, below);
    }

    @Override
    protected BlockState updateShape(BlockState state, LevelReader level, ScheduledTickAccess ticks, BlockPos pos,
            Direction direction, BlockPos neighborPos, BlockState neighborState, RandomSource random) {
        if (direction == Direction.DOWN) {
            return state.setValue(JOINED_BELOW, neighborState.getBlock() instanceof TankBlock);
        }
        return state;
    }

    /** Real {@code shouldSideBeRendered}'s cap-hiding logic now lives in {@code TankBlockEntityRenderer} itself
     * (checking the live neighbour block entity each frame), since the frame no longer goes through the baked-
     * model mesh/cullface system this hook was written for - see {@link #getRenderShape}'s javadoc. */
    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.INVISIBLE;
    }

    @Override
    protected boolean hasAnalogOutputSignal(BlockState state) {
        return true;
    }

    /** Ports {@code TileTank.getComparatorLevel} - real source reports THIS tile's own individual fill level, not
     * the combined connected-stack amount (a real, slightly surprising quirk confirmed directly in source, ported
     * literally rather than "corrected"). */
    @Override
    protected int getAnalogOutputSignal(BlockState state, Level level, BlockPos pos, Direction direction) {
        if (level.getBlockEntity(pos) instanceof TankBlockEntity tank) {
            return tank.getComparatorLevel();
        }
        return 0;
    }

    @Override
    public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new TankBlockEntity(pos, state);
    }

    @Override
    public @Nullable <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        return null;
    }

    /** Ports {@code TileTank.onPlacedBy} -&gt; {@code balanceTankFluids()}: settles fluid down through the newly
     * formed stack (e.g. placing a Tank under an existing partially-filled one should let liquid flow down into
     * the new space), matching real source calling this exactly once, right at placement. */
    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (!level.isClientSide() && level.getBlockEntity(pos) instanceof TankBlockEntity tank) {
            tank.balanceTankFluids();
        }
    }

    /** Ports {@code TileTank.onActivated} -&gt; {@code FluidUtilBC.onTankActivated}: holding any bucket-like item
     * tries to FILL the (connected-stack) tank first, then DRAIN, matching real source's real ordering
     * ({@code FluidUtilBC.move(heldItem, tank)} tried before {@code move(tank, heldItem)}). The item-type check
     * ({@link TankBlockEntity#isRelevantBucket}) must run identically on both client and server - same real
     * dupe-bug shape already found and fixed for {@code EngineBlock}/{@code RefineryBlock}: the client predicts
     * this same dispatch locally, and gating the whole check server-only would let the client's own bucket-
     * placement prediction run unchecked.
     * <p>
     * A relevant bucket ALWAYS returns a consuming result (SUCCESS), even if the fill/drain attempt itself did
     * nothing - checked directly against {@code ServerPlayerGameMode.useItemOn}: a {@code TRY_WITH_EMPTY_HAND}
     * result only reliably triggers {@link #useWithoutItem} as a fallback, and if THAT also doesn't consume,
     * execution falls through to the bucket's OWN {@code useOn} (real placement/empty logic) - the exact dupe
     * shape already found once. Always consuming for a relevant bucket closes that path entirely, at the cost of
     * real source's "open the GUI if holding a bucket that can't fill/drain" edge case - the same accepted
     * trade-off already made for {@code RefineryBlock}. */
    @Override
    protected InteractionResult useItemOn(ItemStack itemStack, BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hitResult) {
        if (level.getBlockEntity(pos) instanceof TankBlockEntity tank && tank.isRelevantBucket(itemStack)) {
            if (!level.isClientSide()) {
                tank.tryFillOrDrainFromHand(player, hand);
            }
            return InteractionResult.SUCCESS;
        }
        return InteractionResult.TRY_WITH_EMPTY_HAND;
    }
}
