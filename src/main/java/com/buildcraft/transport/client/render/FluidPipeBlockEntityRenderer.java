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
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.fluid.FluidTintSource;
import net.neoforged.neoforge.transfer.fluid.FluidResource;
import org.jspecify.annotations.Nullable;

import com.buildcraft.transport.block.PipeBlock;
import com.buildcraft.transport.blockentity.FluidPipeBlockEntity;

/**
 * A real, near-faithful port of {@code PipeFlowRendererFluids} - draws a CENTRE cavity plus up to 6 connected
 * arms representing a pipe's real per-{@code Section} flow state (see {@code FluidPipeBlockEntity}'s own
 * javadoc for the full 7-section flow-engine this renders).
 * <p>
 * <b>Reset from scratch (2026-07-27) per explicit user spec</b>, replacing every earlier animation attempt
 * (edge-position nudge, sine sway, travelling marker, static taper-only, UV-scrolling texture - each had a real,
 * user-reported problem in its own way). The user's own 2-part spec, implemented directly:
 * <ul>
 * <li>(A) Travels orientation-correct from one end to the other, filling the pipe at its real, FIXED
 * cross-section (the same {@code POST0..POST1} interior every arm/centre box already uses elsewhere in this
 * project) - never tapered, never larger or smaller than the pipe's own real bounds.
 * <li>(B) Moves through the connected arms as ONE continuous body: it fills the ENTIRE path (input arm -> centre
 * -> output arm, as one single coordinate) from the entry side toward the exit side.
 * </ul>
 * <b>No reset/cycle, per direct follow-up ("remove the reset part of the animation just let it fill no
 * reset")</b>: an earlier version of this class had the fill wave immediately followed by a symmetric drain
 * wave retreating from the entry side, then both repeating forever. That's gone - {@link #renderArmWave} now
 * anchors the fill to the REAL absolute tick a section's current flow began ({@code
 * FluidPipeBlockEntity.Section#flowStartTick}, set once per genuine flow episode, not every tick), fills once
 * over {@link #CROSS_TICKS}, and then simply STAYS full for as long as that section keeps actively flowing -
 * no wrap, no wraparound math needed at all, since there's no repeating cycle left to wrap.
 * <p>
 * <b>Real bug fixed (user: "it goes smoothly perfectly then after a certain amount of blocks it changes its
 * behaviour to be a different flicker blink type... audit all the code to see if there are multiple behaviour
 * states")</b>: every render-eligibility check in {@link #submit} used to gate on the LIVE {@code
 * FluidPipeRenderState#direction} signal (real {@code ticksInDirection}, which by design can read exactly 0
 * between individual real per-tick transfers, not just once flow genuinely stops - see {@code
 * FluidPipeBlockEntity.Section#save}'s own comment on the earlier {@code flowStartTick} sync bug, which already
 * self-documented this exact mechanism: "the render's direction!=0 gate flickering on/off as sync packets arrived
 * irregularly"). A section that's genuinely still full of fluid mid-flow would render as NOTHING AT ALL on any
 * tick real delivery happened to miss - and real delivery reliability is structurally more likely to waver the
 * farther a section is from the true source (more hops through proportional splitting/backpressure = more
 * chances for one tick's transfer to land on 0), matching "smooth near the start, flickers further along a long
 * run." Every gate now checks real content ({@code amount > 0}) instead of the live direction signal; {@link
 * FluidPipeRenderState#lastSign} (sticky, sourced from {@code Section#lastKnownSign}, never resets to 0 just
 * because the live signal did) is used ONLY to orient which way a still-full arm should visually fill from.
 * <p>
 * <b>Full unification (user: "there are multiple competing behaviours A: moves correctly slowly B: a flicker
 * blink to the next half pipe C: when a fork comes a thin square moves toward each direction of the fork")</b>:
 * every earlier round up to this one kept THREE genuinely different geometry techniques alive at once - a single
 * unified span for a straight 2-arm run, a 3-phase proportional span for a 2-arm corner, and an independent
 * per-arm fallback for anything else (3+-way forks, or the instant a 3rd pipe joined an existing 2-arm run) -
 * selected by {@code dominantDirection}, which picked the "in"/"out" pair from the LIVE {@code
 * FluidPipeRenderState#direction} signal. That signal is BY DESIGN allowed to read 0 between individual real
 * per-tick transfers even while a section is genuinely still full (see the flicker-audit entry above) - so an
 * already-flowing straight/corner run could silently drop out of its own dominant-pair selection and fall into
 * the fallback technique for a frame or two, then back again, every time delivery happened to skip a tick. Three
 * different-looking animations, each individually reasonable, flickering between each other on ONE pipe is
 * exactly "multiple competing behaviours" - (A) was the unified/cornered path when selection held, (B) was the
 * flip BETWEEN techniques (which don't agree on intermediate geometry, only at 0%/100%), (C) was the fallback's
 * own per-arm boxes, correctly described as thin slabs growing toward the middle.
 * <p>
 * <b>Real regression found and fixed the same day (user: "its not sliding from 1 end to another like normal")</b>:
 * the first version of this fix gave EVERY arm its own full {@link #CROSS_TICKS} to cross, extended to reach all
 * the way through the centre - correct for eliminating the mode-switch, but it silently changed the confirmed-
 * good straight/corner PACING too. The old single-span design covered the WHOLE pipe (both arms + centre, the
 * full 1.0-block span) in ONE {@link #CROSS_TICKS}; decomposing that into 2 independent full-{@code CROSS_TICKS}
 * calls doubled the total crossing time and, worse, made the SECOND call start a brand new 0%-to-100% growth from
 * a point already inside the first call's already-filled region - visually: fills most of the pipe, seems to
 * pause, then a second growth pushes out from partway inside to the far edge. Not a continuous slide.
 * <p>
 * Fixed properly by going back to ONE SHARED timeline per pipe (matching the old design's actual rate: the whole
 * 1.0-block pipe crosses in exactly one {@link #CROSS_TICKS}), but deriving it WITHOUT needing to select "the"
 * dominant in/out pair - {@link #submit} anchors to the EARLIEST currently-active INPUT face's own {@code
 * effectiveFlowStart} (sticky-sign-based, not live-signal-based, so it can't flicker), then splits that ONE
 * timeline into the same proportional phases the old corner code used ({@link #ARM_FRAC} then {@link
 * #CENTRE_FRAC} then {@link #ARM_FRAC}, summing to exactly 1.0 of {@link #CROSS_TICKS}): every active INPUT face
 * independently renders phase 1 (its own arm, outer -> post), every active OUTPUT face independently renders
 * phase 3 (post -> outer), both driven by the SAME shared elapsed time - no per-arm anchor needed for output at
 * all (the {@code +VISUAL_CROSS_TICKS} server-side chaining already guarantees an output face's own {@code
 * flowStartTick} lines up with the input's own {@code +CROSS_TICKS}, so deriving everything from the input's
 * anchor alone reproduces the exact same instant). A straight run or corner therefore looks EXACTLY like the old
 * confirmed-smooth single span again (same total 1-block-per-{@code CROSS_TICKS} rate, no seam), while a fork
 * with several simultaneous inputs/outputs just has several arms independently reading the same shared phase -
 * no selection to destabilize, since there's no longer any "which 2 are dominant" question being asked at all.
 * {@link #renderStaticCentre} now simply always renders the centre as soon as it holds content (no separate
 * timing needed for one small internal cube - see its own javadoc).
 */
public class FluidPipeBlockEntityRenderer implements BlockEntityRenderer<FluidPipeBlockEntity, FluidPipeRenderState> {
    private static final int LIGHT = 15728880;
    private static final float POST0 = 4f / 16f;
    private static final float POST1 = 12f / 16f;
    /** Real bug fixed (user-reported "too large in volume... flickering just outside the pipe"): the fluid
     * box's cross-section was exactly {@code POST0..POST1}, the SAME bounds as the pipe's own solid connector-arm
     * frame geometry (real {@code FluidPipeBlock.ARM_SHAPES}/post cavity) - two coincident surfaces at the exact
     * same depth is a real z-fight, the same category of bug already found and fixed for the Tank block
     * elsewhere in this project (its own {@code FLUID_INSET} constant). {@link #CROSS0}/{@link #CROSS1} are the
     * fluid box's own slightly-smaller cross-section, used instead of {@link #POST0}/{@link #POST1} for the 2
     * axes NOT currently being filled along. */
    private static final float CROSS_INSET = 1f / 32f;
    private static final float CROSS0 = POST0 + CROSS_INSET;
    private static final float CROSS1 = POST1 - CROSS_INSET;
    /** Ticks for the fill wave to cross the full entry-to-exit span, once - 2s at 20 tps. After that it just
     * stays full (no drain, no repeat - see class javadoc). Reads {@link FluidPipeBlockEntity#VISUAL_CROSS_TICKS}
     * rather than keeping its own separate value - {@code propagateFlowAnchors} (server-side) chains {@code
     * flowStartTick} across block boundaries using that exact same rate, so the two must never drift apart. */
    private static final float CROSS_TICKS = FluidPipeBlockEntity.VISUAL_CROSS_TICKS;
    /** Fraction of one full {@link #CROSS_TICKS} crossing spent inside a single arm (block edge -> post boundary)
     * - equal to {@link #POST0} itself ("quarter of a block" in the same 0..1 unit space). Together with {@link
     * #CENTRE_FRAC} (twice, once per side) splits the ONE shared per-pipe timeline into entry-arm / centre /
     * exit-arm phases proportional to real physical distance - see class javadoc's "not sliding like normal" fix. */
    private static final float ARM_FRAC = POST0;
    /** Fraction of the crossing spent inside the centre post cavity - the remainder after both arm fractions. */
    private static final float CENTRE_FRAC = POST1 - POST0;

    public FluidPipeBlockEntityRenderer(BlockEntityRendererProvider.Context context) {
    }

    @Override
    public FluidPipeRenderState createRenderState() {
        return new FluidPipeRenderState();
    }

    /** Real perf fix (FPS audit, 2026-07-30): the default {@code getViewDistance()} is 64 blocks - reasonable
     * for large/important renderers (the Quarry's laser+gantry, say), but wasteful for a small internal fill
     * detail nobody can actually perceive at that range, especially in a base with a large fluid-pipe network
     * (this renderer manually builds up to ~7 boxes/frame per pipe with no caching - see class javadoc - so
     * every pipe beyond visual relevance is pure wasted CPU). Halved to 32, comfortably past normal render-
     * distance-limited gameplay visibility of a detail this small. */
    @Override
    public int getViewDistance() {
        return 32;
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
        state.capacity = blockEntity.getSectionCapacity();
        for (Direction dir : Direction.values()) {
            int i = dir.ordinal();
            state.amount[i] = (int) blockEntity.getInterpolatedAmount(dir, partialTicks);
            state.connected[i] = blockEntity.getBlockState().hasProperty(PipeBlock.connectedProperty(dir))
                    && blockEntity.getBlockState().getValue(PipeBlock.connectedProperty(dir));
            state.direction[i] = blockEntity.getDirectionTicks(dir);
            state.flowStart[i] = blockEntity.getFlowStartTick(dir);
            state.lastSign[i] = blockEntity.getLastKnownSign(dir);
            state.firstObserved[i] = blockEntity.getFirstObservedTick(dir);
        }
        state.amount[FluidPipeRenderState.CENTER_INDEX] = (int) blockEntity.getInterpolatedAmount(null, partialTicks);
        state.firstObserved[FluidPipeRenderState.CENTER_INDEX] = blockEntity.getFirstObservedTick(null);

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
        state.time = (level == null ? 0L : level.getGameTime()) + partialTicks;
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
        if (!state.hasFluid || state.capacity <= 0) {
            return;
        }
        RenderType fluidType = cutout(TextureAtlas.LOCATION_BLOCKS);
        // ONE shared timeline for the whole pipe, anchored to whichever INPUT face started earliest.
        // Real bug fixed (user: "moves through pipe smooth then blinks once it reaches the next pipe instead of
        // just continuing to move"): this used to also require amount[i]>0 on the input face to qualify - but a
        // pipe's own input face genuinely drains to 0 once its content has moved on into the centre/output (the
        // normal, expected outcome of it doing its job), right around the same time the front is transitioning
        // into the NEXT pipe. The moment that happened, this loop stopped finding a qualifying input and fell
        // back to the OUTPUT face's own anchor instead - which the server deliberately sets a full CROSS_TICKS
        // LATER than the input's (see propagateFlowAnchors) - so originAnchor jumped forward by a whole
        // CROSS_TICKS in one frame, making elapsed suddenly much smaller and the output arm's own fraction jump
        // backward before catching back up: a real, visible blink. lastSign (sticky) and the server's own {@code
        // flowStartTick} both stay valid and unchanged after a face's amount drains (only {@code
        // firstObservedTick} resets on amount<=0, and {@link #effectiveFlowStart}'s max() already falls back
        // gracefully to the still-valid flowStart when that happens) - so dropping the amount>0 requirement here
        // keeps this pipe's own timeline anchor fixed for its whole flow episode, not just while its input
        // happens to still be non-empty.
        long originAnchor = Long.MAX_VALUE;
        boolean hasInput = false;
        for (Direction dir : Direction.values()) {
            int i = dir.ordinal();
            if (state.connected[i] && state.lastSign[i] < 0) {
                hasInput = true;
                originAnchor = Math.min(originAnchor, effectiveFlowStart(state, dir));
            }
        }
        if (!hasInput) {
            // Rare edge case: this pipe has never had an input-signed face at all (e.g. a purely-relaying tier
            // with only an output ever set) - fall back to the earliest face of ANY sign so output arms still
            // have something stable to anchor to instead of rendering nothing.
            for (Direction dir : Direction.values()) {
                int i = dir.ordinal();
                if (state.connected[i] && state.lastSign[i] != 0) {
                    originAnchor = Math.min(originAnchor, effectiveFlowStart(state, dir));
                }
            }
        }
        long finalOriginAnchor = originAnchor;
        boolean finalHasInput = hasInput;
        submitNodeCollector.submitCustomGeometry(poseStack, fluidType, (pose, buffer) -> {
            if (finalOriginAnchor == Long.MAX_VALUE) {
                return;
            }
            // Real bug fixed (user: "travels through the half cell smoothly then pauses and completely fills the
            // next full cell then moves slowly again... i dont want the sudden fill"): the centre used to be a
            // separate, unanimated static cube that just popped in fully-formed once the timeline gate opened -
            // smooth arm, sudden full centre, smooth arm again, repeating every single pipe. Fixed below by
            // having each INPUT face's own wave grow CONTINUOUSLY through the centre instead of stopping at the
            // post boundary (see renderArmWave) - so there's no separate "cell" that pops, just one uninterrupted
            // growth the whole way. renderStaticCentre is kept ONLY as a last-resort fallback for the rare tail
            // case where the centre still holds residual content but its own input face has already fully
            // drained (amount 0) so no arm is left to carry the growth through it.
            // <p>
            // Real bug fixed (user: screenshot showing "a thin square ahead of" the advancing flow at a fork
            // branch): this used to re-derive "does this pipe have an active input arm" PER FRAME from the LIVE,
            // noisy {@code amount[i] > 0} - but for a low-throughput branch (e.g. a fork splitting flow 3 ways),
            // the real per-tick delayed-insertion buffer genuinely oscillates a section's amount between 0 and a
            // few units tick to tick (confirmed via the debug log: a face logged amt=4,2,0 across 3 consecutive
            // ticks while its centre held 500+ and kept growing) - a real, legitimate, expected dynamic, not a
            // bug in the simulation. On any tick that trickle happened to read exactly 0, this fell into the
            // static-centre fallback and popped an instant, unanimated cube completely disconnected from the
            // still-genuinely-active arm that simply wasn't rendering THAT one frame - exactly the "thin square"
            // artifact. Fixed by reusing {@code finalHasInput} (computed above from the STICKY {@code lastSign},
            // not the noisy live {@code amount}) instead of re-deriving a per-frame flag here - stable across
            // amount's natural oscillation, so the fallback only ever fires for a pipe that's never genuinely had
            // an input face at all, not one that's mid-trickle.
            for (Direction dir : Direction.values()) {
                int i = dir.ordinal();
                if (state.connected[i] && state.amount[i] > 0 && state.lastSign[i] != 0) {
                    renderArmWave(pose, buffer, state, dir, finalOriginAnchor);
                }
            }
            if (!finalHasInput) {
                float elapsed = state.time - finalOriginAnchor;
                if (elapsed > 0f && elapsed / CROSS_TICKS >= ARM_FRAC) {
                    renderStaticCentre(pose, buffer, state);
                }
            }
        });
    }

    /** Last-resort fallback (see {@link #submit}) - a plain, unanimated static full {@link #POST0}..{@link
     * #POST1} cube, only ever drawn when the centre holds residual content but no input arm is currently active
     * to carry a continuous growth through it (see class javadoc's "black hole into a star" fix for why an
     * earlier ALWAYS-drawn growth-based version was reverted, and the "sudden fill" fix for why this is no
     * longer the normal/common path at all). */
    private static void renderStaticCentre(PoseStack.Pose pose, VertexConsumer buffer, FluidPipeRenderState state) {
        if (state.amount[FluidPipeRenderState.CENTER_INDEX] <= 0) {
            return;
        }
        fluidBox(pose, buffer, CROSS0, CROSS0, CROSS0, CROSS1, CROSS1, CROSS1, state);
    }

    /**
     * One connected arm's own share of the ONE shared per-pipe timeline (see {@link #submit}) - an INPUT face
     * (sticky sign negative) renders phase 1+2 COMBINED as one continuous growth (elapsed fraction {@code
     * [0, ARM_FRAC+CENTRE_FRAC]} of {@link #CROSS_TICKS}), filling outer (block edge) all the way through to the
     * FAR post boundary on the opposite side of the centre cavity (arriving from outside and continuing straight
     * through, with no stop/pop at the near post boundary - see class javadoc's "sudden fill" fix for why this
     * used to stop there and hand off to a separate, unanimated centre box instead). An OUTPUT face (positive)
     * renders during phase 3 ({@code [ARM_FRAC+CENTRE_FRAC, 1]}) - but ALSO reaches all the way back through to
     * the FAR side of the centre on ITS OWN axis (far -> outer), not just its own short arm (post -> outer).
     * <p>
     * <b>Real gap found and fixed (user: "if there is a fork it does that thin square maneuver past the
     * fork")</b>: for a straight run or corner, the INPUT face's own reach already grows across the WHOLE centre
     * on its own axis, so the output side never needed to widen anything itself. But at a genuine fork, an output
     * arm typically sits on a DIFFERENT axis than the input - and since the centre's cross-section on any axis
     * NOT actively being grown always stays at the narrow default {@link #CROSS0}/{@link #CROSS1}, the centre
     * never widened on the output's own axis at all. The input's reach looked like a thin beam travelling
     * straight past the branch point, with the other fork arms appearing to sprout from nothing once their own
     * phase began. Making every output ALSO grow across the full centre on its own axis means every axis that has
     * ANY active arm - input or output - gets properly widened, so a fork's branches read as genuinely connected
     * to the centre instead of floating disconnected pieces. For the already-confirmed straight-run case this is
     * harmless redundancy (the input already covers that exact same axis/region by the time output starts, so the
     * union is unchanged); for a corner it's a genuine improvement (the centre is now flush on BOTH axes, not just
     * the input's, fixing a previously-unnoticed step where the exit arm was narrower than the centre it met).
     * <p>
     * <b>Real regression found and fixed the same day (user: "when doing turns the pipe has this thin blue
     * square randomly")</b>: the first version of the fix above grew the output's box as ONE combined span from
     * {@code far} (the FAR side of the centre) toward {@code outer}, using {@code far} as the {@code entry}
     * (fixed, non-growing) end. But {@code far} sits just OUTSIDE the default {@link #CROSS0}/{@link #CROSS1}
     * cross-section every other box in this renderer uses (by exactly {@link #CROSS_INSET}) - so the instant this
     * span starts growing, it's a tiny sliver sitting at {@code far}, not touching or overlapping ANY other
     * geometry (the input's own box doesn't reach that far on this axis, and the output's own arm hasn't drawn
     * yet either) - a real, visibly disconnected "thin square" floating near the centre boundary for the whole
     * ~0.5s it takes to grow large enough to finally merge with something. Fixed by anchoring output's growth at
     * {@code post} instead (exactly where its own arm attaches - see {@link #renderSpan}'s two calls below) and
     * growing in BOTH directions from there simultaneously: one span {@code post -> outer} (the arm itself) and
     * one {@code post -> far} (reaching back into the centre), sharing the identical {@code frac} so they're
     * ALWAYS joined at {@code post} from the very first frame of growth - never a moment where either piece is
     * disconnected from anything, matching the same "always anchored to already-visible geometry" property the
     * INPUT side already had for free (it naturally starts at {@code outer}, the world boundary shared with
     * whatever's feeding it).
     * <p>
     * Both phases derived from the SAME {@code originAnchor}, so a straight run or corner reads as one continuous
     * motion the whole way, not separate independently-timed pieces. Orientation reads {@link
     * FluidPipeRenderState#lastSign} (sticky) rather than {@link FluidPipeRenderState#direction} (live, can read
     * 0 between individual real transfers even while the section is genuinely still full - see class javadoc's
     * "flicker after a certain amount of blocks" fix).
     */
    private static void renderArmWave(PoseStack.Pose pose, VertexConsumer buffer, FluidPipeRenderState state, Direction dir, long originAnchor) {
        boolean flowingOut = state.lastSign[dir.ordinal()] > 0;
        boolean positive = dir.getAxisDirection() == Direction.AxisDirection.POSITIVE;
        float outer = positive ? 1f : 0f;
        float post = positive ? POST1 : POST0;
        float far = positive ? POST0 : POST1;
        float elapsed = state.time - originAnchor;
        if (elapsed <= 0f) {
            return;
        }
        float t = elapsed / CROSS_TICKS;
        if (flowingOut) {
            float frac = clamp01((t - ARM_FRAC - CENTRE_FRAC) / ARM_FRAC);
            renderSpan(pose, buffer, state, post, outer, dir.getAxis(), frac);
            renderSpan(pose, buffer, state, post, far, dir.getAxis(), frac);
            return;
        }
        float frac = clamp01(t / (ARM_FRAC + CENTRE_FRAC));
        renderSpan(pose, buffer, state, outer, far, dir.getAxis(), frac);
    }

    private static float clamp01(float v) {
        return Math.max(0f, Math.min(1f, v));
    }

    /**
     * {@code max(getFlowStartTick, getFirstObservedTick)} for one face - see {@code
     * FluidPipeBlockEntity#getFirstObservedTick}'s javadoc for the full reasoning (user: "cant flow smoothly if
     * new pipes are added while its flowing... blinks forward"). The server-derived {@link
     * FluidPipeRenderState#flowStart} is correct and necessary for an already-converged network (including
     * resuming after a dormant pause), but can land in the past relative to "now" for a pipe that's JUST joining
     * a flow that's already been running a while - taking the max with this client's own {@link
     * FluidPipeRenderState#firstObserved} timestamp guarantees a newly-joined face never renders as further along
     * than "the moment this client first saw it holding real content", without touching the server chaining
     * logic at all.
     * <p>
     * <b>Defensive clamp added, then REMOVED after a real bug it caused (user: "a thin blue square that spawns
     * about a pipe block ahead... as if its a magnet attracting it")</b>: a same-day round added {@code
     * Math.min(anchor, (long) state.time)} here to guard against a corrupted, wildly future-dated {@code
     * flowStartTick} left over from an earlier (now-fixed) drift bug. That guard had a real, precise bug of its
     * own: {@code (long) state.time} TRUNCATES the fractional {@code partialTicks} component, while {@link
     * #renderArmWave}'s own {@code elapsed = state.time - originAnchor} uses the FULL float {@code state.time}.
     * Every downstream face's real {@code flowStart} is INTENTIONALLY a little in the future relative to "now"
     * for as long as the visual front hasn't reached it yet (that's the entire point of the hop-based pacing -
     * see {@code FluidPipeBlockEntity#propagateFlowAnchors}) - a normal, constant, expected condition, not a rare
     * edge case. Whenever it fired (i.e. on basically every hop, every frame, the whole time a pipe is legitimately
     * "not yet reached"), the clamp forced the anchor down to the truncated integer tick, leaving {@code elapsed}
     * equal to just the leftover fractional part - a tiny but POSITIVE number instead of the correct negative one -
     * which slipped past {@code renderArmWave}'s {@code elapsed <= 0} gate and rendered a barely-there sliver: a
     * "thin square" appearing roughly one hop ahead of the real front, constantly, on every single pipe. Removed
     * entirely rather than patched - the corrupted-data scenario it guarded against is rare (an old save from
     * before the server-side drift bugs were fixed) and self-heals on its own once real time naturally catches up
     * to the bad value, whereas this bug affected EVERY pipe on EVERY frame of ordinary flow.
     */
    private static long effectiveFlowStart(FluidPipeRenderState state, Direction dir) {
        int i = dir.ordinal();
        return Math.max(state.flowStart[i], state.firstObserved[i]);
    }

    /**
     * Draws one growing box along a single {@code axis}, from {@code entry} toward {@code exit}, stopping at
     * {@code frac} of the way there (0 = nothing, 1 = the full {@code entry..exit} span) - the shared geometry
     * primitive behind {@link #renderArmWave}'s phase-local slice of the one shared per-pipe timeline. The 2
     * axes NOT passed in {@code axis} always stay at the pipe's fixed {@link #CROSS0}/{@link #CROSS1}
     * cross-section, exactly like every other box this renderer draws - never tapered, never resized.
     */
    private static void renderSpan(PoseStack.Pose pose, VertexConsumer buffer, FluidPipeRenderState state,
            float entry, float exit, Direction.Axis axis, float frac) {
        if (frac <= 0f) {
            return;
        }
        float x0 = CROSS0, x1 = CROSS1, y0 = CROSS0, y1 = CROSS1, z0 = CROSS0, z1 = CROSS1;
        float headWorld = entry + (exit - entry) * frac;
        float lo = Math.min(entry, headWorld);
        float hi = Math.max(entry, headWorld);
        if (hi <= lo) {
            return;
        }
        switch (axis) {
            case X -> { x0 = lo; x1 = hi; }
            case Y -> { y0 = lo; y1 = hi; }
            case Z -> { z0 = lo; z1 = hi; }
        }
        fluidBox(pose, buffer, x0, y0, z0, x1, y1, z1, state);
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
            FluidPipeRenderState state) {
        float u0 = state.spriteU0, u1 = state.spriteU1, v0 = state.spriteV0, v1 = state.spriteV1;
        int color = state.tintColor;
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
