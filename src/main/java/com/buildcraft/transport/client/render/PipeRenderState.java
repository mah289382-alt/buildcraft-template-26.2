package com.buildcraft.transport.client.render;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.world.phys.Vec3;

/** One travelling item ready to submit per entry: its resolved model state and interpolated position. */
public class PipeRenderState extends BlockEntityRenderState {
    public final List<ItemStackRenderState> itemStates = new ArrayList<>();
    public final List<Vec3> positions = new ArrayList<>();
}
