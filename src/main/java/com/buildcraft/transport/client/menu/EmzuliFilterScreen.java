package com.buildcraft.transport.client.menu;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

import com.buildcraft.transport.menu.EmzuliFilterMenu;

/** The Emzuli pipe's 4-slot filter GUI - see {@link PipeGuiUtil} for the procedurally-drawn panel/slots. */
public class EmzuliFilterScreen extends AbstractContainerScreen<EmzuliFilterMenu> {
    private static final int[] SLOT_COLORS = { 0xFFFF5555, 0xFF55FF55, 0xFF5555FF, 0xFFFFFF55 };

    public EmzuliFilterScreen(EmzuliFilterMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, 176, 120);
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        int x = (this.width - this.imageWidth) / 2;
        int y = (this.height - this.imageHeight) / 2;
        PipeGuiUtil.drawPanel(graphics, x, y, this.imageWidth, this.imageHeight);
        for (int i = 0; i < 4; i++) {
            int sx = x + 62 + i * 18;
            int sy = y + 19;
            graphics.fill(sx - 1, sy - 1, sx + 17, sy, SLOT_COLORS[i]);
        }
        PipeGuiUtil.drawSlots(graphics, this.menu, x, y);
    }
}
