package com.buildcraft.transport.pipe.behaviour;

import com.buildcraft.transport.pipe.PipeBehaviour;

/**
 * Ported from {@code PipeBehaviourStructure}: an empty class in the original with no overrides at all - a
 * purely cosmetic connector pipe. Every {@link PipeBehaviour} default already matches that (free routing,
 * connects to anything, no speed modifier), so this class exists only to give it its own identity/instance.
 */
public final class StructureBehaviour implements PipeBehaviour {
    public static final StructureBehaviour INSTANCE = new StructureBehaviour();

    private StructureBehaviour() {}
}
