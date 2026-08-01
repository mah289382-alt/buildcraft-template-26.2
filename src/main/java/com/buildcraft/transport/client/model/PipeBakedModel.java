package com.buildcraft.transport.client.model;

import java.util.List;
import java.util.Map;

import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

import com.buildcraft.transport.block.PipeBlock;

/**
 * The actual per-render connector-arm composition - see {@link PipeUnbakedModel}'s javadoc for the full "why".
 * One shared instance per pipe tier (not per blockstate permutation).
 */
final class PipeBakedModel implements BlockStateModel {
    private final BlockStateModelPart base;
    private final Map<Direction, BlockStateModelPart> normal;
    private final Map<Direction, BlockStateModelPart> extended;
    private final @Nullable Map<Direction, BlockStateModelPart> extendedValve;

    PipeBakedModel(BlockStateModelPart base, Map<Direction, BlockStateModelPart> normal, Map<Direction, BlockStateModelPart> extended,
            @Nullable Map<Direction, BlockStateModelPart> extendedValve) {
        this.base = base;
        this.normal = normal;
        this.extended = extended;
        this.extendedValve = extendedValve;
    }

    /** Confirmed via temporary diagnostic logging (2026-07-30, investigating a one-off measured 13x chunk-mesh
     * regression reported the same day): this method is called only a handful of times per session (once per
     * pipe instance actually meshed, not per frame), and each call consistently completes in well under 30
     * microseconds. Ruled out as the cause of the regression, which did not reproduce on a second identical run
     * - treated as session noise (background OS/disk activity), not a real consequence of this class. */
    @Override
    public void collectParts(BlockAndTintGetter level, BlockPos pos, BlockState state, RandomSource random, List<BlockStateModelPart> parts) {
        parts.add(base);
        PipeBlock.ValveFace valve = extendedValve != null ? state.getValue(PipeBlock.VALVE) : PipeBlock.ValveFace.NONE;
        for (Direction dir : Direction.values()) {
            if (!state.getValue(PipeBlock.connectedProperty(dir))) {
                continue;
            }
            if (extendedValve != null && valve == PipeBlock.ValveFace.of(dir)) {
                parts.add(extendedValve.get(dir));
            } else if (state.getValue(PipeBlock.extendedProperty(dir))) {
                parts.add(extended.get(dir));
            } else {
                parts.add(normal.get(dir));
            }
        }
    }

    /** Lets the engine's own cross-instance geometry cache (see {@code BlockStateModelExtension#createGeometryKey}'s
     * own doc, "connected textures" is the example given) share the composed quad list across every pipe in the
     * world with the exact same connections - never keyed on {@code pos}/{@code random}, only the values that
     * actually change the output. */
    @Override
    public @Nullable Object createGeometryKey(BlockAndTintGetter level, BlockPos pos, BlockState state, RandomSource random) {
        PipeBlock.ValveFace valve = extendedValve != null ? state.getValue(PipeBlock.VALVE) : null;
        return new GeometryKey(this, PipeUnbakedModel.connectedExtendedTuple(state), valve);
    }

    private record GeometryKey(PipeBakedModel model, List<Boolean> connectedExtended, PipeBlock.@Nullable ValveFace valve) {}

    @Override
    public void collectParts(RandomSource random, List<BlockStateModelPart> output) {
        // Deprecated/legacy fallback (see BlockStateModel's own interface javadoc) - the real, current render
        // path always goes through the 5-arg overload above, which has the real BlockState to read. This is
        // never expected to be hit in practice; falling back to just the unconnected post is a safe default
        // rather than throwing.
        output.add(base);
    }

    @Override
    public Material.Baked particleMaterial() {
        return base.particleMaterial();
    }

    @Override
    public int materialFlags() {
        return base.materialFlags();
    }
}
