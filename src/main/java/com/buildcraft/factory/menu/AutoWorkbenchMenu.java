package com.buildcraft.factory.menu;

import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.transfer.item.ItemStacksResourceHandler;
import net.neoforged.neoforge.transfer.item.ResourceHandlerSlot;
import org.jspecify.annotations.Nullable;

import com.buildcraft.factory.blockentity.AutoWorkbenchBlockEntity;

/**
 * 3x3 phantom blueprint grid (see {@link PhantomSlot}) + 3x3 real materials grid + 1 result slot + player
 * inventory. Real source ({@code ContainerAutoWorkbench}/{@code GuiAutoWorkbenchItems}) lays these out as 2
 * side-by-side 3x3 grids with the result slot between them - mirrored here.
 */
public class AutoWorkbenchMenu extends AbstractContainerMenu {
    private static final int GRID_SIZE = 9;
    private static final int BLUEPRINT_START = 0;
    private static final int PREVIEW_INDEX = GRID_SIZE;
    private static final int MATERIALS_START = PREVIEW_INDEX + 1;
    private static final int RESULT_INDEX = MATERIALS_START + GRID_SIZE;
    private static final int PLAYER_INV_START = RESULT_INDEX + 1;
    private static final int PLAYER_SLOT_COUNT = 27;
    private static final int PLAYER_INV_Y = 97;

    private static final int BLUEPRINT_X = 8;
    private static final int BLUEPRINT_Y = 17;
    private static final int PREVIEW_X = 72;
    private static final int PREVIEW_Y = 35;
    private static final int MATERIALS_X = 98;
    private static final int MATERIALS_Y = 17;
    private static final int RESULT_X = 152;
    private static final int RESULT_Y = 35;

    private final @Nullable AutoWorkbenchBlockEntity workbench;
    private final SimpleContainer previewContainer = new SimpleContainer(1);
    private final ContainerData energyData;

    public AutoWorkbenchMenu(MenuType<AutoWorkbenchMenu> type, int containerId, Inventory playerInventory,
            @Nullable AutoWorkbenchBlockEntity workbench) {
        super(type, containerId);
        this.workbench = workbench;

        var blueprint = workbench != null ? workbench.getBlueprint() : new net.minecraft.world.SimpleContainer(GRID_SIZE);
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 3; col++) {
                addSlot(new PhantomSlot(blueprint, col + row * 3, BLUEPRINT_X + col * 18, BLUEPRINT_Y + row * 18));
            }
        }

        // Read-only preview of what the current Pattern will craft. Real vanilla CraftingMenu doesn't sync its
        // own result preview through the tile/block-entity at all - it recomputes on every grid change and puts
        // the result directly into a normal Slot, which syncs through the same proven slot-broadcast mechanism
        // every other slot here already uses. Mirrored here instead of the earlier (broken) approach of trying
        // to sync a plain block-entity field over block-entity NBT.
        addSlot(new PhantomSlot(previewContainer, 0, PREVIEW_X, PREVIEW_Y));

        ItemStacksResourceHandler materials = workbench != null ? workbench.getMaterials() : new ItemStacksResourceHandler(GRID_SIZE);
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 3; col++) {
                int index = col + row * 3;
                addSlot(new ResourceHandlerSlot(materials, materials::set, index,
                        MATERIALS_X + col * 18, MATERIALS_Y + row * 18));
            }
        }

        // Uses getResultView() (extract-only) for placement/pickup checks, not the raw handler, so the player
        // can't manually place an item into the output slot via the GUI - see AutoWorkbenchBlockEntity's
        // extractOnlyResult javadoc. result::set (the raw object) is still what actually updates the slot's
        // displayed contents when the server's real crafted output changes.
        ItemStacksResourceHandler result = workbench != null ? workbench.getResult() : new ItemStacksResourceHandler(1);
        var resultView = workbench != null ? workbench.getResultView() : result;
        addSlot(new ResourceHandlerSlot(resultView, result::set, 0, RESULT_X, RESULT_Y));

        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new net.minecraft.world.inventory.Slot(playerInventory, col + row * 9 + 9, 8 + col * 18, PLAYER_INV_Y + row * 18));
            }
        }
        for (int col = 0; col < 9; col++) {
            addSlot(new net.minecraft.world.inventory.Slot(playerInventory, col, 8 + col * 18, PLAYER_INV_Y + 58));
        }

        this.energyData = workbench != null ? new ContainerData() {
            @Override
            public int get(int index) {
                return workbench.getEnergyPercent();
            }

            @Override
            public void set(int index, int value) {
            }

            @Override
            public int getCount() {
                return 1;
            }
        } : new SimpleContainerData(1);
        addDataSlots(energyData);

        // Real gap fixed: the preview slot was only ever populated on a fresh click, so a pattern already set
        // from a previous session showed no preview at all until you re-set it. Initialize it here so
        // reopening the GUI with an existing pattern shows the correct preview immediately.
        updatePreview();
    }

    public int getEnergyPercent() {
        return energyData.get(0);
    }

    public ItemStack getAssumedResult() {
        return workbench != null ? workbench.getAssumedResult() : ItemStack.EMPTY;
    }

    /** Phantom blueprint slots don't go through normal insert/extract - a left-click sets that slot's example
     * item to 1 copy of whatever's on the cursor (without consuming it), any other click clears it. Real
     * materials/result/player slots fall through to the normal handling. */
    @Override
    public void clicked(int slotId, int button, ContainerInput containerInput, Player player) {
        if (slotId >= BLUEPRINT_START && slotId < BLUEPRINT_START + GRID_SIZE) {
            var slot = slots.get(slotId);
            ItemStack carried = getCarried();
            slot.set(carried.isEmpty() ? ItemStack.EMPTY : carried.copyWithCount(1));
            updatePreview();
            broadcastChanges();
            return;
        }
        super.clicked(slotId, button, containerInput, player);
    }

    /** Server-only (see class javadoc on the preview slot) - recomputes the matched recipe right now (instead of
     * waiting for the block entity's own next-tick dirty check) and pushes the result into the real, synced
     * preview slot. */
    private void updatePreview() {
        if (workbench != null && workbench.getLevel() != null) {
            workbench.recomputeRecipe(workbench.getLevel());
            previewContainer.setItem(0, workbench.getAssumedResult());
        }
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        if (index < 0 || index >= slots.size()) {
            return ItemStack.EMPTY;
        }
        var slot = slots.get(index);
        if ((index >= BLUEPRINT_START && index < BLUEPRINT_START + GRID_SIZE) || index == PREVIEW_INDEX) {
            return ItemStack.EMPTY; // phantom/preview slots never participate in shift-click transfers
        }
        if (!slot.hasItem()) {
            return ItemStack.EMPTY;
        }
        ItemStack original = slot.getItem();
        ItemStack moving = original.copy();
        if (index >= MATERIALS_START && index <= RESULT_INDEX) {
            if (!moveItemStackTo(original, PLAYER_INV_START, PLAYER_INV_START + PLAYER_SLOT_COUNT + 9, true)) {
                return ItemStack.EMPTY;
            }
        } else {
            if (!moveItemStackTo(original, MATERIALS_START, RESULT_INDEX, false)) {
                return ItemStack.EMPTY;
            }
        }
        if (original.isEmpty()) {
            slot.setByPlayer(ItemStack.EMPTY);
        } else {
            slot.setChanged();
        }
        return original.getCount() == moving.getCount() ? ItemStack.EMPTY : moving;
    }

    @Override
    public boolean stillValid(Player player) {
        return true;
    }
}
