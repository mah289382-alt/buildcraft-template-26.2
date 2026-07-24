package com.buildcraft.transport.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.FluidModel;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.fluid.FluidTintSource;
import net.neoforged.neoforge.transfer.fluid.FluidResource;
import org.jspecify.annotations.Nullable;

import com.buildcraft.transport.blockentity.FluidPipeBlockEntity;

/**
 * Shows the fluid actually stored in a pipe's own buffer as a small box filling the post cavity (the same
 * 4/16..12/16 cube {@link com.buildcraft.transport.block.FluidPipeBlock}'s {@code BASE_SHAPE} occupies), scaled
 * vertically by fill percent. Real source's {@code PipeFlowRendererFluids} animates fluid flowing continuously
 * through the connected arms with per-section amounts - this is a deliberate simplification (a single static
 * indicator of "this pipe currently holds X, this full", not per-arm flow animation), matching this project's
 * general pattern of preferring a real, working, simplified visual over an unfinished attempt at the full
 * original animation. Reuses the exact same real-fluid-sprite + tint lookup technique as
 * {@code com.buildcraft.factory.client.render.TankBlockEntityRenderer} (see that class's javadoc for why this,
 * not the older {@code IClientFluidTypeExtensions} texture getters, is the correct mechanism in this NeoForge
 * beta, and for why water needs the tint at all - a deliberately grey/white texture multiplied by a real
 * biome-sampled colour at render time).
 */
public class FluidPipeBlockEntityRenderer implements BlockEntityRenderer<FluidPipeBlockEntity, FluidPipeRenderState> {
    private static final int LIGHT = 15728880;
    private static final float PX0 = 4f / 16f;
    private static final float PX1 = 12f / 16f;

    public FluidPipeBlockEntityRenderer(BlockEntityRendererProvider.Context context) {
    }

    @Override
    public FluidPipeRenderState createRenderState() {
        return new FluidPipeRenderState();
    }

    @Override
    public void extractRenderState(FluidPipeBlockEntity blockEntity, FluidPipeRenderState state, float partialTicks, Vec3 cameraPosition,
            ModelFeatureRenderer.@Nullable CrumblingOverlay breakProgress) {
        BlockEntityRenderer.super.extractRenderState(blockEntity, state, partialTicks, cameraPosition, breakProgress);

        FluidResource resource = blockEntity.getFluidResource();
        state.hasFluid = !resource.isEmpty();
        if (!state.hasFluid) {
            return;
        }
        state.fillPercent = blockEntity.getFillPercent();

        Fluid fluid = resource.getFluid();
        FluidState fluidState = fluid.defaultFluidState();
        FluidModel fluidModel = Minecraft.getInstance().getModelManager().getFluidStateModelSet().get(fluidState);
        TextureAtlasSprite sprite = fluidModel.stillMaterial().sprite();
        state.spriteU0 = sprite.getU0();
        state.spriteU1 = sprite.getU1();
        state.spriteV0 = sprite.getV0();
        state.spriteV1 = sprite.getV1();

        FluidTintSource tintSource = fluidModel.fluidTintSource();
        Level level = blockEntity.getLevel();
        if (tintSource == null) {
            state.tintColor = 0xFFFFFFFF;
        } else if (level instanceof BlockAndTintGetter tintGetter) {
            state.tintColor = tintSource.colorInWorld(fluidState, blockEntity.getBlockState(), tintGetter, blockEntity.getBlockPos());
        } else {
            state.tintColor = tintSource.color(fluidState);
        }
    }

    @Override
    public void submit(FluidPipeRenderState state, PoseStack poseStack, SubmitNodeCollector submitNodeCollector, CameraRenderState camera) {
        if (!state.hasFluid || state.fillPercent <= 0) {
            return;
        }
        float y1 = PX0 + (PX1 - PX0) * Math.min(1f, state.fillPercent / 100f);
        if (y1 <= PX0) {
            return;
        }
        RenderType fluidType = cutout(TextureAtlas.LOCATION_BLOCKS);
        float u0 = state.spriteU0;
        float u1 = state.spriteU1;
        float v0 = state.spriteV0;
        float v1 = state.spriteV1;
        int color = state.tintColor;
        submitNodeCollector.submitCustomGeometry(poseStack, fluidType, (pose, buffer) ->
                fluidBox(pose, buffer, PX0, PX0, PX0, PX1, y1, PX1, u0, u1, v0, v1, color));
    }

    private static RenderType cutout(net.minecraft.resources.Identifier texture) {
        RenderSetup setup = RenderSetup.builder(RenderPipelines.ENTITY_CUTOUT)
                .withTexture("Sampler0", texture)
                .useLightmap()
                .useOverlay()
                .createRenderSetup();
        return RenderType.create("buildcraft_fluid_pipe", setup);
    }

    private static void fluidBox(PoseStack.Pose pose, VertexConsumer buffer, float x0, float y0, float z0, float x1, float y1, float z1,
            float u0, float u1, float v0, float v1, int color) {
        quad(pose, buffer, x1, y0, z1, x0, y0, z1, x0, y0, z0, x1, y0, z0, u0, u1, v0, v1, color);
        quad(pose, buffer, x1, y1, z0, x0, y1, z0, x0, y1, z1, x1, y1, z1, u0, u1, v0, v1, color);
        quad(pose, buffer, x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0, u0, u1, v0, v1, color);
        quad(pose, buffer, x1, y0, z0, x0, y0, z0, x0, y1, z0, x1, y1, z0, u0, u1, v0, v1, color);
        quad(pose, buffer, x1, y0, z1, x1, y0, z0, x1, y1, z0, x1, y1, z1, u0, u1, v0, v1, color);
        quad(pose, buffer, x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1, u0, u1, v0, v1, color);
    }

    private static void quad(PoseStack.Pose pose, VertexConsumer buffer,
            float x1, float y1, float z1, float x2, float y2, float z2,
            float x3, float y3, float z3, float x4, float y4, float z4,
            float u0, float u1, float v0, float v1, int color) {
        vertex(pose, buffer, x1, y1, z1, u1, v0, color);
        vertex(pose, buffer, x2, y2, z2, u0, v0, color);
        vertex(pose, buffer, x3, y3, z3, u0, v1, color);
        vertex(pose, buffer, x4, y4, z4, u1, v1, color);
    }

    private static void vertex(PoseStack.Pose pose, VertexConsumer builder, float x, float y, float z, float u, float v, int color) {
        int a = (color >>> 24) & 0xFF;
        int r = (color >> 16) & 0xFF;
        int g = (color >> 8) & 0xFF;
        int b = color & 0xFF;
        builder.addVertex(pose, x, y, z).setColor(r, g, b, a).setUv(u, v).setOverlay(OverlayTexture.NO_OVERLAY).setLight(LIGHT).setNormal(0, 1, 0);
    }
}
