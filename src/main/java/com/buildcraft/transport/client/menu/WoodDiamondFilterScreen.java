package com.buildcraft.transport.client.menu;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

import com.buildcraft.transport.menu.WoodDiamondFilterMenu;
import com.buildcraft.transport.pipe.behaviour.WoodDiamondBehaviour;

/**
 * The WoodDiamond pipe's 3x3 filter GUI, plus a button cycling its filter mode (whitelist/blacklist/round-
 * robin - see {@link WoodDiamondBehaviour}). Like {@link com.buildcraft.transport.client.menu.DiamondFilterScreen},
 * there's no hand-authored background texture yet, so the panel is a plain drawn rectangle.
 */
public class WoodDiamondFilterScreen extends AbstractContainerScreen<WoodDiamondFilterMenu> {
    private static final int BUTTON_COLOR = 0xFF8B8B8B;
    private static final int BUTTON_X = 8;
    private static final int BUTTON_Y = 20;
    private static final int BUTTON_W = 46;
    private static final int BUTTON_H = 36;

    public WoodDiamondFilterScreen(WoodDiamondFilterMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, 176, 148);
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        int x = (this.width - this.imageWidth) / 2;
        int y = (this.height - this.imageHeight) / 2;
        PipeGuiUtil.drawPanel(graphics, x, y, this.imageWidth, this.imageHeight);
        graphics.fill(x + BUTTON_X, y + BUTTON_Y, x + BUTTON_X + BUTTON_W, y + BUTTON_Y + BUTTON_H, BUTTON_COLOR);
        graphics.textWithWordWrap(this.font, Component.literal(modeLabel()), x + BUTTON_X + 3, y + BUTTON_Y + 3, BUTTON_W - 6, 0xFFFFFF);
        PipeGuiUtil.drawSlots(graphics, this.menu, x, y);
    }

    private String modeLabel() {
        return switch (this.menu.getSyncedMode()) {
            case WHITE_LIST -> "Whitelist";
            case BLACK_LIST -> "Blacklist";
            case ROUND_ROBIN -> "Round Robin";
        };
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent mouseButtonEvent, boolean doubled) {
        int x = (this.width - this.imageWidth) / 2;
        int y = (this.height - this.imageHeight) / 2;
        double mouseX = mouseButtonEvent.x();
        double mouseY = mouseButtonEvent.y();
        if (mouseX >= x + BUTTON_X && mouseX < x + BUTTON_X + BUTTON_W
                && mouseY >= y + BUTTON_Y && mouseY < y + BUTTON_Y + BUTTON_H) {
            if (this.minecraft != null && this.minecraft.gameMode != null) {
                this.minecraft.gameMode.handleInventoryButtonClick(this.menu.containerId, 0);
            }
            return true;
        }
        return super.mouseClicked(mouseButtonEvent, doubled);
    }
}
