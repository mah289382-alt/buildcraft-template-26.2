package com.buildcraft.factory.client.menu;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.Slot;

import com.buildcraft.factory.menu.AutoWorkbenchMenu;
import com.buildcraft.factory.menu.PhantomSlot;

/**
 * The Auto Workbench's GUI - no real GUI texture asset exists for the classic {@code TileAutoWorkbenchItems} in
 * source (same situation as the Refinery - only the modern, unrelated Assembly Table has a real texture), so
 * drawn procedurally like {@link RefineryScreen}. Blueprint (phantom, ghost outline) on the left, real materials
 * grid + result slot on the right, a small FE bar below.
 */
public class AutoWorkbenchScreen extends AbstractContainerScreen<AutoWorkbenchMenu> {
    private static final int BORDER_COLOR = 0xFF373737;
    private static final int PANEL_COLOR = 0xFFC6C6C6;
    private static final int SLOT_COLOR = 0xFF8B8B8B;
    private static final int PHANTOM_SLOT_COLOR = 0xFF6B6B8B;
    private static final int ENERGY_EMPTY_COLOR = 0xFF4A4A4A;
    private static final int ENERGY_FULL_COLOR = 0xFFE0A030;
    private static final int ENERGY_BAR_X = 8;
    private static final int ENERGY_BAR_Y = 78;
    private static final int ENERGY_BAR_W = 160;
    private static final int ENERGY_BAR_H = 6;

    public AutoWorkbenchScreen(AutoWorkbenchMenu menu, net.minecraft.world.entity.player.Inventory inventory, Component title) {
        super(menu, inventory, title, 176, 184);
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        int x = (this.width - this.imageWidth) / 2;
        int y = (this.height - this.imageHeight) / 2;
        graphics.fill(x, y, x + this.imageWidth, y + this.imageHeight, BORDER_COLOR);
        graphics.fill(x + 1, y + 1, x + this.imageWidth - 1, y + this.imageHeight - 1, PANEL_COLOR);

        for (Slot slot : this.menu.slots) {
            int sx = x + slot.x;
            int sy = y + slot.y;
            boolean phantom = slot instanceof PhantomSlot;
            graphics.fill(sx - 1, sy - 1, sx + 17, sy + 17, BORDER_COLOR);
            graphics.fill(sx, sy, sx + 16, sy + 16, phantom ? PHANTOM_SLOT_COLOR : SLOT_COLOR);
        }

        graphics.textWithWordWrap(this.font, Component.literal("Pattern"), x + 8, y + 6, 60, 0x404040);
        graphics.textWithWordWrap(this.font, Component.literal("Materials"), x + 98, y + 6, 70, 0x404040);

        int percent = this.menu.getEnergyPercent();
        int ex = x + ENERGY_BAR_X;
        int ey = y + ENERGY_BAR_Y;
        graphics.fill(ex - 1, ey - 1, ex + ENERGY_BAR_W + 1, ey + ENERGY_BAR_H + 1, BORDER_COLOR);
        graphics.fill(ex, ey, ex + ENERGY_BAR_W, ey + ENERGY_BAR_H, ENERGY_EMPTY_COLOR);
        int filledWidth = ENERGY_BAR_W * Math.max(0, Math.min(100, percent)) / 100;
        if (filledWidth > 0) {
            graphics.fill(ex, ey, ex + filledWidth, ey + ENERGY_BAR_H, ENERGY_FULL_COLOR);
        }
    }
}
