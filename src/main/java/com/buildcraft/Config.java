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

    // Real source (TileQuarry.java:101, confirmed 2026-08-11 via the actual BuildCraft GitHub source):
    // new MjBattery(24000 * MjAPI.MJ) = 24,000 MJ. Real conversion ratio confirmed the same day via the actual
    // BuildCraftAPI source (MjRfConversion.DEFAULT_MJ_PER_RF = MjAPI.MJ/10): 1 MJ = 10 FE. 24,000 MJ x10 = 240,000 FE.
    public static final ModConfigSpec.IntValue QUARRY_ENERGY_CAPACITY = BUILDER
            .comment("How much FE the Quarry's internal energy buffer can hold (real source: 24,000 MJ).")
            .defineInRange("energyCapacity", 240_000, 1_000, Integer.MAX_VALUE);

    // Real source: TileQuarry.java:97, MAX_POWER_PER_TICK = 512 * MjAPI.MJ = 512 MJ/tick -> 5,120 FE/tick.
    public static final ModConfigSpec.IntValue QUARRY_MAX_FE_PER_TICK = BUILDER
            .comment("The maximum FE the Quarry can draw from its buffer per tick (at full battery; see energy "
                    + "ramp-up). Real source: 512 MJ/tick.")
            .defineInRange("maxFePerTick", 5_120, 1, Integer.MAX_VALUE);

    // Real source: BlockUtil.computeBlockBreakPower (common/buildcraft/lib/misc/BlockUtil.java:401-405) =
    // floor(16 * MJ * (hardness+1) * 2 * miningMultiplier) = 32 MJ * (hardness+1) at the default
    // miningMultiplier=1.0 (BCCoreConfig.java:56). 32 MJ x10 = 320 FE per hardness point. The "+1" means even a
    // hardness-0 block (e.g. dirt) still costs something, not just the multiplier's floor.
    public static final ModConfigSpec.IntValue QUARRY_ENERGY_PER_HARDNESS_POINT = BUILDER
            .comment("FE cost to mine a block = this * (hardness + 1). Applies both to normal mining and to "
                    + "clearing obstacles out of the Quarry's own frame area. Real source: 32 MJ per hardness point.")
            .defineInRange("energyPerHardnessPoint", 320, 0, Integer.MAX_VALUE);

    // Real source: TileQuarry.java TaskAddFrame.getTarget() = 24 * MjAPI.MJ = 24 MJ -> 240 FE.
    public static final ModConfigSpec.IntValue QUARRY_FRAME_BLOCK_COST = BUILDER
            .comment("FE cost to place a single Frame block (real source: 24 MJ).")
            .defineInRange("frameBlockCost", 240, 0, Integer.MAX_VALUE);

    // Real source: TileQuarry.java TaskMoveDrill.getTarget() = distance * 20 * MjAPI.MJ = 20 MJ per block of
    // travel -> 200 FE/block.
    public static final ModConfigSpec.IntValue QUARRY_MOVE_COST_PER_BLOCK = BUILDER
            .comment("FE cost for the drill to travel one block, while repositioning between mining targets. "
                    + "Real source: 20 MJ per block.")
            .defineInRange("moveCostPerBlock", 200, 0, Integer.MAX_VALUE);

    // Real source: TileQuarry.java lines 561-570, quarryTaskPowerDivisor (BCBuildersConfig.java:28, default 2) -
    // each ADDITIONAL task completed within the same tick (beyond the first) costs progressively more power:
    // nNeeded = needed * (divisor+i) / divisor for the i-th extra task, so at divisor=2 the 2nd task in a tick
    // costs 1.5x, the 3rd costs 2x, etc. This is real source's actual speed-limiting mechanic for a
    // well-powered Quarry - without it, enough power alone lets QUARRY_MAX_TASKS_PER_TICK tasks complete every
    // tick at full rate; with it, completing more than one task per tick gets progressively less power-efficient,
    // naturally throttling max real-world mining speed even when power is abundant. This port didn't implement
    // this mechanic before 2026-08-11 - a real, source-verified omission, not a deliberate deviation.
    public static final ModConfigSpec.IntValue QUARRY_TASK_POWER_DIVISOR = BUILDER
            .comment("Real source's quarryTaskPowerDivisor: each additional task completed within the same tick "
                    + "costs progressively more power (task i costs needed*(divisor+i)/divisor), throttling max "
                    + "real-world mining speed even with abundant power. 0 disables the surcharge entirely.")
            .defineInRange("taskPowerDivisor", 2, 0, 100);

    // Real source (common/buildcraft/core/BCCoreConfig.java:235-238, confirmed 2026-08-12 via the actual
    // BuildCraft GitHub source, user-reported "there are 50+ more blocks left" led to finding this): real
    // DEFAULT is propMiningMaxDepth = config.get(general, "miningMaxDepth", 512), but real source's own
    // CONFIGURABLE RANGE is 32-4096 (setMinValue(32).setMaxValue(4096)) - shared by BOTH the Quarry and Mining
    // Well/Pump in real source too ("How much further down can miners (like the quarry or the mining well)
    // dig?"), matching this port's own existing single-config sharing exactly. Set to 3000 here (a real
    // player-chosen value within real source's own allowed range, not source's literal default) per explicit
    // user request, for future-proofing against deep-world-height mods like Large Mountains that push far below
    // vanilla's normal -64 floor - a legitimate, in-range customization, not a source-accuracy deviation.
    public static final ModConfigSpec.IntValue QUARRY_MAX_MINE_DEPTH = BUILDER
            .comment("How many blocks below the frame's bottom layer the Quarry (and separately, the Mining "
                    + "Well/Pump's own shaft) is allowed to dig, at most. Real source default is 512, but its own "
                    + "allowed range is 32-4096 - set to 3000 here for future-proofing against deep-world mods.")
            .defineInRange("maxMineDepth", 3000, 32, 4096);

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

    // Real source engine constants, confirmed 2026-08-11 by reading the actual BuildCraft/BuildCraftAPI GitHub
    // source directly (TileEngineRedstone_BC8.java, TileEngineStone_BC8.java, TileEngineIron_BC8.java) and the
    // real conversion ratio (MjRfConversion.DEFAULT_MJ_PER_RF = MjAPI.MJ/10, i.e. 1 MJ = 10 FE). Every value
    // below is the real MJ figure x10, a genuine 1:1 port at that ratio - not an invented "preserve the relative
    // ordering" number like the earlier version of these comments described. Real buffer ratio really is
    // 1:1000:10000 (Redstone:Stirling:Combustion) as MJ, i.e. 10:10,000:100,000 as FE.
    public static final ModConfigSpec.IntValue ENGINE_REDSTONE_CAPACITY = BUILDER
            .comment("Redstone (Wood) Engine's internal FE buffer capacity - kept topped off while powered, per "
                    + "source. Real source: 1 MJ.")
            .defineInRange("redstoneCapacity", 10, 1, Integer.MAX_VALUE);

    public static final ModConfigSpec.IntValue ENGINE_STIRLING_CAPACITY = BUILDER
            .comment("Stirling (Stone) Engine's internal FE buffer capacity. Real source: 1000 MJ.")
            .defineInRange("stirlingCapacity", 10_000, 1_000, Integer.MAX_VALUE);

    // Real source: TileEngineStone_BC8.java MIN_OUTPUT = MAX_OUTPUT/3 = (1 MJ)/3 = 333,333 microjoules exactly
    // (integer division truncates) = 0.333333 MJ -> 3.33333 FE, rounded to the nearest whole FE (3).
    public static final ModConfigSpec.IntValue ENGINE_STIRLING_MIN_OUTPUT = BUILDER
            .comment("Stirling Engine's minimum push-per-tick output (source: MAX_OUTPUT/3) - the PI controller "
                    + "output is clamped between this and the max. Real source: ~0.333 MJ.")
            .defineInRange("stirlingMinOutput", 3, 1, Integer.MAX_VALUE);

    // Real source: TileEngineStone_BC8.java MAX_OUTPUT = MjAPI.MJ = 1 MJ -> 10 FE. This is Stirling's real
    // per-tick PRODUCTION ceiling (how fast its own buffer can fill) - much smaller than its extract cap
    // (see ENGINE_STIRLING_MAX_PULSE_OUTPUT below), which is why a well-charged Stirling can briefly burst far
    // above this rate to a receiver before throttling down once its buffer drains to what it can actually produce.
    public static final ModConfigSpec.IntValue ENGINE_STIRLING_MAX_OUTPUT = BUILDER
            .comment("Stirling Engine's maximum push-per-tick output INTO ITS OWN BUFFER (not to a receiver - "
                    + "see stirlingMaxPulseOutput for that). Real source: 1 MJ.")
            .defineInRange("stirlingMaxOutput", 10, 1, Integer.MAX_VALUE);

    public static final ModConfigSpec.IntValue ENGINE_COMBUSTION_CAPACITY = BUILDER
            .comment("Combustion (Iron) Engine's internal FE buffer capacity. Real source: 10,000 MJ.")
            .defineInRange("combustionCapacity", 100_000, 1_000, Integer.MAX_VALUE);

    // Real source (common/buildcraft/energy/BCEnergyRecipes.java, the real BC8 fuel registry - NOT the older
    // src_old_license tree an earlier version of this port's comments cited, which used different, inconsistent
    // numbers): crudeOil ("Oil") registered via addDirtyFuel(crudeOil, 8, 3, 4) -> multiplier=3 ->
    // powerPerCycle = 3 MJ/tick (1 "cycle" confirmed = 1 game tick, TileEngineIron_BC8.burn() calls
    // addPower(currentFuel.getPowerPerCycle()) once per tick while burnTime>0) -> 30 FE/tick. totalBurningTime=
    // 10,000, /1000.0 (TileEngineIron_BC8.java:207) = 10 ticks of burn per mB consumed.
    public static final ModConfigSpec.IntValue ENGINE_COMBUSTION_OIL_FE_PER_TICK = BUILDER
            .comment("FE produced by the Combustion Engine per tick while burning Oil (real source: crudeOil, 3 MJ/tick).")
            .defineInRange("combustionOilFePerTick", 30, 1, Integer.MAX_VALUE);

    public static final ModConfigSpec.IntValue ENGINE_COMBUSTION_OIL_BURN_TICKS_PER_MB = BUILDER
            .comment("How many ticks 1 mB of Oil keeps the Combustion Engine burning (real source: crudeOil, 10 ticks/mB).")
            .defineInRange("combustionOilBurnTicksPerMb", 10, 1, Integer.MAX_VALUE);

    // Real source, NOW FULLY VERIFIED (2026-08-11, via BCEnergyFluids.java's own preInit(), the actual fluid
    // registration): when BCModules.FACTORY.isLoaded() is false - the exact real mode this port's simplified
    // 2-fluid Oil/Fuel economy is based on - real source defines EXACTLY 2 fluids: crudeOil (as "oil") and,
    // critically, fuelLight AS "fuel" (`fuelLight = new BCFluid[] { defineFluid(data[7], 0, "fuel_light") }`),
    // not fuelDense as an earlier guess in this file assumed. fuelLight's real fuel-burn registration
    // (BCEnergyRecipes.java: addFuel(BCEnergyFluids.fuelLight, _light, 6, 6), multiplier=6) -> powerPerCycle =
    // 6 MJ/tick -> 60 FE/tick. This addFuel() call runs unconditionally (outside the FACTORY.isLoaded() check),
    // so it's real and active in fallback mode too - a genuine, no-longer-a-guess 1:1 port.
    public static final ModConfigSpec.IntValue ENGINE_COMBUSTION_FUEL_FE_PER_TICK = BUILDER
            .comment("FE produced by the Combustion Engine per tick while burning refined Fuel (real source: "
                    + "fuelLight, the exact real fallback-mode 'fuel', 6 MJ/tick).")
            .defineInRange("combustionFuelFePerTick", 60, 1, Integer.MAX_VALUE);

    // Real source: fuelLight's totalTime = TIME_BASE(240,000) * 6/4 / 6 / 4 = 15,000, /1000.0
    // (TileEngineIron_BC8.java:207) = 15 ticks of burn per mB.
    public static final ModConfigSpec.IntValue ENGINE_COMBUSTION_FUEL_BURN_TICKS_PER_MB = BUILDER
            .comment("How many ticks 1 mB of refined Fuel keeps the Combustion Engine burning (real source: fuelLight, 15 ticks/mB).")
            .defineInRange("combustionFuelBurnTicksPerMb", 15, 1, Integer.MAX_VALUE);

    // Real source: TileEngineBase_BC8.maxPowerExtracted() overrides, confirmed 2026-08-11 - ONE cap per tier,
    // applying to EVERY receiver alike (pulsed or continuous), not a "per-pulse-only" number as an earlier
    // version of this port's own comments incorrectly assumed. Redstone 4 MJ, Stirling 100 MJ, Combustion 500
    // MJ -> x10 = 40/1000/5000 FE. See EngineBlockEntity.getMaxPowerExtracted's javadoc for the full explanation
    // of why a real engine's own buffer dynamics (not a second config value) are what naturally produce the
    // "small trickle for Redstone, burst-then-recover for Stirling/Combustion" gameplay feel.
    public static final ModConfigSpec.IntValue ENGINE_REDSTONE_MAX_PULSE_OUTPUT = BUILDER
            .comment("Redstone Engine's max FE extractable in a single tick, to ANY receiver (real source: 4 MJ) - "
                    + "practically never the binding constraint, since its own buffer (see redstoneCapacity) is smaller.")
            .defineInRange("redstoneMaxPulseOutput", 40, 1, Integer.MAX_VALUE);

    public static final ModConfigSpec.IntValue ENGINE_STIRLING_MAX_PULSE_OUTPUT = BUILDER
            .comment("Stirling Engine's max FE extractable in a single tick, to ANY receiver (real source: 100 MJ).")
            .defineInRange("stirlingMaxPulseOutput", 1_000, 1, Integer.MAX_VALUE);

    public static final ModConfigSpec.IntValue ENGINE_COMBUSTION_MAX_PULSE_OUTPUT = BUILDER
            .comment("Combustion Engine's max FE extractable in a single tick, to ANY receiver (real source: 500 MJ).")
            .defineInRange("combustionMaxPulseOutput", 5_000, 1, Integer.MAX_VALUE);

    // Real fuel is meant to come from BuildCraft's own crude-oil-refinery chain (registered in
    // BCEnergyRecipes.java), which doesn't exist yet since the Fuel module comes after Engines in the user's
    // stated priority order. Lava is used as a documented placeholder fuel in the meantime - the source's real
    // water coolant registration (0.0023 degrees/mB, verbatim below) IS ported as-is since water/ice coolant is
    // genuinely source-accurate, unlike the fuel side.
    public static final ModConfigSpec.DoubleValue ENGINE_COMBUSTION_COOLANT_DEGREES_PER_MB = BUILDER
            .comment("Heat removed per mB of coolant fluid drained per tick (source: water = 0.0023).")
            .defineInRange("combustionCoolantDegreesPerMb", 0.0023, 0.0, 100.0);

    // Real source: TileEngineBase_BC8.update() lines 309-315, drains a flat 1 MJ per tick from ANY engine's
    // buffer whenever it isn't redstone-powered, regardless of that tier's own max capacity - not a proportional
    // trickle. 1 MJ x10 = 10 FE. At Redstone's own now-real 10 FE buffer, this still empties it in a single
    // tick, matching source's near-instant-drain feel exactly (not by coincidence - both numbers are now real).
    public static final ModConfigSpec.IntValue ENGINE_UNPOWERED_DRAIN_PER_TICK = BUILDER
            .comment("Flat FE drained from ANY engine's buffer per tick while it has no redstone signal (matches "
                    + "the original draining a flat amount regardless of the engine's own capacity). Real source: 1 MJ.")
            .defineInRange("unpoweredDrainPerTick", 10, 1, Integer.MAX_VALUE);

    static {
        BUILDER.pop();
        BUILDER.push("factory");
    }

    // Real source (common/buildcraft/factory/tile/TileDistiller_BC8.java:86, confirmed 2026-08-11 via the actual
    // BuildCraft GitHub source): new MjBattery(1024 * MjAPI.MJ) = 1024 MJ -> 10,240 FE.
    //
    // REAL, VERIFIED DERIVATION for the per-mB cost (corrected 2026-08-11 - an earlier version of this comment
    // wrongly concluded no real recipe exists at all, based on misreading BCModules.FACTORY.isLoaded() as an
    // optional add-on most players wouldn't have; it's actually one of BuildCraft's own bundled modules, loaded
    // by default in any normal install including modpacks like Tekkit - the full distillation system IS real,
    // normally-active content). This port's Oil="crudeOil"/Fuel="fuelLight" (see
    // ENGINE_COMBUSTION_FUEL_FE_PER_TICK's own comment) requires TWO real chained distillation stages to turn
    // crude oil into fuelLight specifically (BCEnergyRecipes.java): stage 1, addDistillation(oil->gas+
    // light_dense_residue, heat 0, 32 MJ) consumes 8mB oil for 3mB light_dense_residue (+16mB gas, a byproduct
    // this port's simplified 2-fluid model doesn't produce); stage 2, addDistillation(light_dense_residue->
    // light+dense_residue, heat 1, 16 MJ) consumes that exact 3mB for 4mB of real "light" fuel (+2mB
    // dense_residue, likewise not modeled). Total real cost for the full chain: 32+16=48 MJ, yielding 4mB of
    // fuelLight from 8mB of crude oil. Per mB of throughput (this port's Oil->Fuel is 1mB:1mB, unlike real
    // source's real 8:4=2:1 ratio across the two stages): 48 MJ / 4 mB = 12 MJ/mB = 120 FE/mB - which is this
    // config's existing default, now a confirmed real-source derivation rather than an arbitrary judgment call.
    public static final ModConfigSpec.IntValue REFINERY_TANK_CAPACITY = BUILDER
            .comment("The Refinery's oil-in and fuel-out tank capacity, in mB.")
            .defineInRange("refineryTankCapacity", 10_000, 100, Integer.MAX_VALUE);

    public static final ModConfigSpec.IntValue REFINERY_ENERGY_CAPACITY = BUILDER
            .comment("The Refinery's internal FE buffer capacity (real source: 1024 MJ).")
            .defineInRange("refineryEnergyCapacity", 10_240, 100, Integer.MAX_VALUE);

    public static final ModConfigSpec.IntValue REFINERY_ENERGY_PER_MB = BUILDER
            .comment("FE cost to refine 1mB of Oil into 1mB of Fuel (real source: the combined real 2-stage "
                    + "crude-oil-to-fuelLight distillation chain costs 48 MJ for a real 8mB:4mB yield - see the "
                    + "full derivation in the comment above this field - equivalent to 12 MJ per mB of throughput).")
            .defineInRange("refineryEnergyPerMb", 120, 1, Integer.MAX_VALUE);

    // Real source: TileDistiller_BC8.java:80, MAX_MJ_PER_TICK = 6 * MjAPI.MJ = 6 MJ/tick -> 60 FE/tick.
    public static final ModConfigSpec.IntValue REFINERY_MAX_FE_PER_TICK = BUILDER
            .comment("The maximum FE the Refinery can draw from its buffer per tick. Real source: 6 MJ/tick.")
            .defineInRange("refineryMaxFePerTick", 60, 1, Integer.MAX_VALUE);

    // Real source (TileTank(): this(16 * Fluid.BUCKET_VOLUME)) - 16 buckets, holds any single fluid (generic,
    // unlike the Refinery's fixed Oil/Fuel tanks).
    public static final ModConfigSpec.IntValue TANK_CAPACITY = BUILDER
            .comment("The Tank block's fluid capacity, in mB (real source: 16 buckets).")
            .defineInRange("tankCapacity", 16_000, 100, Integer.MAX_VALUE);

    // Real source (common/buildcraft/factory/tile/TileMiner.java:261-263, TileMiningWell doesn't override it):
    // 500 * MjAPI.MJ = 500 MJ -> 5,000 FE. Break cost uses the exact same BlockUtil.computeBlockBreakPower
    // formula as the Quarry (cost = 32 MJ * (hardness+1)) - see QUARRY_ENERGY_PER_HARDNESS_POINT's own comment,
    // ported here as a separate config value rather than reusing the Quarry's own (a real player may want to
    // tune them independently).
    public static final ModConfigSpec.IntValue MINING_WELL_ENERGY_CAPACITY = BUILDER
            .comment("How much FE the Mining Well's internal energy buffer can hold. Real source: 500 MJ.")
            .defineInRange("miningWellEnergyCapacity", 5_000, 1_000, Integer.MAX_VALUE);

    // DEFINITIVELY CONFIRMED (2026-08-11, via the real MjBattery.java source, fetched directly from the actual
    // BuildCraftAPI GitHub repo): extractPower(min, max) has NO internal per-call cap of its own at all - it's
    // literally `return Math.min(microJoules, max)`. Real TileMiningWell.mine() passes exactly the block's own
    // remaining cost as `max`, with no separate artificial per-tick throttle - only the battery's total capacity
    // (see miningWellEnergyCapacity) limits it. So the real, confirmed answer is that this cap SHOULDN'T bind at
    // all; set equal to the buffer capacity so it never artificially throttles below what the buffer itself allows.
    public static final ModConfigSpec.IntValue MINING_WELL_MAX_FE_PER_TICK = BUILDER
            .comment("The maximum FE the Mining Well can draw from its buffer per tick. Real source has no "
                    + "separate cap here at all (confirmed via MjBattery.extractPower's real implementation) - "
                    + "set to match the buffer capacity so this never artificially throttles below it.")
            .defineInRange("miningWellMaxFePerTick", 5_000, 1, Integer.MAX_VALUE);

    public static final ModConfigSpec.IntValue MINING_WELL_ENERGY_PER_HARDNESS_POINT = BUILDER
            .comment("FE cost to mine a block = this * (hardness + 1), same formula as the Quarry. Real source: 32 MJ.")
            .defineInRange("miningWellEnergyPerHardnessPoint", 320, 0, Integer.MAX_VALUE);

    // Real source (common/buildcraft/factory/tile/TilePump.java:469-471, getBatteryCapacity override): 50 *
    // MjAPI.MJ = 50 MJ -> 500 FE - deliberately smaller than the Mining Well's shared 500 MJ default, since
    // draining is a fixed per-block cost (see below) rather than hardness-scaled.
    public static final ModConfigSpec.IntValue PUMP_ENERGY_CAPACITY = BUILDER
            .comment("How much FE the Pump's internal energy buffer can hold. Real source: 50 MJ.")
            .defineInRange("pumpEnergyCapacity", 500, 100, Integer.MAX_VALUE);

    // Same real, now-confirmed finding as MINING_WELL_MAX_FE_PER_TICK - MjBattery.extractPower has no internal
    // cap, so this shouldn't artificially bind either. Set to match the buffer capacity.
    public static final ModConfigSpec.IntValue PUMP_MAX_FE_PER_TICK = BUILDER
            .comment("The maximum FE the Pump can draw from its buffer per tick. Real source has no separate cap "
                    + "here at all (confirmed via MjBattery.extractPower's real implementation) - set to match "
                    + "the buffer capacity so this never artificially throttles below it.")
            .defineInRange("pumpMaxFePerTick", 500, 1, Integer.MAX_VALUE);

    // Real source: TilePump.mine() spends a FIXED "10 * MJAPI.MJ" per fluid block drained, unlike the Quarry/
    // Mining Well's hardness-scaled cost (draining doesn't have a "hardness" concept).
    public static final ModConfigSpec.IntValue PUMP_ENERGY_PER_DRAIN = BUILDER
            .comment("FE cost for the Pump to drain a single fluid source block (fixed, not hardness-based).")
            .defineInRange("pumpEnergyPerDrain", 100, 0, Integer.MAX_VALUE);

    // Real source: BCCoreConfig.pumpMaxDistance - confirmed directly this session (common/buildcraft/core/
    // BCCoreConfig.java: "propPumpMaxDistance = config.get(general, "pumpMaxDistance", 64)"). The earlier 20
    // default in this port was an unverified guess; corrected to the real value now that it's been read.
    public static final ModConfigSpec.IntValue PUMP_MAX_DISTANCE = BUILDER
            .comment("How far (in blocks) the Pump's flood-fill fluid search can reach from its own position. "
                    + "Matches real source's own default (BCCoreConfig.pumpMaxDistance = 64).")
            .defineInRange("pumpMaxDistance", 64, 1, 256);

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
    // (see MinerBlockEntity.paused) behind the shaft having physically reached its target first, so the hose
    // visibly reaches down before anything happens - a real gameplay change, not a source-accuracy fix.
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

    // Real source (TileAutoWorkbenchBase): POWER_GEN_PASSIVE = MjAPI.MJ / 5 per tick (a slow, unpowered
    // self-charge rate - "it takes 10 seconds to craft an item" with nothing feeding it), POWER_REQUIRED =
    // PASSIVE * 200 (10 real seconds' worth of passive charge), POWER_LOST = PASSIVE * 10 (decays 10x faster
    // than it passively charges whenever it currently can't craft - missing materials, no matching recipe, or
    // a full output slot). These FE numbers preserve that exact 1:200:10 ratio rather than the literal MJ
    // values (same category of deviation as every other FE substitution in this port).
    public static final ModConfigSpec.IntValue AUTO_WORKBENCH_PASSIVE_FE_PER_TICK = BUILDER
            .comment("FE the Auto Workbench self-generates per tick with no external power at all, but ONLY "
                    + "while it actually has a valid recipe+materials+room to craft (source: MJ/5).")
            .defineInRange("autoWorkbenchPassiveFePerTick", 5, 1, Integer.MAX_VALUE);

    public static final ModConfigSpec.IntValue AUTO_WORKBENCH_ENERGY_REQUIRED = BUILDER
            .comment("Total FE needed to craft one item (source: passive rate * 200, i.e. 10 seconds unpowered).")
            .defineInRange("autoWorkbenchEnergyRequired", 1_000, 10, Integer.MAX_VALUE);

    public static final ModConfigSpec.IntValue AUTO_WORKBENCH_ENERGY_LOST_PER_TICK = BUILDER
            .comment("FE drained from the stored buffer per tick whenever it currently CAN'T craft (source: "
                    + "passive rate * 10).")
            .defineInRange("autoWorkbenchEnergyLostPerTick", 50, 1, Integer.MAX_VALUE);

    public static final ModConfigSpec.IntValue AUTO_WORKBENCH_MAX_FE_PER_TICK = BUILDER
            .comment("The maximum FE an external source (e.g. an Engine) can push into the Auto Workbench per "
                    + "tick - unlike the passive trickle above, external power fills the buffer unconditionally, "
                    + "matching source's IMjRedstoneReceiver.receivePower not being gated by canCraft().")
            .defineInRange("autoWorkbenchMaxFePerTick", 1_000, 1, Integer.MAX_VALUE);

    static {
        BUILDER.pop();
    }

    static final ModConfigSpec SPEC = BUILDER.build();

    private static boolean validateItemName(final Object obj) {
        return obj instanceof String itemName && BuiltInRegistries.ITEM.containsKey(Identifier.parse(itemName));
    }
}
