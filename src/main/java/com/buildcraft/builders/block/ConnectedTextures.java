package com.buildcraft.builders.block;

import java.util.EnumMap;
import java.util.Map;
import java.util.function.Predicate;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;

/**
 * Shared per-direction "connected" boolean blockstate properties used by the Quarry and Frame blocks to visually
 * link up with adjacent Quarry/Frame blocks, mirroring the original's connected-texture look.
 */
public final class ConnectedTextures {
    public static final BooleanProperty CONNECTED_DOWN = BooleanProperty.create("connected_down");
    public static final BooleanProperty CONNECTED_UP = BooleanProperty.create("connected_up");
    public static final BooleanProperty CONNECTED_NORTH = BooleanProperty.create("connected_north");
    public static final BooleanProperty CONNECTED_SOUTH = BooleanProperty.create("connected_south");
    public static final BooleanProperty CONNECTED_WEST = BooleanProperty.create("connected_west");
    public static final BooleanProperty CONNECTED_EAST = BooleanProperty.create("connected_east");

    private static final Map<Direction, BooleanProperty> BY_DIRECTION = new EnumMap<>(Direction.class);
    static {
        BY_DIRECTION.put(Direction.DOWN, CONNECTED_DOWN);
        BY_DIRECTION.put(Direction.UP, CONNECTED_UP);
        BY_DIRECTION.put(Direction.NORTH, CONNECTED_NORTH);
        BY_DIRECTION.put(Direction.SOUTH, CONNECTED_SOUTH);
        BY_DIRECTION.put(Direction.WEST, CONNECTED_WEST);
        BY_DIRECTION.put(Direction.EAST, CONNECTED_EAST);
    }

    private ConnectedTextures() {}

    public static BooleanProperty propertyFor(Direction direction) {
        return BY_DIRECTION.get(direction);
    }

    public static void addProperties(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(CONNECTED_DOWN, CONNECTED_UP, CONNECTED_NORTH, CONNECTED_SOUTH, CONNECTED_WEST, CONNECTED_EAST);
    }

    public static BlockState initial(BlockState state, BlockGetter level, BlockPos pos, Predicate<BlockState> isConnectable) {
        BlockState result = state;
        for (Direction dir : Direction.values()) {
            result = result.setValue(propertyFor(dir), isConnectable.test(level.getBlockState(pos.relative(dir))));
        }
        return result;
    }

    public static BlockState updateOne(BlockState state, Direction direction, BlockState neighborState, Predicate<BlockState> isConnectable) {
        return state.setValue(propertyFor(direction), isConnectable.test(neighborState));
    }

    public static boolean isFrameOrQuarry(BlockState state) {
        return state.is(com.buildcraft.builders.BuildersContent.QUARRY_BLOCK.get())
                || state.is(com.buildcraft.builders.BuildersContent.FRAME_BLOCK.get());
    }
}
