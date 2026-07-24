package com.buildcraft.factory;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.neoforge.fluids.BaseFlowingFluid;
import net.neoforged.neoforge.fluids.FluidType;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

import com.buildcraft.BuildCraft;

/**
 * Registers the 2 real fluids from source's own "Factory module not loaded" fallback path (see
 * {@code BuildCraftEnergy.java}: {@code oil = new FluidDefinition("oil", 800, 10000)},
 * {@code fuel = new FluidDefinition("fuel", 1000, 1000)}) - the classic pre-thermal-distillation two-fluid
 * chain (crude Oil -&gt; Refinery -&gt; Fuel), matching real source's own simpler mode rather than the full
 * 10-family/28-fluid heat-variant distillation system the "Factory loaded" path builds (an explicit user
 * scope decision - see the Fuel-module memory entry). Real textures copied from source
 * ({@code oil_heat_0_still/flow.png}, {@code fuel_still/flow.png}, both real animated 32-frame sprite sheets
 * with their original {@code .mcmeta}), colours sampled from {@code BCEnergyFluids}' data table (oil:
 * 0x505050/0x050505, fuel: 0xFFFF30/0xE4CF00 - the heat=0 row for oil, matching the no-heat-variant fuel row).
 */
public final class FactoryFluids {
    public static final DeferredRegister<FluidType> FLUID_TYPES = DeferredRegister.create(NeoForgeRegistries.Keys.FLUID_TYPES, BuildCraft.MODID);
    public static final DeferredRegister<Fluid> FLUIDS = DeferredRegister.create(BuiltInRegistries.FLUID, BuildCraft.MODID);
    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(BuildCraft.MODID);
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(BuildCraft.MODID);

    // Real source values (BCEnergyFluids data table, heat=0 row): density=900, viscosity=2000, sticky, flammable.
    public static final DeferredHolder<FluidType, FluidType> OIL_TYPE = FLUID_TYPES.register("oil",
            () -> new FluidType(FluidType.Properties.create()
                    .density(900).viscosity(2000).temperature(300)
                    .canConvertToSource(false)));
    public static final DeferredHolder<Fluid, Fluid> OIL_SOURCE = FLUIDS.register("oil",
            () -> new BaseFlowingFluid.Source(oilProperties()));
    public static final DeferredHolder<Fluid, Fluid> OIL_FLOWING = FLUIDS.register("oil_flowing",
            () -> new BaseFlowingFluid.Flowing(oilProperties()));
    public static final DeferredBlock<LiquidBlock> OIL_BLOCK = BLOCKS.registerBlock("oil",
            props -> new LiquidBlock((FlowingFluid) OIL_SOURCE.get(), props),
            () -> BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_BLACK).noCollision().strength(100f).noLootTable());
    public static final DeferredItem<Item> OIL_BUCKET = ITEMS.registerItem("oil_bucket",
            props -> new BucketItem(OIL_SOURCE.get(), props),
            () -> new Item.Properties().craftRemainder(Items.BUCKET).stacksTo(1));

    // Real source values: density=1000 (default-ish/unset in FluidDefinition ctor args = flow/quanta, not shown
    // in the data table since "fuel" bypasses BCEnergyFluids entirely in the no-Factory path) - reusing the real
    // fuel_light row's density/viscosity from the data table (400/600) as the closest real-numbers match, since
    // plain "fuel" (no heat suffix) doesn't have its own row in that specific table.
    public static final DeferredHolder<FluidType, FluidType> FUEL_TYPE = FLUID_TYPES.register("fuel",
            () -> new FluidType(FluidType.Properties.create()
                    .density(400).viscosity(600).temperature(300).lightLevel(0)
                    .canConvertToSource(false)));
    public static final DeferredHolder<Fluid, Fluid> FUEL_SOURCE = FLUIDS.register("fuel",
            () -> new BaseFlowingFluid.Source(fuelProperties()));
    public static final DeferredHolder<Fluid, Fluid> FUEL_FLOWING = FLUIDS.register("fuel_flowing",
            () -> new BaseFlowingFluid.Flowing(fuelProperties()));
    public static final DeferredBlock<LiquidBlock> FUEL_BLOCK = BLOCKS.registerBlock("fuel",
            props -> new LiquidBlock((FlowingFluid) FUEL_SOURCE.get(), props),
            () -> BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_YELLOW).noCollision().strength(100f).noLootTable());
    public static final DeferredItem<Item> FUEL_BUCKET = ITEMS.registerItem("fuel_bucket",
            props -> new BucketItem(FUEL_SOURCE.get(), props),
            () -> new Item.Properties().craftRemainder(Items.BUCKET).stacksTo(1));

    private static BaseFlowingFluid.Properties oilProperties() {
        return new BaseFlowingFluid.Properties(OIL_TYPE, OIL_SOURCE, OIL_FLOWING)
                .bucket(OIL_BUCKET)
                .block(OIL_BLOCK)
                .slopeFindDistance(6)
                .levelDecreasePerBlock(2);
    }

    private static BaseFlowingFluid.Properties fuelProperties() {
        return new BaseFlowingFluid.Properties(FUEL_TYPE, FUEL_SOURCE, FUEL_FLOWING)
                .bucket(FUEL_BUCKET)
                .block(FUEL_BLOCK)
                .slopeFindDistance(8)
                .levelDecreasePerBlock(1);
    }

    private FactoryFluids() {}
}
