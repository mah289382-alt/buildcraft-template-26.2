package com.buildcraft.transport.pipe.behaviour;

import org.jspecify.annotations.Nullable;

import com.buildcraft.transport.pipe.PipeBehaviour;

/**
 * The "just move items through, no filtering or forced direction" behaviour shared by Cobblestone, Stone,
 * Quartz, and Gold pipes in the original - the first three extend {@code PipeBehaviourSeparate} (only connect
 * to another pipe of the exact same material - see {@code separateGroup}/{@link #canConnectToPipe}), while Gold
 * has no special routing logic at all, differing only in how fast items travel. Cobblestone is the true
 * baseline "plain pipe" (confirmed via source: it's the cheapest, most basic passive router - NOT Wood, which is
 * actually a directional MJ-powered extractor, a real distinction this port previously got wrong by calling the
 * baseline behaviour "Wood").
 * <p>
 * {@code fluidRate}/{@code fluidDelay} are the real per-material {@code BCTransportConfig.fluidTransfer(...)}
 * numbers (Cobblestone=10/10, Stone=20/10, Quartz=40/10, Gold=80/2 - Gold's real fluid speed advantage is
 * entirely the lower delay, not a special routing rule - confirmed directly in source, its
 * {@code PipeBehaviourGold} class has no fluid-specific override at all) - the SAME shared instance backs both
 * this material's item pipe AND its fluid pipe in real source, matching this reuse here.
 */
public record PassiveBehaviour(int ticksPerPhase, @Nullable String separateGroup, int fluidRate, int fluidDelay) implements PipeBehaviour {
    public static final PassiveBehaviour COBBLESTONE = new PassiveBehaviour(5, "cobblestone", 10, 10);
    public static final PassiveBehaviour STONE = new PassiveBehaviour(6, "stone", 20, 10);
    public static final PassiveBehaviour QUARTZ = new PassiveBehaviour(8, "quartz", 40, 10);
    public static final PassiveBehaviour GOLD = new PassiveBehaviour(2, null, 80, 2);

    @Override
    public int ticksPerPhase() {
        return ticksPerPhase;
    }

    @Override
    public int fluidTransferPerTick() {
        return fluidRate;
    }

    @Override
    public int fluidTransferDelay() {
        return fluidDelay;
    }

    /**
     * Ported from {@code PipeBehaviourSeparate.canConnect}: if this pipe belongs to a "Separate" family
     * (non-null group), only connect to another pipe that's ALSO Separate-family AND has the exact same group;
     * connects freely to anything non-Separate (Gold, Iron, Void, ...). A non-Separate instance (Gold) always
     * connects freely, matching the original never overriding {@code canConnect} at all.
     * <p>
     * Real bug found and fixed (2026-08-03 QC pass, see {@code PipeBlock.setPlacedBy}'s own javadoc for the full
     * story): {@code other} can genuinely be {@code null} - {@code PipeBlock.canConnectPipes} passes it when a
     * pipe's own behaviour isn't resolvable yet (mid-placement, before its block entity exists). The old code
     * treated a null {@code other} the same as "a non-Separate material" and connected freely - wrong for a
     * Separate-family material specifically, since "I don't know what's over there yet" must NOT be assumed
     * compatible. Now explicitly conservative: unknown means refuse, not connect - matches this method's own
     * safe default and gets corrected for real once the placing pipe's own block entity exists anyway.
     */
    @Override
    public boolean canConnectToPipe(@Nullable PipeBehaviour other) {
        if (separateGroup == null) {
            return true;
        }
        if (other == null) {
            return false;
        }
        if (other instanceof PassiveBehaviour otherPassive && otherPassive.separateGroup != null) {
            return separateGroup.equals(otherPassive.separateGroup);
        }
        return true;
    }
}
