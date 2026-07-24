package com.buildcraft;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.ModConfigSpec;

// An example config class. This is not required, but it's a good idea to have one to keep your config organized.
// Demonstrates how to use Neo's config APIs
public class Config {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.BooleanValue LOG_DIRT_BLOCK = BUILDER
            .comment("Whether to log the dirt block on common setup")
            .define("logDirtBlock", true);

    public static final ModConfigSpec.IntValue MAGIC_NUMBER = BUILDER
            .comment("A magic number")
            .defineInRange("magicNumber", 42, 0, Integer.MAX_VALUE);

    public static final ModConfigSpec.ConfigValue<String> MAGIC_NUMBER_INTRODUCTION = BUILDER
            .comment("What you want the introduction message to be for the magic number")
            .define("magicNumberIntroduction", "The magic number is... ");

    // a list of strings that are treated as resource locations for items
    public static final ModConfigSpec.ConfigValue<List<? extends String>> ITEM_STRINGS = BUILDER
            .comment("A list of items to log on common setup.")
            .defineListAllowEmpty("items", List.of("minecraft:iron_ingot"), () -> "", Config::validateItemName);

    static {
        BUILDER.push("quarry");
    }

    public static final ModConfigSpec.IntValue QUARRY_ENERGY_CAPACITY = BUILDER
            .comment("How much FE the Quarry's internal energy buffer can hold.")
            .defineInRange("energyCapacity", 1_000_000, 1_000, Integer.MAX_VALUE);

    public static final ModConfigSpec.IntValue QUARRY_MAX_FE_PER_TICK = BUILDER
            .comment("The maximum FE the Quarry can draw from its buffer per tick (at full battery; see energy ramp-up).")
            .defineInRange("maxFePerTick", 2_000, 1, Integer.MAX_VALUE);

    // Matches the original's BlockUtil.computeBlockBreakPower: cost = C * (hardness + 1). The "+1" means even a
    // hardness-0 block (e.g. dirt) still costs something, not just the multiplier's floor.
    public static final ModConfigSpec.IntValue QUARRY_ENERGY_PER_HARDNESS_POINT = BUILDER
            .comment("FE cost to mine a block = this * (hardness + 1). Applies both to normal mining and to "
                    + "clearing obstacles out of the Quarry's own frame area.")
            .defineInRange("energyPerHardnessPoint", 200, 0, Integer.MAX_VALUE);

    public static final ModConfigSpec.IntValue QUARRY_FRAME_BLOCK_COST = BUILDER
            .comment("FE cost to place a single Frame block.")
            .defineInRange("frameBlockCost", 200, 0, Integer.MAX_VALUE);

    // Matches the original's real MJ ratio between TaskMoveDrill.getTarget() (distance * 20 * MJ) and
    // TaskBreakBlock.getTarget() via BlockUtil.computeBlockBreakPower (16 * MJ * (hardness+1) * 2 * miningMultiplier
    // = 32 * MJ * (hardness+1) at the default multiplier of 1): moveCost / breakCostPerHardnessPoint = 20/32 = 0.625.
    // At energyPerHardnessPoint=200 that's 125, so breaking stone (hardness 1.5) costs 500 FE against a 125 FE
    // move - a 4:1 ratio, matching the original exactly (was previously 400, an invented value with a 2:1 ratio -
    // moving was proportionally ~3.2x more expensive relative to breaking than in the original).
    public static final ModConfigSpec.IntValue QUARRY_MOVE_COST_PER_BLOCK = BUILDER
            .comment("FE cost for the drill to travel one block, while repositioning between mining targets.")
            .defineInRange("moveCostPerBlock", 125, 0, Integer.MAX_VALUE);

    public static final ModConfigSpec.IntValue QUARRY_MAX_MINE_DEPTH = BUILDER
            .comment("How many blocks below the frame's bottom layer the Quarry is allowed to dig, at most.")
            .defineInRange("maxMineDepth", 32, 1, 4096);

    public static final ModConfigSpec.IntValue QUARRY_FRAME_HEIGHT = BUILDER
            .comment("The height (in blocks) of the Quarry's frame, for both the default area and marker-defined areas.")
            .defineInRange("frameHeight", 4, 1, 256);

    // Matches the original's quarryMaxTasksPerTick: how many separate task completions (break/place/finish-a-move)
    // are allowed within a single tick when power is abundant enough to finish more than one back-to-back.
    public static final ModConfigSpec.IntValue QUARRY_MAX_TASKS_PER_TICK = BUILDER
            .comment("Maximum number of tasks (block breaks, frame placements, drill moves) the Quarry can finish "
                    + "in a single tick, even if it has enough power for more.")
            .defineInRange("maxTasksPerTick", 4, 1, 64);

    // Engines now exist (Redstone/Stirling/Combustion/Creative) and can feed the Quarry's real Capabilities.Energy
    // capability directly, so this debug crutch defaults OFF - the Quarry genuinely depends on an Engine now,
    // matching the original always being power-delivery-constrained. Left available for testing without wiring
    // up a real Engine.
    public static final ModConfigSpec.BooleanValue QUARRY_DEBUG_INFINITE_POWER = BUILDER
            .comment("DEBUG ONLY. If true, every Quarry's energy buffer receives a small trickle of FE each tick "
                    + "(see debugPowerPerTick), simulating a modest power source, bypassing the need for a real "
                    + "Engine. Off by default now that Engines exist.")
            .define("debugInfinitePower", false);

    // Deliberately a modest trickle, not "top off to full": at maxFePerTick=2000 and a stone block costing 500 FE
    // (energyPerHardnessPoint * (hardness+1) = 200*2.5), topping the buffer off every tick let every task complete
    // within a single tick - meaning onReceivePower (which drives the crack overlay and the drill's plunge
    // animation) never ran with a partial value, and moving/breaking had no perceptible duration at all. The
    // original was ALWAYS power-delivery-constrained this way (a real Engine's MJ/tick output is the bottleneck,
    // never abundant) - this trickle restores that same multi-tick-per-task pacing without needing a real Engine.
    public static final ModConfigSpec.IntValue QUARRY_DEBUG_POWER_PER_TICK = BUILDER
            .comment("DEBUG ONLY. FE inserted into the Quarry's buffer per tick while debugInfinitePower is true - "
                    + "a deliberately modest trickle (not a full top-off) so tasks take multiple ticks, matching "
                    + "the original's always power-constrained pacing.")
            .defineInRange("debugPowerPerTick", 20, 0, Integer.MAX_VALUE);

    static {
        BUILDER.pop();
        BUILDER.push("pipes");
    }

    // The original's Wood/Obsidian/Emzuli pipes draw MJ (BCTransportConfig.mjPerItem etc); no Engines module
    // exists yet to produce real MJ, so - matching the Quarry's own documented FE substitution above - these
    // powered pipe tiers draw generic FE via Capabilities.Energy.BLOCK instead. Not source-derived amounts.
    public static final ModConfigSpec.IntValue PIPE_POWERED_ENERGY_CAPACITY = BUILDER
            .comment("How much FE a powered pipe's (Wood, Obsidian, ...) internal energy buffer can hold.")
            .defineInRange("poweredEnergyCapacity", 10_000, 100, Integer.MAX_VALUE);

    public static final ModConfigSpec.IntValue PIPE_POWERED_MAX_FE_PER_TICK = BUILDER
            .comment("The maximum FE a powered pipe can draw from its buffer per tick.")
            .defineInRange("poweredMaxFePerTick", 200, 1, Integer.MAX_VALUE);

    public static final ModConfigSpec.IntValue PIPE_WOOD_FE_PER_ITEM = BUILDER
            .comment("FE cost for a Wood pipe to extract a single item from its wrench-picked adjacent inventory.")
            .defineInRange("woodFePerItem", 40, 1, Integer.MAX_VALUE);

    public static final ModConfigSpec.IntValue PIPE_OBSIDIAN_FE_PER_ITEM = BUILDER
            .comment("Base FE cost for an Obsidian pipe to suck in one dropped item entity (plus a per-block-distance cost).")
            .defineInRange("obsidianFePerItem", 20, 1, Integer.MAX_VALUE);

    public static final ModConfigSpec.IntValue PIPE_OBSIDIAN_FE_PER_METRE = BUILDER
            .comment("Additional FE cost per block of distance for an Obsidian pipe to suck in a dropped item entity.")
            .defineInRange("obsidianFePerMetre", 10, 0, Integer.MAX_VALUE);

    public static final ModConfigSpec.IntValue PIPE_STRIPES_ENERGY_PER_HARDNESS_POINT = BUILDER
            .comment("FE cost for a Stripes pipe to break the block in its set direction = this * (hardness + 1), "
                    + "matching the Quarry's own break-cost formula.")
            .defineInRange("stripesEnergyPerHardnessPoint", 200, 0, Integer.MAX_VALUE);

    static {
        BUILDER.pop();
        BUILDER.push("engines");
    }

    // The original's engines are denominated in MJ (microjoules, MjAPI.MJ per tier - Redstone max=1*MJ,
    // Stirling max=1000*MJ, Combustion max=10000*MJ - a 1:1000:10000 ratio). This port runs on FE natively (the
    // user's standing design decision - no bespoke MJ unit), so these are fresh FE numbers preserving the same
    // RELATIVE tier ordering (weakest to strongest) and the real per-tier formulas/gating/heat curve exactly,
    // but NOT the literal 1:1000:10000 buffer ratio (which was calibrated for microjoule-scale numbers, not FE) -
    // chosen instead to sit sensibly against this project's existing FE economy (Quarry draws up to 2000 FE/tick,
    // pipes up to 200 FE/tick). Same category of deviation as the Quarry's/pipes' own FE substitutions.
    public static final ModConfigSpec.IntValue ENGINE_REDSTONE_CAPACITY = BUILDER
            .comment("Redstone (Wood) Engine's internal FE buffer capacity - kept topped off while powered, per source.")
            .defineInRange("redstoneCapacity", 1_000, 100, Integer.MAX_VALUE);

    public static final ModConfigSpec.IntValue ENGINE_REDSTONE_FE_PER_TICK = BUILDER
            .comment("Redstone (Wood) Engine's constant push-per-tick output while powered (source: MJ/20, a flat trickle).")
            .defineInRange("redstoneFePerTick", 10, 1, Integer.MAX_VALUE);

    public static final ModConfigSpec.IntValue ENGINE_STIRLING_CAPACITY = BUILDER
            .comment("Stirling (Stone) Engine's internal FE buffer capacity.")
            .defineInRange("stirlingCapacity", 20_000, 1_000, Integer.MAX_VALUE);

    public static final ModConfigSpec.IntValue ENGINE_STIRLING_MIN_OUTPUT = BUILDER
            .comment("Stirling Engine's minimum push-per-tick output (source: MAX_OUTPUT/3) - the PI controller "
                    + "output is clamped between this and the max.")
            .defineInRange("stirlingMinOutput", 40, 1, Integer.MAX_VALUE);

    public static final ModConfigSpec.IntValue ENGINE_STIRLING_MAX_OUTPUT = BUILDER
            .comment("Stirling Engine's maximum push-per-tick output.")
            .defineInRange("stirlingMaxOutput", 120, 1, Integer.MAX_VALUE);

    public static final ModConfigSpec.IntValue ENGINE_COMBUSTION_CAPACITY = BUILDER
            .comment("Combustion (Iron) Engine's internal FE buffer capacity.")
            .defineInRange("combustionCapacity", 200_000, 1_000, Integer.MAX_VALUE);

    // Real source (src_old_license/BuildCraftEnergy.java): Oil = 30 power/cycle, 5000 ticks total burn per mB;
    // Fuel = 60 power/cycle, 25000 ticks total burn per mB - i.e. real Fuel is 2x the power AND 5x the duration
    // of Oil (10x the total energy per mB). These FE numbers preserve that exact 2x/5x/10x ratio rather than
    // the literal RF values (same category of deviation as every other FE substitution in this port) - Oil's
    // numbers match what used to be this port's lava-placeholder values (now replaced with the real fluid).
    public static final ModConfigSpec.IntValue ENGINE_COMBUSTION_OIL_FE_PER_TICK = BUILDER
            .comment("FE produced by the Combustion Engine per tick while burning Oil (source: 30 power/cycle).")
            .defineInRange("combustionOilFePerTick", 300, 1, Integer.MAX_VALUE);

    public static final ModConfigSpec.IntValue ENGINE_COMBUSTION_OIL_BURN_TICKS_PER_MB = BUILDER
            .comment("How many ticks 1 mB of Oil keeps the Combustion Engine burning (source: 5000 ticks).")
            .defineInRange("combustionOilBurnTicksPerMb", 20, 1, Integer.MAX_VALUE);

    public static final ModConfigSpec.IntValue ENGINE_COMBUSTION_FUEL_FE_PER_TICK = BUILDER
            .comment("FE produced by the Combustion Engine per tick while burning refined Fuel (source: 60 power/cycle).")
            .defineInRange("combustionFuelFePerTick", 600, 1, Integer.MAX_VALUE);

    public static final ModConfigSpec.IntValue ENGINE_COMBUSTION_FUEL_BURN_TICKS_PER_MB = BUILDER
            .comment("How many ticks 1 mB of refined Fuel keeps the Combustion Engine burning (source: 25000 ticks).")
            .defineInRange("combustionFuelBurnTicksPerMb", 100, 1, Integer.MAX_VALUE);

    // Real source's maxPowerExtracted()/mjPerItem ratio (Stirling: 100MJ/1MJ = up to 100 items in one pulse,
    // bounded only by chest stock, same as this port before this tuning) permits large single-pulse bursts too -
    // this ISN'T a source-accuracy gap. But a user-reported "one pump extracted 4 items, then later 50" made
    // clear that source's own theoretical ceiling is too generous for good gameplay pacing at this port's FE
    // scale, and - since heat is a direct linear function of buffer-fill ratio for any tier using the generic
    // heat formula (Stirling) - a smaller single-pulse buffer swing also directly means smoother heat-stage
    // transitions, fixing a second reported symptom ("heat jumps stages rapidly") with the same change. These
    // deliberately replace the buffer-fraction-based getMaxPowerExtracted() ratios with small flat per-tier caps
    // sized to roughly a handful of Wood-pipe items (see PIPE_WOOD_FE_PER_ITEM) per pulse, not a
    // literal source-ratio port - same category of deliberate FE-economy tuning as the capacity values above.
    public static final ModConfigSpec.IntValue ENGINE_REDSTONE_MAX_PULSE_OUTPUT = BUILDER
            .comment("Redstone Engine's max FE deliverable in a single pulse to a pulsed receiver (e.g. a Wood pipe) - roughly 1 item's worth.")
            .defineInRange("redstoneMaxPulseOutput", 40, 1, Integer.MAX_VALUE);

    public static final ModConfigSpec.IntValue ENGINE_STIRLING_MAX_PULSE_OUTPUT = BUILDER
            .comment("Stirling Engine's max FE deliverable in a single pulse to a pulsed receiver - roughly 2 items' worth.")
            .defineInRange("stirlingMaxPulseOutput", 80, 1, Integer.MAX_VALUE);

    public static final ModConfigSpec.IntValue ENGINE_COMBUSTION_MAX_PULSE_OUTPUT = BUILDER
            .comment("Combustion Engine's max FE deliverable in a single pulse to a pulsed receiver - roughly 4 items' worth.")
            .defineInRange("combustionMaxPulseOutput", 160, 1, Integer.MAX_VALUE);

    // Real fuel is meant to come from BuildCraft's own crude-oil-refinery chain (registered in
    // BCEnergyRecipes.java), which doesn't exist yet since the Fuel module comes after Engines in the user's
    // stated priority order. Lava is used as a documented placeholder fuel in the meantime - the source's real
    // water coolant registration (0.0023 degrees/mB, verbatim below) IS ported as-is since water/ice coolant is
    // genuinely source-accurate, unlike the fuel side.
    public static final ModConfigSpec.DoubleValue ENGINE_COMBUSTION_COOLANT_DEGREES_PER_MB = BUILDER
            .comment("Heat removed per mB of coolant fluid drained per tick (source: water = 0.0023).")
            .defineInRange("combustionCoolantDegreesPerMb", 0.0023, 0.0, 100.0);

    // Source drains a flat MjAPI.MJ per tick from ANY engine's buffer whenever it isn't redstone-powered,
    // regardless of that tier's own max capacity - not a proportional trickle. Sized here so it still empties
    // the (smallest) Redstone Engine's buffer in a single tick, matching the source's near-instant-drain feel.
    public static final ModConfigSpec.IntValue ENGINE_UNPOWERED_DRAIN_PER_TICK = BUILDER
            .comment("Flat FE drained from ANY engine's buffer per tick while it has no redstone signal (matches "
                    + "the original draining a flat amount regardless of the engine's own capacity).")
            .defineInRange("unpoweredDrainPerTick", 1_000, 1, Integer.MAX_VALUE);

    static {
        BUILDER.pop();
        BUILDER.push("factory");
    }

    // Real source (src_old_license/BuildCraftEnergy.java): BuildcraftRecipeRegistry.refinery.addRecipe("buildcraft:fuel",
    // new FluidStack(oil.fluid, 1), new FluidStack(fuel.fluid, 1), 120, 1) - 1mB Oil -> 1mB Fuel, 120 RF, 1-tick
    // delay (attempts a craft every tick if energy allows, a steady conversion not a slow batch). The 120 RF cost
    // is a fresh FE number (same category of deviation as the Quarry's/Engines' own FE substitutions), not a
    // literal 1:1 RF->FE port.
    public static final ModConfigSpec.IntValue REFINERY_TANK_CAPACITY = BUILDER
            .comment("The Refinery's oil-in and fuel-out tank capacity, in mB.")
            .defineInRange("refineryTankCapacity", 10_000, 100, Integer.MAX_VALUE);

    public static final ModConfigSpec.IntValue REFINERY_ENERGY_CAPACITY = BUILDER
            .comment("The Refinery's internal FE buffer capacity.")
            .defineInRange("refineryEnergyCapacity", 10_000, 100, Integer.MAX_VALUE);

    public static final ModConfigSpec.IntValue REFINERY_ENERGY_PER_MB = BUILDER
            .comment("FE cost to refine 1mB of Oil into 1mB of Fuel (source: 120 RF per real recipe).")
            .defineInRange("refineryEnergyPerMb", 120, 1, Integer.MAX_VALUE);

    public static final ModConfigSpec.IntValue REFINERY_MAX_FE_PER_TICK = BUILDER
            .comment("The maximum FE the Refinery can draw from its buffer per tick.")
            .defineInRange("refineryMaxFePerTick", 200, 1, Integer.MAX_VALUE);

    // Real source (TileTank(): this(16 * Fluid.BUCKET_VOLUME)) - 16 buckets, holds any single fluid (generic,
    // unlike the Refinery's fixed Oil/Fuel tanks).
    public static final ModConfigSpec.IntValue TANK_CAPACITY = BUILDER
            .comment("The Tank block's fluid capacity, in mB (real source: 16 buckets).")
            .defineInRange("tankCapacity", 16_000, 100, Integer.MAX_VALUE);

    // Real source (TileMiner.getBatteryCapacity, TileMiningWell doesn't override it): 500 MJ battery. Break cost
    // uses the exact same BlockUtil.computeBlockBreakPower formula as the Quarry (cost = C * (hardness + 1)) -
    // see QUARRY_ENERGY_PER_HARDNESS_POINT's own comment for the "+1" reasoning, ported here as a separate
    // config value rather than reusing the Quarry's own (a real player may want to tune them independently).
    public static final ModConfigSpec.IntValue MINING_WELL_ENERGY_CAPACITY = BUILDER
            .comment("How much FE the Mining Well's internal energy buffer can hold.")
            .defineInRange("miningWellEnergyCapacity", 250_000, 1_000, Integer.MAX_VALUE);

    public static final ModConfigSpec.IntValue MINING_WELL_MAX_FE_PER_TICK = BUILDER
            .comment("The maximum FE the Mining Well can draw from its buffer per tick.")
            .defineInRange("miningWellMaxFePerTick", 1_000, 1, Integer.MAX_VALUE);

    public static final ModConfigSpec.IntValue MINING_WELL_ENERGY_PER_HARDNESS_POINT = BUILDER
            .comment("FE cost to mine a block = this * (hardness + 1), same formula as the Quarry.")
            .defineInRange("miningWellEnergyPerHardnessPoint", 200, 0, Integer.MAX_VALUE);

    // Real source (TilePump.getBatteryCapacity override): 50 MJ - deliberately smaller than the Mining Well's
    // shared 500 MJ default, since draining is a fixed per-block cost (see below) rather than hardness-scaled.
    public static final ModConfigSpec.IntValue PUMP_ENERGY_CAPACITY = BUILDER
            .comment("How much FE the Pump's internal energy buffer can hold.")
            .defineInRange("pumpEnergyCapacity", 25_000, 1_000, Integer.MAX_VALUE);

    public static final ModConfigSpec.IntValue PUMP_MAX_FE_PER_TICK = BUILDER
            .comment("The maximum FE the Pump can draw from its buffer per tick.")
            .defineInRange("pumpMaxFePerTick", 500, 1, Integer.MAX_VALUE);

    // Real source: TilePump.mine() spends a FIXED "10 * MJAPI.MJ" per fluid block drained, unlike the Quarry/
    // Mining Well's hardness-scaled cost (draining doesn't have a "hardness" concept).
    public static final ModConfigSpec.IntValue PUMP_ENERGY_PER_DRAIN = BUILDER
            .comment("FE cost for the Pump to drain a single fluid source block (fixed, not hardness-based).")
            .defineInRange("pumpEnergyPerDrain", 100, 0, Integer.MAX_VALUE);

    // Real source: BCCoreConfig.pumpMaxDistance, a real config value bounding the flood-fill search radius - the
    // exact real default wasn't confirmed from source reading (not directly cited in the files read this
    // session), so this is a reasonable, clearly-flagged-as-unverified default rather than a claimed-exact port.
    public static final ModConfigSpec.IntValue PUMP_MAX_DISTANCE = BUILDER
            .comment("How far (in blocks) the Pump's flood-fill fluid search can reach from its own position. "
                    + "Real source has an equivalent config value; this default isn't confirmed against it.")
            .defineInRange("pumpMaxDistance", 20, 1, 128);

    // Real source: TilePump's own tank (Tank("tank", 16 * Fluid.BUCKET_VOLUME, this)) - 16 buckets, matching the
    // Tank block's own default capacity.
    public static final ModConfigSpec.IntValue PUMP_TANK_CAPACITY = BUILDER
            .comment("The Pump's internal fluid buffer capacity, in mB (real source: 16 buckets).")
            .defineInRange("pumpTankCapacity", 16_000, 100, Integer.MAX_VALUE);

    // Real source: BCCoreConfig.pumpsConsumeWater, default false - matching vanilla's own "2+ adjacent water
    // sources regenerate infinitely" rule, a Pump doesn't actually deplete a real infinite water source unless
    // this is turned on.
    public static final ModConfigSpec.BooleanValue PUMP_CONSUMES_WATER = BUILDER
            .comment("If false (real source's default), the Pump won't actually deplete a water source it "
                    + "detects as infinite (2+ connected water sources) - it keeps draining it forever instead "
                    + "of eventually running it dry, matching vanilla's own infinite-water-source rule.")
            .define("pumpConsumesWater", false);

    // DEVIATION FROM SOURCE (explicit user request, not a port): real TileMiner.updateLength() places every Tube
    // block from the machine down to its target in one synchronous call, the same tick a target is found - both
    // the physical pole and (functionally) the ability to start mining/draining appear instantly. This value
    // paces that placement to 1 block per this-many ticks instead, and gates a Mining Well/Pump's own work
    // (see MinerBlockEntity.isFullyExtended()) behind the shaft having physically reached its target first, so
    // the hose visibly reaches down before anything happens - a real gameplay change, not a source-accuracy fix.
    public static final ModConfigSpec.IntValue MINER_SHAFT_TICKS_PER_BLOCK = BUILDER
            .comment("Ticks to extend the Mining Well/Pump shaft by 1 block - it probes continuously from power "
                    + "alone and only pauses to mine/drain (see MinerBlockEntity.paused/stopped), it doesn't "
                    + "wait to detect something first. NOT a real source value - real BuildCraft places the "
                    + "whole shaft instantly. Higher = slower, more visible descent.")
            .defineInRange("minerShaftTicksPerBlock", 60, 1, 400);

    // DEVIATION FROM SOURCE (explicit user request: the shaft should genuinely "extend from power", not just be
    // paced by a timer - real source doesn't have a paced shaft at all, see MINER_SHAFT_TICKS_PER_BLOCK's own
    // comment). Without this, tickShaftGrowth advanced unconditionally every tick regardless of whether the
    // machine actually had any FE, which looked like it was running on an "always powered" cheat.
    public static final ModConfigSpec.IntValue MINER_SHAFT_ENERGY_PER_TICK = BUILDER
            .comment("FE the Mining Well/Pump shaft must actually draw from its own buffer each tick to extend "
                    + "at all - with no power (or an empty buffer), the shaft simply doesn't move.")
            .defineInRange("minerShaftEnergyPerTick", 20, 0, Integer.MAX_VALUE);

    static {
        BUILDER.pop();
    }

    static final ModConfigSpec SPEC = BUILDER.build();

    private static boolean validateItemName(final Object obj) {
        return obj instanceof String itemName && BuiltInRegistries.ITEM.containsKey(Identifier.parse(itemName));
    }
}
