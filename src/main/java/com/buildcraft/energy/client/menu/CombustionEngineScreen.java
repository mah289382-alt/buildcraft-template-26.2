package com.buildcraft.energy.client.menu;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Inventory;

import com.buildcraft.BuildCraft;
import com.buildcraft.energy.menu.CombustionEngineMenu;

/**
 * The Combustion Engine's tank-display GUI. Uses the real background texture ({@code combustion_engine_gui.png},
 * copied from source as {@code engine_combustion.png}) instead of a hand-drawn panel - it already has the 3
 * tank rulers/borders baked in. Real positions confirmed against {@code GuiEngineIron_BC8.java}: window
 * 176x177, fuel tank rect (26,18,16,60), coolant (80,18,16,60), residue (134,18,16,60). Each tank is drawn as
 * source's {@code WidgetFluidTank} does: the fluid's real texture (source: {@code GuiUtil.drawFluid} ->
 * {@code FluidRenderer.drawFluidForGui}, which tiles the fluid's registered still-texture sprite, scissored to
 * the fill height - an earlier pass used a flat representative colour instead since no fluid-sprite GUI
 * rendering existed yet; now ported properly by directly blitting the same still-texture files already used for
 * world rendering, sampling only the first 16x16 animation frame for a simple static look), THEN the real
 * {@code ICON_TANK_OVERLAY} sub-region (texture (176,0), 16x60 - a red tick-mark "ruler" on a transparent
 * background) blitted on top so the ticks stay visible through the fill, matching source's
 * {@code overlay.drawCutInside(this)} draw order exactly. The fuel tank accepts EITHER Oil or Fuel (see
 * {@link com.buildcraft.energy.blockentity.CombustionEngineBlockEntity}'s javadoc) - real fluid identity is
 * synced via {@link CombustionEngineMenu#getFuelTankFluidId()} so the correct texture renders (a real bug
 * fixed here: it previously always rendered as Fuel regardless of which one was actually in the tank, since
 * nothing tracked/synced which fluid was really there). Clicking directly on a tank rect sends a menu-button
 * click for that tank (see {@link CombustionEngineMenu#clickMenuButton}), porting source's
 * {@code Tank.onGuiClicked} - the same protocol {@code WoodDiamondFilterScreen} uses for its button.
 */
public class CombustionEngineScreen extends AbstractContainerScreen<CombustionEngineMenu> {
    private static final Identifier TEXTURE = Identifier.fromNamespaceAndPath(BuildCraft.MODID, "textures/gui/engine_combustion.png");
    private static final int TEXTURE_SIZE = 256;
    private static final Identifier OIL_STILL = Identifier.fromNamespaceAndPath(BuildCraft.MODID, "textures/block/oil_still.png");
    private static final Identifier FUEL_STILL = Identifier.fromNamespaceAndPath(BuildCraft.MODID, "textures/block/fuel_still.png");
    private static final Identifier WATER_STILL = Identifier.fromNamespaceAndPath("minecraft", "textures/block/water_still.png");
    private static final int FLUID_TEXTURE_HEIGHT = 512; // 32 animation frames of 16x16 each - only frame 0 is used here
    private static final int TANK_EMPTY_COLOR = 0xFF4A4A4A;
    private static final int RESIDUE_COLOR = 0xFF6B5A3C;
    private static final int TANK_Y = 18;
    private static final int TANK_W = 16;
    private static final int TANK_H = 60;
    private static final int OVERLAY_U = 176;
    private static final int OVERLAY_V = 0;
    private static final int[] TANK_X = {26, 80, 134};
    // Matches CombustionEngineMenu's single ResourceHandlerSlot position (52,40) - centered on the real "+"
    // icon baked into the texture between the fuel/coolant gauges (pixel-diffed bbox x=53-67,y=41-55, center
    // (60,48); slot x/y is the top-left of its 16x16 item area, so top-left = center - 8, background square
    // drawn 1px outset on each side, vanilla-style).
    private static final int BUCKET_SLOT_X = 51;
    private static final int BUCKET_SLOT_Y = 39;
    private static final int SLOT_SIZE = 18;
    private static final int SLOT_BORDER_COLOR = 0xFF373737;

    public CombustionEngineScreen(CombustionEngineMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, 176, 177);
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        int x = (this.width - this.imageWidth) / 2;
        int y = (this.height - this.imageHeight) / 2;
        graphics.blit(RenderPipelines.GUI_TEXTURED, TEXTURE, x, y, 0, 0, this.imageWidth, this.imageHeight, TEXTURE_SIZE, TEXTURE_SIZE);

        Identifier fuelTexture = switch (this.menu.getFuelTankFluidId()) {
            case 2 -> FUEL_STILL;
            default -> OIL_STILL;
        };
        drawFluidTank(graphics, x + TANK_X[0], y + TANK_Y, this.menu.getFuelPercent(), fuelTexture);
        drawFluidTank(graphics, x + TANK_X[1], y + TANK_Y, this.menu.getCoolantPercent(), WATER_STILL);
        drawTank(graphics, x + TANK_X[2], y + TANK_Y, this.menu.getResiduePercent(), RESIDUE_COLOR);

        drawSlotBorder(graphics, x + BUCKET_SLOT_X, y + BUCKET_SLOT_Y);
    }

    /** Real texture-based tank fill: tiles the fluid's still-texture sprite (first animation frame only)
     * across the fill area, scissored to the actual fill height, matching source's real
     * {@code FluidRenderer.drawFluidForGui} technique instead of a flat colour. */
    private void drawFluidTank(GuiGraphicsExtractor graphics, int x, int y, int percent, Identifier fluidTexture) {
        graphics.fill(x, y, x + TANK_W, y + TANK_H, TANK_EMPTY_COLOR);
        int filledHeight = TANK_H * Math.max(0, Math.min(100, percent)) / 100;
        if (filledHeight > 0) {
            int fillTop = y + TANK_H - filledHeight;
            graphics.enableScissor(x, fillTop, x + TANK_W, y + TANK_H);
            for (int tileY = y + TANK_H; tileY > fillTop; tileY -= 16) {
                graphics.blit(RenderPipelines.GUI_TEXTURED, fluidTexture, x, tileY - 16, 0, 0, TANK_W, 16, TANK_W, FLUID_TEXTURE_HEIGHT);
            }
            graphics.disableScissor();
        }
        graphics.blit(RenderPipelines.GUI_TEXTURED, TEXTURE, x, y, OVERLAY_U, OVERLAY_V, TANK_W, TANK_H, TEXTURE_SIZE, TEXTURE_SIZE);
    }

    /** A thin border-only frame around the bucket slot - deliberately NOT filled, so the real "+" icon already
     * baked into the texture stays visible through the slot while it's empty (vanilla's real texture wasn't
     * authored with this slot in mind, so there's no baked-in sunken-slot sprite there to use instead). */
    private void drawSlotBorder(GuiGraphicsExtractor graphics, int x, int y) {
        graphics.fill(x, y, x + SLOT_SIZE, y + 1, SLOT_BORDER_COLOR);
        graphics.fill(x, y + SLOT_SIZE - 1, x + SLOT_SIZE, y + SLOT_SIZE, SLOT_BORDER_COLOR);
        graphics.fill(x, y, x + 1, y + SLOT_SIZE, SLOT_BORDER_COLOR);
        graphics.fill(x + SLOT_SIZE - 1, y, x + SLOT_SIZE, y + SLOT_SIZE, SLOT_BORDER_COLOR);
    }

    private void drawTank(GuiGraphicsExtractor graphics, int x, int y, int percent, int fillColor) {
        graphics.fill(x, y, x + TANK_W, y + TANK_H, TANK_EMPTY_COLOR);
        int filledHeight = TANK_H * Math.max(0, Math.min(100, percent)) / 100;
        if (filledHeight > 0) {
            graphics.fill(x, y + TANK_H - filledHeight, x + TANK_W, y + TANK_H, fillColor);
        }
        graphics.blit(RenderPipelines.GUI_TEXTURED, TEXTURE, x, y, OVERLAY_U, OVERLAY_V, TANK_W, TANK_H, TEXTURE_SIZE, TEXTURE_SIZE);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent mouseButtonEvent, boolean doubled) {
        int x = (this.width - this.imageWidth) / 2;
        int y = (this.height - this.imageHeight) / 2;
        double mouseX = mouseButtonEvent.x();
        double mouseY = mouseButtonEvent.y();
        for (int i = 0; i < TANK_X.length; i++) {
            int tx = x + TANK_X[i];
            int ty = y + TANK_Y;
            if (mouseX >= tx && mouseX < tx + TANK_W && mouseY >= ty && mouseY < ty + TANK_H) {
                if (this.minecraft != null && this.minecraft.gameMode != null) {
                    this.minecraft.gameMode.handleInventoryButtonClick(this.menu.containerId, i);
                }
                return true;
            }
        }
        return super.mouseClicked(mouseButtonEvent, doubled);
    }
}
