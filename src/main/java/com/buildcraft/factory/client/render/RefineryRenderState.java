package com.buildcraft.factory.client.render;

import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.core.Direction;

/** What the renderer needs each frame: the 2 magnet pistons' animation position/speed, facing, and tank fill
 * levels (0-100) for the fluid-box indicators visible through the tanks' transparent "window" texture. */
public class RefineryRenderState extends BlockEntityRenderState {
    public int animationStage;
    public float animationSpeed = 1;
    public Direction facing = Direction.NORTH;
    public int oilPercent;
    public int fuelPercent;
}
