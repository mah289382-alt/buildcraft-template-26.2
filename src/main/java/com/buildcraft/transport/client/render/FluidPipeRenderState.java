package com.buildcraft.transport.client.render;

import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;

/** What the fluid-content renderer needs each frame - real per-section (CENTER + 6 arms, index by
 * {@code Direction.ordinal()}, index 6 = center) fill amounts, plus the resolved fluid sprite's atlas UV + real
 * tint - same sprite/tint lookup technique as {@code com.buildcraft.factory.client.render.TankRenderState}, now
 * driving 7 independently-sized boxes instead of one flat indicator (see {@code FluidPipeBlockEntityRenderer}'s
 * javadoc for how closely this follows real {@code PipeFlowRendererFluids}, and for why there's no flow-offset
 * animation field here any more). */
public class FluidPipeRenderState extends BlockEntityRenderState {
    public static final int SECTION_COUNT = 7;
    public static final int CENTER_INDEX = 6;

    public boolean hasFluid;
    public int capacity;
    public final int[] amount = new int[SECTION_COUNT];
    public final boolean[] connected = new boolean[6];
    /** Real {@code Section.ticksInDirection} sign per face, index by {@code Direction.ordinal()} - negative =
     * actively receiving fluid IN that face, positive = actively pushing fluid OUT, 0 = neutral. Lets the
     * renderer show the centre cavity filling directionally (input side toward output side) for a straight-
     * through pipe run instead of growing symmetrically with no sense of which way the fluid is actually moving. */
    public final int[] direction = new int[6];
    /** Real {@code Section.flowStartTick} per face - the absolute game tick that face's CURRENT continuous flow
     * began, letting the renderer anchor a one-shot "fills in, then stays full" animation to a real starting
     * point (see {@code FluidPipeBlockEntityRenderer}'s javadoc). */
    public final long[] flowStart = new long[6];
    /** {@code Section.firstObservedTick}, index by {@code Direction.ordinal()} PLUS {@link #CENTER_INDEX} (7
     * entries, unlike {@link #flowStart} which only covers faces - the centre section has no real {@code
     * flowStartTick} of its own at all, so its fork-junction reveal is driven ENTIRELY by this field). See
     * {@code FluidPipeBlockEntity#getFirstObservedTick}'s javadoc for why the renderer needs this alongside
     * {@link #flowStart} to avoid a newly-joined pipe popping in already-full. */
    public final long[] firstObserved = new long[SECTION_COUNT];
    /** Real {@code Section.lastKnownSign} per face - like {@link #direction} but never decays back to 0 just
     * because a single tick's real transfer happened to miss (see {@code FluidPipeBlockEntityRenderer}'s "flicker
     * after a certain amount of blocks" fix). Used ONLY to orient which way a still-full arm should visually fill
     * from when {@link #direction} has gone quiet but {@link #amount} says it's still genuinely holding fluid. */
    public final int[] lastSign = new int[6];
    /** Absolute {@code gameTime + partialTicks}, captured once per frame - a pure function of real elapsed time,
     * with no per-section stored/accumulated phase to drift or desync. */
    public float time;
    public float spriteU0, spriteU1, spriteV0, spriteV1;
    public int tintColor = 0xFFFFFFFF;
}
