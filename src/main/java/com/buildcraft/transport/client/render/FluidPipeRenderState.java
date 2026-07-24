package com.buildcraft.transport.client.render;

import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;

/** What the fluid-content renderer needs each frame: whether there's anything to show, how full, and the
 * resolved fluid sprite's atlas UV + real tint - same fields/lookup technique as
 * {@code com.buildcraft.factory.client.render.TankRenderState}. */
public class FluidPipeRenderState extends BlockEntityRenderState {
    public boolean hasFluid;
    public int fillPercent;
    public float spriteU0, spriteU1, spriteV0, spriteV1;
    public int tintColor = 0xFFFFFFFF;
}
