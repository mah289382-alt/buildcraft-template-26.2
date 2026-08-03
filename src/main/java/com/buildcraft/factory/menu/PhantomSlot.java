package com.buildcraft.factory.menu;

import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * A "ghost" slot - shows an example item as a template (real source: {@code ItemHandlerManager.EnumAccess
 * .PHANTOM}) without ever really holding/consuming it. Never accepts real insertion/extraction via the normal
 * slot-click machinery (see {@link AutoWorkbenchMenu#clicked} for how a click actually sets its example item),
 * always caps at 1 so it only ever displays "which item type," not a real quantity.
 */
public class PhantomSlot extends Slot {
    public PhantomSlot(Container container, int index, int x, int y) {
        super(container, index, x, y);
    }

    @Override
    public boolean mayPlace(ItemStack stack) {
        return false;
    }

    @Override
    public boolean mayPickup(Player player) {
        return false;
    }

    @Override
    public int getMaxStackSize() {
        return 1;
    }
}
