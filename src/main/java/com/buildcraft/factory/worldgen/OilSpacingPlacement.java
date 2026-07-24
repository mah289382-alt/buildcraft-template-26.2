package com.buildcraft.factory.worldgen;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.placement.PlacementContext;
import net.minecraft.world.level.levelgen.placement.PlacementFilter;
import net.minecraft.world.level.levelgen.placement.PlacementModifierType;

/**
 * Keeps oil wells from clustering in nearby chunks. Real BuildCraft's own {@code OilGenerator} rolls an
 * independent probability per chunk with no minimum-distance enforcement at all - it really can cluster.
 * User feedback after testing: wanted wells spread out, not bunched into the same handful of chunks. This
 * uses the exact jittered-grid technique vanilla itself uses to space out structures
 * ({@code RandomSpreadStructurePlacement.getPotentialStructureChunk} - verified by reading that class
 * directly): the world is divided into {@code spacing}-chunk square cells; each cell deterministically (seeded
 * by world seed + cell coordinates + a salt, so different {@code salt} values don't correlate with each other)
 * picks exactly one "slot" chunk somewhere within a {@code spacing - separation} sized sub-range; only that one
 * chunk in the whole cell is allowed through. Guarantees a hard minimum spacing between any two wells sharing
 * the same salt, while still looking naturally scattered rather than grid-aligned.
 */
public class OilSpacingPlacement extends PlacementFilter {
    public static final MapCodec<OilSpacingPlacement> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            Codec.intRange(1, 4096).fieldOf("spacing").forGetter(p -> p.spacing),
            Codec.intRange(0, 4096).fieldOf("separation").forGetter(p -> p.separation),
            Codec.INT.fieldOf("salt").forGetter(p -> p.salt)
    ).apply(instance, OilSpacingPlacement::new));

    private final int spacing;
    private final int separation;
    private final int salt;

    public OilSpacingPlacement(int spacing, int separation, int salt) {
        this.spacing = spacing;
        this.separation = separation;
        this.salt = salt;
    }

    @Override
    protected boolean shouldPlace(PlacementContext context, RandomSource random, BlockPos pos) {
        int chunkX = pos.getX() >> 4;
        int chunkZ = pos.getZ() >> 4;
        int cellX = Math.floorDiv(chunkX, spacing);
        int cellZ = Math.floorDiv(chunkZ, spacing);

        WorldgenRandom cellRandom = new WorldgenRandom(new LegacyRandomSource(0L));
        cellRandom.setLargeFeatureWithSalt(context.getLevel().getSeed(), cellX, cellZ, salt);
        int limit = spacing - separation;
        int slotX = cellRandom.nextInt(limit);
        int slotZ = cellRandom.nextInt(limit);

        return chunkX == cellX * spacing + slotX && chunkZ == cellZ * spacing + slotZ;
    }

    @Override
    public PlacementModifierType<?> type() {
        return FactoryWorldgen.OIL_SPACING.get();
    }
}
