package com.buildcraft.transport.pipe.behaviour;

import com.buildcraft.transport.pipe.PipeBehaviour;

/** Ports {@code PipeBehaviourVoid}: anything reaching the center is destroyed. No GUI, no routing logic needed.
 * Real source's {@code moveFluidToCentre} zeroes the fluid array before it reaches centre - same shared class
 * backs both the item and fluid pipe in real source; {@link #destroysFluids} ports that same effect here. */
public final class VoidBehaviour implements PipeBehaviour {
    public static final VoidBehaviour INSTANCE = new VoidBehaviour();

    private VoidBehaviour() {}

    @Override
    public boolean destroysItems() {
        return true;
    }

    @Override
    public boolean destroysFluids() {
        return true;
    }

    /** Real {@code BCTransportConfig}: Void's fluid pipe transfers 80 mB/tick, delay 10 (fluid still flows INTO
     * it at this rate - {@link #destroysFluids} is what makes it vanish once there, not a rate of zero). */
    @Override
    public int fluidTransferPerTick() {
        return 80;
    }
}
