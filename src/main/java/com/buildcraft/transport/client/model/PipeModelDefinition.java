package com.buildcraft.transport.client.model;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.neoforged.neoforge.client.model.block.CustomBlockModelDefinition;

/**
 * Real perf/load-time fix (2026-07-30 FPS/load-time audit) - see {@link PipeUnbakedModel}'s own javadoc for the
 * full mechanism. This is the top-level hook: a pipe tier's blockstate JSON file now just references this
 * definition (via NeoForge's {@code "neoforge:definition_type"} dispatch, confirmed by reading
 * {@code BlockStateModelHooks.makeDefinitionCodec} directly in the NeoForge 26.2 sources) instead of a
 * {@code multipart} block listing 13-19 "when" conditions - collapsing what used to be up to 28,672 individually
 * resolved blockstate permutations per tier down to ONE shared dynamic model per tier.
 * <p>
 * {@link #instantiate} maps EVERY state in the block's real {@link StateDefinition} (unchanged - still needed
 * for real collision-shape/connectivity logic in {@code PipeBlock}, none of which this change touches) to the
 * exact SAME {@link PipeUnbakedModel} instance. The actual connector-arm selection (which of the existing,
 * unchanged 13-19 baked sub-models per tier apply) happens per-instance at render time in
 * {@link PipeBakedModel#collectParts}, reading the real {@code BlockState} passed in directly - not via JSON
 * {@code when}-clause evaluation, and not via any new block-entity/ModelData lookup.
 */
public record PipeModelDefinition(String material, boolean valve) implements CustomBlockModelDefinition {
    public static final MapCodec<PipeModelDefinition> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            Codec.STRING.fieldOf("material").forGetter(PipeModelDefinition::material),
            Codec.BOOL.optionalFieldOf("valve", false).forGetter(PipeModelDefinition::valve)).apply(instance, PipeModelDefinition::new));

    @Override
    public Map<BlockState, BlockStateModel.UnbakedRoot> instantiate(StateDefinition<Block, BlockState> states, Supplier<String> sourceSupplier) {
        PipeUnbakedModel shared = new PipeUnbakedModel(material, valve);
        Map<BlockState, BlockStateModel.UnbakedRoot> result = new HashMap<>();
        for (BlockState state : states.getPossibleStates()) {
            result.put(state, shared);
        }
        return result;
    }

    @Override
    public MapCodec<? extends CustomBlockModelDefinition> codec() {
        return CODEC;
    }
}
