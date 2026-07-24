package com.buildcraft.transport.pipe.behaviour;

import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.neoforged.neoforge.transfer.fluid.FluidResource;
import org.jspecify.annotations.Nullable;

import com.buildcraft.transport.block.PipeBlock;
import com.buildcraft.transport.pipe.PipeBehaviour;

/**
 * Ports {@code PipeBehaviourIron}/{@code PipeBehaviourDirectional}: a forced single-direction valve. Wrench
 * right-click on the central post cycles through connected faces in {@code Direction} order; wrench right-click
 * on a specific connected face sets it directly. Once set, every item reaching center is forced out that one
 * face - if it's no longer connected/valid, the original auto-advances to the next valid face each tick
 * ({@code PipeBehaviourDirectional.onTick}), which this ports too.
 */
public final class IronBehaviour implements PipeBehaviour {
    private @Nullable Direction currentDir;

    @Override
    public Set<Direction> filterDestinations(Level level, BlockPos pipePos, Direction from, ItemStack stack,
            @Nullable DyeColor colour, Set<Direction> candidates) {
        if (currentDir == null || !PipeBlock.isConnectable(level, pipePos, currentDir)) {
            advanceFacing(level, pipePos);
        }
        return currentDir == null ? Set.of() : Set.of(currentDir);
    }

    /** Real {@code PipeBehaviourIron.fluidSideCheck}: fluid is just as forced-single-direction as items - same
     * {@code currentDir} field, same auto-advance-if-invalid check, shared between both flavours since real
     * source registers the exact same {@code PipeBehaviourIron} class for both the item and fluid pipe. */
    @Override
    public Set<Direction> filterFluidDestinations(Level level, BlockPos pipePos, Direction from, FluidResource fluid,
            Set<Direction> candidates) {
        if (currentDir == null || !PipeBlock.isConnectable(level, pipePos, currentDir)) {
            advanceFacing(level, pipePos);
        }
        return currentDir == null ? Set.of() : Set.of(currentDir);
    }

    /** Real {@code BCTransportConfig}: Iron's fluid pipe transfers 40 mB/tick, delay 10. */
    @Override
    public int fluidTransferPerTick() {
        return 40;
    }

    /**
     * Falls back to cycling whenever the clicked face isn't a usable target - aiming precisely at one of a
     * pipe's thin connector arms is unreliable (a plain click easily raytraces to a face, e.g. the central
     * post's own top, that isn't connected to anything at all), so a click that doesn't land on a real target
     * is treated the same as a center/sneak click instead of silently doing nothing.
     */
    @Override
    public boolean onWrenchClick(Level level, BlockPos pipePos, @Nullable Direction clickedFace) {
        if (clickedFace != null && clickedFace != currentDir && PipeBlock.isConnectable(level, pipePos, clickedFace)) {
            currentDir = clickedFace;
        } else {
            advanceFacing(level, pipePos);
        }
        return true;
    }

    /** @return true if the facing changed. */
    private boolean advanceFacing(Level level, BlockPos pipePos) {
        Direction[] all = Direction.values();
        int start = currentDir == null ? -1 : currentDir.ordinal();
        for (int i = 1; i <= all.length; i++) {
            Direction candidate = all[(start + i) % all.length];
            if (PipeBlock.isConnectable(level, pipePos, candidate)) {
                currentDir = candidate;
                return true;
            }
        }
        currentDir = null;
        return false;
    }

    @Override
    public void save(ValueOutput output) {
        if (currentDir != null) {
            output.store("currentDir", Direction.CODEC, currentDir);
        }
    }

    @Override
    public void load(ValueInput input) {
        currentDir = input.read("currentDir", Direction.CODEC).orElse(null);
    }
}
