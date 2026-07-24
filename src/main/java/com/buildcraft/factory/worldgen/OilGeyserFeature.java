package com.buildcraft.factory.worldgen;

import com.mojang.serialization.Codec;

import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;

import com.buildcraft.factory.FactoryFluids;

/**
 * Ports the real {@code OilGenStructure.Spout} + {@code OilGenerator.createSphere} combo
 * ({@code OilGenerator.java}, BuildCraft 1.12.2) - deliberately NOT a copy of vanilla's symmetric
 * {@code minecraft:lake} blob (an earlier, incorrect first pass used that). Real source builds an underground
 * spherical oil reservoir, then punches a narrow vertical shaft ("spout") from that pocket all the way up
 * through solid terrain to the real surface, filled entirely with oil top to bottom - given the pocket sits
 * well underground, a single narrow shaft alone easily holds 100+ buckets before even counting the sphere.
 * <p>
 * The above-surface portion deviates from a literal port on user request (real source only ever stacks 1-2
 * abrupt cylinder segments - medium wells, the common case, had zero width variation at all, a flat 1-wide
 * pillar). Two rounds of feedback shaped this: first, replace the abrupt segments with a smooth per-block-
 * height cone that flares at the base and tapers to a point at the tip ("spreads downward, 1 at the tippy
 * top, like water"); second, add real shape VARIETY rather than just one cone silhouette, so wells don't all
 * look the same. {@link #fillChimney} picks one of 6 distinct shape families per well (see their individual
 * javadocs), each with its own continuous size randomization on top of the shared size roll - real variety
 * through combination, not "dozens" of near-identical bespoke algorithms.
 * <p>
 * Frequency/spacing/biome tiering is handled entirely at the datapack level now (see
 * {@link OilSpacingPlacement} and the 3 biome-tiered {@code placed_feature}/{@code biome_modifier} pairs this
 * feature is wired into - common/marine-bonus/arid-rare), not in this class.
 * <p>
 * Deliberately NOT ported (documented scope gaps, see fuel-status memory): the probabilistic flood-fill surface
 * "tendril" lake every real well also gets (the mod's existing {@code oil_lake_surface}/{@code oil_lake.json}
 * vanilla-Lake-based JSON feature already provides a simplified surface-pool analogue instead), and the real
 * bottom-of-the-world {@code TileSpringOil} infinite spring block large wells get at Y=0 (needs its own
 * block/block-entity, not built yet in this port).
 */
public class OilGeyserFeature extends Feature<NoneFeatureConfiguration> {
    public OilGeyserFeature(Codec<NoneFeatureConfiguration> codec) {
        super(codec);
    }

    @Override
    public boolean place(FeaturePlaceContext<NoneFeatureConfiguration> context) {
        WorldGenLevel level = context.level();
        RandomSource random = context.random();
        BlockPos origin = context.origin();

        BlockState oilState = FactoryFluids.OIL_BLOCK.get().defaultBlockState();

        // Skewed toward small (product of two uniform rolls), so most wells are modest and big ones are rare -
        // gives continuous size variety instead of a rigid two-tier binary.
        float sizeRoll = random.nextFloat() * random.nextFloat();
        int sphereRadius = 4 + Math.round(sizeRoll * 12); // 4-16, matching real source's 4-16 large/medium span
        int shaftRadius = sizeRoll > 0.7f ? 1 : 0; // keep the deep shaft narrow, matching real spout radii

        fillSphere(level, origin, sphereRadius, oilState);

        int surfaceY = level.getHeight(Heightmap.Types.WORLD_SURFACE_WG, origin.getX(), origin.getZ()) - 1;
        fillShaft(level, origin.getX(), origin.getZ(), origin.getY(), surfaceY, shaftRadius, oilState);

        int chimneyHeight = 8 + Math.round(sizeRoll * 24); // 8-32 blocks tall
        int chimneyBaseRadius = Math.max(shaftRadius + 1, 1 + Math.round(sizeRoll * 3)); // 1-4, always >= shaft
        fillChimney(level, origin.getX(), origin.getZ(), surfaceY, chimneyHeight, chimneyBaseRadius, random, oilState);

        return true;
    }

    private static void fillSphere(WorldGenLevel level, BlockPos center, int radius, BlockState state) {
        double radiusSq = radius * radius + 0.01;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (dx * dx + (double) dy * dy + dz * dz <= radiusSq) {
                        cursor.setWithOffset(center, dx, dy, dz);
                        if (level.isInsideBuildHeight(cursor)) {
                            level.setBlock(cursor, state, 2);
                        }
                    }
                }
            }
        }
    }

    private static void fillShaft(WorldGenLevel level, int centerX, int centerZ, int minY, int maxY, int radius, BlockState state) {
        int radiusSq = radius * radius;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int y = minY; y <= maxY; y++) {
            fillDisc(level, cursor, centerX, y, centerZ, radius, radiusSq, state);
        }
    }

    /** Picks one of 6 distinct above-ground shape families per well, each with its own randomization. */
    private static void fillChimney(WorldGenLevel level, int x, int z, int baseY, int height, int baseRadius, RandomSource random, BlockState state) {
        switch (random.nextInt(6)) {
            case 0 -> fillCone(level, x, z, baseY, height, baseRadius, state);
            case 1 -> fillDome(level, x, z, baseY, height, baseRadius, state);
            case 2 -> fillCrater(level, x, z, baseY, baseRadius, random, state);
            case 3 -> fillVentCluster(level, x, z, baseY, height, baseRadius, random, state);
            case 4 -> fillLeaningSpout(level, x, z, baseY, height, baseRadius, random, state);
            default -> fillTerracedMound(level, x, z, baseY, height, baseRadius, state);
        }
    }

    /** Simple cone: linear taper from a wide base to a single point at the tip. */
    private static void fillCone(WorldGenLevel level, int x, int z, int baseY, int height, int baseRadius, BlockState state) {
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int i = 0; i <= height; i++) {
            int r = Math.round(baseRadius * (1.0f - i / (float) height));
            fillDisc(level, cursor, x, baseY + i, z, r, r * r, state);
        }
    }

    /** Dome/bulb: full-width base (guarantees the whole footprint is replaced, not just a point), bulging out
     *  further around the middle third, then tapering to a point at the tip. */
    private static void fillDome(WorldGenLevel level, int x, int z, int baseY, int height, int baseRadius, BlockState state) {
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int i = 0; i <= height; i++) {
            float t = i / (float) height;
            float taper = 1.0f - t; // guarantees base (t=0) = full radius, tip (t=1) = 0
            float bulge = (float) Math.sin(t * Math.PI) * 0.5f;
            int r = Math.round(baseRadius * (taper + bulge));
            fillDisc(level, cursor, x, baseY + i, z, r, r * r, state);
        }
    }

    /** Crater/surface seep: a short, wide, mostly-flat pool with a slightly raised rim - like a tar pit. The
     *  base layers are always fully replaced through the whole footprint (including any water there) - the
     *  rim is purely an ADDITIVE raised lip on top, never a shrink that would leave part of the base untouched. */
    private static void fillCrater(WorldGenLevel level, int x, int z, int baseY, int baseRadius, RandomSource random, BlockState state) {
        int poolRadius = baseRadius + 2 + random.nextInt(3);
        int poolRadiusSq = poolRadius * poolRadius;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        fillDisc(level, cursor, x, baseY, z, poolRadius, poolRadiusSq, state);
        fillDisc(level, cursor, x, baseY + 1, z, poolRadius, poolRadiusSq, state);
        int rimInner = Math.max(1, poolRadius - 2);
        int rimInnerSq = rimInner * rimInner;
        for (int dx = -poolRadius; dx <= poolRadius; dx++) {
            for (int dz = -poolRadius; dz <= poolRadius; dz++) {
                int distSq = dx * dx + dz * dz;
                if (distSq > rimInnerSq && distSq <= poolRadiusSq) {
                    cursor.set(x + dx, baseY + 2, z + dz);
                    if (level.isInsideBuildHeight(cursor)) {
                        level.setBlock(cursor, state, 2);
                    }
                }
            }
        }
    }

    /** Multi-vent cluster: the main cone plus 2-4 smaller offset cones nearby, like several seeping vents. */
    private static void fillVentCluster(WorldGenLevel level, int x, int z, int baseY, int height, int baseRadius, RandomSource random, BlockState state) {
        fillCone(level, x, z, baseY, height, baseRadius, state);
        int vents = 2 + random.nextInt(3);
        int spread = baseRadius * 3 + 3;
        for (int v = 0; v < vents; v++) {
            int offsetX = random.nextInt(spread) - spread / 2;
            int offsetZ = random.nextInt(spread) - spread / 2;
            int ventHeight = Math.max(2, height / 3 + random.nextInt(height / 2 + 1));
            int ventRadius = Math.max(1, baseRadius - 1 - random.nextInt(2));
            fillCone(level, x + offsetX, z + offsetZ, baseY, ventHeight, ventRadius, state);
        }
    }

    /** Leaning/twisted spout: a cone whose center drifts sideways with height, like it's curving as it rises. */
    private static void fillLeaningSpout(WorldGenLevel level, int x, int z, int baseY, int height, int baseRadius, RandomSource random, BlockState state) {
        double driftAngle = random.nextDouble() * Math.PI * 2;
        double driftPerBlock = 0.15 + random.nextDouble() * 0.2;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int i = 0; i <= height; i++) {
            int r = Math.round(baseRadius * (1.0f - i / (float) height));
            int cx = x + (int) Math.round(Math.cos(driftAngle) * driftPerBlock * i);
            int cz = z + (int) Math.round(Math.sin(driftAngle) * driftPerBlock * i);
            fillDisc(level, cursor, cx, baseY + i, cz, r, r * r, state);
        }
    }

    /** Terraced mound: a few discrete flat plateaus narrowing upward, like a rocky ziggurat. */
    private static void fillTerracedMound(WorldGenLevel level, int x, int z, int baseY, int height, int baseRadius, BlockState state) {
        int steps = Math.max(2, Math.min(baseRadius + 1, 4));
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        int y = baseY;
        for (int s = 0; s < steps; s++) {
            int stepHeight = Math.max(1, height / steps);
            int r = Math.round(baseRadius * (1.0f - s / (float) steps));
            for (int i = 0; i < stepHeight && y <= baseY + height; i++, y++) {
                fillDisc(level, cursor, x, y, z, r, r * r, state);
            }
        }
        fillDisc(level, cursor, x, baseY + height, z, 0, 0, state);
    }

    private static void fillDisc(WorldGenLevel level, BlockPos.MutableBlockPos cursor, int centerX, int y, int centerZ, int radius, int radiusSq, BlockState state) {
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (dx * dx + dz * dz <= radiusSq) {
                    cursor.set(centerX + dx, y, centerZ + dz);
                    if (level.isInsideBuildHeight(cursor)) {
                        level.setBlock(cursor, state, 2);
                    }
                }
            }
        }
    }
}
