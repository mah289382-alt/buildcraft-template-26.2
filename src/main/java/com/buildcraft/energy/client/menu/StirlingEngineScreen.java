package com.buildcraft.energy.client.menu;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Inventory;

import com.buildcraft.BuildCraft;
import com.buildcraft.energy.menu.StirlingEngineMenu;

/**
 * The Stirling Engine's fuel-slot GUI. Uses the real background texture ({@code steam_engine_gui.png}, copied
 * from source as {@code engine_stirling.png}) instead of a hand-drawn panel, and the real furnace-style flame
 * icon - confirmed against {@code GuiEngineStone_BC8.drawBackgroundLayer}: the flame is a 14x16 strip at
 * texture (176,0), clipped from the bottom based on {@code deltaFuelLeft} (this port: {@code getFuelPercent()}),
 * drawn at (81,25) - both real pixel positions, not estimated. Window size 176x166 is real
 * ({@code GuiEngineStone_BC8.SIZE_X/SIZE_Y}).
 */
public class StirlingEngineScreen extends AbstractContainerScreen<StirlingEngineMenu> {
    private static final Identifier TEXTURE = Identifier.fromNamespaceAndPath(BuildCraft.MODID, "textures/gui/engine_stirling.png");
    private static final int TEXTURE_SIZE = 256;
    private static final int FLAME_X = 81;
    private static final int FLAME_Y = 25;
    private static final int FLAME_W = 14;
    private static final int FLAME_H = 14;

    public StirlingEngineScreen(StirlingEngineMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, 176, 166);
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        int x = (this.width - this.imageWidth) / 2;
        int y = (this.height - this.imageHeight) / 2;
        graphics.blit(RenderPipelines.GUI_TEXTURED, TEXTURE, x, y, 0, 0, this.imageWidth, this.imageHeight, TEXTURE_SIZE, TEXTURE_SIZE);

        int percent = this.menu.getFuelPercent();
        if (percent > 0) {
            int flameHeight = (int) Math.ceil(percent / 100.0 * FLAME_H);
            graphics.blit(RenderPipelines.GUI_TEXTURED, TEXTURE,
                    x + FLAME_X, y + FLAME_Y + FLAME_H - flameHeight,
                    176, FLAME_H - flameHeight,
                    FLAME_W, flameHeight + 2,
                    TEXTURE_SIZE, TEXTURE_SIZE);
        }
    }
}
