package com.buildcraft.factory.menu;

import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.transfer.item.ItemStacksResourceHandler;
import net.neoforged.neoforge.transfer.item.ResourceHandlerSlot;
import org.jspecify.annotations.Nullable;

import com.buildcraft.factory.blockentity.RefineryBlockEntity;

/**
 * Player inventory plus 1 real GUI slot for bucket fill/drain - real source ({@code TileRefinery}) has no item
 * slots either (interaction is a direct right-click-in-hand via {@code BlockRefinery.onBlockActivated}, ported
 * separately on the block itself), but per direct user request this port ALSO adds a visible GUI "spot", same
 * reasoning as the Combustion Engine's slot. Tank fill levels synced via a small {@link ContainerData}, same
 * pattern as {@code CombustionEngineMenu}.
 */
public class RefineryMenu extends AbstractContainerMenu {
    private static final int PLAYER_INV_Y = 84;
    private static final int PLAYER_SLOT_COUNT = 27;

    private final @Nullable RefineryBlockEntity refinery;
    private final ContainerData tankLevels;

    public RefineryMenu(MenuType<RefineryMenu> type, int containerId, Inventory playerInventory, @Nullable RefineryBlockEntity refinery) {
        super(type, containerId);
        this.refinery = refinery;

        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new Slot(playerInventory, col + row * 9 + 9, 8 + col * 18, PLAYER_INV_Y + row * 18));
            }
        }
        for (int col = 0; col < 9; col++) {
            addSlot(new Slot(playerInventory, col, 8 + col * 18, PLAYER_INV_Y + 58));
        }

        // 1 real GUI slot for filling/draining by hand - see RefineryBlockEntity's javadoc. Positioned between
        // the 2 tank bars (oil ends at x=64, fuel starts at x=112 - centered in that 48px gap), vertically
        // centered on the tanks. MUST be added unconditionally (same slot count on client and server) - the
        // client-side menu instance is always constructed with refinery=null, so gating this behind
        // "if (refinery != null)" would desync the container-sync packet's slot count and crash the client the
        // moment the GUI opens - the exact bug already found and fixed for the Combustion Engine's slot.
        ItemStacksResourceHandler bucketSlot = refinery != null ? refinery.getBucketSlot() : new ItemStacksResourceHandler(1);
        addSlot(new ResourceHandlerSlot(bucketSlot, bucketSlot::set, 0, 79, 34));

        this.tankLevels = refinery != null ? new ContainerData() {
            @Override
            public int get(int index) {
                return refinery.getTankFillPercent(index);
            }

            @Override
            public void set(int index, int value) {
            }

            @Override
            public int getCount() {
                return 2;
            }
        } : new SimpleContainerData(2);
        addDataSlots(tankLevels);
    }

    public int getOilPercent() {
        return tankLevels.get(0);
    }

    public int getFuelPercent() {
        return tankLevels.get(1);
    }

    /** Shift-clicking the bucket slot ({@link ResourceHandlerSlot}) just returns its contents to the player -
     * filling/draining already happens automatically every tick regardless of whether the GUI is even open.
     * Shift-clicking a player slot does nothing special (real source has no shift-click-fills-the-tile
     * behavior for the Refinery specifically, unlike the Combustion Engine). */
    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        if (index < 0 || index >= slots.size()) {
            return ItemStack.EMPTY;
        }
        Slot slot = slots.get(index);
        if (!(slot instanceof ResourceHandlerSlot) || !slot.hasItem()) {
            return ItemStack.EMPTY;
        }
        ItemStack stack = slot.getItem();
        ItemStack clicked = stack.copy();
        if (!moveItemStackTo(stack, 0, PLAYER_SLOT_COUNT, true)) {
            return ItemStack.EMPTY;
        }
        slot.onQuickCraft(stack, clicked);
        if (stack.isEmpty()) {
            slot.setByPlayer(ItemStack.EMPTY);
        } else {
            slot.setChanged();
        }
        return stack.getCount() == clicked.getCount() ? ItemStack.EMPTY : clicked;
    }

    @Override
    public boolean stillValid(Player player) {
        return true;
    }
}
