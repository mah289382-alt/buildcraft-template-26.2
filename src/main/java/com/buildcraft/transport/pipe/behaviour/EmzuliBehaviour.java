package com.buildcraft.transport.pipe.behaviour;

import net.minecraft.network.chat.Component;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.jspecify.annotations.Nullable;

import com.buildcraft.transport.TransportContent;
import com.buildcraft.transport.menu.EmzuliFilterMenu;

/**
 * Ports {@code PipeBehaviourEmzuli} (extends {@code PipeBehaviourWood}): 4 fixed slots (Square/Circle/Triangle/
 * Cross, each with its own default colour tag - Red/Green/Blue/Yellow, matching source's {@code SlotIndex}
 * enum exactly) that round-robin extraction across whichever slots have a filter set, tagging each extracted
 * item with its slot's colour (see {@link WoodBehaviour#colourForExtraction}) for a downstream Daizuli pipe to
 * match against.
 * <p>
 * Simplified vs. source: the original's per-slot activation is driven by an external redstone-gate action
 * ({@code ActionExtractionPreset}, a 2-tick TTL that must be continuously re-triggered to keep a slot "live") -
 * this port has no gate/statement system at all yet (a documented gap across the whole transport module, not
 * specific to Emzuli), so there's no equivalent trigger surface to port that timing control from. Instead,
 * every slot with a non-empty filter is simply always eligible, and the round-robin cycles among all of them
 * continuously - preserving the visible "cycles through up to 4 colour-tagged item filters" behaviour without
 * the gate-driven activation window. Per-slot colour reassignment (source allows overriding each slot's default
 * colour) also isn't exposed - the four colours stay fixed at their source defaults.
 */
public final class EmzuliBehaviour extends WoodBehaviour implements MenuProvider {
    public enum Slot {
        SQUARE(DyeColor.RED), CIRCLE(DyeColor.GREEN), TRIANGLE(DyeColor.BLUE), CROSS(DyeColor.YELLOW);

        public final DyeColor colour;

        Slot(DyeColor colour) {
            this.colour = colour;
        }
    }

    private final SimpleContainer filters = new SimpleContainer(4);
    private int currentSlot = 0;

    public SimpleContainer getFilters() {
        return filters;
    }

    /**
     * A pure lookup (no side effects) - {@link #canExtract} is called once per slot in the neighbor's
     * inventory while probing for a match, so it must not mutate {@link #currentSlot} itself; only
     * {@link #onExtracted} (called once, after a real extraction succeeds) advances the round-robin cursor.
     */
    private int firstFilledFrom(int start, boolean skipStart) {
        for (int i = skipStart ? 1 : 0; i < 4; i++) {
            int candidate = (start + i) % 4;
            if (!filters.getItem(candidate).isEmpty()) {
                return candidate;
            }
        }
        return -1;
    }

    @Override
    protected boolean canExtract(ItemStack stack) {
        int index = firstFilledFrom(currentSlot, false);
        if (index < 0) {
            return false;
        }
        ItemStack filter = filters.getItem(index);
        return ItemStack.isSameItemSameComponents(filter, stack);
    }

    @Override
    protected @Nullable DyeColor colourForExtraction() {
        int used = firstFilledFrom(currentSlot, false);
        return Slot.values()[used < 0 ? currentSlot : used].colour;
    }

    @Override
    protected void onExtracted(ItemStack extracted) {
        int used = firstFilledFrom(currentSlot, false);
        int base = used < 0 ? currentSlot : used;
        int next = firstFilledFrom(base, true);
        currentSlot = next < 0 ? base : next;
    }

    @Override
    public @Nullable AbstractContainerMenu createMenu(int containerId, Inventory playerInventory, Player player) {
        return new EmzuliFilterMenu(TransportContent.EMZULI_FILTER_MENU.get(), containerId, playerInventory, filters);
    }

    @Override
    public Component getDisplayName() {
        return Component.translatable("block.buildcraft.pipe_emzuli");
    }

    @Override
    public void save(ValueOutput output) {
        super.save(output);
        ValueOutput.TypedOutputList<ItemStack> list = output.list("filters", ItemStack.OPTIONAL_CODEC);
        for (int i = 0; i < filters.getContainerSize(); i++) {
            list.add(filters.getItem(i));
        }
        output.putInt("currentSlot", currentSlot);
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
        currentSlot = input.getIntOr("currentSlot", 0) % 4;
    }
}
