package com.buildcraft.transport.block;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

import com.mojang.serialization.MapCodec;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Player;
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
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.transfer.ResourceHandler;
import net.neoforged.neoforge.transfer.item.ItemResource;
import org.jspecify.annotations.Nullable;

import com.buildcraft.transport.blockentity.PipeBlockEntity;
import com.buildcraft.transport.pipe.PipeBehaviour;
import com.buildcraft.transport.pipe.PipeConnectable;

/**
 * A single pipe segment: a small post at the block's center with a connector arm reaching toward each side it's
 * connected to, mirroring {@code BlockPipeHolder}'s exact {@code BOX_CENTER}/{@code BOX_DOWN..EAST} geometry
 * (identical 4/16..12/16 post + arm coordinates to {@link com.buildcraft.builders.block.FrameBlock}, reused here).
 * <p>
 * A side counts as "connected" - both for the visual arm and for item flow - if the neighbor is another pipe, or
 * exposes an item capability (an inventory it can push items into/pull items from). This is a simplified,
 * capability-based stand-in for the original's {@code IFlowItems}/{@code ItemTransactorHelper} connection checks.
 */
public class PipeBlock extends Block implements EntityBlock {
    public static final BooleanProperty CONNECTED_DOWN = BooleanProperty.create("connected_down");
    public static final BooleanProperty CONNECTED_UP = BooleanProperty.create("connected_up");
    public static final BooleanProperty CONNECTED_NORTH = BooleanProperty.create("connected_north");
    public static final BooleanProperty CONNECTED_SOUTH = BooleanProperty.create("connected_south");
    public static final BooleanProperty CONNECTED_WEST = BooleanProperty.create("connected_west");
    public static final BooleanProperty CONNECTED_EAST = BooleanProperty.create("connected_east");

    // True when the connected neighbor on that side is NOT another pipe (a container, or a PipeConnectable like
    // the Quarry) - selects the "extended" connector arm variant that pokes slightly past the block boundary so
    // it visually reaches recessed vanilla container models (e.g. a chest's lid tops out at y=14/16, not 16/16 -
    // see ChestModel.java). Only applied against non-pipe neighbors: two adjacent pipes' arms are already exactly
    // coplanar at the shared boundary, so extending both would make their arm geometry overlap and z-fight.
    public static final BooleanProperty EXTENDED_DOWN = BooleanProperty.create("extended_down");
    public static final BooleanProperty EXTENDED_UP = BooleanProperty.create("extended_up");
    public static final BooleanProperty EXTENDED_NORTH = BooleanProperty.create("extended_north");
    public static final BooleanProperty EXTENDED_SOUTH = BooleanProperty.create("extended_south");
    public static final BooleanProperty EXTENDED_WEST = BooleanProperty.create("extended_west");
    public static final BooleanProperty EXTENDED_EAST = BooleanProperty.create("extended_east");

    /**
     * The single face that's this pipe's "valve" (or {@code NONE}) - the wrench-picked extraction/power-
     * receiving direction for behaviours that have one (Wood/WoodDiamond's {@code currentDir}, via
     * {@link PipeBehaviour#getValveDirection}) - ports {@code PipeBehaviourWood.getTextureData} returning a
     * distinct {@code TEX_FILLED} texture only on that face. A single 7-value enum property (not 6 independent
     * booleans): 6 booleans would be 64 combinations for this dimension alone, and multiplied across the
     * existing 12 CONNECTED/EXTENDED booleans (4096 combinations) that's 262_144 states for every block that has
     * it - a real resource-loading hang with runaway memory growth, caught via a client smoke test on the first
     * (6-boolean) attempt. This enum keeps it to 7, for 4096*7=28_672 states - see {@link WoodPipeBlock} for why
     * it's not on every pipe tier in the first place.
     */
    public enum ValveFace implements StringRepresentable {
        NONE, DOWN, UP, NORTH, SOUTH, WEST, EAST;

        public static ValveFace of(@Nullable Direction direction) {
            if (direction == null) {
                return NONE;
            }
            return switch (direction) {
                case DOWN -> DOWN;
                case UP -> UP;
                case NORTH -> NORTH;
                case SOUTH -> SOUTH;
                case WEST -> WEST;
                case EAST -> EAST;
            };
        }

        @Override
        public String getSerializedName() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    public static final EnumProperty<ValveFace> VALVE = EnumProperty.create("valve", ValveFace.class);

    private static final Map<Direction, BooleanProperty> CONNECTED_BY_DIRECTION = new EnumMap<>(Direction.class);
    private static final Map<Direction, BooleanProperty> EXTENDED_BY_DIRECTION = new EnumMap<>(Direction.class);
    static {
        CONNECTED_BY_DIRECTION.put(Direction.DOWN, CONNECTED_DOWN);
        CONNECTED_BY_DIRECTION.put(Direction.UP, CONNECTED_UP);
        CONNECTED_BY_DIRECTION.put(Direction.NORTH, CONNECTED_NORTH);
        CONNECTED_BY_DIRECTION.put(Direction.SOUTH, CONNECTED_SOUTH);
        CONNECTED_BY_DIRECTION.put(Direction.WEST, CONNECTED_WEST);
        CONNECTED_BY_DIRECTION.put(Direction.EAST, CONNECTED_EAST);

        EXTENDED_BY_DIRECTION.put(Direction.DOWN, EXTENDED_DOWN);
        EXTENDED_BY_DIRECTION.put(Direction.UP, EXTENDED_UP);
        EXTENDED_BY_DIRECTION.put(Direction.NORTH, EXTENDED_NORTH);
        EXTENDED_BY_DIRECTION.put(Direction.SOUTH, EXTENDED_SOUTH);
        EXTENDED_BY_DIRECTION.put(Direction.WEST, EXTENDED_WEST);
        EXTENDED_BY_DIRECTION.put(Direction.EAST, EXTENDED_EAST);
    }

    public static BooleanProperty connectedProperty(Direction direction) {
        return CONNECTED_BY_DIRECTION.get(direction);
    }

    public static BooleanProperty extendedProperty(Direction direction) {
        return EXTENDED_BY_DIRECTION.get(direction);
    }

    // Ported from BlockPipeHolder.BOX_CENTER/BOX_DOWN../BOX_EAST - identical coordinates to FrameBlock's own
    // post+arm shape (both are independently the original's "small post, thin arms" pipe/frame visual language).
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

    private final Supplier<BlockEntityType<PipeBlockEntity>> blockEntityType;
    private final Supplier<PipeBehaviour> behaviourFactory;

    /**
     * @param blockEntityType this pipe material's own {@code BlockEntityType} - a lazy supplier since blocks are
     *         constructed before their matching block entity type's {@code DeferredHolder} resolves.
     * @param behaviourFactory creates a NEW {@link PipeBehaviour} instance per placed pipe (some behaviours, like
     *         Iron's forced direction, hold real per-instance state - sharing one instance across every pipe of
     *         that material would leak state between unrelated pipes).
     */
    public PipeBlock(BlockBehaviour.Properties properties, Supplier<BlockEntityType<PipeBlockEntity>> blockEntityType,
            Supplier<PipeBehaviour> behaviourFactory) {
        super(properties);
        this.blockEntityType = blockEntityType;
        this.behaviourFactory = behaviourFactory;
        BlockState defaultState = this.stateDefinition.any();
        for (Direction dir : Direction.values()) {
            defaultState = defaultState.setValue(connectedProperty(dir), false).setValue(extendedProperty(dir), false);
        }
        this.registerDefaultState(defaultState);
    }

    // Each pipe material is a distinct PipeBlock instance carrying non-codable state (a behaviour factory), so
    // (unlike FrameBlock/QuarryBlock/MarkerBlock's simpleCodec) it can't be reconstructed generically from data -
    // this always resolves back to this exact registered instance, which is how the block registry actually uses
    // it in practice (matching a block's DeferredHolder, not deserializing an arbitrary new one).
    private final MapCodec<PipeBlock> codec = MapCodec.unit(() -> this);

    @Override
    protected MapCodec<PipeBlock> codec() {
        return codec;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(CONNECTED_DOWN, CONNECTED_UP, CONNECTED_NORTH, CONNECTED_SOUTH, CONNECTED_WEST, CONNECTED_EAST,
                EXTENDED_DOWN, EXTENDED_UP, EXTENDED_NORTH, EXTENDED_SOUTH, EXTENDED_WEST, EXTENDED_EAST);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        VoxelShape shape = BASE_SHAPE;
        for (Direction dir : Direction.values()) {
            if (state.getValue(connectedProperty(dir))) {
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
        return state.setValue(connectedProperty(dir), isConnectable(level, pos, dir))
                .setValue(extendedProperty(dir), isContainerNeighbor(level, pos, dir));
    }

    /**
     * A side is connected if the neighbor is another pipe, exposes an item capability (an inventory), or
     * explicitly opts in via {@link com.buildcraft.transport.pipe.PipeConnectable} (blocks like the Quarry that
     * push items out on their own schedule rather than exposing real item storage - see that interface's
     * javadoc for why a capability check alone isn't enough to connect to those).
     */
    public static boolean isConnectable(LevelReader level, BlockPos pos, Direction direction) {
        if (level.getBlockEntity(pos.relative(direction)) instanceof PipeBlockEntity neighborPipe) {
            return canConnectPipes(level, pos, neighborPipe);
        }
        return isContainerNeighbor(level, pos, direction);
    }

    /**
     * Both sides get a say, mirroring {@code PipeBehaviour.canConnect(face, PipeBehaviour other)} - used by the
     * Cobblestone/Stone/Quartz "Separate" family to only connect to another pipe of the exact same material.
     */
    private static boolean canConnectPipes(LevelReader level, BlockPos pos, PipeBlockEntity neighborPipe) {
        PipeBehaviour self = selfBehaviour(level, pos);
        PipeBehaviour other = neighborPipe.getBehaviour();
        if (self != null && !self.canConnectToPipe(other)) {
            return false;
        }
        return other.canConnectToPipe(self);
    }

    /**
     * True if this side connects to a non-pipe neighbor (a real inventory, or a {@link PipeConnectable} like the
     * Quarry) - used to pick the "extended" connector arm variant. Only non-pipe neighbors get the extended arm:
     * two adjacent pipes' arms already meet exactly at the shared block boundary, so extending both would make
     * their geometry overlap and z-fight, whereas vanilla container models (e.g. a chest, whose lid tops out at
     * y=14/16 rather than 16/16 per {@code ChestModel.java}) sit recessed from the true boundary and need the
     * extra reach to visually touch.
     */
    private static boolean isContainerNeighbor(LevelReader level, BlockPos pos, Direction direction) {
        PipeBehaviour self = selfBehaviour(level, pos);
        if (self != null && !self.connectsToContainers()) {
            return false;
        }
        BlockPos neighborPos = pos.relative(direction);
        BlockEntity neighborEntity = level.getBlockEntity(neighborPos);
        // Every pipe tier also exposes Capabilities.Item.BLOCK (for real item transport), so without this check
        // the capability lookup below would treat any OTHER pipe as a "container" too, extending both sides'
        // arms into each other and overlapping - the exact bug this method exists to avoid for genuine
        // containers only applies one-sided (a chest doesn't grow its own arm to compensate).
        if (neighborEntity instanceof PipeBlockEntity) {
            return false;
        }
        if (neighborEntity instanceof PipeConnectable) {
            return true;
        }
        if (level instanceof Level realLevel) {
            ResourceHandler<ItemResource> handler =
                    realLevel.getCapability(Capabilities.Item.BLOCK, neighborPos, direction.getOpposite());
            return handler != null;
        }
        return false;
    }

    private static @Nullable PipeBehaviour selfBehaviour(LevelReader level, BlockPos pos) {
        return level.getBlockEntity(pos) instanceof PipeBlockEntity self ? self.getBehaviour() : null;
    }

    /**
     * Ports {@code PipeBehaviourWood.getTextureData}'s face selection: sets {@link #VALVE} to
     * {@link PipeBehaviour#getValveDirection()} (Wood/WoodDiamond's wrench-picked extraction face), or
     * {@code NONE}. Called after a wrench click changes that direction - unlike CONNECTED/EXTENDED, this doesn't
     * depend on neighbor state so {@code updateShape} never triggers it on its own.
     */
    public static void syncValveState(Level level, BlockPos pos, BlockState state, PipeBehaviour behaviour) {
        if (!state.hasProperty(VALVE)) {
            // This tier's block (e.g. plain PipeBlock, not WoodPipeBlock) was never given the valve property -
            // every non-Wood behaviour's getValveDirection() returns null anyway, but a wrench click on ANY
            // tier funnels through this method, so this guard is required to avoid an IllegalArgumentException
            // from setValue on a property that doesn't exist in this block's state definition.
            return;
        }
        ValveFace face = ValveFace.of(behaviour.getValveDirection());
        if (state.getValue(VALVE) != face) {
            level.setBlockAndUpdate(pos, state.setValue(VALVE, face));
        }
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new PipeBlockEntity(blockEntityType.get(), pos, state, behaviourFactory);
    }

    @Override
    public @Nullable <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        return (lvl, pos, st, be) -> PipeBlockEntity.tick(lvl, pos, st, (PipeBlockEntity) be);
    }

    /**
     * Ports {@code PipeBehaviourDiamond.onPipeActivate}: right-clicking a pipe whose behaviour provides a GUI
     * (Diamond's 54-slot filter inventory) opens it. Wired to {@code useWithoutItem} rather than {@code
     * useItemOn} so it doesn't compete with {@link com.buildcraft.core.item.WrenchItem}'s own item-side
     * interaction (Iron/Wood's direction-setting) - {@code useItemOn}'s default {@code TRY_WITH_EMPTY_HAND}
     * falls through to this for any held item that doesn't otherwise handle the click, matching the original
     * opening the GUI on a plain right-click regardless of what's held.
     */
    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hitResult) {
        if (level.getBlockEntity(pos) instanceof PipeBlockEntity pipe && pipe.getBehaviour() instanceof MenuProvider provider) {
            if (player instanceof ServerPlayer serverPlayer) {
                serverPlayer.openMenu(provider, pos);
            }
            return InteractionResult.SUCCESS;
        }
        return InteractionResult.PASS;
    }
}
