package com.buildcraft.transport.menu;

import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import com.buildcraft.transport.pipe.behaviour.EmzuliBehaviour;

/**
 * The 4-slot (Square/Circle/Triangle/Cross) filter grid for an Emzuli pipe - see {@link EmzuliBehaviour}.
 * Arranged in a single row; each slot's fixed colour (Red/Green/Blue/Yellow) is drawn by the screen, not
 * stored per-slot in this menu.
 */
public class EmzuliFilterMenu extends AbstractContainerMenu {
    private static final int FILTER_ORIGIN_X = 62;
    private static final int FILTER_ORIGIN_Y = 20;
    private static final int PLAYER_INV_Y = 56;

    private final Container filters;

    public EmzuliFilterMenu(MenuType<EmzuliFilterMenu> type, int containerId, Inventory playerInventory, Container filters) {
        super(type, containerId);
        checkContainerSize(filters, 4);
        this.filters = filters;
        filters.startOpen(playerInventory.player);

        for (int i = 0; i < 4; i++) {
            addSlot(new Slot(filters, i, FILTER_ORIGIN_X + i * 18, FILTER_ORIGIN_Y));
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
        if (index < 4) {
            if (!moveItemStackTo(original, 4, slots.size(), true)) {
                return ItemStack.EMPTY;
            }
        } else {
            if (!moveItemStackTo(original, 0, 4, false)) {
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
