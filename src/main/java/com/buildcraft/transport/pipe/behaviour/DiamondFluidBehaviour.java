package com.buildcraft.transport.pipe.behaviour;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.transfer.fluid.FluidResource;
import org.jspecify.annotations.Nullable;

/**
 * Ports {@code PipeBehaviourDiamondFluid extends PipeBehaviourDiamond}: the exact same 54-slot (9-per-face x 6
 * faces) filter inventory/GUI as the item tier, just compared by FLUID identity instead of item identity - real
 * source's {@code sideCheck} calls {@code FluidUtil.getFluidContained(compareTo)} on each filter slot's
 * {@code ItemStack} (a bucket-like item) and compares the resulting {@code FluidStack} against the fluid actually
 * trying to leave. This port's modern equivalent of "what fluid does this bucket item represent" is simply
 * {@link BucketItem#content} (a public field, confirmed by reading the decompiled class directly - simpler than
 * this project's established {@code BucketResourceHandler}/{@code ItemAccess} machinery, which is built for
 * mutating a real player-held/slotted stack, not for a read-only peek at a filter's configured item).
 * <p>
 * Same three-way logic as the item variant: a face with no filters at all is left untouched; a face with filters
 * where one matches is allowed (narrowing the candidate set, standing in for the original's
 * {@code increasePriority(face, 12)}); a face with filters where none match is disallowed entirely.
 */
public final class DiamondFluidBehaviour extends DiamondBehaviour {
    @Override
    public Set<Direction> filterFluidDestinations(Level level, BlockPos pipePos, Direction from,
            FluidResource fluid, Set<Direction> candidates) {
        Set<Direction> matched = EnumSet.noneOf(Direction.class);
        Set<Direction> disallowed = EnumSet.noneOf(Direction.class);
        for (Direction dir : candidates) {
            int offset = FILTERS_PER_SIDE * dir.ordinal();
            boolean foundItem = false;
            boolean sideAllowed = false;
            for (int i = 0; i < FILTERS_PER_SIDE; i++) {
                ItemStack filter = getFilters().getItem(offset + i);
                if (filter.isEmpty()) {
                    continue;
                }
                Fluid target = filterFluid(filter);
                if (target == null) {
                    continue;
                }
                foundItem = true;
                if (target == fluid.getFluid()) {
                    sideAllowed = true;
                    break;
                }
            }
            if (foundItem) {
                if (sideAllowed) {
                    matched.add(dir);
                } else {
                    disallowed.add(dir);
                }
            }
        }
        Set<Direction> remaining = new HashSet<>(candidates);
        remaining.removeAll(disallowed);
        if (!matched.isEmpty()) {
            remaining.retainAll(matched);
        }
        return remaining;
    }

    private static @Nullable Fluid filterFluid(ItemStack stack) {
        return stack.getItem() instanceof BucketItem bucket && bucket.content != Fluids.EMPTY ? bucket.content : null;
    }

    /** Real {@code BCTransportConfig}: Diamond's fluid pipe transfers {@code baseFlowRate*8}=80 mB/tick, delay 10. */
    @Override
    public int fluidTransferPerTick() {
        return 80;
    }

    @Override
    public Component getDisplayName() {
        return Component.translatable("block.buildcraft.pipe_diamond_fluid");
    }
}
