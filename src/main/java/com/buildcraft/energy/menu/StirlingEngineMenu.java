package com.buildcraft.energy.menu;

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

import com.buildcraft.energy.blockentity.StirlingEngineBlockEntity;

/**
 * A single fuel slot + player inventory, for the Stirling Engine's GUI. Slot positions are real, confirmed
 * against {@code ContainerEngineStone_BC8.java} (fuel slot at exactly (80,41)) and
 * {@code GuiEngineStone_BC8.java} (real window size 176x166, {@code addFullPlayerInventory(84)}) - not
 * estimated.
 */
public class StirlingEngineMenu extends AbstractContainerMenu {
    private static final int FUEL_X = 80;
    private static final int FUEL_Y = 41;
    private static final int PLAYER_INV_Y = 84;

    private final Container fuel;
    private final ContainerData fuelData;

    public StirlingEngineMenu(MenuType<StirlingEngineMenu> type, int containerId, Inventory playerInventory, StirlingEngineBlockEntity engine) {
        this(type, containerId, playerInventory, engine.getFuelSlot(), engine);
    }

    public StirlingEngineMenu(MenuType<StirlingEngineMenu> type, int containerId, Inventory playerInventory, Container fuel) {
        this(type, containerId, playerInventory, fuel, null);
    }

    private StirlingEngineMenu(MenuType<StirlingEngineMenu> type, int containerId, Inventory playerInventory, Container fuel,
            @Nullable StirlingEngineBlockEntity engine) {
        super(type, containerId);
        checkContainerSize(fuel, 1);
        this.fuel = fuel;
        fuel.startOpen(playerInventory.player);

        addSlot(new Slot(fuel, 0, FUEL_X, FUEL_Y));

        this.fuelData = engine != null ? new ContainerData() {
            @Override
            public int get(int index) {
                return engine.getFuelPercent();
            }

            @Override
            public void set(int index, int value) {
            }

            @Override
            public int getCount() {
                return 1;
            }
        } : new SimpleContainerData(1);
        addDataSlots(fuelData);

        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new Slot(playerInventory, col + row * 9 + 9, 8 + col * 18, PLAYER_INV_Y + row * 18));
            }
        }
        for (int col = 0; col < 9; col++) {
            addSlot(new Slot(playerInventory, col, 8 + col * 18, PLAYER_INV_Y + 58));
        }
    }

    public int getFuelPercent() {
        return fuelData.get(0);
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        Slot slot = slots.get(index);
        if (slot == null || !slot.hasItem()) {
            return ItemStack.EMPTY;
        }
        ItemStack original = slot.getItem();
        ItemStack moving = original.copy();
        if (index == 0) {
            if (!moveItemStackTo(original, 1, slots.size(), true)) {
                return ItemStack.EMPTY;
            }
        } else {
            if (!moveItemStackTo(original, 0, 1, false)) {
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
        return fuel.stillValid(player);
    }

    @Override
    public void removed(Player player) {
        super.removed(player);
        fuel.stopOpen(player);
    }
}
