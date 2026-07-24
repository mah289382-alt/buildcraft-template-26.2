package com.buildcraft.factory.client.render;

import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;

/** What the Mining Well/Pump growing-tip renderer needs each frame: the real interpolated total shaft length
 * (whole blocks placed + the currently-growing partial block's fraction - see
 * {@code MinerBlockEntity.getInterpolatedLength}). */
public class MinerRenderState extends BlockEntityRenderState {
    public double length;
}
