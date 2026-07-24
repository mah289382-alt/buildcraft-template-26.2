package com.buildcraft.transport.block;

import java.util.function.Supplier;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;

import com.buildcraft.transport.blockentity.PipeBlockEntity;
import com.buildcraft.transport.pipe.PipeBehaviour;

/**
 * Wood/WoodDiamond-only subclass adding the {@link PipeBlock#VALVE} blockstate property (ports
 * {@code PipeBehaviourWood.getTextureData}'s per-face texture swap for the wrench-picked extraction direction).
 * Deliberately kept OUT of the shared {@link PipeBlock} base class: even this single 7-value enum property would
 * multiply every one of the 17 pipe tiers' blockstate permutation count 7x (4096 -&gt; 28_672 states) if added
 * there, and an earlier attempt using 6 independent booleans there (64x, 4096 -&gt; 262_144) caused a real
 * client resource-loading hang with runaway memory growth, caught via a smoke test. Scoping the extra property
 * to only the 2 tiers that actually have a valve concept avoids that entirely, keeping every other tier at its
 * original state count.
 */
public class WoodPipeBlock extends PipeBlock {
    public WoodPipeBlock(BlockBehaviour.Properties properties, Supplier<BlockEntityType<PipeBlockEntity>> blockEntityType,
            Supplier<PipeBehaviour> behaviourFactory) {
        super(properties, blockEntityType, behaviourFactory);
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        super.createBlockStateDefinition(builder);
        builder.add(VALVE);
    }
}
