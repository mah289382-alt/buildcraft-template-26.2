package com.buildcraft.factory.client.render;

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
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.fluid.FluidTintSource;
import net.neoforged.neoforge.transfer.fluid.FluidResource;
import org.jspecify.annotations.Nullable;

import com.buildcraft.BuildCraft;
import com.buildcraft.factory.block.TankBlock;
import com.buildcraft.factory.blockentity.TankBlockEntity;

/**
 * Ports {@code RenderTank}'s fluid-content rendering (see the fluid-box javadoc further down) AND, since
 * 2026-07-21, the frame itself too (real source uses a real baked JSON block model for the frame - see
 * {@code TankBlock}'s javadoc for why this port switched to fully custom rendering instead: a static model's
 * quads are single-sided, so looking through the tank's transparent "window" texture toward a far wall showed
 * nothing - a real quad IS drawn there, it's just backface-culled from the viewer's side).
 * <p>
 * The first attempt at fixing that "fixed" it by emitting each wall face TWICE (normal + fully reversed vertex
 * order) - which turned out to be redundant AND actively harmful: {@link #cutout}'s pipeline
 * ({@code RenderPipelines.ENTITY_CUTOUT}) already has culling disabled by default - a fact already established
 * directly in this project's own {@code QuarryBlockEntityRenderer} (see its {@code LASER_UNLIT_CUTOUT_PIPELINE}
 * javadoc: "since entityCutout has culling disabled"), which should have been checked before assuming a
 * duplicate-geometry fix was needed. A SINGLE quad under this pipeline is already visible from both sides; the
 * second, perfectly coincident reversed-winding copy was pure duplicate geometry at the exact same depth - a
 * textbook z-fight generator (reported back as "spaced out weird"/flicker). Fixed by drawing each face exactly
 * once - the real cause of "can't see the far wall" was the STATIC BAKED MODEL's chunk-layer culling (a
 * different pipeline than {@code ENTITY_CUTOUT}), not anything that needed duplicate geometry to solve once
 * rendering moved to a custom, already-unculled TESR pipeline.
 * <p>
 * Real {@code shouldSideBeRendered}'s cap-hiding (between 2 stacked Tanks) is now computed directly here each
 * frame (checking the live neighbour block entity), replacing the baked-model-cullface mechanism it used to
 * rely on. The real {@code JOINED_BELOW} blockstate property still selects which side texture variant to bind
 * (unchanged real intent - just texture, not geometry).
 */
public class TankBlockEntityRenderer implements BlockEntityRenderer<TankBlockEntity, TankRenderState> {
    private static final int LIGHT = 15728880;
    private static final Identifier SIDE = Identifier.fromNamespaceAndPath(BuildCraft.MODID, "textures/block/tank/side.png");
    private static final Identifier SIDE_JOINED_BELOW = Identifier.fromNamespaceAndPath(BuildCraft.MODID, "textures/block/tank/side_joined_below.png");
    private static final Identifier END = Identifier.fromNamespaceAndPath(BuildCraft.MODID, "textures/block/tank/end.png");
    // Real bounding box (2/16..14/16 x 0..16 x 2/16..14/16), see TankBlock.SHAPE.
    private static final float FX0 = 2f / 16f;
    private static final float FX1 = 14f / 16f;
    // Anti-z-fight lift for the frame's own bottom (walls + down cap together, so they stay sealed) - the real
    // bounding box's Y0 is exactly 0, flush with the block below (ground or any solid neighbour), which
    // z-fights/visibly clips into it. Same fix, same constant value, as the Refinery's tanks got earlier this
    // session - position-only, kept separate from UV (which is derived from the box's fixed local coordinates,
    // not this position, so the lift can't corrupt it the way the Refinery's original bug did).
    private static final float FRAME_SHRINK = 0.01f;

    private static final float X0 = 0.13f;
    private static final float X1 = 0.86f;
    private static final float Z0 = 0.13f;
    private static final float Z1 = 0.86f;
    private static final float Y0_UNCONNECTED = 0.01f;
    private static final float Y1_UNCONNECTED = 0.99f;
    private static final float Y0_CONNECTED = 0f;
    private static final float Y1_CONNECTED = 1f - 1e-5f;

    public TankBlockEntityRenderer(BlockEntityRendererProvider.Context context) {
    }

    @Override
    public TankRenderState createRenderState() {
        return new TankRenderState();
    }

    @Override
    public void extractRenderState(TankBlockEntity blockEntity, TankRenderState state, float partialTicks, Vec3 cameraPosition,
            ModelFeatureRenderer.@Nullable CrumblingOverlay breakProgress) {
        BlockEntityRenderer.super.extractRenderState(blockEntity, state, partialTicks, cameraPosition, breakProgress);

        Level level = blockEntity.getLevel();
        BlockPos pos = blockEntity.getBlockPos();
        state.hideUp = level != null && level.getBlockEntity(pos.above()) instanceof TankBlockEntity;
        state.hideDown = level != null && level.getBlockEntity(pos.below()) instanceof TankBlockEntity;
        state.joinedBelowTexture = blockEntity.getBlockState().hasProperty(TankBlock.JOINED_BELOW)
                && blockEntity.getBlockState().getValue(TankBlock.JOINED_BELOW);

        FluidResource resource = blockEntity.getFluidResource();
        state.hasFluid = !resource.isEmpty();
        if (!state.hasFluid) {
            return;
        }
        state.fillPercent = blockEntity.getFillPercent();
        state.connectedUp = blockEntity.isFullyConnected(Direction.UP);
        state.connectedDown = blockEntity.isFullyConnected(Direction.DOWN);
        Fluid fluid = resource.getFluid();
        FluidState fluidState = fluid.defaultFluidState();
        FluidModel fluidModel = Minecraft.getInstance().getModelManager().getFluidStateModelSet().get(fluidState);
        TextureAtlasSprite sprite = fluidModel.stillMaterial().sprite();
        state.spriteU0 = sprite.getU0();
        state.spriteU1 = sprite.getU1();
        state.spriteV0 = sprite.getV0();
        state.spriteV1 = sprite.getV1();

        // Real fluid rendering multiplies the still sprite by a per-fluid tint (water: real biome-averaged
        // colour, sampled the same way vanilla water blocks are - FluidTintSources.water()'s colorInWorld calls
        // BiomeColors.getAverageWaterColor directly; lava and this mod's own Oil/Fuel have no tint source at
        // all, since their textures are already fully coloured - see BuildCraftClient.registerFluidModels,
        // which passes null for both). Water_still.png (and any other tinted vanilla/modded fluid) is designed
        // GREYSCALE specifically expecting this multiply - skipping it (as an earlier version of this renderer
        // did, always drawing white/untinted) is exactly why water rendered grey/white instead of blue.
        FluidTintSource tintSource = fluidModel.fluidTintSource();
        if (tintSource == null) {
            state.tintColor = 0xFFFFFFFF;
        } else if (level instanceof BlockAndTintGetter tintGetter) {
            state.tintColor = tintSource.colorInWorld(fluidState, blockEntity.getBlockState(), tintGetter, pos);
        } else {
            state.tintColor = tintSource.color(fluidState);
        }
    }

    @Override
    public void submit(TankRenderState state, PoseStack poseStack, SubmitNodeCollector submitNodeCollector, CameraRenderState camera) {
        // FRAME_SHRINK's lift only belongs at a REAL ground/block boundary (avoiding the anti-clip z-fight) -
        // applying it unconditionally also lifted the wall's bottom edge away from a TANK stacked below, leaving
        // a real 0.01-block gap at every tank-to-tank seam (reported: "gap in the side wall... at the middle
        // meeting point"). When hideDown is true (a tank sits directly below), the wall's own bottom edge must
        // stay at the true y=0 to butt seamlessly against that tank's own top (y=1, never shrunk) with zero gap.
        float wallY0 = state.hideDown ? 0f : FRAME_SHRINK;
        RenderType sideType = cutout(state.joinedBelowTexture ? SIDE_JOINED_BELOW : SIDE);
        submitNodeCollector.submitCustomGeometry(poseStack, sideType, (pose, buffer) ->
                wallFaces(pose, buffer, FX0, wallY0, FX0, FX1, 1f, FX1));

        RenderType endType = cutout(END);
        boolean hideUp = state.hideUp;
        boolean hideDown = state.hideDown;
        submitNodeCollector.submitCustomGeometry(poseStack, endType, (pose, buffer) ->
                capFaces(pose, buffer, FX0, wallY0, FX0, FX1, 1f, FX1, hideDown, hideUp));

        if (!state.hasFluid || state.fillPercent <= 0) {
            return;
        }
        float y0 = state.connectedDown ? Y0_CONNECTED : Y0_UNCONNECTED;
        float y1Full = state.connectedUp ? Y1_CONNECTED : Y1_UNCONNECTED;
        float y1 = y0 + (y1Full - y0) * Math.min(1f, state.fillPercent / 100f);
        if (y1 <= y0) {
            return;
        }

        RenderType fluidType = cutout(TextureAtlas.LOCATION_BLOCKS);
        boolean skipUp = state.connectedUp;
        boolean skipDown = state.connectedDown;
        float u0 = state.spriteU0;
        float u1 = state.spriteU1;
        float v0 = state.spriteV0;
        float v1 = state.spriteV1;
        int tint = state.tintColor;
        submitNodeCollector.submitCustomGeometry(poseStack, fluidType, (pose, buffer) ->
                fluidBox(pose, buffer, X0, y0, Z0, X1, y1, Z1, u0, u1, v0, v1, tint, skipUp, skipDown));
    }

    private static RenderType cutout(Identifier texture) {
        RenderSetup setup = RenderSetup.builder(RenderPipelines.ENTITY_CUTOUT)
                .withTexture("Sampler0", texture)
                .useLightmap()
                .useOverlay()
                .createRenderSetup();
        return RenderType.create("buildcraft_tank", setup);
    }

    /** The 4 vertical wall faces. UV is CROPPED to the box's own local coordinates (u: {@link #FX0}-{@link #FX1}
     * = 2/16-14/16, v: full 0-1 since the box spans the full block height) rather than stretching the whole
     * 16x16 texture across the 12-unit-wide face - this matches real vanilla's own auto-UV-derivation for a
     * block model element with no explicit {@code "uv"} (which the original, working baked JSON model relied on
     * before this block switched to custom rendering - see {@code TankBlock}'s javadoc), and is what the real
     * texture's art was actually designed against (the outer ~2px margin on each edge is intentionally never
     * shown). An earlier version of this renderer used full 0-1 UV per face, stretching/distorting the pattern
     * across the narrower face - the exact "spaced out wrong" symptom reported, the same category of bug
     * already found and fixed once for the Refinery's own tank textures this session.
     * <p>
     * V is deliberately passed as (1,0), not (0,1): {@code quad()} maps each face's BOTTOM vertex pair to v0 and
     * its TOP pair to v1, and image row 0 (v=0) is the texture's TOP row (standard raster order) - so a naive
     * (0,1) mapping puts image row 0 at the world-bottom and row 15 at the world-top. But
     * {@code side_joined_below.png}'s ONE edited row (confirmed via exact pixel diff earlier this session -
     * only row 15, the image's BOTTOM row, differs from {@code side.png}, missing that row's rung) is meant to
     * land exactly at the SEAM with a tank below (world y=0) - with the naive mapping it was rendering at the
     * world-TOP instead, nowhere near the actual seam it's supposed to close. This is the real cause of "the
     * textures dead in the middle... don't line up" - not a missing feature (the correct texture variant WAS
     * already being selected, per the previous round's re-verification), a wrong-end UV mapping. */
    private static void wallFaces(PoseStack.Pose pose, VertexConsumer buffer, float x0, float y0, float z0, float x1, float y1, float z1) {
        quad(pose, buffer, x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0, FX0, FX1, 1f, 0f, 0xFFFFFFFF); // west
        quad(pose, buffer, x1, y0, z1, x1, y0, z0, x1, y1, z0, x1, y1, z1, FX0, FX1, 1f, 0f, 0xFFFFFFFF); // east
        quad(pose, buffer, x1, y0, z0, x0, y0, z0, x0, y1, z0, x1, y1, z0, FX0, FX1, 1f, 0f, 0xFFFFFFFF); // north
        quad(pose, buffer, x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1, FX0, FX1, 1f, 0f, 0xFFFFFFFF); // south
    }

    /** The 2 horizontal cap faces, each real {@code shouldSideBeRendered}-hideable (skipped entirely when a
     * neighbour Tank sits directly above/below, so the 2 blocks read as one seamless column). UV cropped to
     * {@link #FX0}-{@link #FX1} on BOTH axes this time (the cap face is 12x12, inset on both X and Z), same
     * reasoning as {@link #wallFaces}. */
    private static void capFaces(PoseStack.Pose pose, VertexConsumer buffer, float x0, float y0, float z0, float x1, float y1, float z1,
            boolean hideDown, boolean hideUp) {
        if (!hideDown) {
            quad(pose, buffer, x1, y0, z1, x0, y0, z1, x0, y0, z0, x1, y0, z0, FX0, FX1, FX0, FX1, 0xFFFFFFFF);
        }
        if (!hideUp) {
            quad(pose, buffer, x1, y1, z0, x0, y1, z0, x0, y1, z1, x1, y1, z1, FX0, FX1, FX0, FX1, 0xFFFFFFFF);
        }
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

    /** Same fluid-box helper as before (real {@code RenderTank} fixed inner bounds, extended flush to the block
     * edge and face-skipped on whichever side visually merges with a same-fluid connected neighbour - see
     * {@code TankBlockEntity.isFullyConnected}'s javadoc), sampling a real vanilla fluid sprite's own atlas UV
     * rect rather than a dedicated file - see {@code extractRenderState}'s javadoc for why this is the correct
     * generic mechanism in this NeoForge beta, and for the real per-fluid tint ({@code color}, packed ARGB -
     * real vanilla water is a grey/white texture multiplied by a biome-sampled blue tint at render time; drawing
     * it untinted, as an earlier version of this renderer did, is exactly why water rendered grey/white instead
     * of blue). Single-sided (unlike the frame) - matching real source's fluid rendering, and there's no
     * immersion complaint about the fluid content specifically. */
    private static void fluidBox(PoseStack.Pose pose, VertexConsumer buffer, float x0, float y0, float z0, float x1, float y1, float z1,
            float u0, float u1, float v0, float v1, int color, boolean skipUp, boolean skipDown) {
        if (!skipDown) {
            quad(pose, buffer, x1, y0, z1, x0, y0, z1, x0, y0, z0, x1, y0, z0, u0, u1, v0, v1, color);
        }
        if (!skipUp) {
            quad(pose, buffer, x1, y1, z0, x0, y1, z0, x0, y1, z1, x1, y1, z1, u0, u1, v0, v1, color);
        }
        quad(pose, buffer, x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0, u0, u1, v0, v1, color);
        quad(pose, buffer, x1, y0, z0, x0, y0, z0, x0, y1, z0, x1, y1, z0, u0, u1, v0, v1, color);
        quad(pose, buffer, x1, y0, z1, x1, y0, z0, x1, y1, z0, x1, y1, z1, u0, u1, v0, v1, color);
        quad(pose, buffer, x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1, u0, u1, v0, v1, color);
    }

    private static void vertex(PoseStack.Pose pose, VertexConsumer builder, float x, float y, float z, float u, float v, int color) {
        int a = (color >>> 24) & 0xFF;
        int r = (color >> 16) & 0xFF;
        int g = (color >> 8) & 0xFF;
        int b = color & 0xFF;
        builder.addVertex(pose, x, y, z).setColor(r, g, b, a).setUv(u, v).setOverlay(OverlayTexture.NO_OVERLAY).setLight(LIGHT).setNormal(0, 1, 0);
    }
}
