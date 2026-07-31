package com.buildcraft.transport.pipe.behaviour;

import java.util.EnumSet;
import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.transfer.fluid.FluidResource;
import org.jspecify.annotations.Nullable;

import com.buildcraft.transport.blockentity.FluidPipeBlockEntity;
import com.buildcraft.transport.blockentity.PipeBlockEntity;
import com.buildcraft.transport.pipe.PipeBehaviour;

/**
 * Ported from {@code PipeBehaviourClay.orderSides}: boosts the priority of any side connected to a real
 * inventory (a "TILE", in the original's {@code ConnectedType} terms) so high that it always wins over a
 * pipe-connected side, regardless of what other routing logic (filters, etc.) might otherwise prefer. Modeled
 * here as a hard filter rather than a priority number, since this port's destination selection is a simple
 * round-robin over an allowed set rather than the original's weighted ordering - the observable behaviour is
 * the same either way: if ANY candidate side leads to a non-pipe neighbor, only those sides are eligible.
 * <p>
 * Real source registers TWO separate {@code orderSides} handlers on the exact same class - one for
 * {@code PipeEventItem.SideCheck}, one for {@code PipeEventFluid.SideCheck} - with byte-for-byte identical logic
 * (both just boost priority 100 on any TILE-connected face), confirmed by reading {@code PipeBehaviourClay.java}
 * directly. {@link #filterFluidDestinations} below ports that second handler; unlike {@link ClayBehaviour}'s
 * neighbor {@code ClayBehaviour} record-based fluid siblings, this isn't a case of "real source has no fluid
 * override" - Clay actually needed its own explicit port, not just a fluid rate constant.
 */
public final class ClayBehaviour implements PipeBehaviour {
    public static final ClayBehaviour INSTANCE = new ClayBehaviour();

    private ClayBehaviour() {}

    @Override
    public int ticksPerPhase() {
        return PassiveBehaviour.COBBLESTONE.ticksPerPhase();
    }

    @Override
    public Set<Direction> filterDestinations(Level level, BlockPos pipePos, Direction from, ItemStack stack,
            @Nullable DyeColor colour, Set<Direction> candidates) {
        Set<Direction> tileSides = EnumSet.noneOf(Direction.class);
        for (Direction dir : candidates) {
            if (!(level.getBlockEntity(pipePos.relative(dir)) instanceof PipeBlockEntity)) {
                tileSides.add(dir);
            }
        }
        return tileSides.isEmpty() ? candidates : tileSides;
    }

    @Override
    public Set<Direction> filterFluidDestinations(Level level, BlockPos pipePos, Direction from,
            FluidResource fluid, Set<Direction> candidates) {
        Set<Direction> tileSides = EnumSet.noneOf(Direction.class);
        for (Direction dir : candidates) {
            if (!(level.getBlockEntity(pipePos.relative(dir)) instanceof FluidPipeBlockEntity)) {
                tileSides.add(dir);
            }
        }
        return tileSides.isEmpty() ? candidates : tileSides;
    }

    /** Real {@code BCTransportConfig}: Clay's fluid pipe transfers {@code baseFlowRate*4}=40 mB/tick, delay 10
     * (same rate tier as Iron/Quartz). */
    @Override
    public int fluidTransferPerTick() {
        return 40;
    }
}
