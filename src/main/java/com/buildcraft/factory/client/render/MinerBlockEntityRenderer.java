package com.buildcraft.factory.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

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
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

import com.buildcraft.Config;
import com.buildcraft.builders.client.render.QuarryBlockEntityRenderer;
import com.buildcraft.factory.blockentity.MinerBlockEntity;

/**
 * Draws the ENTIRE Mining Well/Pump shaft as ONE continuous cosmetic beam, sourced from the machine and growing
 * smoothly every frame - matching {@code QuarryBlockEntity}'s own laser gantry (100% cosmetic, no real placed
 * geometry at all for the visual).
 * <p>
 * History (why this replaced several earlier designs, per explicit user feedback each round): (1) a full-length
 * cosmetic overlay drawn OVER real, VISIBLE placed {@code TubeBlock}s z-fought against them once placement
 * itself became paced. (2) Drawing ONLY the partial leading tip fixed that, but every real block popping in was
 * a visible seam. (3) Making {@code TubeBlock} a plain invisible marker and drawing the whole beam fresh every
 * frame fixed the seam, using a fixed spatial UV per block - geometrically correct, but per user feedback it
 * read as "extends from a new source every block" instead of "one thing flowing out of the machine".
 * <p>
 * (4) The UV mapping is TIME-dependent, not just spatial: {@code v(d, L) = frac(d - L)} for depth {@code d} (0
 * at the machine) and current total length {@code L} - the standard "subtract speed*time from the spatial
 * coordinate" scroll-texture formula, which makes the pattern appear to flow in the {@code +d} direction (away
 * from the machine, toward the tip) for as long as {@code L} is increasing. Since {@code L} only advances while
 * the shaft is actively growing ({@link MinerBlockEntity#paused}/complete freezes it), the WHOLE beam visibly
 * scrolls outward from the machine while extending and goes completely still the instant it stops.
 * <p>
 * (5, Mining-Well-only, explicit user request) {@code drilling}: an octagonal cross-section instead of a square
 * post (as close to round as flat quads reasonably get), twisted along its length like a real drill bit's
 * flutes - {@code rotationDegrees(d) = d * DRILL_DEGREES_PER_BLOCK}, purely a function of depth, so the twist is
 * a static geometric feature (no separate animation system) that also naturally reads as "drilling downward"
 * once combined with (4)'s outward flow. The Pump keeps the plain square beam - only passed {@code true} from
 * its own registration in {@code BuildCraftClient}.
 */
public class MinerBlockEntityRenderer implements BlockEntityRenderer<MinerBlockEntity, MinerRenderState> {
    private static final int LIGHT = 15728880;
    private static final float PX0 = 4f / 16f;
    private static final float PX1 = 12f / 16f;
    private static final int MAX_SEGMENTS = 4096;

    private static final int OCTAGON_SIDES = 8;
    private static final float OCTAGON_RADIUS = 4f / 16f;
    // Was 120 (over 1/3 turn per block) - found via user report ("two different rotating columns"): a twist
    // that fast on an 8-sided shape is a classic wagon-wheel/barber-pole illusion, where the eye groups it into
    // what looks like several interleaved spirals instead of one coherent column. A gentler rate reads as a
    // single continuous twist.
    private static final float DRILL_DEGREES_PER_BLOCK = 30f;
    // Caps how much depth (and therefore how much twist) a single quad spans, so the octagon's rotation looks
    // like a smooth continuous spiral instead of a handful of large, obviously-flat twisted panels.
    private static final double MAX_TWIST_STEP = 1.0 / 8.0;

    private final Identifier texture;
    private final boolean drilling;

    public MinerBlockEntityRenderer(BlockEntityRendererProvider.Context context, Identifier texture, boolean drilling) {
        this.texture = texture;
        this.drilling = drilling;
    }

    @Override
    public MinerRenderState createRenderState() {
        return new MinerRenderState();
    }

    @Override
    public void extractRenderState(MinerBlockEntity blockEntity, MinerRenderState state, float partialTicks, Vec3 cameraPosition,
            ModelFeatureRenderer.@Nullable CrumblingOverlay breakProgress) {
        BlockEntityRenderer.super.extractRenderState(blockEntity, state, partialTicks, cameraPosition, breakProgress);
        state.length = blockEntity.getInterpolatedLength(partialTicks);
    }

    @Override
    public void submit(MinerRenderState state, PoseStack poseStack, SubmitNodeCollector submitNodeCollector, CameraRenderState camera) {
        double length = state.length;
        if (length <= 0.001) {
            return;
        }
        RenderType beamType = cutout(texture);
        if (drilling) {
            submitNodeCollector.submitCustomGeometry(poseStack, beamType, (pose, buffer) -> submitDrillingBeam(pose, buffer, length));
        } else {
            submitNodeCollector.submitCustomGeometry(poseStack, beamType, (pose, buffer) -> submitSquareBeam(pose, buffer, length));
        }
    }

    /** The plain square-post beam (Pump). Walks depth {@code d} from 0 (the machine) down to {@code length},
     * splitting into sub-quads wherever the flowing {@code v(d,L) = frac(d - length)} formula wraps past 1.0 -
     * each individual quad's UV must stay within 0-1 (this pipeline can't safely rely on out-of-range UV
     * wrapping - see QuarryBlockEntityRenderer/RefineryScreen precedent), so a wrap boundary always ends a
     * quad. */
    private static void submitSquareBeam(PoseStack.Pose pose, VertexConsumer buffer, double length) {
        double d = 0;
        for (int guard = 0; d < length - 1.0E-6 && guard < MAX_SEGMENTS; guard++) {
            double v0 = frac(d - length);
            double segmentLength = Math.min(1.0 - v0, length - d);
            double v1 = v0 + segmentLength;
            float top = (float) -d;
            float bottom = (float) -(d + segmentLength);
            walls(pose, buffer, PX0, bottom, PX0, PX1, top, PX1, (float) v0, (float) v1);
            d += segmentLength;
        }
        // Real bug (found via user report - "hyperthin walls"): side walls alone form a HOLLOW, uncapped tube -
        // with nothing filling the open bottom end, the growing tip visibly reveals it's an empty shell rather
        // than a solid rod. Sealing it with a flat bottom cap at the current tip.
        bottomCap(pose, buffer, (float) -length);
    }

    /** The octagonal, twisting "drill" beam (Mining Well only) - same flowing-UV formula as
     * {@link #submitSquareBeam}, but each sub-quad is ALSO capped to {@link #MAX_TWIST_STEP} of depth (on top of
     * the UV-wrap cap) so the octagon's rotation reads as a smooth spiral rather than a few large twisted
     * panels, and 8 side faces are emitted per step instead of 4.
     * <p>
     * Real bug (found via user report - "it extends the first block, then delegates the growth to the next
     * block down, instead of from the source"): rotation used to be {@code d * DEGREES} - a function of depth
     * ALONE, frozen in time once a point exists. That meant only the freshly-growing TIP ever showed new
     * rotation; every already-placed depth's twist was permanently fixed the moment it appeared, so the only
     * thing that visually looked "active" was whatever depth happened to be growing right now - exactly
     * "growth delegates to the next block down" instead of feeling sourced from the machine. Rotation is now
     * {@code (d - length) * DEGREES}, the SAME shifted-coordinate trick as the UV flow formula: a fixed depth's
     * angle keeps changing for as long as {@code length} (anywhere below it) is still increasing, and freezes
     * the instant it stops - so rotation and texture now both flow together, from the source, consistently. */
    private static void submitDrillingBeam(PoseStack.Pose pose, VertexConsumer buffer, double length) {
        double d = 0;
        for (int guard = 0; d < length - 1.0E-6 && guard < MAX_SEGMENTS; guard++) {
            double v0 = frac(d - length);
            double segmentLength = Math.min(Math.min(1.0 - v0, MAX_TWIST_STEP), length - d);
            double v1 = v0 + segmentLength;
            double dEnd = d + segmentLength;

            float[] xTop = new float[OCTAGON_SIDES];
            float[] zTop = new float[OCTAGON_SIDES];
            float[] xBot = new float[OCTAGON_SIDES];
            float[] zBot = new float[OCTAGON_SIDES];
            octagon(xTop, zTop, (float) ((d - length) * DRILL_DEGREES_PER_BLOCK));
            octagon(xBot, zBot, (float) ((dEnd - length) * DRILL_DEGREES_PER_BLOCK));
            float yTop = (float) -d;
            float yBot = (float) -dEnd;

            for (int i = 0; i < OCTAGON_SIDES; i++) {
                int j = (i + 1) % OCTAGON_SIDES;
                quad(pose, buffer,
                        xTop[i], yTop, zTop[i],
                        xTop[j], yTop, zTop[j],
                        xBot[j], yBot, zBot[j],
                        xBot[i], yBot, zBot[i],
                        (float) v0, (float) v1);
            }
            d = dEnd;
        }
        // Real bug (found via user report - "looks like toilet paper stuck to it"): a square cap's CORNERS
        // reach 4/16*sqrt(2) =~ 5.66/16 from center, well past the octagon's own vertices at exactly 4/16 - a
        // real ~41% overhang, not the "negligible" difference originally assumed. Filling the octagon's actual
        // shape instead, as a triangle fan from the center to each pair of adjacent tip vertices.
        float[] xTip = new float[OCTAGON_SIDES];
        float[] zTip = new float[OCTAGON_SIDES];
        octagon(xTip, zTip, 0f); // rotation at d=length is always (length-length)*DEGREES = 0
        octagonCap(pose, buffer, (float) -length, xTip, zTip);
    }

    /** Fills {@code outX}/{@code outZ} (length {@link #OCTAGON_SIDES}) with an octagon centered on the block's
     * middle, rotated by {@code degreesOffset} - as close to a round drill-bit cross-section as flat quads
     * reasonably approximate in Minecraft. */
    private static void octagon(float[] outX, float[] outZ, float degreesOffset) {
        double offsetRad = Math.toRadians(degreesOffset);
        for (int i = 0; i < OCTAGON_SIDES; i++) {
            double angle = offsetRad + i * (2 * Math.PI / OCTAGON_SIDES);
            outX[i] = 0.5f + OCTAGON_RADIUS * (float) Math.cos(angle);
            outZ[i] = 0.5f + OCTAGON_RADIUS * (float) Math.sin(angle);
        }
    }

    private static double frac(double x) {
        return x - Math.floor(x);
    }

    /** Extends the renderer's own culling volume down through the whole real max-mine-depth range - the beam
     * can reach many blocks below this block entity's own position while this one stays off-screen. */
    @Override
    public AABB getRenderBoundingBox(MinerBlockEntity blockEntity) {
        BlockPos pos = blockEntity.getBlockPos();
        int maxDepth = Config.QUARRY_MAX_MINE_DEPTH.get();
        return new AABB(pos.getX(), pos.getY() - maxDepth, pos.getZ(), pos.getX() + 1, pos.getY() + 1, pos.getZ() + 1);
    }

    /** Reuses {@code QuarryBlockEntityRenderer.LASER_UNLIT_CUTOUT_PIPELINE} (unlit, winding-independent shading)
     * so brightness stays consistent regardless of quad winding, matching the safe, already-proven choice used
     * elsewhere in this project. */
    private static RenderType cutout(Identifier texture) {
        RenderSetup setup = RenderSetup.builder(QuarryBlockEntityRenderer.LASER_UNLIT_CUTOUT_PIPELINE)
                .withTexture("Sampler0", texture)
                .useLightmap()
                .useOverlay()
                .createRenderSetup();
        return RenderType.create("buildcraft_miner_shaft", setup);
    }

    /** The 4 vertical faces (no caps - the tip is either inside solid ground/fluid, or still growing) of a box
     * from {@code (x0,y0,z0)} to {@code (x1,y1,z1)}, with V mapped to {@code [v0,v1]}. */
    private static void walls(PoseStack.Pose pose, VertexConsumer buffer, float x0, float y0, float z0, float x1, float y1, float z1,
            float v0, float v1) {
        quad(pose, buffer, x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0, v0, v1); // west
        quad(pose, buffer, x1, y0, z1, x1, y0, z0, x1, y1, z0, x1, y1, z1, v0, v1); // east
        quad(pose, buffer, x1, y0, z0, x0, y0, z0, x0, y1, z0, x1, y1, z0, v0, v1); // north
        quad(pose, buffer, x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1, v0, v1); // south
    }

    private static void quad(PoseStack.Pose pose, VertexConsumer buffer,
            float x1, float y1, float z1, float x2, float y2, float z2,
            float x3, float y3, float z3, float x4, float y4, float z4, float v0, float v1) {
        vertex(pose, buffer, x1, y1, z1, 1, v1);
        vertex(pose, buffer, x2, y2, z2, 0, v1);
        vertex(pose, buffer, x3, y3, z3, 0, v0);
        vertex(pose, buffer, x4, y4, z4, 1, v0);
    }

    /** A flat SQUARE cap filling the cross-section at height {@code y} - seals the open end of
     * {@link #submitSquareBeam}'s hollow side-wall tube so the tip reads as a solid rod instead of an empty
     * shell. */
    private static void bottomCap(PoseStack.Pose pose, VertexConsumer buffer, float y) {
        quad(pose, buffer, PX0, y, PX0, PX1, y, PX0, PX1, y, PX1, PX0, y, PX1, 0f, 1f);
    }

    /** A flat OCTAGON cap (a center-point triangle fan, one triangle per side) filling the drill beam's actual
     * cross-section at height {@code y} - unlike {@link #bottomCap}'s square, this matches the octagon's real
     * shape exactly, with no corners overhanging past the round profile. Each triangle is a degenerate quad
     * (the center point repeated as both the first and last corner) - {@link #quad} already collapses cleanly
     * to a triangle when 2 of its 4 corners coincide. */
    private static void octagonCap(PoseStack.Pose pose, VertexConsumer buffer, float y, float[] x, float[] z) {
        float cx = 0.5f;
        float cz = 0.5f;
        for (int i = 0; i < OCTAGON_SIDES; i++) {
            int j = (i + 1) % OCTAGON_SIDES;
            quad(pose, buffer, cx, y, cz, x[i], y, z[i], x[j], y, z[j], cx, y, cz, 0f, 1f);
        }
    }

    private static void vertex(PoseStack.Pose pose, VertexConsumer builder, float x, float y, float z, float u, float v) {
        builder.addVertex(pose, x, y, z).setColor(-1).setUv(u, v).setOverlay(OverlayTexture.NO_OVERLAY).setLight(LIGHT).setNormal(0, 1, 0);
    }
}
