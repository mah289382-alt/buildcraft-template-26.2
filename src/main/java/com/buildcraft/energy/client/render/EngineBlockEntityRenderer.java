package com.buildcraft.energy.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;

import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

import com.buildcraft.BuildCraft;
import com.buildcraft.energy.blockentity.EngineBlockEntity;

/**
 * Ports the real {@code engine_base.json} model geometry - confirmed by reading that file directly, since
 * BuildCraft 1.12.2 used its own runtime-parametrized JSON model format (expressions like {@code progress_size}/
 * {@code trunk_tex}/{@code stage_light}, a {@code builtin:rotate_facing} rule) that has no modern vanilla
 * equivalent - so this renders the exact 4-element structure directly instead: a static {@code base} (0,0,0)-
 * (16,4,16), a {@code base_moving} piston head of identical size that travels from y=4 up to y≈16 (flush with the
 * trunk's own top, not beyond it) and back in a triangle wave (source's real ~8px/half-block stroke, per
 * {@code engine_base.json}'s {@code progress_size = triangle(progress) * (8*2-0.01)}), a fixed {@code trunk}
 * column (4,4,4)-(12,16,12) whose texture switches between 5 real shared stage
 * textures ({@code trunk_blue/green/yellow/red/overheat}, copied from source - NOT a colour tint, this port's
 * first attempt's simplification), and a {@code chamber} sleeve (3,4,3)-(13,4+stroke,13), 4 side faces only,
 * that grows/shrinks with the piston and reveals a scrolling shaft texture. The whole assembly is authored
 * "facing up" and rotated to the block's real {@code FACING}, matching source's {@code rotate_facing} rule.
 * <p>
 * Not ported: source's per-stage LIGHT EMISSION on the trunk (glowing brighter as it overheats) - that needs
 * updating the level's real light engine (a different, heavier mechanism than per-vertex brightness), not just
 * this renderer; a documented gap, not attempted.
 */
public class EngineBlockEntityRenderer implements BlockEntityRenderer<EngineBlockEntity, EngineRenderState> {
    private static final int LIGHT = 15728880;
    // Real source constant from engine_base.json's progress_size expression: "(8 * 2 - 0.01)" = 15.99, in the
    // model's 0-16 pixel space. Max stroke is HALF a block (not a full one) - the piston head's top face peaks
    // at pixel y = 8 + 7.995 = 15.995, i.e. flush with the trunk's own top (y=16), not into the block above.
    private static final float MAX_STROKE_PX = 8 * 2 - 0.01f;

    public EngineBlockEntityRenderer(BlockEntityRendererProvider.Context context) {
    }

    @Override
    public EngineRenderState createRenderState() {
        return new EngineRenderState();
    }

    /** Real perf fix (2026-07-31 FPS audit, same reasoning as the pipe renderers' own trim): a single 1x1x1
     * piston/trunk animation isn't perceptible past normal range - the default 64-block view distance is wasted
     * cost for every engine in a base beyond that. */
    @Override
    public int getViewDistance() {
        return 32;
    }

    @Override
    public void extractRenderState(EngineBlockEntity blockEntity, EngineRenderState state, float partialTicks, Vec3 cameraPosition,
            ModelFeatureRenderer.@Nullable CrumblingOverlay breakProgress) {
        BlockEntityRenderer.super.extractRenderState(blockEntity, state, partialTicks, cameraPosition, breakProgress);
        state.progress = blockEntity.getProgress();
        state.stage = blockEntity.getPowerStage();
        BlockState blockState = blockEntity.getBlockState();
        state.facing = blockState.hasProperty(BlockStateProperties.FACING)
                ? blockState.getValue(BlockStateProperties.FACING) : Direction.UP;
        Identifier body = blockEntity.getBodyTexture();
        state.backTexture = texturePath(body, "_back");
        state.sideTexture = texturePath(body, "");
    }

    private static Identifier texturePath(Identifier body, String suffix) {
        return Identifier.fromNamespaceAndPath(body.getNamespace(), "textures/" + body.getPath() + suffix + ".png");
    }

    private static Identifier trunkTexture(EngineBlockEntity.PowerStage stage) {
        String name = switch (stage) {
            case BLUE -> "trunk_blue";
            case GREEN -> "trunk_green";
            case YELLOW -> "trunk_yellow";
            case RED -> "trunk_red";
            case OVERHEAT -> "trunk_overheat";
        };
        return Identifier.fromNamespaceAndPath(BuildCraft.MODID, "textures/block/engine_" + name + ".png");
    }

    private static Identifier chamberTexture() {
        return Identifier.fromNamespaceAndPath(BuildCraft.MODID, "textures/block/engine_chamber_base.png");
    }

    /** Source's exact triangle wave in block units: rises 0->~0.5 over progress 0->0.5, falls back 0.5->1. */
    private static float progressSize(float progress) {
        float triangle = progress > 0.5f ? (1f - progress) : progress; // 0..0.5
        return (triangle * MAX_STROKE_PX) / 16f;
    }

    @Override
    public void submit(EngineRenderState state, PoseStack poseStack, SubmitNodeCollector submitNodeCollector, CameraRenderState camera) {
        float ps = progressSize(state.progress);

        poseStack.pushPose();
        poseStack.translate(0.5, 0.5, 0.5);
        applyFacingRotation(poseStack, state.facing);
        poseStack.translate(-0.5, -0.5, -0.5);

        RenderType backType = cutout(state.backTexture);
        RenderType sideType = cutout(state.sideTexture);
        RenderType trunkType = cutout(trunkTexture(state.stage));

        // base (static) - down/up use "back", the 4 sides use "side".
        submitNodeCollector.submitCustomGeometry(poseStack, backType, (pose, buffer) ->
                faceDownUp(pose, buffer, 0, 0, 0, 1, 4 / 16f, 1));
        submitNodeCollector.submitCustomGeometry(poseStack, sideType, (pose, buffer) ->
                faceSides(pose, buffer, 0, 0, 0, 1, 4 / 16f, 1, 0, 0, 1, 4 / 16f));

        // base_moving (the piston head) - identical shape, translated by the current stroke offset.
        float y0 = 4 / 16f + ps;
        float y1 = 8 / 16f + ps;
        submitNodeCollector.submitCustomGeometry(poseStack, backType, (pose, buffer) ->
                faceDownUp(pose, buffer, 0, y0, 0, 1, y1, 1));
        submitNodeCollector.submitCustomGeometry(poseStack, sideType, (pose, buffer) ->
                faceSides(pose, buffer, 0, y0, 0, 1, y1, 1, 0, 0, 1, 4 / 16f));

        // trunk (fixed column, stage-textured).
        submitNodeCollector.submitCustomGeometry(poseStack, trunkType, (pose, buffer) -> {
            faceDownUpUV(pose, buffer, 4 / 16f, 4 / 16f, 4 / 16f, 12 / 16f, 16 / 16f, 12 / 16f, 0, 0, 8, 8);
            faceSidesUV(pose, buffer, 4 / 16f, 4 / 16f, 4 / 16f, 12 / 16f, 16 / 16f, 12 / 16f, 8, 0, 16, 12);
        });

        // chamber (grows with the piston, 4 side faces only, texture scrolls as it grows).
        if (ps > 0.001f) {
            RenderType chamberType = cutout(chamberTexture());
            float cy1 = 4 / 16f + ps;
            submitNodeCollector.submitCustomGeometry(poseStack, chamberType, (pose, buffer) ->
                    faceSidesUV(pose, buffer, 3 / 16f, 4 / 16f, 3 / 16f, 13 / 16f, cy1, 13 / 16f, 3, ps * 16, 13, 0));
        }

        poseStack.popPose();
    }

    private static void applyFacingRotation(PoseStack poseStack, Direction facing) {
        switch (facing) {
            case UP -> {
            }
            case DOWN -> poseStack.mulPose(Axis.XP.rotationDegrees(180));
            case NORTH -> poseStack.mulPose(Axis.XP.rotationDegrees(-90));
            case SOUTH -> poseStack.mulPose(Axis.XP.rotationDegrees(90));
            case WEST -> poseStack.mulPose(Axis.ZP.rotationDegrees(90));
            case EAST -> poseStack.mulPose(Axis.ZP.rotationDegrees(-90));
        }
    }

    private static RenderType cutout(Identifier texture) {
        RenderSetup setup = RenderSetup.builder(RenderPipelines.ENTITY_CUTOUT)
                .withTexture("Sampler0", texture)
                .useLightmap()
                .useOverlay()
                .createRenderSetup();
        return RenderType.create("buildcraft_engine_" + texture.getPath(), setup);
    }

    /** down+up faces, full 0-16 UV (matches source's "back" texture use on base/base_moving). */
    private static void faceDownUp(PoseStack.Pose pose, VertexConsumer buffer, float x0, float y0, float z0, float x1, float y1, float z1) {
        faceDownUpUV(pose, buffer, x0, y0, z0, x1, y1, z1, 0, 0, 16, 16);
    }

    private static void faceDownUpUV(PoseStack.Pose pose, VertexConsumer buffer, float x0, float y0, float z0, float x1, float y1, float z1,
            float u0, float v0, float u1, float v1) {
        quad(pose, buffer, x0, y0, z1, u0, v0, x1, y0, z1, u1, v0, x1, y0, z0, u1, v1, x0, y0, z0, u0, v1);
        quad(pose, buffer, x0, y1, z0, u0, v0, x1, y1, z0, u1, v0, x1, y1, z1, u1, v1, x0, y1, z1, u0, v1);
    }

    /** north/south/west/east faces using the "side" texture, cropped to [su0,sv0,su1,sv1] (source: 0,0,16,4). */
    private static void faceSides(PoseStack.Pose pose, VertexConsumer buffer, float x0, float y0, float z0, float x1, float y1, float z1,
            float su0, float sv0, float su1, float sv1) {
        quad(pose, buffer, x1, y1, z0, su0, sv0, x0, y1, z0, su1, sv0, x0, y0, z0, su1, sv1, x1, y0, z0, su0, sv1);
        quad(pose, buffer, x0, y1, z1, su0, sv0, x1, y1, z1, su1, sv0, x1, y0, z1, su1, sv1, x0, y0, z1, su0, sv1);
        quad(pose, buffer, x0, y1, z0, su0, sv0, x0, y1, z1, su1, sv0, x0, y0, z1, su1, sv1, x0, y0, z0, su0, sv1);
        quad(pose, buffer, x1, y1, z1, su0, sv0, x1, y1, z0, su1, sv0, x1, y0, z0, su1, sv1, x1, y0, z1, su0, sv1);
    }

    private static void faceSidesUV(PoseStack.Pose pose, VertexConsumer buffer, float x0, float y0, float z0, float x1, float y1, float z1,
            float u0, float v0, float u1, float v1) {
        faceSides(pose, buffer, x0, y0, z0, x1, y1, z1, u0, v0, u1, v1);
    }

    private static void quad(PoseStack.Pose pose, VertexConsumer buffer,
            float x1, float y1, float z1, float u1, float v1,
            float x2, float y2, float z2, float u2, float v2,
            float x3, float y3, float z3, float u3, float v3,
            float x4, float y4, float z4, float u4, float v4) {
        vertex(pose, buffer, x1, y1, z1, u1 / 16f, v1 / 16f);
        vertex(pose, buffer, x2, y2, z2, u2 / 16f, v2 / 16f);
        vertex(pose, buffer, x3, y3, z3, u3 / 16f, v3 / 16f);
        vertex(pose, buffer, x4, y4, z4, u4 / 16f, v4 / 16f);
    }

    private static void vertex(PoseStack.Pose pose, VertexConsumer builder, float x, float y, float z, float u, float v) {
        builder.addVertex(pose, x, y, z).setColor(-1).setUv(u, v).setOverlay(OverlayTexture.NO_OVERLAY).setLight(LIGHT).setNormal(0, 1, 0);
    }
}
