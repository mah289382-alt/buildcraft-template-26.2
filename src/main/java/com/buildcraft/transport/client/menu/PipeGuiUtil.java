package com.buildcraft.transport.client.menu;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;

/**
 * Shared background drawing for this port's filter GUIs (Diamond/WoodDiamond/Emzuli) - none of them have a
 * hand-authored panel texture yet, so instead of vanilla's textured 18x18 slot art, this draws each slot as a
 * small recessed square (dark border, mid-gray "hole") against the lighter panel fill, using the same three
 * tones vanilla's own inventory texture uses (panel 0xC6C6C6, slot recess 0x8B8B8B, border 0x373737) so the
 * result reads as real inventory slots rather than a flat, featureless rectangle.
 */
final class PipeGuiUtil {
    private static final int PANEL_COLOR = 0xFFC6C6C6;
    private static final int BORDER_COLOR = 0xFF373737;
    private static final int SLOT_COLOR = 0xFF8B8B8B;

    private PipeGuiUtil() {}

    static void drawPanel(GuiGraphicsExtractor graphics, int x, int y, int width, int height) {
        graphics.fill(x, y, x + width, y + height, BORDER_COLOR);
        graphics.fill(x + 1, y + 1, x + width - 1, y + height - 1, PANEL_COLOR);
    }

    /** Draws a real recessed square under every slot in the menu, at its actual (x, y) - always in sync. */
    static void drawSlots(GuiGraphicsExtractor graphics, AbstractContainerMenu menu, int leftPos, int topPos) {
        for (Slot slot : menu.slots) {
            int sx = leftPos + slot.x;
            int sy = topPos + slot.y;
            graphics.fill(sx - 1, sy - 1, sx + 17, sy + 17, BORDER_COLOR);
            graphics.fill(sx, sy, sx + 16, sy + 16, SLOT_COLOR);
        }
    }
}
