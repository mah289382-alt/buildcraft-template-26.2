package com.buildcraft.transport.pipe.behaviour;

import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.jspecify.annotations.Nullable;

import com.buildcraft.transport.block.PipeBlock;
import com.buildcraft.transport.pipe.PipeBehaviour;

/**
 * Ports {@code PipeBehaviourDaizuli} (extends {@code PipeBehaviourDirectional}): a forced single direction
 * (like Iron, but - unlike Wood - {@code canFaceDirection} always allows any connected side, pipe or tile) that
 * additionally only lets an item leave through that face if the item's current colour tag (see
 * {@link PipeBehaviour#stampColour}, stamped upstream by a Lapis pipe) matches this pipe's own configured
 * colour; a mismatched colour is disallowed entirely rather than falling back to any other side, matching
 * {@code sideCheck}'s exact two-branch logic (match -> {@code disallowAllExcept(currentDir)}, mismatch ->
 * {@code disallow(currentDir)} - since {@code currentDir} is the only real candidate either way, both branches
 * collapse to "allow only if matching, else nothing").
 * <p>
 * Wrench interaction is a simplified merge of the original's dual click-target system (clicking the center or
 * the currently-forced face cycles colour; clicking any other connected face sets the forced direction) onto
 * this port's single sneak/click signal: sneaking, or plain-clicking the already-forced face, cycles colour;
 * plain-clicking any other connected face sets the forced direction - preserving both distinct actions using
 * only the click information this port's simplified wrench actually carries (see {@code IronBehaviour}/{@code
 * WrenchItem} for the same underlying convention).
 */
public final class DaizuliBehaviour implements PipeBehaviour {
    private @Nullable Direction currentDir;
    private DyeColor colour = DyeColor.WHITE;

    @Override
    public Set<Direction> filterDestinations(Level level, BlockPos pipePos, Direction from, ItemStack stack,
            @Nullable DyeColor itemColour, Set<Direction> candidates) {
        if (currentDir == null || !PipeBlock.isConnectable(level, pipePos, currentDir)) {
            advanceFacing(level, pipePos);
        }
        if (currentDir == null || colour != itemColour) {
            return Set.of();
        }
        return Set.of(currentDir);
    }

    /**
     * Falls back to advancing the direction whenever a non-center click's face isn't a usable target - aiming
     * precisely at one of a pipe's thin connector arms is unreliable (a plain click easily raytraces to a face
     * that isn't connected to anything at all), so a click that doesn't land on a real target now does
     * something useful instead of silently doing nothing.
     */
    @Override
    public boolean onWrenchClick(Level level, BlockPos pipePos, @Nullable Direction clickedFace) {
        if (clickedFace == null || clickedFace == currentDir) {
            int ordinal = colour.getId();
            colour = DyeColor.byId(clickedFace == null ? (ordinal + 15) % 16 : (ordinal + 1) % 16);
        } else if (PipeBlock.isConnectable(level, pipePos, clickedFace)) {
            currentDir = clickedFace;
        } else {
            advanceFacing(level, pipePos);
        }
        return true;
    }

    private void advanceFacing(Level level, BlockPos pipePos) {
        Direction[] all = Direction.values();
        int start = currentDir == null ? -1 : currentDir.ordinal();
        for (int i = 1; i <= all.length; i++) {
            Direction candidate = all[(start + i) % all.length];
            if (PipeBlock.isConnectable(level, pipePos, candidate)) {
                currentDir = candidate;
                return;
            }
        }
        currentDir = null;
    }

    @Override
    public void save(ValueOutput output) {
        if (currentDir != null) {
            output.store("currentDir", Direction.CODEC, currentDir);
        }
        output.store("colour", DyeColor.CODEC, colour);
    }

    @Override
    public void load(ValueInput input) {
        currentDir = input.read("currentDir", Direction.CODEC).orElse(null);
        colour = input.read("colour", DyeColor.CODEC).orElse(DyeColor.WHITE);
    }
}
