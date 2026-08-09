package com.buildcraft.builders.client.render;

import java.util.HashMap;
import java.util.Map;

import org.jspecify.annotations.Nullable;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;

import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

import com.buildcraft.BuildCraft;
import com.buildcraft.builders.blockentity.QuarryBlockEntity;

/**
 * Mirrors the original's laser-based visuals (it never used a real 3D drill/gantry mesh either). Three mutually
 * exclusive states, matching {@code RenderQuarry.java} exactly:
 * <ul>
 * <li>No drill position yet (still building the frame, or frame work has interrupted mining): the whole working
 * area as a 12-edge textured wireframe box ({@code STRIPES_WRITE}) - unless...
 * <li>...an obstacle inside the frame's own footprint is being lasered away from a distance: a thin targeting
 * beam ({@code POWER_LOW}) from the Quarry block itself to the obstacle.
 * <li>Actively mining: a horizontal gantry crosshair at the frame's top level, a vertical connector down to the
 * drill, and a short drill-textured segment that plunges into the block being broken and retracts as it's about
 * to finish, tracking the block's own selection-shape height so short blocks don't get a drill floating in air.
 * </ul>
 */
public class QuarryBlockEntityRenderer implements BlockEntityRenderer<QuarryBlockEntity, QuarryRenderState> {
    private static final Identifier FRAME_TEXTURE = Identifier.fromNamespaceAndPath(BuildCraft.MODID, "textures/block/frame.png");
    private static final Identifier DRILL_TEXTURE = Identifier.fromNamespaceAndPath(BuildCraft.MODID, "textures/block/quarry_drill.png");
    private static final Identifier STRIPES_WRITE_TEXTURE = Identifier.fromNamespaceAndPath(BuildCraft.MODID, "textures/block/stripes_write.png");
    private static final Identifier POWER_LOW_TEXTURE = Identifier.fromNamespaceAndPath(BuildCraft.MODID, "textures/block/power_low.png");
    private static final int WHITE = -1;
    private static final double LASER_SCALE = 1.0 / 16.0;
    // LaserBoxRenderer.RENDER_SCALE - deliberately not 1/16, so the wireframe sits a hair inside the true frame
    // boundary instead of exactly on it (avoids z-fighting with the Frame blocks' own faces).
    private static final double STRIPES_RENDER_SCALE = 1.0 / 16.05;
    // ENGINEERING COMPROMISE, not a source-derived value: the gantry/connector beams' cross-section (0.5 blocks,
    // matching FRAME_HALF_WIDTH*2) is, by source design, EXACTLY as wide as the Frame post's own solid
    // cross-section, and travels straight through the post's block for the last 4/16 of its length (endpoints
    // reach the post's far face). That means the beam's 4 side faces are fully coincident - not just touching at
    // a point - with the post's own solid faces along that whole stretch, which z-fights regardless of winding
    // or render type. The original doesn't have this problem (different rendering internals entirely), so there
    // is no source answer for "the correct fix" - this is a small, deliberately visible-but-minor cross-section
    // shrink (only for FRAME/FRAME_BOTTOM, not DRILL/STRIPES_WRITE/POWER_LOW, which don't have this coincidence)
    // so the beam sits a hair inside the post's surface along the whole overlap, not just at one endpoint.
    private static final float FRAME_CROSS_SHRINK = 0.9F;

    /**
     * A single pixel-space UV rectangle from a {@code LaserData_BC8.LaserRow} in the original, always taken out
     * of a 16x16 texture ({@code textureSize} in the original defaults to 16 and none of the rows we use override it).
     */
    private record LaserRow(float uMin, float vMin, float uMax, float vMax) {
        float widthPx() {
            return uMax - uMin;
        }

        float heightPx() {
            return vMax - vMin;
        }
    }

    /**
     * Mirrors {@code LaserData_BC8.LaserType}: the cap/start/middle-variations/end/capEnd row set for one laser
     * style. {@code start}/{@code end} are nullable exactly like the original ({@code CompiledLaserType}'s
     * constructor null-checks them) - a laser type with no {@code start} row puts all its leftover, non-whole-tile
     * length onto {@code end} instead (see {@code CompiledLaserType.bakeFor}'s branching), and vice versa.
     */
    private record LaserTypeDef(LaserRow capStart, @Nullable LaserRow start, LaserRow[] middle, @Nullable LaserRow end, LaserRow capEnd) {
    }

    /**
     * The pre-drilling "wireframe box" is NOT a flat outline in the original: it's 12 real segmented laser beams
     * (one per box edge), reusing {@code BuildCraftLaserManager.STRIPES_WRITE}, which itself reuses
     * {@code MARKER_VOLUME_CONNECTED}'s row layout with the {@code stripes_write} sprite. Every row (cap/start/
     * middle x6/end) is 2px tall, giving a uniform, thin cross-section unlike the FRAME/DRILL beams.
     */
    private static final LaserTypeDef STRIPES_WRITE = new LaserTypeDef(
            new LaserRow(0, 0, 2, 2),
            new LaserRow(0, 0, 16, 2),
            new LaserRow[] {
                    new LaserRow(0, 2, 16, 4), new LaserRow(0, 4, 16, 6), new LaserRow(0, 6, 16, 8),
                    new LaserRow(0, 8, 16, 10), new LaserRow(0, 10, 16, 12), new LaserRow(0, 12, 16, 14)
            },
            new LaserRow(0, 14, 16, 16),
            new LaserRow(14, 14, 16, 16));

    /**
     * The "targeting" beam shown when the Quarry is lasering an obstacle out of its own frame footprint from a
     * distance (no gantry there yet). Reuses {@code BuildCraftLaserManager.POWER_LOW}, itself
     * {@code MARKER_VOLUME_POSSIBLE}'s row layout (14 cycling 1px-tall middle rows) with the {@code power_low}
     * sprite. The original sprite is an 8-frame vertical animation strip (16x128); we use only its first frame -
     * a static approximation, since porting MC's scrolling-sprite animation system for one rarely-seen beam
     * wasn't worth the added machinery.
     */
    private static final LaserTypeDef POWER_LOW = new LaserTypeDef(
            new LaserRow(0, 0, 1, 1),
            new LaserRow(0, 0, 16, 1),
            new LaserRow[] {
                    new LaserRow(0, 1, 16, 2), new LaserRow(0, 2, 16, 3), new LaserRow(0, 3, 16, 4),
                    new LaserRow(0, 4, 16, 5), new LaserRow(0, 5, 16, 6), new LaserRow(0, 6, 16, 7),
                    new LaserRow(0, 7, 16, 8), new LaserRow(0, 8, 16, 9), new LaserRow(0, 9, 16, 10),
                    new LaserRow(0, 10, 16, 11), new LaserRow(0, 11, 16, 12), new LaserRow(0, 12, 16, 13),
                    new LaserRow(0, 13, 16, 14), new LaserRow(0, 14, 16, 15)
            },
            new LaserRow(0, 15, 16, 16),
            new LaserRow(15, 15, 16, 16));

    /**
     * The gantry crosshair beams (2 along X, 2 along Z at the frame's top level), ported verbatim from
     * {@code RenderQuarry.FRAME}: no {@code start} row (all leftover length lands on {@code end}, which uses the
     * exact same UV rect as the single {@code middle} row, so the tiling seam is invisible), and both caps are
     * degenerate 0x0 rows - the crosshair beams have no visible end caps at all in the original.
     */
    private static final LaserTypeDef FRAME = new LaserTypeDef(
            new LaserRow(0, 0, 0, 0),
            null,
            new LaserRow[] { new LaserRow(0, 4, 16, 12) },
            new LaserRow(0, 4, 16, 12),
            new LaserRow(0, 0, 0, 0));

    /**
     * The vertical connector rod from the drill up to the gantry, ported verbatim from
     * {@code RenderQuarry.FRAME_BOTTOM}: identical to {@link #FRAME} except {@code capEnd} is a real 8x8px plate
     * (the frame post's own cross-section) instead of a degenerate row - since the connector is always drawn from
     * the drill (start, local length=0) up to the gantry (end, local length=full), that real cap lands at the top,
     * where the rod visually plugs into the gantry rail.
     */
    private static final LaserTypeDef FRAME_BOTTOM = new LaserTypeDef(
            new LaserRow(0, 0, 0, 0),
            null,
            new LaserRow[] { new LaserRow(0, 4, 16, 12) },
            new LaserRow(0, 4, 16, 12),
            new LaserRow(4, 4, 12, 12));

    /**
     * The drill tip segment, ported verbatim from {@code RenderQuarry.DRILL}: real 4x4px caps at both ends (from
     * the {@code quarry_drill.png} sprite's top-center) and no {@code start}/{@code end} rows at all - the drill
     * beam is always exactly 1 block long (see the original's {@code yOffset} math, which always spans exactly
     * {@code interpolatedPos.y+1+yOffset} to {@code interpolatedPos.y+yOffset}), which is exactly one whole
     * {@code middle} tile (16px wide at 1/16 scale = 1 block) with zero leftover, so {@code start}/{@code end}
     * are provably never baked and were correctly omitted by the original.
     */
    private static final LaserTypeDef DRILL = new LaserTypeDef(
            new LaserRow(6, 0, 10, 4),
            null,
            new LaserRow[] { new LaserRow(0, 0, 16, 4) },
            null,
            new LaserRow(6, 0, 10, 4));

    private static final int LIGHT = 15728880;

    /**
     * The original calls {@code RenderHelper.disableStandardItemLighting()} before drawing ANY laser geometry -
     * completely bypassing per-face directional shading, both front AND back. Forcing a fixed world-space normal
     * (see {@link #vertex}) only fixed the "front" (outward) side, since the standard {@code entityCutout}
     * pipeline still splits vertex color into separate front/back shades ({@code PER_FACE_LIGHTING}) and a single
     * normal can only make one of the two bright - the "back"/inward side (visible through the gaps in these
     * beams, since {@code entityCutout} has culling disabled) was still dark. There's no built-in NeoForge/vanilla
     * render type that's both opaque (correct self-occlusion - {@code entityTranslucentEmissive} was tried
     * earlier and rejected for exactly this reason, see git history) AND has no directional shading at all, so
     * this registers one: an exact copy of {@code RenderPipelines.ENTITY_CUTOUT} with {@code PER_FACE_LIGHTING}
     * swapped for {@code NO_CARDINAL_LIGHTING} (confirmed via {@code entity.vsh}: that define makes
     * {@code vertexColor = Color} unconditionally, skipping the front/back light split entirely) - the same
     * pattern NeoForge itself uses for {@code NeoForgeRenderPipelines.ENTITY_UNLIT_TRANSLUCENT}, just kept opaque
     * instead of translucent. Registered via {@link com.buildcraft.BuildCraftClient}.
     */
    public static final RenderPipeline LASER_UNLIT_CUTOUT_PIPELINE = RenderPipeline.builder(RenderPipelines.ENTITY_SNIPPET)
            .withLocation(Identifier.fromNamespaceAndPath(BuildCraft.MODID, "pipeline/laser_unlit_cutout"))
            .withShaderDefine("ALPHA_CUTOUT", 0.1F)
            .withShaderDefine("NO_CARDINAL_LIGHTING")
            .withBindGroupLayout(BindGroupLayouts.SAMPLER1)
            .withCull(false)
            .build();

    private static RenderType laserUnlitCutout(Identifier texture) {
        RenderSetup state = RenderSetup.builder(LASER_UNLIT_CUTOUT_PIPELINE)
                .withTexture("Sampler0", texture)
                .useLightmap()
                .useOverlay()
                .createRenderSetup();
        return RenderType.create("buildcraft_laser_unlit_cutout", state);
    }

    // Diagnostic-only: tracks the last logged state per Quarry position so we log on change, not every frame.
    private record LoggedState(@Nullable Vec3 drillPos, @Nullable BlockPos targetingObstacle, @Nullable BlockPos breakTarget) {}
    private final Map<BlockPos, LoggedState> lastLogged = new HashMap<>();

    public QuarryBlockEntityRenderer(BlockEntityRendererProvider.Context context) {
    }

    @Override
    public QuarryRenderState createRenderState() {
        return new QuarryRenderState();
    }

    @Override
    public void extractRenderState(QuarryBlockEntity blockEntity, QuarryRenderState state, float partialTicks, Vec3 cameraPosition,
            ModelFeatureRenderer.@Nullable CrumblingOverlay breakProgress) {
        BlockEntityRenderer.super.extractRenderState(blockEntity, state, partialTicks, cameraPosition, breakProgress);
        state.frameMin = blockEntity.getFrameMin();
        state.frameMax = blockEntity.getFrameMax();
        state.drillPos = blockEntity.getInterpolatedDrillPos(partialTicks);
        state.targetingObstacle = blockEntity.getTargetingObstacle();
        state.breakTarget = blockEntity.getBreakTarget();
        state.breakProgress = blockEntity.getInterpolatedBreakProgress(partialTicks);
        state.breakTargetMinY = 0.0F;
        state.breakTargetMaxY = 1.0F;

        Level level = blockEntity.getLevel();
        if (state.breakTarget != null && level != null) {
            BlockState breakState = level.getBlockState(state.breakTarget);
            VoxelShape shape = breakState.getShape(level, state.breakTarget);
            if (!shape.isEmpty()) {
                AABB bounds = shape.bounds();
                state.breakTargetMinY = (float) bounds.minY;
                state.breakTargetMaxY = (float) bounds.maxY;
            }
        }

        BlockPos quarryPos = blockEntity.getBlockPos();
        LoggedState current = new LoggedState(state.drillPos, state.targetingObstacle, state.breakTarget);
        LoggedState previous = lastLogged.put(quarryPos, current);
        if (!current.equals(previous)) {
            logGeometry(quarryPos, state);
        }
    }

    /** Logs the current visual state per Quarry position, on change only. */
    private static void logGeometry(BlockPos quarryPos, QuarryRenderState state) {
        if (state.frameMin == null || state.frameMax == null) {
            BuildCraft.LOGGER.warn("Quarry renderer at {}: frameMin/frameMax are null, nothing will be drawn", quarryPos);
            return;
        }
        if (state.drillPos == null) {
            if (state.targetingObstacle != null) {
                BuildCraft.LOGGER.debug("Quarry renderer at {}: lasering obstacle at {} from a distance",
                        quarryPos, state.targetingObstacle);
            } else {
                BuildCraft.LOGGER.debug("Quarry renderer at {}: not drilling, wireframe box world=[{} .. {}]",
                        quarryPos, state.frameMin, state.frameMax.offset(1, 1, 1));
            }
            return;
        }
        BuildCraft.LOGGER.debug("Quarry renderer at {}: drillPos={} breakTarget={} breakProgress={}",
                quarryPos, state.drillPos, state.breakTarget, state.breakProgress);
        double gantryY = state.frameMax.getY() + 0.5;
        if (gantryY <= state.drillPos.y) {
            BuildCraft.LOGGER.warn("Quarry renderer at {}: gantryY ({}) is at or below the drill ({}) - geometry may look inverted",
                    quarryPos, gantryY, state.drillPos.y);
        }
    }

    @Override
    public void submit(QuarryRenderState state, PoseStack poseStack, SubmitNodeCollector submitNodeCollector, CameraRenderState camera) {
        if (state.frameMin == null || state.frameMax == null) {
            return;
        }
        BlockPos origin = state.blockPos;

        if (state.drillPos == null) {
            if (state.targetingObstacle != null) {
                submitTargetingLaser(submitNodeCollector, poseStack, origin, state.targetingObstacle);
            } else {
                submitFrameAreaBox(submitNodeCollector, poseStack, origin, state.frameMin, state.frameMax);
            }
            return;
        }

        double minX = state.frameMin.getX() - origin.getX();
        double maxX = state.frameMax.getX() - origin.getX() + 1;
        double minZ = state.frameMin.getZ() - origin.getZ();
        double maxZ = state.frameMax.getZ() - origin.getZ() + 1;

        double cx = state.drillPos.x - origin.getX();
        double cz = state.drillPos.z - origin.getZ();
        double dy = state.drillPos.y - origin.getY();
        // Vertical center of the frame's top layer, matching the original's "max.getY() + 0.5" convention.
        double gantryY = state.frameMax.getY() - origin.getY() + 0.5;

        // Gantry crosshair: FOUR separate beams (not two continuous spans), ported verbatim from
        // RenderQuarry.render's FRAME block. Each pair (X, Z) starts at the drill's own RAW column coordinate
        // (cx / cz, NOT cx+0.5 / cz+0.5 - the original genuinely anchors the split point off-center) and reaches
        // out to the Frame post's face on each side (post spans 4/16..12/16 within its block, not the block edge).
        // The fixed cross-axis IS centered (cz+0.5 / cx+0.5), since that's the drill's actual visual column.
        // These 4 beams reach exactly to the frame post's FAR face (max/min.getX()+12/16D or +4/16D in the
        // original), and their cross-section width (0.5 blocks) exactly equals the post's own width - so for the
        // whole 4/16-long stretch where the beam travels through the post's block, the beam's 4 side faces are
        // fully coincident (same position, not just touching at a point) with the post's own solid faces. An
        // endpoint-only inset can't fix that (it only trims the very tip, leaving the rest of the overlap
        // untouched) - see FRAME_CROSS_SHRINK's javadoc for the actual fix.
        double postMinX = minX + 4.0 / 16.0;
        double postMaxX = maxX - 4.0 / 16.0;
        double postMinZ = minZ + 4.0 / 16.0;
        double postMaxZ = maxZ - 4.0 / 16.0;
        submitBeamX(submitNodeCollector, poseStack, cx, postMaxX, gantryY, cz + 0.5, FRAME, FRAME_TEXTURE, LASER_SCALE, FRAME_CROSS_SHRINK);
        submitBeamX(submitNodeCollector, poseStack, cx, postMinX, gantryY, cz + 0.5, FRAME, FRAME_TEXTURE, LASER_SCALE, FRAME_CROSS_SHRINK);
        submitBeamZ(submitNodeCollector, poseStack, cz, postMaxZ, cx + 0.5, gantryY, FRAME, FRAME_TEXTURE, LASER_SCALE, FRAME_CROSS_SHRINK);
        submitBeamZ(submitNodeCollector, poseStack, cz, postMinZ, cx + 0.5, gantryY, FRAME, FRAME_TEXTURE, LASER_SCALE, FRAME_CROSS_SHRINK);

        // Vertical connector from the drill up to the gantry, using FRAME_BOTTOM (not FRAME) - its real end cap
        // (an 8x8px plate matching the Frame post's own cross-section) lands at the gantry end since the beam
        // always travels start=drill -> end=gantry, giving the rod a visible "plug into the rail" cap up top.
        // Same coincidence risk as the gantry beams (the cap plate sits flush against the post), so same shrink.
        double connectorBottom = dy + 1 + 4.0 / 16.0;
        if (gantryY > connectorBottom) {
            submitBeamY(submitNodeCollector, poseStack, cx + 0.5, cz + 0.5, connectorBottom, gantryY, FRAME_BOTTOM, FRAME_TEXTURE, LASER_SCALE, FRAME_CROSS_SHRINK);
        }

        // The drill bit: parked flush with the connector's bottom by default, plunging down into the block
        // (based on its own selection-shape height) as breaking approaches ~90% done, then springing back up
        // to the parked position right as the block breaks. Uses DRILL's real 4x4px tip caps at both ends.
        // Not coincident with any solid Frame geometry (it sits inside the block being mined), so no shrink needed.
        double yOffset = computeDrillYOffset(state);
        double drillBottom = dy + yOffset;
        double drillTop = dy + 1 + yOffset;
        submitBeamY(submitNodeCollector, poseStack, cx + 0.5, cz + 0.5, drillBottom, drillTop, DRILL, DRILL_TEXTURE, LASER_SCALE, 1.0F);
    }

    /** Ports the original's {@code yOffset} bob curve from {@code RenderQuarry.render}. */
    private static double computeDrillYOffset(QuarryRenderState state) {
        double scaleMax = 1 + 4.0 / 16.0;
        if (state.breakTarget == null) {
            return scaleMax;
        }
        double v = state.breakProgress;
        // Ping-pong: 1 (parked) at v=0, dips to 0 at v=0.9 (plunged in), springs back to 1 by v=1 (block breaks).
        double eased = v < 0.9 ? (1 - v / 0.9) : ((v - 0.9) / 0.1);
        double scaleMin = 1 - (1 - state.breakTargetMaxY) - (state.breakTargetMaxY - state.breakTargetMinY) / 2.0;
        return scaleMin + eased * (scaleMax - scaleMin);
    }

    /**
     * Renders a real segmented laser beam from (cx, yFrom, cz) to (cx, yTo, cz) - requires {@code yTo > yFrom}
     * (both call sites always travel upward: drill-to-gantry, drill-bottom-to-drill-top).
     */
    private static void submitBeamY(SubmitNodeCollector collector, PoseStack poseStack, double cx, double cz,
            double yFrom, double yTo, LaserTypeDef type, Identifier texture, double scale, float crossShrink) {
        poseStack.pushPose();
        poseStack.translate(cx, 0, cz);
        submitSegmentedLocalYBeam(collector, poseStack, yFrom, yTo, type, texture, scale, crossShrink);
        poseStack.popPose();
    }

    /**
     * Renders a real segmented laser beam from (xFrom, y, z) to (xTo, y, z), in either direction - direction
     * matters for which physical end gets the type's {@code capEnd} (see {@link #FRAME_BOTTOM}), so this always
     * translates to the true {@code xFrom} start point and picks the rotation sign that makes local +Y follow
     * the actual xFrom -> xTo direction, rather than normalizing to a min/max span.
     */
    private static void submitBeamX(SubmitNodeCollector collector, PoseStack poseStack, double xFrom, double xTo,
            double y, double z, LaserTypeDef type, Identifier texture, double scale, float crossShrink) {
        double length = xTo - xFrom;
        if (Math.abs(length) < 1.0E-6) {
            return;
        }
        poseStack.pushPose();
        poseStack.translate(xFrom, y, z);
        // RotZ(-90) maps local +Y -> world +X; RotZ(+90) maps local +Y -> world -X (verified via the standard
        // 2D rotation matrix, not assumed: RotZ(theta) sends (x,y) -> (x*cos - y*sin, x*sin + y*cos)).
        poseStack.mulPose(Axis.ZP.rotationDegrees(length >= 0 ? -90 : 90));
        submitSegmentedLocalYBeam(collector, poseStack, 0, Math.abs(length), type, texture, scale, crossShrink);
        poseStack.popPose();
    }

    /** Renders a real segmented laser beam from (x, y, zFrom) to (x, y, zTo), in either direction. */
    private static void submitBeamZ(SubmitNodeCollector collector, PoseStack poseStack, double zFrom, double zTo,
            double x, double y, LaserTypeDef type, Identifier texture, double scale, float crossShrink) {
        double length = zTo - zFrom;
        if (Math.abs(length) < 1.0E-6) {
            return;
        }
        poseStack.pushPose();
        poseStack.translate(x, y, zFrom);
        // RotX(+90) maps local +Y -> world +Z; RotX(-90) maps local +Y -> world -Z (same derivation as above,
        // applied to the (y,z) plane).
        poseStack.mulPose(Axis.XP.rotationDegrees(length >= 0 ? 90 : -90));
        submitSegmentedLocalYBeam(collector, poseStack, 0, Math.abs(length), type, texture, scale, crossShrink);
        poseStack.popPose();
    }

    /**
     * Renders the pre-drilling wireframe box: 12 edges, each a real segmented laser beam (cap + tapered start +
     * repeating middle + tapered end + cap), matching {@code LaserBoxRenderer.renderLaserBoxStatic} exactly.
     * Both corners are placed at their block's center ({@code center=true} in the original), and each edge is
     * inset 1/16 block at both ends so adjoining edges' caps don't poke past the corner. An edge along a given
     * axis is only drawn when the box actually has extent along it (a 1-block-thin box doesn't need edges
     * doubling back on themselves), and - matching the original - top/side edges are only added when the
     * perpendicular axes also have extent, so a flat or line-thin box doesn't get degenerate duplicate edges.
     */
    private static void submitFrameAreaBox(SubmitNodeCollector collector, PoseStack poseStack,
            BlockPos origin, BlockPos frameMin, BlockPos frameMax) {
        double minX = frameMin.getX() - origin.getX() + 0.5;
        double minY = frameMin.getY() - origin.getY() + 0.5;
        double minZ = frameMin.getZ() - origin.getZ() + 0.5;
        double maxX = frameMax.getX() - origin.getX() + 0.5;
        double maxY = frameMax.getY() - origin.getY() + 0.5;
        double maxZ = frameMax.getZ() - origin.getZ() + 0.5;

        boolean renderX = frameMax.getX() > frameMin.getX();
        boolean renderY = frameMax.getY() > frameMin.getY();
        boolean renderZ = frameMax.getZ() > frameMin.getZ();

        // Real bug fixed (2026-08-08): this was 1/16 (0.0625), meant to keep each edge's own length shy of the
        // corner so two perpendicular edges' caps don't poke into each other. But the actual collision risk is
        // between an edge's LENGTH-wise start and the OTHER edge's CROSS-SECTION half-width at that corner - for
        // STRIPES_WRITE that's heightPx()/2 * STRIPES_RENDER_SCALE = 1 * (1/16.05) = 0.06231, just 0.0002 blocks
        // narrower than the old inset. That's nowhere near enough margin for depth-buffer precision at any real
        // render distance - the two surfaces were close enough to genuinely swap depth-test winners frame to
        // frame as the camera moved, which is exactly what z-fighting flicker looks like. A comfortably larger
        // gap (double the cross-section width) removes the ambiguity outright.
        double inset = 2.0 / 16.0;
        double lx0 = minX + inset, lx1 = maxX - inset;
        double ly0 = minY + inset, ly1 = maxY - inset;
        double lz0 = minZ + inset, lz1 = maxZ - inset;

        if (renderX) {
            submitXEdge(collector, poseStack, lx0, lx1, minY, minZ, STRIPES_WRITE, STRIPES_WRITE_TEXTURE, STRIPES_RENDER_SCALE);
            if (renderY) {
                submitXEdge(collector, poseStack, lx0, lx1, maxY, minZ, STRIPES_WRITE, STRIPES_WRITE_TEXTURE, STRIPES_RENDER_SCALE);
                if (renderZ) {
                    submitXEdge(collector, poseStack, lx0, lx1, maxY, maxZ, STRIPES_WRITE, STRIPES_WRITE_TEXTURE, STRIPES_RENDER_SCALE);
                }
            }
            if (renderZ) {
                submitXEdge(collector, poseStack, lx0, lx1, minY, maxZ, STRIPES_WRITE, STRIPES_WRITE_TEXTURE, STRIPES_RENDER_SCALE);
            }
        }
        if (renderY) {
            submitYEdge(collector, poseStack, minX, ly0, ly1, minZ, STRIPES_WRITE, STRIPES_WRITE_TEXTURE, STRIPES_RENDER_SCALE);
            if (renderX) {
                submitYEdge(collector, poseStack, maxX, ly0, ly1, minZ, STRIPES_WRITE, STRIPES_WRITE_TEXTURE, STRIPES_RENDER_SCALE);
                if (renderZ) {
                    submitYEdge(collector, poseStack, maxX, ly0, ly1, maxZ, STRIPES_WRITE, STRIPES_WRITE_TEXTURE, STRIPES_RENDER_SCALE);
                }
            }
            if (renderZ) {
                submitYEdge(collector, poseStack, minX, ly0, ly1, maxZ, STRIPES_WRITE, STRIPES_WRITE_TEXTURE, STRIPES_RENDER_SCALE);
            }
        }
        if (renderZ) {
            submitZEdge(collector, poseStack, minX, minY, lz0, lz1, STRIPES_WRITE, STRIPES_WRITE_TEXTURE, STRIPES_RENDER_SCALE);
            if (renderX) {
                submitZEdge(collector, poseStack, maxX, minY, lz0, lz1, STRIPES_WRITE, STRIPES_WRITE_TEXTURE, STRIPES_RENDER_SCALE);
                if (renderY) {
                    submitZEdge(collector, poseStack, maxX, maxY, lz0, lz1, STRIPES_WRITE, STRIPES_WRITE_TEXTURE, STRIPES_RENDER_SCALE);
                }
            }
            if (renderY) {
                submitZEdge(collector, poseStack, minX, maxY, lz0, lz1, STRIPES_WRITE, STRIPES_WRITE_TEXTURE, STRIPES_RENDER_SCALE);
            }
        }
    }

    /**
     * The beam shown while lasering an obstacle out of the frame's own footprint: from the Quarry block's own
     * center to the obstacle's center, in whatever direction that happens to be (not necessarily axis-aligned,
     * unlike every other beam this renderer draws) - so this is the one place we need a real to-direction
     * rotation instead of a fixed 90-degree axis swap.
     */
    private static void submitTargetingLaser(SubmitNodeCollector collector, PoseStack poseStack, BlockPos origin, BlockPos obstacle) {
        double fromX = 0.5, fromY = 0.5, fromZ = 0.5;
        double toX = obstacle.getX() - origin.getX() + 0.5;
        double toY = obstacle.getY() - origin.getY() + 0.5;
        double toZ = obstacle.getZ() - origin.getZ() + 0.5;
        double dx = toX - fromX, dy = toY - fromY, dz = toZ - fromZ;
        double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (length < 1.0E-6) {
            return;
        }
        poseStack.pushPose();
        poseStack.translate(fromX, fromY, fromZ);
        Vector3f dir = new Vector3f((float) (dx / length), (float) (dy / length), (float) (dz / length));
        poseStack.mulPose(new Quaternionf().rotationTo(new Vector3f(0, 1, 0), dir));
        submitSegmentedLocalYBeam(collector, poseStack, 0, length, POWER_LOW, POWER_LOW_TEXTURE, LASER_SCALE, 1.0F);
        poseStack.popPose();
    }

    private static void submitXEdge(SubmitNodeCollector collector, PoseStack poseStack, double xMin, double xMax, double y, double z,
            LaserTypeDef type, Identifier texture, double scale) {
        poseStack.pushPose();
        poseStack.translate(xMin, y, z);
        poseStack.mulPose(Axis.ZP.rotationDegrees(-90));
        submitSegmentedLocalYBeam(collector, poseStack, 0, xMax - xMin, type, texture, scale, 1.0F);
        poseStack.popPose();
    }

    private static void submitYEdge(SubmitNodeCollector collector, PoseStack poseStack, double x, double yMin, double yMax, double z,
            LaserTypeDef type, Identifier texture, double scale) {
        poseStack.pushPose();
        poseStack.translate(x, 0, z);
        submitSegmentedLocalYBeam(collector, poseStack, yMin, yMax, type, texture, scale, 1.0F);
        poseStack.popPose();
    }

    private static void submitZEdge(SubmitNodeCollector collector, PoseStack poseStack, double x, double y, double zMin, double zMax,
            LaserTypeDef type, Identifier texture, double scale) {
        poseStack.pushPose();
        poseStack.translate(x, y, zMin);
        poseStack.mulPose(Axis.XP.rotationDegrees(90));
        submitSegmentedLocalYBeam(collector, poseStack, 0, zMax - zMin, type, texture, scale, 1.0F);
        poseStack.popPose();
    }

    /**
     * Ports {@code CompiledLaserType.bakeFor} exactly, including its null-{@code start}/{@code end} branching
     * (a type missing one of those rows puts all leftover, non-whole-tile length onto whichever one it has -
     * see {@link #FRAME}/{@link #DRILL}, both of which omit at least one): bakes flat start/end caps (always,
     * even when degenerate - a 0-height/0-width row bakes a zero-area quad, which is harmless), then an optional
     * tapered start segment, a run of repeating middle tiles (cycling through the type's variations), and an
     * optional tapered end segment. All math mirrors the original 1:1, just working directly in world units
     * instead of the original's "bake in pixel space, then apply a uniform scale matrix" two-step (equivalent,
     * since that matrix's only job was the same uniform scale we apply inline here).
     */
    private static void submitSegmentedLocalYBeam(SubmitNodeCollector collector, PoseStack poseStack, double yStart, double yEnd,
            LaserTypeDef type, Identifier texture, double scale, float crossShrink) {
        double realLength = yEnd - yStart;
        if (realLength <= 1.0E-6) {
            return;
        }
        double length = realLength / scale;
        LaserRow startRow = type.start();
        LaserRow endRow = type.end();
        double startWidth = startRow == null ? 0 : startRow.widthPx();
        double endWidth = endRow == null ? 0 : endRow.widthPx();
        double middleWidth = type.middle()[0].widthPx();

        double lengthForMiddle = Math.max(0, length - startWidth - endWidth);
        int numMiddle = (int) Math.floor(lengthForMiddle / middleWidth);
        double leftOver = lengthForMiddle - middleWidth * numMiddle;
        if (leftOver > 1.0E-9) {
            numMiddle++;
        }
        double lengthEnds = length - middleWidth * numMiddle;
        final double startLength, endLength;
        if (startWidth > 0 && endWidth > 0) {
            double ratio = startWidth / endWidth;
            startLength = (lengthEnds / 2) * ratio;
            endLength = (lengthEnds / 2) / ratio;
        } else if (startWidth <= 0) {
            startLength = 0;
            endLength = lengthEnds;
        } else {
            startLength = lengthEnds;
            endLength = 0;
        }
        int middleCount = numMiddle;

        collector.submitCustomGeometry(poseStack, laserUnlitCutout(texture), (pose, buffer) -> {
            bakeCap(pose, buffer, yStart, type.capStart(), false, scale, crossShrink);
            bakeCap(pose, buffer, yStart + length * scale, type.capEnd(), true, scale, crossShrink);
            if (startLength > 0 && startRow != null) {
                float uNear = texU(startRow, 1 - startLength / startWidth);
                float uFar = texU(startRow, 1);
                float h = (float) (startRow.heightPx() / 2 * scale) * crossShrink;
                bakeStraightSegment(pose, buffer, yStart, yStart + startLength * scale, h, startRow, uNear, uFar);
            }
            double cursor = startLength;
            for (int i = 0; i < middleCount; i++) {
                LaserRow row = type.middle()[i % type.middle().length];
                float h = (float) (row.heightPx() / 2 * scale) * crossShrink;
                double segStart = yStart + cursor * scale;
                double segEnd = yStart + (cursor + middleWidth) * scale;
                bakeStraightSegment(pose, buffer, segStart, segEnd, h, row, texU(row, 0), texU(row, 1));
                cursor += middleWidth;
            }
            if (endLength > 0 && endRow != null) {
                float uNear = texU(endRow, 0);
                float uFar = texU(endRow, endLength / endWidth);
                float h = (float) (endRow.heightPx() / 2 * scale) * crossShrink;
                double segStart = yStart + (length - endLength) * scale;
                double segEnd = yStart + length * scale;
                bakeStraightSegment(pose, buffer, segStart, segEnd, h, endRow, uNear, uFar);
            }
        });
    }

    /**
     * Ports {@code CompiledLaserRow.bakeStartCap}/{@code bakeEndCap}: a flat square face capping the tube's end.
     * Skips degenerate 0x0 rows (both {@link #FRAME}'s caps, and the invisible end of {@link #FRAME_BOTTOM}) -
     * baking them would only add a zero-area quad, so skipping is a pure no-op optimization, not a behavior change.
     */
    private static void bakeCap(PoseStack.Pose pose, VertexConsumer buffer, double y, LaserRow row, boolean end, double scale, float crossShrink) {
        if (row.widthPx() <= 0 && row.heightPx() <= 0) {
            return;
        }
        float h = (float) (row.heightPx() / 2 * scale) * crossShrink;
        float fy = (float) y;
        float u0 = texU(row, 0), u1 = texU(row, 1), v0 = texV(row, 0), v1 = texV(row, 1);
        // Winding fixed (verified via right-hand-rule cross product of consecutive edges, not assumed): the
        // original vertex order here produced a face whose CCW-front side pointed inward instead of outward -
        // invisible with culling on, but with entityCutout's withCull(false) both sides render, and the shader's
        // per-face front/back color split (see the vertex() javadoc) then showed the dim "back" shade on the
        // outward-facing view and the bright "front" shade only when seen from inside - exactly the "outside
        // dark, inside lit like glass" symptom. Reversed the cyclic order (same 4 corners/UVs, opposite winding).
        if (!end) {
            quad4(pose, buffer,
                    h, fy, h, u1, v1,
                    -h, fy, h, u0, v1,
                    -h, fy, -h, u0, v0,
                    h, fy, -h, u1, v0);
        } else {
            quad4(pose, buffer,
                    -h, fy, h, u0, v1,
                    h, fy, h, u1, v1,
                    h, fy, -h, u1, v0,
                    -h, fy, -h, u0, v0);
        }
    }

    /**
     * Ports {@code CompiledLaserRow.bakeStart}/{@code bakeFor}/{@code bakeEnd} (all three share one 4-face tube
     * shape, differing only in which {@code uNear}/{@code uFar} and row get passed in).
     */
    private static void bakeStraightSegment(PoseStack.Pose pose, VertexConsumer buffer, double yNear, double yFar,
            float h, LaserRow row, float uNear, float uFar) {
        float yN = (float) yNear;
        float yF = (float) yFar;
        float v0 = texV(row, 0);
        float v1 = texV(row, 1);
        // Winding fixed on all 4 faces (see bakeCap's javadoc for the exact symptom/verification method).
        // TOP (original +Y) -> +X face
        quad4(pose, buffer,
                h, yN, -h, uNear, v0,
                h, yF, -h, uFar, v0,
                h, yF, h, uFar, v1,
                h, yN, h, uNear, v1);
        // BOTTOM (original -Y) -> -X face
        quad4(pose, buffer,
                -h, yF, -h, uFar, v0,
                -h, yN, -h, uNear, v0,
                -h, yN, h, uNear, v1,
                -h, yF, h, uFar, v1);
        // LEFT (original -Z) -> -Z face
        quad4(pose, buffer,
                -h, yN, -h, uNear, v0,
                -h, yF, -h, uFar, v0,
                h, yF, -h, uFar, v1,
                h, yN, -h, uNear, v1);
        // RIGHT (original +Z) -> +Z face
        quad4(pose, buffer,
                -h, yF, h, uFar, v0,
                -h, yN, h, uNear, v0,
                h, yN, h, uNear, v1,
                h, yF, h, uFar, v1);
    }

    private static float texU(LaserRow row, double t) {
        return (float) ((row.uMin() + t * (row.uMax() - row.uMin())) / 16.0);
    }

    private static float texV(LaserRow row, double t) {
        return (float) ((row.vMin() + t * (row.vMax() - row.vMin())) / 16.0);
    }

    private static void quad4(PoseStack.Pose pose, VertexConsumer buffer,
            float x1, float y1, float z1, float u1, float v1,
            float x2, float y2, float z2, float u2, float v2,
            float x3, float y3, float z3, float u3, float v3,
            float x4, float y4, float z4, float u4, float v4) {
        vertex(pose, buffer, x1, y1, z1, u1, v1);
        vertex(pose, buffer, x2, y2, z2, u2, v2);
        vertex(pose, buffer, x3, y3, z3, u3, v3);
        vertex(pose, buffer, x4, y4, z4, u4, v4);
    }

    /**
     * The original explicitly calls {@code RenderHelper.disableStandardItemLighting()} before drawing any laser
     * geometry (see {@code RenderQuarry.render}), meaning none of these beams get real per-face directional
     * shading in the original - they're flat/uniformly lit. Our render types (built on {@code ENTITY_SNIPPET})
     * always define {@code PER_FACE_LIGHTING}, which shades each quad by its transformed normal versus two fixed
     * "sun" directions - so a geometrically-correct rotated normal (e.g. on the topmost face of a horizontal
     * beam, which ends up with a downward-pointing normal on one of the tube's 4 faces depending on rotation)
     * gets a real, undesired darkening the original never had. Passing a fixed world-space (0,1,0) normal here -
     * skipping the pose transform entirely, unlike a geometrically-real normal - makes every face's "front" side
     * resolve to the same maximum brightness regardless of the beam's actual orientation, matching the
     * original's flat/undirected look.
     */
    private static void vertex(PoseStack.Pose pose, VertexConsumer builder, float x, float y, float z, float u, float v) {
        builder.addVertex(pose, x, y, z).setColor(WHITE).setUv(u, v).setOverlay(OverlayTexture.NO_OVERLAY).setLight(LIGHT).setNormal(0, 1, 0);
    }

    @Override
    public AABB getRenderBoundingBox(QuarryBlockEntity blockEntity) {
        BlockPos min = blockEntity.getFrameMin();
        BlockPos max = blockEntity.getFrameMax();
        if (min == null || max == null) {
            return new AABB(blockEntity.getBlockPos());
        }
        // Real bug fixed (2026-08-08): frameMin/frameMax's own Y-range is just the frame POSTS' height (a few
        // blocks near the top, per QuarryBlockEntity.initArea - Config.QUARRY_FRAME_HEIGHT), not the drill's
        // full working range. The vertical connector rod + drill bit routinely extend far below frameMin.getY()
        // as the Quarry digs deeper (down to mineMin.getY(), which can reach the world's min build height).
        // Frustum.isVisible(AABB) tests the WHOLE box at once - from camera angles where only that deep
        // connector/drill geometry would be on-screen but the shallow frame-height slice genuinely isn't, the
        // box failed the test and the ENTIRE gantry (frame crosshair AND connector rod together, not just the
        // rod) vanished, since submit() is all-or-nothing per block entity. Extending the box's min Y down to
        // the real deepest reachable point fixes this without weakening the fine-grained test elsewhere.
        BlockPos mineMin = blockEntity.getMineMin();
        int minY = mineMin != null ? Math.min(min.getY(), mineMin.getY()) : min.getY();
        return new AABB(min.getX(), minY, min.getZ(), max.getX() + 1, max.getY() + 1, max.getZ() + 1);
    }

    /** Real bug fixed (2026-08-07): {@link #getRenderBoundingBox} alone is NOT enough to stop the gantry from
     * vanishing when the Quarry's own block leaves view - confirmed by reading {@code LevelExtractor} and
     * {@code BlockEntityRenderDispatcher} directly. There are TWO separate culling layers: (1) a coarse, per-
     * CHUNK-SECTION pass ({@code LevelExtractor.extractVisibleBlockEntities} only even considers block entities
     * belonging to a section currently in {@code visibleSections()} - this ignores any AABB override entirely,
     * since it runs before {@code getRenderBoundingBox} is ever consulted, and it's keyed to the Quarry BLOCK's
     * own home section, not the gantry's real drawn extent); (2) the fine per-block-entity frustum test against
     * {@link #getRenderBoundingBox} (already correctly overridden above). Fixing (2) alone only helps once (1)
     * has already let the block entity through - if the player looks away enough that the Quarry's own 16x16x16
     * home section drops out of {@code visibleSections()}, the whole gantry disappears regardless, even while
     * large parts of it (per the frame's real extent) are still genuinely on-screen.
     * <p>
     * {@code shouldRenderOffScreen()} is vanilla's own purpose-built bypass for exactly this (confirmed via
     * {@code ClientLevel.onBlockEntityAdded}: any renderer returning {@code true} here gets its block entity
     * added to a separate {@code globallyRenderedBlockEntities} set, extracted every frame independent of section
     * visibility - {@code BlockEntityRenderDispatcher.tryExtractRenderState} then STILL runs the real
     * {@link #getRenderBoundingBox} frustum test against it, so this doesn't disable culling, it just moves the
     * Quarry out of the coarse section-based path and into the fine, real-extent-based one). Default {@code
     * getViewDistance()} (64 blocks, unmodified here) still caps how far from the CAMERA the Quarry's own anchor
     * block may be before this stops applying - untouched since no observed Quarry size has needed more. */
    @Override
    public boolean shouldRenderOffScreen() {
        return true;
    }
}
