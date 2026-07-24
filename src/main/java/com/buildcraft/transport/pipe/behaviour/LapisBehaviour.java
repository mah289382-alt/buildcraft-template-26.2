package com.buildcraft.transport.pipe.behaviour;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.jspecify.annotations.Nullable;

import com.buildcraft.transport.pipe.PipeBehaviour;

/**
 * Ports {@code PipeBehaviourLapis}: wrench right-click cycles a colour, which then gets unconditionally stamped
 * onto every item reaching this pipe's center - the tag travels with the item through however many further
 * pipes it passes (see {@link PipeBehaviour#stampColour}), for a downstream tier (Daizuli) to match against.
 * Plain right-click advances the colour by one (wrapping); sneak + right-click goes backward by one, matching
 * the original's {@code n = colour.metadata + (sneaking ? 15 : 1) & 15} (an add-15-and-wrap being the same as
 * subtract-1-and-wrap over 16 values).
 */
public final class LapisBehaviour implements PipeBehaviour {
    private DyeColor colour = DyeColor.WHITE;

    public DyeColor getColour() {
        return colour;
    }

    /**
     * The original drives the forward/backward choice off sneak state directly ({@code n = colour.metadata +
     * (sneaking ? 15 : 1) & 15} - add-15-and-wrap being the same as subtract-1-and-wrap over 16 values). This
     * port's {@code WrenchItem}/{@code PipeBlockEntity.onWrenchClick} convention passes {@code null} as the
     * clicked face specifically when sneaking (see {@code IronBehaviour}'s use of the same signal), which is
     * repurposed here as the forward/backward switch even though Lapis doesn't otherwise care which face (or
     * the center) was actually clicked.
     */
    @Override
    public boolean onWrenchClick(Level level, BlockPos pipePos, @Nullable Direction clickedFace) {
        int ordinal = colour.getId();
        colour = DyeColor.byId(clickedFace == null ? (ordinal + 15) % 16 : (ordinal + 1) % 16);
        return true;
    }

    @Override
    public @Nullable DyeColor stampColour(@Nullable DyeColor incoming) {
        return colour;
    }

    @Override
    public void save(ValueOutput output) {
        output.store("colour", DyeColor.CODEC, colour);
    }

    @Override
    public void load(ValueInput input) {
        colour = input.read("colour", DyeColor.CODEC).orElse(DyeColor.WHITE);
    }
}
