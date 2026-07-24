package com.buildcraft.factory.worldgen;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;
import net.minecraft.world.level.levelgen.placement.PlacementModifierType;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

import com.buildcraft.BuildCraft;

/** Registers the real {@link OilGeyserFeature} and {@link OilSpacingPlacement} (see their javadocs for the source these port). */
public final class FactoryWorldgen {
    public static final DeferredRegister<Feature<?>> FEATURES = DeferredRegister.create(BuiltInRegistries.FEATURE, BuildCraft.MODID);
    public static final DeferredRegister<PlacementModifierType<?>> PLACEMENT_MODIFIER_TYPES =
            DeferredRegister.create(BuiltInRegistries.PLACEMENT_MODIFIER_TYPE, BuildCraft.MODID);

    public static final DeferredHolder<Feature<?>, OilGeyserFeature> OIL_GEYSER =
            FEATURES.register("oil_geyser", () -> new OilGeyserFeature(NoneFeatureConfiguration.CODEC));

    public static final DeferredHolder<PlacementModifierType<?>, PlacementModifierType<OilSpacingPlacement>> OIL_SPACING =
            PLACEMENT_MODIFIER_TYPES.register("oil_spacing", () -> () -> OilSpacingPlacement.CODEC);

    private FactoryWorldgen() {}
}
