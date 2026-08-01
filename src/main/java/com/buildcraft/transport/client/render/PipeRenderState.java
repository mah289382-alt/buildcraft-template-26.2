package com.buildcraft.transport.client.render;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.world.phys.Vec3;

/**
 * One travelling item ready to submit per entry: its resolved model state and interpolated position.
 * <p>
 * Real perf fix (2026-07-31 FPS audit): {@code itemStates} is a POOL, not a fresh list every frame - entries are
 * never removed, only reused in place via {@link ItemStackRenderState#clear()} (which {@code
 * ItemModelResolver.updateForTopItem} already calls internally before repopulating, confirmed via source - the
 * class is explicitly designed for this, matching vanilla's own {@code LayerRenderState[]}/{@code
 * ensureCapacity} reuse pattern inside {@code ItemStackRenderState} itself). {@link #activeCount} tracks how many
 * of the pool's entries are actually in use THIS frame; {@code submit} must iterate up to {@code activeCount},
 * never {@code itemStates.size()} (which only ever grows, to the high-water mark of items ever seen at once).
 */
public class PipeRenderState extends BlockEntityRenderState {
    public final List<ItemStackRenderState> itemStates = new ArrayList<>();
    public final List<Vec3> positions = new ArrayList<>();
    public int activeCount;
}
