package com.buildcraft.transport.client.render;

import java.util.ArrayList;
import java.util.List;

import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.item.ItemModelResolver;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

import com.buildcraft.transport.blockentity.PipeBlockEntity;

/**
 * Renders items travelling through a pipe as small floating itemstacks moving along the pipe's interior -
 * ports the original's {@code PipeFlowRendererItems}: it also just renders the real {@code ItemStack} model at
 * an interpolated position (see {@code TravellingItem.getRenderPosition}) rather than any pipe-specific mesh.
 * The position/timing math itself lives in {@link PipeBlockEntity#getRenderItems}, matching how
 * {@code QuarryBlockEntityRenderer} keeps its own interpolation logic on the block entity side.
 */
public class PipeBlockEntityRenderer implements BlockEntityRenderer<PipeBlockEntity, PipeRenderState> {
    private static final int LIGHT = 15728880;
    // Ported from the exact value in ItemRenderUtil.renderItemStackInternal's batch path (the one
    // PipeFlowRendererItems actually calls): "float scale = 0.30f;" - not a guess.
    private static final float ITEM_SCALE = 0.30F;

    private final ItemModelResolver itemModelResolver;

    public PipeBlockEntityRenderer(BlockEntityRendererProvider.Context context) {
        this.itemModelResolver = context.itemModelResolver();
    }

    @Override
    public PipeRenderState createRenderState() {
        return new PipeRenderState();
    }

    @Override
    public void extractRenderState(PipeBlockEntity blockEntity, PipeRenderState state, float partialTicks, Vec3 cameraPosition,
            ModelFeatureRenderer.@Nullable CrumblingOverlay breakProgress) {
        BlockEntityRenderer.super.extractRenderState(blockEntity, state, partialTicks, cameraPosition, breakProgress);
        state.positions.clear();
        int seed = 0;
        int index = 0;
        for (PipeBlockEntity.RenderItem item : blockEntity.getRenderItems(partialTicks)) {
            // Real perf fix (2026-07-31 FPS audit): reuse a pooled ItemStackRenderState instead of allocating a
            // fresh one every frame for every travelling item in every visible pipe - see PipeRenderState's own
            // javadoc for why this is safe (the class is explicitly designed for in-place reuse).
            ItemStackRenderState itemState;
            if (index < state.itemStates.size()) {
                itemState = state.itemStates.get(index);
            } else {
                itemState = new ItemStackRenderState();
                state.itemStates.add(itemState);
            }
            // NONE, not GROUND: GROUND's baked display transform is for a dropped item sitting ON the ground
            // (bottom-anchored, with its own offset) - the original explicitly bypasses any such per-context
            // transform (it renders raw baked quads with no display-context translation at all) and does its
            // own centering manually instead (see the recenter below), which NONE matches most closely.
            itemModelResolver.updateForTopItem(itemState, item.stack(), ItemDisplayContext.NONE, blockEntity.getLevel(), null, seed++);
            state.positions.add(item.position());
            index++;
        }
        state.activeCount = index;
    }

    /** Real perf fix (2026-07-31 FPS audit, same reasoning as {@code FluidPipeBlockEntityRenderer}'s own trim):
     * the default 64-block view distance is wasteful for a small in-pipe detail nobody can perceive at that
     * range - and item pipes are typically the MOST numerous pipe tier in a real base, more so than fluid pipes,
     * making this renderer's per-instance cost matter more in aggregate. */
    @Override
    public int getViewDistance() {
        return 32;
    }

    @Override
    public void submit(PipeRenderState state, PoseStack poseStack, SubmitNodeCollector submitNodeCollector, CameraRenderState camera) {
        for (int i = 0; i < state.activeCount; i++) {
            ItemStackRenderState itemState = state.itemStates.get(i);
            if (itemState.isEmpty()) {
                continue;
            }
            Vec3 pos = state.positions.get(i);

            // Recenter using the model's ACTUAL computed bounding box (ItemStackRenderState.getModelBoundingBox
            // - it re-walks every baked quad's real vertex positions through the exact same transform chain
            // rendering uses: ItemTransforms.getTransform(NONE) confirmed to resolve to NO_TRANSFORM via
            // source, and CuboidItemModelWrapper's own "transformation" field is unset since our model JSON
            // never specifies one - but rather than trust that hand-derived chain, this queries Minecraft's own
            // real computed geometry directly, so it's correct regardless of any transform-chain step I could
            // have mis-traced) - not a hardcoded "-0.5" guess at where the model's center should be.
            AABB bounds = itemState.getModelBoundingBox();
            double centerX = (bounds.minX + bounds.maxX) / 2.0;
            double centerY = (bounds.minY + bounds.maxY) / 2.0;
            double centerZ = (bounds.minZ + bounds.maxZ) / 2.0;

            poseStack.pushPose();
            // Order matters (each call transforms local geometry submitted after it, innermost-first): move to
            // the interpolated world position, then scale down, then - innermost of all - shift the model's
            // real computed center to the local origin so it's centered on the position, matching
            // ItemRenderUtil's original technique of recentering before scaling (just using the actual computed
            // center instead of an assumed constant).
            poseStack.translate(pos.x, pos.y, pos.z);
            poseStack.scale(ITEM_SCALE, ITEM_SCALE, ITEM_SCALE);
            poseStack.translate(-centerX, -centerY, -centerZ);
            // outlineColor=0, not -1: confirmed via EntityRenderState.outlineColor's own default/disabled value
            // (0). -1 is 0xFFFFFFFF - a real, fully-opaque WHITE outline color, not a "disabled" sentinel, which
            // was the actual cause of the bright white outline.
            itemState.submit(poseStack, submitNodeCollector, LIGHT, OverlayTexture.NO_OVERLAY, 0);
            poseStack.popPose();
        }
    }
}
