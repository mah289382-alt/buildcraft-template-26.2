package com.buildcraft.factory.client.render;

import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;

/** What the renderer needs each frame: whether the frame's own cap faces should be hidden (any Tank neighbour,
 * regardless of fluid - real {@code shouldSideBeRendered}), which side texture variant to use (real
 * {@code JOINED_BELOW}), this tile's own fill percent, whether the fluid box visually merges with the tank
 * above/below (real {@code RenderTank.isFullyConnected} - fluid-specific, stricter than the frame's own cap
 * hiding), and the resolved fluid sprite's atlas UV rect. */
public class TankRenderState extends BlockEntityRenderState {
    public boolean hideUp;
    public boolean hideDown;
    public boolean joinedBelowTexture;
    public int fillPercent;
    public boolean connectedUp;
    public boolean connectedDown;
    public boolean hasFluid;
    public float spriteU0, spriteU1, spriteV0, spriteV1;
    /** Packed ARGB (real {@code FluidTintSource} convention) - white ({@code 0xFFFFFFFF}) for untinted fluids
     * (lava, this mod's own Oil/Fuel - their texture is already fully coloured), the real biome-averaged water
     * colour for water, etc. See {@code TankBlockEntityRenderer.extractRenderState}. */
    public int tintColor = 0xFFFFFFFF;
}
