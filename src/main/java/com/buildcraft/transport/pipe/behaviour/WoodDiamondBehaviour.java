package com.buildcraft.transport.pipe.behaviour;

import net.minecraft.network.chat.Component;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.jspecify.annotations.Nullable;

import com.buildcraft.transport.TransportContent;
import com.buildcraft.transport.menu.WoodDiamondFilterMenu;

/**
 * Ports {@code PipeBehaviourWoodDiamond}: {@link WoodBehaviour}'s extraction loop, narrowed by a 9-slot filter
 * with three modes - {@code WHITE_LIST} (only extract items matching a non-empty filter slot; if every slot is
 * empty, falls back to accepting anything, matching source's {@code filters.extract(...).isEmpty()} check),
 * {@code BLACK_LIST} (the inverse), and {@code ROUND_ROBIN} (only match the single "current" filter slot,
 * advancing to the next non-empty slot after each successful extraction). Fluid extraction/filtering isn't
 * ported (no fluid pipe system exists in this port yet).
 */
public final class WoodDiamondBehaviour extends WoodBehaviour implements MenuProvider {
    private static final int FILTER_SLOTS = 9;

    public enum FilterMode {
        WHITE_LIST, BLACK_LIST, ROUND_ROBIN;

        static FilterMode byOrdinal(int i) {
            FilterMode[] values = values();
            return i >= 0 && i < values.length ? values[i] : WHITE_LIST;
        }
    }

    private final SimpleContainer filters = new SimpleContainer(FILTER_SLOTS);
    private FilterMode mode = FilterMode.WHITE_LIST;
    private int currentFilter = 0;

    public SimpleContainer getFilters() {
        return filters;
    }

    public FilterMode getMode() {
        return mode;
    }

    public void setMode(FilterMode mode) {
        this.mode = mode;
    }

    @Override
    protected boolean canExtract(ItemStack stack) {
        return switch (mode) {
            case WHITE_LIST -> allFiltersEmpty() || matchesAnyFilter(stack);
            case BLACK_LIST -> !allFiltersEmpty() && !matchesAnyFilter(stack);
            case ROUND_ROBIN -> {
                ItemStack filter = filters.getItem(currentFilter);
                yield !filter.isEmpty() && ItemStack.isSameItemSameComponents(filter, stack);
            }
        };
    }

    @Override
    protected void onExtracted(ItemStack extracted) {
        if (mode == FilterMode.ROUND_ROBIN) {
            advanceFilter();
        }
    }

    private boolean allFiltersEmpty() {
        for (int i = 0; i < filters.getContainerSize(); i++) {
            if (!filters.getItem(i).isEmpty()) {
                return false;
            }
        }
        return true;
    }

    private boolean matchesAnyFilter(ItemStack stack) {
        for (int i = 0; i < filters.getContainerSize(); i++) {
            ItemStack filter = filters.getItem(i);
            if (!filter.isEmpty() && ItemStack.isSameItemSameComponents(filter, stack)) {
                return true;
            }
        }
        return false;
    }

    private void advanceFilter() {
        int start = currentFilter;
        do {
            currentFilter = (currentFilter + 1) % filters.getContainerSize();
        } while (currentFilter != start && filters.getItem(currentFilter).isEmpty());
    }

    @Override
    public @Nullable AbstractContainerMenu createMenu(int containerId, Inventory playerInventory, Player player) {
        return new WoodDiamondFilterMenu(TransportContent.WOOD_DIAMOND_FILTER_MENU.get(), containerId, playerInventory, this);
    }

    @Override
    public Component getDisplayName() {
        return Component.translatable("block.buildcraft.pipe_wood_diamond");
    }

    @Override
    public void save(ValueOutput output) {
        super.save(output);
        ValueOutput.TypedOutputList<ItemStack> list = output.list("filters", ItemStack.OPTIONAL_CODEC);
        for (int i = 0; i < filters.getContainerSize(); i++) {
            list.add(filters.getItem(i));
        }
        output.putInt("mode", mode.ordinal());
        output.putInt("currentFilter", currentFilter);
    }

    @Override
    public void load(ValueInput input) {
        super.load(input);
        input.list("filters", ItemStack.OPTIONAL_CODEC).ifPresent(list -> {
            int i = 0;
            for (ItemStack stack : list) {
                if (i >= filters.getContainerSize()) {
                    break;
                }
                filters.setItem(i++, stack);
            }
        });
        mode = FilterMode.byOrdinal(input.getIntOr("mode", 0));
        currentFilter = input.getIntOr("currentFilter", 0) % FILTER_SLOTS;
    }
}
