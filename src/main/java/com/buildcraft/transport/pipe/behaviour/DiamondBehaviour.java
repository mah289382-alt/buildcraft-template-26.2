package com.buildcraft.transport.pipe.behaviour;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.jspecify.annotations.Nullable;

import com.buildcraft.transport.TransportContent;
import com.buildcraft.transport.menu.DiamondFilterMenu;
import com.buildcraft.transport.pipe.PipeBehaviour;

/**
 * Ports {@code PipeBehaviourDiamond}/{@code PipeBehaviourDiamondItem}: a 6-face x 9-slot filter inventory
 * (right-click opens the GUI) that routes items to whichever connected face has a matching filter. Mirrors
 * {@code sideCheck}'s exact three-way logic per face: no filters at all on that face -> untouched/still a
 * normal candidate; has filters and the item matches one -> allowed (and - since this port's destination
 * selection is a plain round-robin over an allowed set rather than the original's weighted priority numbers -
 * a match takes priority over any unfiltered candidate by narrowing to matched faces only, functionally the
 * same outcome as the original's {@code increasePriority(face, 12)}); has filters but none match -> disallowed.
 * <p>
 * Not ported: {@code PipeEventItem.Split}'s proportional multi-way stack splitting across every matching face
 * simultaneously - this port's items travel one-at-a-time to a single chosen destination (see
 * {@code PipeBlockEntity.onReachCenter}), so a matching item goes to one matching face at a time (round-robin
 * across ties) rather than being divided across all of them in the same tick. An explicit, documented scope
 * reduction given the existing single-destination-per-item architecture.
 */
public final class DiamondBehaviour implements PipeBehaviour, MenuProvider {
    public static final int FILTERS_PER_SIDE = 9;
    public static final int TOTAL_FILTER_SLOTS = FILTERS_PER_SIDE * 6;

    private final SimpleContainer filters = new SimpleContainer(TOTAL_FILTER_SLOTS);

    public SimpleContainer getFilters() {
        return filters;
    }

    @Override
    public Set<Direction> filterDestinations(Level level, BlockPos pipePos, Direction from, ItemStack stack,
            @Nullable DyeColor colour, Set<Direction> candidates) {
        Set<Direction> matched = EnumSet.noneOf(Direction.class);
        Set<Direction> disallowed = EnumSet.noneOf(Direction.class);
        for (Direction dir : candidates) {
            int offset = FILTERS_PER_SIDE * dir.ordinal();
            boolean foundItem = false;
            boolean sideAllowed = false;
            for (int i = 0; i < FILTERS_PER_SIDE; i++) {
                ItemStack filter = filters.getItem(offset + i);
                if (filter.isEmpty()) {
                    continue;
                }
                foundItem = true;
                if (ItemStack.isSameItemSameComponents(filter, stack)) {
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

    @Override
    public @Nullable AbstractContainerMenu createMenu(int containerId, Inventory playerInventory, Player player) {
        return new DiamondFilterMenu(TransportContent.DIAMOND_FILTER_MENU.get(), containerId, playerInventory, filters);
    }

    @Override
    public Component getDisplayName() {
        return Component.translatable("block.buildcraft.pipe_diamond");
    }

    @Override
    public void save(ValueOutput output) {
        ValueOutput.TypedOutputList<ItemStack> list = output.list("filters", ItemStack.OPTIONAL_CODEC);
        for (int i = 0; i < filters.getContainerSize(); i++) {
            list.add(filters.getItem(i));
        }
    }

    @Override
    public void load(ValueInput input) {
        input.list("filters", ItemStack.OPTIONAL_CODEC).ifPresent(list -> {
            int i = 0;
            for (ItemStack stack : list) {
                if (i >= filters.getContainerSize()) {
                    break;
                }
                filters.setItem(i++, stack);
            }
        });
    }
}
