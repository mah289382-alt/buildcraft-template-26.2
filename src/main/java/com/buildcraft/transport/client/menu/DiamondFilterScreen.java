package com.buildcraft.transport.client.menu;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;

import com.buildcraft.transport.menu.DiamondFilterMenu;
import com.buildcraft.transport.pipe.behaviour.DiamondBehaviour;

/**
 * The Diamond pipe's filter GUI. No custom background texture asset exists yet (this port doesn't include a
 * hand-authored GUI panel image), so the panel and slots are drawn procedurally instead of a {@code blit}-ed
 * texture. The per-row tint IS real though - sampled directly from the original's
 * {@code buildcrafttransport:textures/gui/filter.png} (one row per {@code Direction}, in
 * {@code Direction.values()} order: down/up/north/south/west/east), not guessed: down=(101,101,101),
 * up=(136,136,136), north=(136,101,101) red, south=(101,101,136) blue, west=(101,136,101) green,
 * east=(150,141,101) tan. Slot item rendering, tooltips, and drag/quick-move are inherited from
 * {@link AbstractContainerScreen}.
 */
public class DiamondFilterScreen extends AbstractContainerScreen<DiamondFilterMenu> {
    private static final int BORDER_COLOR = 0xFF373737;
    private static final int PANEL_COLOR = 0xFFC6C6C6;
    private static final int PLAYER_SLOT_COLOR = 0xFF8B8B8B;
    private static final int[] ROW_COLORS = {
            0xFF656565, // down
            0xFF888888, // up
            0xFF886565, // north (red)
            0xFF656588, // south (blue)
            0xFF658865, // west (green)
            0xFF968D65, // east (tan)
    };

    public DiamondFilterScreen(DiamondFilterMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, 175, 225);
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        int x = (this.width - this.imageWidth) / 2;
        int y = (this.height - this.imageHeight) / 2;
        graphics.fill(x, y, x + this.imageWidth, y + this.imageHeight, BORDER_COLOR);
        graphics.fill(x + 1, y + 1, x + this.imageWidth - 1, y + this.imageHeight - 1, PANEL_COLOR);

        for (int i = 0; i < this.menu.slots.size(); i++) {
            Slot slot = this.menu.slots.get(i);
            int color = i < DiamondBehaviour.TOTAL_FILTER_SLOTS
                    ? ROW_COLORS[i / DiamondBehaviour.FILTERS_PER_SIDE]
                    : PLAYER_SLOT_COLOR;
            int sx = x + slot.x;
            int sy = y + slot.y;
            graphics.fill(sx - 1, sy - 1, sx + 17, sy + 17, BORDER_COLOR);
            graphics.fill(sx, sy, sx + 16, sy + 16, color);
        }
    }
}
