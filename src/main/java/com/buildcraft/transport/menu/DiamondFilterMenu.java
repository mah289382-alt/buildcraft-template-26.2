package com.buildcraft.transport.menu;

import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import com.buildcraft.transport.pipe.behaviour.DiamondBehaviour;

/**
 * The filter grid for a Diamond pipe (see {@link DiamondBehaviour}) - laid out as the real source GUI does
 * (confirmed by sampling {@code buildcrafttransport:textures/gui/filter.png} directly, since there's no
 * layout logic in {@code GuiDiamondPipe.java} itself - it's baked into the texture): 9 columns x 6 ROWS, one
 * row per {@code Direction} in {@code Direction.values()} order (down/up/north/south/west/east) - NOT a
 * compass-style grid of six 3x3 groups, which was this port's first (wrong) attempt. Row index lines up
 * exactly with {@link DiamondBehaviour}'s own filter indexing ({@code offset = FILTERS_PER_SIDE *
 * dir.ordinal()}), so slot {@code row*9 + col} is already correct with no backend change needed. Each slot
 * holds a real {@link ItemStack} used as a match template (compared via
 * {@code ItemStack.isSameItemSameComponents}, not consumed by matching).
 */
public class DiamondFilterMenu extends AbstractContainerMenu {
    private static final int SLOTS_PER_ROW = DiamondBehaviour.FILTERS_PER_SIDE;
    private static final int GRID_ORIGIN_X = 7;
    private static final int GRID_ORIGIN_Y = 17;
    private static final int PLAYER_INV_Y = 139;

    private final Container filters;

    public DiamondFilterMenu(MenuType<DiamondFilterMenu> type, int containerId, Inventory playerInventory, Container filters) {
        super(type, containerId);
        checkContainerSize(filters, DiamondBehaviour.TOTAL_FILTER_SLOTS);
        this.filters = filters;
        filters.startOpen(playerInventory.player);

        for (int row = 0; row < 6; row++) {
            for (int col = 0; col < SLOTS_PER_ROW; col++) {
                int x = GRID_ORIGIN_X + col * 18;
                int y = GRID_ORIGIN_Y + row * 18;
                addSlot(new Slot(filters, row * SLOTS_PER_ROW + col, x, y));
            }
        }

        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new Slot(playerInventory, col + row * 9 + 9, 8 + col * 18, PLAYER_INV_Y + row * 18));
            }
        }
        for (int col = 0; col < 9; col++) {
            addSlot(new Slot(playerInventory, col, 8 + col * 18, PLAYER_INV_Y + 58));
        }
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        Slot slot = slots.get(index);
        if (slot == null || !slot.hasItem()) {
            return ItemStack.EMPTY;
        }
        ItemStack original = slot.getItem();
        ItemStack moving = original.copy();
        int filterSlotCount = DiamondBehaviour.TOTAL_FILTER_SLOTS;
        if (index < filterSlotCount) {
            if (!moveItemStackTo(original, filterSlotCount, slots.size(), true)) {
                return ItemStack.EMPTY;
            }
        } else {
            if (!moveItemStackTo(original, 0, filterSlotCount, false)) {
                return ItemStack.EMPTY;
            }
        }
        if (original.isEmpty()) {
            slot.set(ItemStack.EMPTY);
        } else {
            slot.setChanged();
        }
        return moving;
    }

    @Override
    public boolean stillValid(Player player) {
        return filters.stillValid(player);
    }

    @Override
    public void removed(Player player) {
        super.removed(player);
        filters.stopOpen(player);
    }
}
