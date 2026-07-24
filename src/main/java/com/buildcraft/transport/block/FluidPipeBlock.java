package com.buildcraft.transport.block;

import java.util.EnumMap;
import java.util.Map;
import java.util.function.Supplier;

import com.mojang.serialization.MapCodec;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
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
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.transfer.ResourceHandler;
import net.neoforged.neoforge.transfer.fluid.FluidResource;
import org.jspecify.annotations.Nullable;

import com.buildcraft.transport.blockentity.FluidPipeBlockEntity;
import com.buildcraft.transport.pipe.PipeBehaviour;
import com.buildcraft.transport.pipe.PipeConnectable;

/**
 * The fluid ("waterproof") counterpart to {@link PipeBlock} - real BuildCraft treats item and fluid pipes as
 * genuinely separate blocks/items forming 2 parallel, non-interoperating pipe networks (you place a "Waterproof
 * Pipe" next to a fluid tank, not a "Transport Pipe"), not a unified dual-purpose pipe. Geometry, blockstate
 * shape (same post+arm {@code CONNECTED_*}/{@code EXTENDED_*} properties - reused directly from {@link PipeBlock},
 * not redeclared, since blockstate {@code Property} objects are safely shareable across unrelated block classes),
 * and the connector-arm visual/connectivity system are otherwise identical to {@link PipeBlock} - the only real
 * difference is which capability governs "is this a valid non-pipe neighbour" ({@code Capabilities.Fluid.BLOCK}
 * instead of {@code Capabilities.Item.BLOCK}). Kept as a sibling class (not a {@code PipeBlock} subclass) since
 * the two hold structurally different neighbour-capability types and block-entity types - forcing one to extend
 * the other would mean fighting the type system for no real code reuse (the shared PARTS - shape/blockstate/
 * connectivity-decision-shape - are copied here deliberately small and simple rather than abstracted into a
 * common base, matching this project's general preference for a little duplication over a premature abstraction).
 */
public class FluidPipeBlock extends Block implements EntityBlock {
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

    private final Supplier<BlockEntityType<FluidPipeBlockEntity>> blockEntityType;
    private final Supplier<PipeBehaviour> behaviourFactory;

    public FluidPipeBlock(BlockBehaviour.Properties properties, Supplier<BlockEntityType<FluidPipeBlockEntity>> blockEntityType,
            Supplier<PipeBehaviour> behaviourFactory) {
        super(properties);
        this.blockEntityType = blockEntityType;
        this.behaviourFactory = behaviourFactory;
        BlockState defaultState = this.stateDefinition.any();
        for (Direction dir : Direction.values()) {
            defaultState = defaultState.setValue(PipeBlock.connectedProperty(dir), false)
                    .setValue(PipeBlock.extendedProperty(dir), false);
        }
        this.registerDefaultState(defaultState);
    }

    private final MapCodec<FluidPipeBlock> codec = MapCodec.unit(() -> this);

    @Override
    protected MapCodec<FluidPipeBlock> codec() {
        return codec;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(PipeBlock.CONNECTED_DOWN, PipeBlock.CONNECTED_UP, PipeBlock.CONNECTED_NORTH,
                PipeBlock.CONNECTED_SOUTH, PipeBlock.CONNECTED_WEST, PipeBlock.CONNECTED_EAST,
                PipeBlock.EXTENDED_DOWN, PipeBlock.EXTENDED_UP, PipeBlock.EXTENDED_NORTH,
                PipeBlock.EXTENDED_SOUTH, PipeBlock.EXTENDED_WEST, PipeBlock.EXTENDED_EAST);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        VoxelShape shape = BASE_SHAPE;
        for (Direction dir : Direction.values()) {
            if (state.getValue(PipeBlock.connectedProperty(dir))) {
                shape = Shapes.or(shape, ARM_SHAPES.get(dir));
            }
        }
        return shape;
    }

    @Override
    public @Nullable BlockState getStateForPlacement(BlockPlaceContext context) {
        BlockState state = this.defaultBlockState();
        for (Direction dir : Direction.values()) {
            state = withConnection(state, context.getLevel(), context.getClickedPos(), dir);
        }
        return state;
    }

    @Override
    protected BlockState updateShape(BlockState state, LevelReader level, ScheduledTickAccess ticks, BlockPos pos,
            Direction direction, BlockPos neighborPos, BlockState neighborState, RandomSource random) {
        return withConnection(state, level, pos, direction);
    }

    private static BlockState withConnection(BlockState state, LevelReader level, BlockPos pos, Direction dir) {
        return state.setValue(PipeBlock.connectedProperty(dir), isConnectable(level, pos, dir))
                .setValue(PipeBlock.extendedProperty(dir), isContainerNeighbor(level, pos, dir));
    }

    /** Fluid-pipe equivalent of {@link PipeBlock#isConnectable} - a side connects to another fluid pipe (subject
     * to material compatibility via {@link PipeBehaviour#canConnectToPipe}) or a real fluid-storing neighbour. */
    public static boolean isConnectable(LevelReader level, BlockPos pos, Direction direction) {
        if (level.getBlockEntity(pos.relative(direction)) instanceof FluidPipeBlockEntity neighborPipe) {
            return canConnectPipes(level, pos, neighborPipe);
        }
        return isContainerNeighbor(level, pos, direction);
    }

    private static boolean canConnectPipes(LevelReader level, BlockPos pos, FluidPipeBlockEntity neighborPipe) {
        PipeBehaviour self = selfBehaviour(level, pos);
        PipeBehaviour other = neighborPipe.getBehaviour();
        if (self != null && !self.canConnectToPipe(other)) {
            return false;
        }
        return other.canConnectToPipe(self);
    }

    private static boolean isContainerNeighbor(LevelReader level, BlockPos pos, Direction direction) {
        PipeBehaviour self = selfBehaviour(level, pos);
        if (self != null && !self.connectsToContainers()) {
            return false;
        }
        BlockPos neighborPos = pos.relative(direction);
        BlockEntity neighborEntity = level.getBlockEntity(neighborPos);
        if (neighborEntity instanceof FluidPipeBlockEntity) {
            return false;
        }
        if (neighborEntity instanceof PipeConnectable) {
            return true;
        }
        if (level instanceof Level realLevel) {
            ResourceHandler<FluidResource> handler =
                    realLevel.getCapability(Capabilities.Fluid.BLOCK, neighborPos, direction.getOpposite());
            return handler != null;
        }
        return false;
    }

    private static @Nullable PipeBehaviour selfBehaviour(LevelReader level, BlockPos pos) {
        return level.getBlockEntity(pos) instanceof FluidPipeBlockEntity self ? self.getBehaviour() : null;
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new FluidPipeBlockEntity(blockEntityType.get(), pos, state, behaviourFactory);
    }

    @Override
    public @Nullable <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        return (lvl, pos, st, be) -> FluidPipeBlockEntity.tick(lvl, pos, st, (FluidPipeBlockEntity) be);
    }
}
