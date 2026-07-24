package com.buildcraft.transport.menu;

import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

import com.buildcraft.transport.pipe.behaviour.WoodDiamondBehaviour;

/**
 * The single 3x3 filter grid for a WoodDiamond pipe (see {@link WoodDiamondBehaviour}) - much smaller than
 * Diamond's own 6x9 grid since WoodDiamond only filters its one extraction direction, not per-side routing.
 * {@code behaviour} is {@code null} on the client's network-reconstructed instance (see
 * {@code TransportContent.WOOD_DIAMOND_FILTER_MENU}'s factory) - filter-mode cycling is server-authoritative
 * only there, matching how {@code DiamondFilterMenu} needs no such control at all.
 */
public class WoodDiamondFilterMenu extends AbstractContainerMenu {
    private static final int FILTER_ORIGIN_X = 62;
    private static final int FILTER_ORIGIN_Y = 20;
    private static final int PLAYER_INV_Y = 84;

    private final Container filters;
    private final ContainerData modeData;
    public final @Nullable WoodDiamondBehaviour behaviour;

    public WoodDiamondFilterMenu(MenuType<WoodDiamondFilterMenu> type, int containerId, Inventory playerInventory, WoodDiamondBehaviour behaviour) {
        this(type, containerId, playerInventory, behaviour.getFilters(), behaviour);
    }

    public WoodDiamondFilterMenu(MenuType<WoodDiamondFilterMenu> type, int containerId, Inventory playerInventory, Container filters) {
        this(type, containerId, playerInventory, filters, null);
    }

    private WoodDiamondFilterMenu(MenuType<WoodDiamondFilterMenu> type, int containerId, Inventory playerInventory,
            Container filters, @Nullable WoodDiamondBehaviour behaviour) {
        super(type, containerId);
        checkContainerSize(filters, 9);
        this.filters = filters;
        this.behaviour = behaviour;
        filters.startOpen(playerInventory.player);

        for (int i = 0; i < 9; i++) {
            int x = FILTER_ORIGIN_X + (i % 3) * 18;
            int y = FILTER_ORIGIN_Y + (i / 3) * 18;
            addSlot(new Slot(filters, i, x, y));
        }

        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new Slot(playerInventory, col + row * 9 + 9, 8 + col * 18, PLAYER_INV_Y + row * 18));
            }
        }
        for (int col = 0; col < 9; col++) {
            addSlot(new Slot(playerInventory, col, 8 + col * 18, PLAYER_INV_Y + 58));
        }

        this.modeData = behaviour != null ? new ContainerData() {
            @Override
            public int get(int index) {
                return behaviour.getMode().ordinal();
            }

            @Override
            public void set(int index, int value) {
                // Mode changes flow one way, through clickMenuButton -> behaviour.setMode - never written here.
            }

            @Override
            public int getCount() {
                return 1;
            }
        } : new SimpleContainerData(1);
        addDataSlots(modeData);
    }

    /** The filter mode as last synced from the server - meaningful on both sides, unlike {@link #behaviour}. */
    public WoodDiamondBehaviour.FilterMode getSyncedMode() {
        WoodDiamondBehaviour.FilterMode[] modes = WoodDiamondBehaviour.FilterMode.values();
        return modes[modeData.get(0) % modes.length];
    }

    /**
     * Cycles the filter mode (whitelist -> blacklist -> round-robin -> ...). Wired through vanilla's built-in
     * lightweight menu-button protocol ({@code ServerboundContainerButtonClickPacket} ->
     * {@link #clickMenuButton}, the same mechanism vanilla uses for e.g. the enchanting table's page buttons) -
     * only meaningful server-side, where {@link #behaviour} is the real instance (see this class's javadoc).
     */
    @Override
    public boolean clickMenuButton(Player player, int buttonId) {
        if (buttonId == 0 && behaviour != null) {
            WoodDiamondBehaviour.FilterMode[] modes = WoodDiamondBehaviour.FilterMode.values();
            behaviour.setMode(modes[(behaviour.getMode().ordinal() + 1) % modes.length]);
            return true;
        }
        return false;
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        Slot slot = slots.get(index);
        if (slot == null || !slot.hasItem()) {
            return ItemStack.EMPTY;
        }
        ItemStack original = slot.getItem();
        ItemStack moving = original.copy();
        if (index < 9) {
            if (!moveItemStackTo(original, 9, slots.size(), true)) {
                return ItemStack.EMPTY;
            }
        } else {
            if (!moveItemStackTo(original, 0, 9, false)) {
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
