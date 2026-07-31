package com.buildcraft.transport.block;

import java.util.function.Supplier;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;

import com.buildcraft.transport.blockentity.FluidPipeBlockEntity;
import com.buildcraft.transport.pipe.PipeBehaviour;

/**
 * Fluid-pipe counterpart to {@link WoodPipeBlock}: adds the shared {@link PipeBlock#VALVE} blockstate property
 * (same {@code Property} instance, safely reused across block classes - see {@link FluidPipeBlock}'s own reuse of
 * {@code CONNECTED_*}/{@code EXTENDED_*}) for Wood/Diamond-Wood's fluid tiers. Real source's {@code woodFluid}/
 * {@code diaWoodFluid} pipe definitions carry the same {@code texSuffixes("_clear", "_filled")} call as their item
 * counterparts (confirmed in {@code BCTransportPipes.java}), so the valve visual is a genuine real-source feature
 * on the fluid tier too, not just the item one - scoped to its own subclass rather than the shared
 * {@link FluidPipeBlock} base for the same combinatorial-blockstate-explosion reason documented on
 * {@link WoodPipeBlock}.
 */
public class WoodFluidPipeBlock extends FluidPipeBlock {
    public WoodFluidPipeBlock(BlockBehaviour.Properties properties, Supplier<BlockEntityType<FluidPipeBlockEntity>> blockEntityType,
            Supplier<PipeBehaviour> behaviourFactory) {
        super(properties, blockEntityType, behaviourFactory);
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        super.createBlockStateDefinition(builder);
        builder.add(PipeBlock.VALVE);
    }
}
