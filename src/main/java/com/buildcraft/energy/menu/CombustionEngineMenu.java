package com.buildcraft.energy.menu;

import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.transfer.ResourceHandler;
import net.neoforged.neoforge.transfer.ResourceHandlerUtil;
import net.neoforged.neoforge.transfer.access.ItemAccess;
import net.neoforged.neoforge.transfer.fluid.BucketResourceHandler;
import net.neoforged.neoforge.transfer.fluid.FluidResource;
import net.neoforged.neoforge.transfer.item.ItemStacksResourceHandler;
import net.neoforged.neoforge.transfer.item.ResourceHandlerSlot;
import net.neoforged.neoforge.transfer.transaction.Transaction;
import org.jspecify.annotations.Nullable;

import com.buildcraft.energy.blockentity.CombustionEngineBlockEntity;

/**
 * Player inventory only, at the real Y offset (95, confirmed against {@code ContainerEngineIron_BC8.java}'s
 * {@code addFullPlayerInventory(95)}) - source has NO item slots for the tanks at all. Real interaction is
 * ported from {@code Tank.transferStackToTank}/{@code onGuiClicked}: shift-clicking any fluid-container item
 * (bucket, etc) tries each tank in order (fuel, coolant, residue) - first filling the tank FROM the item, then
 * (if that didn't apply) draining the tank INTO the item - via the modern {@link BucketResourceHandler}/
 * {@link ResourceHandlerUtil#move} idiom instead of source's {@code IFluidHandlerItem}. Clicking directly on one
 * of the screen's tank gauges (see {@link com.buildcraft.energy.client.menu.CombustionEngineScreen}) does the
 * same for just THAT tank, using whatever's on the cursor - ported via vanilla's lightweight menu-button
 * protocol ({@code ServerboundContainerButtonClickPacket} -> {@link #clickMenuButton}), the same mechanism
 * {@code WoodDiamondFilterMenu} uses for its mode-cycle button.
 * <p>
 * Tank fill levels are synced to the client via a small {@link ContainerData} (3 ints, 0-1000 PERMILLE each, not
 * percent - see {@link CombustionEngineBlockEntity#getTankFillPercent}'s javadoc for why) for the screen's bars.
 */
public class CombustionEngineMenu extends AbstractContainerMenu {
    private static final int PLAYER_INV_Y = 95;
    private static final int TANK_COUNT = 3;
    private static final int PLAYER_SLOT_COUNT = 27;

    private final @Nullable CombustionEngineBlockEntity engine;
    private final ContainerData tankLevels;

    public CombustionEngineMenu(MenuType<CombustionEngineMenu> type, int containerId, Inventory playerInventory, @Nullable CombustionEngineBlockEntity engine) {
        super(type, containerId);
        this.engine = engine;

        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new Slot(playerInventory, col + row * 9 + 9, 8 + col * 18, PLAYER_INV_Y + row * 18));
            }
        }
        for (int col = 0; col < 9; col++) {
            addSlot(new Slot(playerInventory, col, 8 + col * 18, PLAYER_INV_Y + 58));
        }

        // 1 real GUI slot for filling by hand - see CombustionEngineBlockEntity's javadoc for why this exists
        // beyond a strict source port (source is shift-click-only, with no visible "spot" to fill at all).
        // Positioned on the real "+" icon baked into engine_combustion.png between the fuel/coolant gauges
        // (pixel-diffed against the background: glyph bbox x=53-67,y=41-55, center (60,48); slot x/y is the
        // top-left of its 16x16 item area, so top-left = center - 8). Auto-routes to whichever tank the
        // bucket's fluid matches - NOT 2 separate fixed-purpose slots (an earlier, over-engineered first pass).
        // MUST be added unconditionally (same slot count on client and server) - the client-side menu instance
        // is always constructed with engine=null (see EnergyContent's IMenuTypeExtension factory), so gating
        // this behind "if (engine != null)" made the client menu have fewer slots than the server's, which
        // desyncs the container-sync packet's slot count and crashes the client with a network protocol error
        // the moment the GUI opens. A throwaway local handler stands in for the real one when engine is null,
        // exactly like tankLevels already falls back to a plain SimpleContainerData below.
        ItemStacksResourceHandler bucketSlot = engine != null ? engine.getBucketSlot() : new ItemStacksResourceHandler(1);
        addSlot(new ResourceHandlerSlot(bucketSlot, bucketSlot::set, 0, 52, 40));

        // 4th value (index 3) is the fuel tank's fluid identity (0=empty/1=Oil/2=Fuel) - the fuel tank accepts
        // either real fluid, and the GUI needs to know which one to pick the right texture/colour, since it
        // previously always assumed Fuel regardless of what was actually in there.
        this.tankLevels = engine != null ? new ContainerData() {
            @Override
            public int get(int index) {
                return index == 3 ? engine.getFuelTankFluidId() : engine.getTankFillPercent(index);
            }

            @Override
            public void set(int index, int value) {
            }

            @Override
            public int getCount() {
                return 4;
            }
        } : new SimpleContainerData(4);
        addDataSlots(tankLevels);
    }

    public int getFuelPercent() {
        return tankLevels.get(0);
    }

    public int getCoolantPercent() {
        return tankLevels.get(1);
    }

    public int getResiduePercent() {
        return tankLevels.get(2);
    }

    /** 0=empty, 1=Oil, 2=Fuel - which fluid is actually in the fuel tank right now. */
    public int getFuelTankFluidId() {
        return tankLevels.get(3);
    }

    /** Shift-clicking a player slot ports {@code ContainerEngineIron_BC8.transferStackInSlot}: try every tank in
     * order with the item. Shift-clicking one of the 2 dedicated bucket slots ({@link ResourceHandlerSlot}, NOT
     * a player slot - {@code slot.getContainerSlot()} means something different there, so it must NOT be passed
     * to {@link ItemAccess#forPlayerSlot}) just returns its contents to the player instead - filling already
     * happens automatically every tick regardless of whether the GUI is even open. */
    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        if (engine == null || index < 0 || index >= slots.size()) {
            return ItemStack.EMPTY;
        }
        Slot slot = slots.get(index);
        if (!slot.hasItem()) {
            return ItemStack.EMPTY;
        }
        if (slot instanceof ResourceHandlerSlot) {
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
        if (!player.level().isClientSide()) {
            ItemAccess access = ItemAccess.forPlayerSlot(player, slot.getContainerSlot());
            for (int i = 0; i < TANK_COUNT; i++) {
                if (tryTransfer(engine.getTank(i), access)) {
                    break;
                }
            }
        }
        return ItemStack.EMPTY;
    }

    /** Ports {@code Tank.onGuiClicked}: clicking a specific tank gauge tries only that tank, using the cursor item. */
    @Override
    public boolean clickMenuButton(Player player, int buttonId) {
        if (engine != null && buttonId >= 0 && buttonId < TANK_COUNT && !player.level().isClientSide()) {
            return tryTransfer(engine.getTank(buttonId), ItemAccess.forPlayerCursor(player, this));
        }
        return super.clickMenuButton(player, buttonId);
    }

    /** First try filling the tank from the item, then (only if that moved nothing) draining the tank into it. */
    private static boolean tryTransfer(ResourceHandler<FluidResource> tank, ItemAccess access) {
        ResourceHandler<FluidResource> bucket = new BucketResourceHandler(access);
        try (Transaction tx = Transaction.openRoot()) {
            if (ResourceHandlerUtil.move(bucket, tank, r -> true, Integer.MAX_VALUE, tx) > 0) {
                tx.commit();
                return true;
            }
        }
        try (Transaction tx = Transaction.openRoot()) {
            if (ResourceHandlerUtil.move(tank, bucket, r -> true, Integer.MAX_VALUE, tx) > 0) {
                tx.commit();
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean stillValid(Player player) {
        return true;
    }
}
