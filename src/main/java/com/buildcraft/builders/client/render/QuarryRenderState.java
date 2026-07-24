package com.buildcraft.builders.client.render;

import org.jspecify.annotations.Nullable;

import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

public class QuarryRenderState extends BlockEntityRenderState {
    public @Nullable BlockPos frameMin;
    public @Nullable BlockPos frameMax;
    /** Interpolated world-space drill position, or null to show the wireframe box / targeting laser instead. */
    public @Nullable Vec3 drillPos;
    /** Non-null while lasering an obstacle out of the frame's own footprint from a distance. */
    public @Nullable BlockPos targetingObstacle;
    /** Non-null only while actively breaking a block during real mining (drives the drill's plunge/retract bob). */
    public @Nullable BlockPos breakTarget;
    public float breakProgress;
    /** breakTarget's own selection-shape Y bounds (0..1 local space), for the plunge depth. Defaults to a full block. */
    public float breakTargetMinY = 0.0F;
    public float breakTargetMaxY = 1.0F;
}
