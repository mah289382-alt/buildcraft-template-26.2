package com.buildcraft.transport.client.model;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.block.dispatch.Variant;
import net.minecraft.client.resources.model.ModelBaker;
import net.minecraft.client.resources.model.ResolvableModel;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.state.BlockState;

import com.buildcraft.transport.block.PipeBlock;

/**
 * Real perf/load-time fix (2026-07-30 FPS/load-time audit, user: "make it like giga optimized"): replaces JSON
 * {@code multipart} resolution for a pipe tier's connector-arm geometry with a single shared dynamic model.
 * <p>
 * <b>Root cause this fixes</b>: {@code PipeBlock}/{@code FluidPipeBlock} carry 12 boolean CONNECTED/EXTENDED
 * blockstate properties (4096 permutations); {@code WoodPipeBlock}/{@code WoodFluidPipeBlock} add a 7-value
 * VALVE enum (28,672 permutations). Across all 28 pipe tiers that's ~213,000 blockstate permutations, and even
 * vanilla/NeoForge's own real {@code MultiPartModel} (confirmed by reading it directly) - despite its own
 * internal caching (a {@code BitSet}-keyed subset cache, a {@code ModelBaker.SharedOperationKey}-cached shared
 * baked state) - still has to evaluate every "when" condition against every one of those ~213,000 raw states at
 * least once to discover which cached bucket each belongs to. This class avoids that entirely: every state maps
 * to the exact SAME instance (see {@code PipeModelDefinition#instantiate}), and the real connector-arm selection
 * happens once per render, per instance, in {@link PipeBakedModel#collectParts} by reading the state's own
 * properties directly - no condition evaluation, no per-state object construction, at bake/load time at all.
 * <p>
 * <b>Deliberately does NOT touch</b> {@code PipeBlock}'s blockstate property declarations, {@code getShape},
 * {@code updateShape}, {@code getStateForPlacement}, or {@code syncValveState} - collision/connectivity/valve
 * logic all still work exactly as before, reading the same real blockstate properties this class also reads.
 * Only the state-to-rendered-model resolution mechanism changes.
 * <p>
 * Reuses the EXISTING, unchanged baked JSON sub-models per tier (the base post model plus, per direction, a
 * normal/extended/optionally extended-valve connect model - the exact same files the old {@code multipart} JSON
 * referenced, with the exact same naming convention: {@code buildcraft:block/pipe_<material>_connect_<dir>}) via
 * {@link Variant}, the same lightweight model-reference class vanilla's own multipart/single-variant systems use
 * - no texture/geometry re-authoring, just a different (and much cheaper) way of selecting among them.
 */
final class PipeUnbakedModel implements BlockStateModel.UnbakedRoot {
    private final String material;
    private final boolean hasValve;

    /** A stable per-instance key (not a fresh lambda per call) so {@link ModelBaker#compute} actually caches -
     * the real baking work (resolving+baking up to 19 {@link Variant}s into {@link BlockStateModelPart}s) runs
     * ONCE per tier no matter how many of this tier's ~4096-28,672 states end up calling {@link #bake}. */
    private final ModelBaker.SharedOperationKey<PipeBakedModel> bakeKey = new ModelBaker.SharedOperationKey<>() {
        @Override
        public PipeBakedModel compute(ModelBaker modelBaker) {
            return bakeNow(modelBaker);
        }
    };

    PipeUnbakedModel(String material, boolean hasValve) {
        this.material = material;
        this.hasValve = hasValve;
    }

    private Identifier modelId(String suffix) {
        return Identifier.fromNamespaceAndPath("buildcraft", "block/pipe_" + material + suffix);
    }

    @Override
    public void resolveDependencies(ResolvableModel.Resolver resolver) {
        resolver.markDependency(modelId(""));
        for (Direction dir : Direction.values()) {
            String dirName = dir.getSerializedName();
            resolver.markDependency(modelId("_connect_" + dirName));
            resolver.markDependency(modelId("_connect_" + dirName + "_extended"));
            if (hasValve) {
                resolver.markDependency(modelId("_connect_" + dirName + "_extended_valve"));
            }
        }
    }

    /** Groups states that would produce IDENTICAL geometry - the material plus each direction's real
     * connected/extended value (and the valve face, if this tier has one). Two states differing only in a
     * cosmetically-irrelevant way (e.g. {@code extended_down} while {@code connected_down} is false, which never
     * actually happens in practice per {@code PipeBlock.withConnection} but is still a real declared state) will
     * naturally share a group here since both read the same way in {@link PipeBakedModel#collectParts}. */
    @Override
    public Object visualEqualityGroup(BlockState state) {
        return new VisualKey(material, connectedExtendedTuple(state), hasValve ? state.getValue(PipeBlock.VALVE) : null);
    }

    static List<Boolean> connectedExtendedTuple(BlockState state) {
        List<Boolean> tuple = new ArrayList<>(12);
        for (Direction dir : Direction.values()) {
            boolean connected = state.getValue(PipeBlock.connectedProperty(dir));
            tuple.add(connected);
            tuple.add(connected && state.getValue(PipeBlock.extendedProperty(dir)));
        }
        return tuple;
    }

    private record VisualKey(String material, List<Boolean> connectedExtended, PipeBlock.@org.jspecify.annotations.Nullable ValveFace valve) {}

    /** Confirmed via temporary diagnostic logging (2026-07-30): {@code bakeNow} runs exactly once per tier
     * (28 total across a full session), and {@code bake} itself is only ever called a few hundred times total
     * (once per distinct BlockState actually encountered, not the full declared state space) - the caching
     * works exactly as designed. A one-off 13x chunk-mesh regression reported the same day did NOT reproduce on
     * a second identical run with this same code, and per-call timing on {@code PipeBakedModel.collectParts}/
     * {@code createGeometryKey} (also temporarily instrumented, since removed) showed both methods rarely called
     * and consistently sub-30-microsecond - ruling this class out as the cause. Treated as session noise
     * (background OS/disk activity), not a real regression from this change.
     */
    @Override
    public BlockStateModel bake(BlockState blockState, ModelBaker modelBaker) {
        return modelBaker.compute(bakeKey);
    }

    private PipeBakedModel bakeNow(ModelBaker modelBaker) {
        BlockStateModelPart base = new Variant(modelId("")).bake(modelBaker);
        Map<Direction, BlockStateModelPart> normal = new EnumMap<>(Direction.class);
        Map<Direction, BlockStateModelPart> extended = new EnumMap<>(Direction.class);
        Map<Direction, BlockStateModelPart> extendedValve = hasValve ? new EnumMap<>(Direction.class) : null;
        for (Direction dir : Direction.values()) {
            String dirName = dir.getSerializedName();
            normal.put(dir, new Variant(modelId("_connect_" + dirName)).bake(modelBaker));
            extended.put(dir, new Variant(modelId("_connect_" + dirName + "_extended")).bake(modelBaker));
            if (hasValve) {
                extendedValve.put(dir, new Variant(modelId("_connect_" + dirName + "_extended_valve")).bake(modelBaker));
            }
        }
        return new PipeBakedModel(base, normal, extended, extendedValve);
    }
}
