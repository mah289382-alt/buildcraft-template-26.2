package com.buildcraft.factory.client.menu;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;

import com.buildcraft.BuildCraft;
import com.buildcraft.factory.menu.RefineryMenu;

/**
 * The Refinery's tank-display GUI - no real GUI texture asset exists for the classic {@code TileRefinery} in
 * source (only a small filter-selection icon for its 2-input flexible-crafter design, not applicable to this
 * port's simplified single-recipe Oil-&gt;Fuel version), so drawn procedurally like {@code DiamondFilterScreen}.
 * 2 tank bars, each rendered with the fluid's REAL still-texture sprite (source: {@code GuiUtil.drawFluid} ->
 * {@code FluidRenderer.drawFluidForGui}, tiled and scissored to the fill height - an earlier pass used a flat
 * colour instead, now ported properly using the same still-texture files already used for world rendering,
 * first animation frame only) - Oil in, Fuel out. No fluid-identity ambiguity here (unlike the Combustion
 * Engine's shared fuel tank) since these are 2 fixed, separate tanks.
 */
public class RefineryScreen extends AbstractContainerScreen<RefineryMenu> {
    private static final int BORDER_COLOR = 0xFF373737;
    private static final int PANEL_COLOR = 0xFFC6C6C6;
    private static final int SLOT_COLOR = 0xFF8B8B8B;
    private static final int TANK_EMPTY_COLOR = 0xFF4A4A4A;
    private static final Identifier OIL_STILL = Identifier.fromNamespaceAndPath(BuildCraft.MODID, "textures/block/oil_still.png");
    private static final Identifier FUEL_STILL = Identifier.fromNamespaceAndPath(BuildCraft.MODID, "textures/block/fuel_still.png");
    private static final int FLUID_TEXTURE_SIZE = 16;
    private static final int FLUID_TEXTURE_HEIGHT = 512; // 32 animation frames of 16x16 each - only frame 0 is used here
    private static final int TANK_Y = 18;
    private static final int TANK_W = 20;
    private static final int TANK_H = 50;
    private static final int OIL_X = 44;
    private static final int FUEL_X = 112;

    public RefineryScreen(RefineryMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, 176, 166);
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        int x = (this.width - this.imageWidth) / 2;
        int y = (this.height - this.imageHeight) / 2;
        graphics.fill(x, y, x + this.imageWidth, y + this.imageHeight, BORDER_COLOR);
        graphics.fill(x + 1, y + 1, x + this.imageWidth - 1, y + this.imageHeight - 1, PANEL_COLOR);

        drawTank(graphics, x + OIL_X, y + TANK_Y, this.menu.getOilPercent(), OIL_STILL);
        drawTank(graphics, x + FUEL_X, y + TANK_Y, this.menu.getFuelPercent(), FUEL_STILL);
        graphics.textWithWordWrap(this.font, Component.literal("Oil"), x + OIL_X, y + TANK_Y - 10, TANK_W + 20, 0x404040);
        graphics.textWithWordWrap(this.font, Component.literal("Fuel"), x + FUEL_X, y + TANK_Y - 10, TANK_W + 20, 0x404040);

        for (Slot slot : this.menu.slots) {
            int sx = x + slot.x;
            int sy = y + slot.y;
            graphics.fill(sx - 1, sy - 1, sx + 17, sy + 17, BORDER_COLOR);
            graphics.fill(sx, sy, sx + 16, sy + 16, SLOT_COLOR);
        }
    }

    /** Real texture-based tank fill: tiles the fluid's still-texture sprite (first animation frame only) across
     * the fill area (both directions, since {@link #TANK_W}=20 isn't a multiple of the texture's 16px tile
     * size - the scissor naturally crops each edge tile's overflow), scissored to the actual fill height. */
    private void drawTank(GuiGraphicsExtractor graphics, int x, int y, int percent, Identifier fluidTexture) {
        graphics.fill(x - 1, y - 1, x + TANK_W + 1, y + TANK_H + 1, BORDER_COLOR);
        graphics.fill(x, y, x + TANK_W, y + TANK_H, TANK_EMPTY_COLOR);
        int filledHeight = TANK_H * Math.max(0, Math.min(100, percent)) / 100;
        if (filledHeight > 0) {
            int fillTop = y + TANK_H - filledHeight;
            graphics.enableScissor(x, fillTop, x + TANK_W, y + TANK_H);
            for (int tileX = x; tileX < x + TANK_W; tileX += FLUID_TEXTURE_SIZE) {
                for (int tileY = y + TANK_H; tileY > fillTop; tileY -= FLUID_TEXTURE_SIZE) {
                    graphics.blit(RenderPipelines.GUI_TEXTURED, fluidTexture, tileX, tileY - FLUID_TEXTURE_SIZE, 0, 0,
                            FLUID_TEXTURE_SIZE, FLUID_TEXTURE_SIZE, FLUID_TEXTURE_SIZE, FLUID_TEXTURE_HEIGHT);
                }
            }
            graphics.disableScissor();
        }
        graphics.textWithWordWrap(this.font, Component.literal(percent + "%"), x - 4, y + TANK_H + 2, TANK_W + 8, 0xFFFFFF);
    }
}
