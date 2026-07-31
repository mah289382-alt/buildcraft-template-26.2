package com.buildcraft.transport.pipe.behaviour;

import com.buildcraft.transport.pipe.PipeBehaviour;

/**
 * Ported from {@code PipeBehaviourSandstone}: connects freely to any other pipe ({@code canConnect(face,
 * PipeBehaviour)} always true) but NEVER to an inventory/tile ({@code canConnect(face, TileEntity)} always
 * false) - a pure pipe-to-pipe relay. Shares Stone's speed constant in the original
 * ({@code SPEED_DELTA = PipeBehaviourStone.SPEED_DELTA}), so it reuses Stone's {@code ticksPerPhase} here too.
 */
public final class SandstoneBehaviour implements PipeBehaviour {
    public static final SandstoneBehaviour INSTANCE = new SandstoneBehaviour();

    private SandstoneBehaviour() {}

    @Override
    public int ticksPerPhase() {
        return PassiveBehaviour.STONE.ticksPerPhase();
    }

    @Override
    public boolean connectsToContainers() {
        return false;
    }

    /** Real {@code BCTransportConfig}: Sandstone's fluid pipe transfers {@code baseFlowRate*2}=20 mB/tick, delay
     * 10 (same rate as Stone - confirmed in source, both use the doubled base rate). */
    @Override
    public int fluidTransferPerTick() {
        return 20;
    }
}
