package com.buildcraft.energy.client.render;

import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;

import com.buildcraft.energy.blockentity.EngineBlockEntity;

/** What the renderer needs each frame: interpolated piston progress, current heat stage, facing, and textures. */
public class EngineRenderState extends BlockEntityRenderState {
    public float progress;
    public EngineBlockEntity.PowerStage stage = EngineBlockEntity.PowerStage.BLUE;
    public Direction facing = Direction.UP;
    public Identifier backTexture;
    public Identifier sideTexture;
}
