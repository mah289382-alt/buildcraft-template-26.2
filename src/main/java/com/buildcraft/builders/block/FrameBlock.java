package com.buildcraft.builders.block;

import java.util.EnumMap;
import java.util.Map;

import com.mojang.serialization.MapCodec;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.ScheduledTickAccess;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jspecify.annotations.Nullable;

/**
 * Marks the outline of a Quarry's working area. Placed automatically by the Quarry as it builds its frame;
 * not intended to be crafted or placed directly by the player.
 */
public class FrameBlock extends Block {
    public static final MapCodec<FrameBlock> CODEC = simpleCodec(FrameBlock::new);

    // Ported from BlockFrame.BASE_AABB/CONNECTION_AABB (original 1.12.2 source): a small 8x8x8 post, plus a
    // thin connector arm reaching to the block edge on each connected side. Matches the block's already-correct
    // model geometry exactly - same coordinates as the multipart blockstate's connection overlays.
    private static final VoxelShape BASE_SHAPE = Shapes.box(4 / 16D, 4 / 16D, 4 / 16D, 12 / 16D, 12 / 16D, 12 / 16D);
    private static final Map<Direction, VoxelShape> ARM_SHAPES = new EnumMap<>(Direction.class);
    static {
        ARM_SHAPES.put(Direction.DOWN, Shapes.box(4 / 16D, 0 / 16D, 4 / 16D, 12 / 16D, 4 / 16D, 12 / 16D));
        ARM_SHAPES.put(Direction.UP, Shapes.box(4 / 16D, 12 / 16D, 4 / 16D, 12 / 16D, 16 / 16D, 12 / 16D));
        ARM_SHAPES.put(Direction.NORTH, Shapes.box(4 / 16D, 4 / 16D, 0 / 16D, 12 / 16D, 12 / 16D, 4 / 16D));
        ARM_SHAPES.put(Direction.SOUTH, Shapes.box(4 / 16D, 4 / 16D, 12 / 16D, 12 / 16D, 12 / 16D, 16 / 16D));
        ARM_SHAPES.put(Direction.WEST, Shapes.box(0 / 16D, 4 / 16D, 4 / 16D, 4 / 16D, 12 / 16D, 12 / 16D));
        ARM_SHAPES.put(Direction.EAST, Shapes.box(12 / 16D, 4 / 16D, 4 / 16D, 16 / 16D, 12 / 16D, 12 / 16D));
    }

    public FrameBlock(BlockBehaviour.Properties properties) {
        super(properties);
        BlockState defaultState = this.stateDefinition.any();
        for (Direction dir : Direction.values()) {
            defaultState = defaultState.setValue(ConnectedTextures.propertyFor(dir), false);
        }
        this.registerDefaultState(defaultState);
    }

    @Override
    protected MapCodec<FrameBlock> codec() {
        return CODEC;
    }

    /**
     * The original explicitly declares {@code isFullCube=false}/{@code isOpaqueCube=false} and returns a real
     * post+arms bounding box (see {@code BlockFrame.getBoundingBox}) - this was never ported to this class, so
     * {@code getShape} silently fell back to {@code BlockBehaviour}'s default {@code Shapes.block()} (a full
     * cube). Since {@code getOcclusionShape}/{@code getCollisionShape} both default to this same shape, that
     * made the Frame occlude (and collide as) a full block despite only rendering a thin post - causing
     * neighboring blocks' touching faces to be culled as if the Frame were solid, which is the "x-ray" bug.
     */
    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        VoxelShape shape = BASE_SHAPE;
        for (Direction dir : Direction.values()) {
            if (state.getValue(ConnectedTextures.propertyFor(dir))) {
                shape = Shapes.or(shape, ARM_SHAPES.get(dir));
            }
        }
        return shape;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        ConnectedTextures.addProperties(builder);
    }

    @Override
    public @Nullable BlockState getStateForPlacement(BlockPlaceContext context) {
        return ConnectedTextures.initial(this.defaultBlockState(), context.getLevel(), context.getClickedPos(), ConnectedTextures::isFrameOrQuarry);
    }

    @Override
    protected BlockState updateShape(BlockState state, LevelReader level, ScheduledTickAccess ticks, BlockPos pos,
            Direction direction, BlockPos neighborPos, BlockState neighborState, RandomSource random) {
        return ConnectedTextures.updateOne(state, direction, neighborState, ConnectedTextures::isFrameOrQuarry);
    }

    public static BlockState computeState(BlockState defaultState, net.minecraft.world.level.BlockGetter level, BlockPos pos) {
        return ConnectedTextures.initial(defaultState, level, pos, ConnectedTextures::isFrameOrQuarry);
    }
}
