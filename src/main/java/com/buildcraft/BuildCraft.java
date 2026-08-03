package com.buildcraft;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.common.world.chunk.RegisterTicketControllersEvent;

import com.buildcraft.builders.BuildersContent;
import com.buildcraft.builders.blockentity.QuarryBlockEntity;
import com.buildcraft.core.CoreContent;
import com.buildcraft.energy.EnergyContent;
import com.buildcraft.factory.FactoryContent;
import com.buildcraft.factory.FactoryFluids;
import com.buildcraft.factory.worldgen.FactoryWorldgen;
import com.buildcraft.transport.TransportContent;
import com.buildcraft.transport.blockentity.PipeBlockEntity;

// The value here should match an entry in the META-INF/neoforge.mods.toml file
@Mod(BuildCraft.MODID)
public class BuildCraft {
    // Define mod id in a common place for everything to reference
    public static final String MODID = "buildcraft";
    // Directly reference a slf4j logger
    public static final Logger LOGGER = LogUtils.getLogger();
    // Create a Deferred Register to hold Blocks which will all be registered under the "buildcraft" namespace
    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(MODID);
    // Create a Deferred Register to hold Items which will all be registered under the "buildcraft" namespace
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MODID);
    // Create a Deferred Register to hold CreativeModeTabs which will all be registered under the "buildcraft" namespace
    public static final DeferredRegister<CreativeModeTab> CREATIVE_MODE_TABS = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, MODID);

    // The mod's single creative tab - icon is the Quarry, the flagship BuildCraft item
    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> BUILDCRAFT_TAB = CREATIVE_MODE_TABS.register("main", () -> CreativeModeTab.builder()
            .title(Component.translatable("itemGroup.buildcraft"))
            .withTabsBefore(CreativeModeTabs.COMBAT)
            .icon(() -> BuildersContent.QUARRY_ITEM.get().getDefaultInstance())
            .displayItems((parameters, output) -> {
                output.accept(BuildersContent.QUARRY_ITEM.get());
                output.accept(BuildersContent.FRAME_ITEM.get());
                output.accept(BuildersContent.MARKER_ITEM.get());
                output.accept(TransportContent.WRENCH_ITEM.get());
                output.accept(TransportContent.PIPE_COBBLESTONE_ITEM.get());
                output.accept(TransportContent.PIPE_STONE_ITEM.get());
                output.accept(TransportContent.PIPE_IRON_ITEM.get());
                output.accept(TransportContent.PIPE_GOLD_ITEM.get());
                output.accept(TransportContent.PIPE_VOID_ITEM.get());
                output.accept(TransportContent.PIPE_QUARTZ_ITEM.get());
                output.accept(TransportContent.PIPE_SANDSTONE_ITEM.get());
                output.accept(TransportContent.PIPE_CLAY_ITEM.get());
                output.accept(TransportContent.PIPE_STRUCTURE_ITEM.get());
                output.accept(TransportContent.PIPE_WOOD_ITEM.get());
                output.accept(TransportContent.PIPE_DIAMOND_ITEM.get());
                output.accept(TransportContent.PIPE_LAPIS_ITEM.get());
                output.accept(TransportContent.PIPE_DAIZULI_ITEM.get());
                output.accept(TransportContent.PIPE_OBSIDIAN_ITEM.get());
                output.accept(TransportContent.PIPE_WOOD_DIAMOND_ITEM.get());
                output.accept(TransportContent.PIPE_STRIPES_ITEM.get());
                output.accept(TransportContent.PIPE_EMZULI_ITEM.get());
                output.accept(EnergyContent.ENGINE_REDSTONE_ITEM.get());
                output.accept(EnergyContent.ENGINE_STIRLING_ITEM.get());
                output.accept(EnergyContent.ENGINE_CREATIVE_ITEM.get());
                output.accept(EnergyContent.ENGINE_COMBUSTION_ITEM.get());
                output.accept(FactoryFluids.OIL_BUCKET.get());
                output.accept(FactoryFluids.FUEL_BUCKET.get());
                output.accept(FactoryContent.REFINERY_ITEM.get());
                output.accept(FactoryContent.TANK_ITEM.get());
                output.accept(TransportContent.PIPE_SEALANT_ITEM.get());
                output.accept(TransportContent.PIPE_COBBLESTONE_FLUID_ITEM.get());
                output.accept(TransportContent.PIPE_IRON_FLUID_ITEM.get());
                output.accept(TransportContent.PIPE_VOID_FLUID_ITEM.get());
                output.accept(TransportContent.PIPE_GOLD_FLUID_ITEM.get());
                output.accept(TransportContent.PIPE_STONE_FLUID_ITEM.get());
                output.accept(TransportContent.PIPE_QUARTZ_FLUID_ITEM.get());
                output.accept(TransportContent.PIPE_SANDSTONE_FLUID_ITEM.get());
                output.accept(TransportContent.PIPE_CLAY_FLUID_ITEM.get());
                output.accept(TransportContent.PIPE_WOOD_FLUID_ITEM.get());
                output.accept(TransportContent.PIPE_WOOD_DIAMOND_FLUID_ITEM.get());
                output.accept(TransportContent.PIPE_DIAMOND_FLUID_ITEM.get());
                output.accept(FactoryContent.MINING_WELL_ITEM.get());
                output.accept(FactoryContent.PUMP_ITEM.get());
                output.accept(FactoryContent.AUTO_WORKBENCH_ITEM.get());
                output.accept(CoreContent.GEAR_WOOD_ITEM.get());
                output.accept(CoreContent.GEAR_STONE_ITEM.get());
                output.accept(CoreContent.GEAR_IRON_ITEM.get());
                output.accept(CoreContent.GEAR_GOLD_ITEM.get());
                output.accept(CoreContent.GEAR_DIAMOND_ITEM.get());
            }).build());

    // The constructor for the mod class is the first code that is run when your mod is loaded.
    // FML will recognize some parameter types like IEventBus or ModContainer and pass them in automatically.
    public BuildCraft(IEventBus modEventBus, ModContainer modContainer) {
        // Register the commonSetup method for modloading
        modEventBus.addListener(this::commonSetup);

        // Register the Deferred Register to the mod event bus so blocks get registered
        BLOCKS.register(modEventBus);
        // Register the Deferred Register to the mod event bus so items get registered
        ITEMS.register(modEventBus);
        // Register the Deferred Register to the mod event bus so tabs get registered
        CREATIVE_MODE_TABS.register(modEventBus);

        // Register the core module's content (Gears)
        CoreContent.ITEMS.register(modEventBus);

        // Register the builders module's content (Quarry, Frame)
        BuildersContent.BLOCKS.register(modEventBus);
        BuildersContent.ITEMS.register(modEventBus);
        BuildersContent.BLOCK_ENTITY_TYPES.register(modEventBus);
        modEventBus.addListener(this::registerCapabilities);
        modEventBus.addListener(this::registerTicketControllers);

        // Register the transport module's content (Pipes)
        TransportContent.BLOCKS.register(modEventBus);
        TransportContent.ITEMS.register(modEventBus);
        TransportContent.BLOCK_ENTITY_TYPES.register(modEventBus);
        TransportContent.MENUS.register(modEventBus);

        // Register the energy module's content (Engines)
        EnergyContent.BLOCKS.register(modEventBus);
        EnergyContent.ITEMS.register(modEventBus);
        EnergyContent.BLOCK_ENTITY_TYPES.register(modEventBus);
        EnergyContent.MENUS.register(modEventBus);

        // Register the factory module's content (Fuel: Oil/Fuel fluids, Refinery)
        FactoryFluids.FLUID_TYPES.register(modEventBus);
        FactoryFluids.FLUIDS.register(modEventBus);
        FactoryFluids.BLOCKS.register(modEventBus);
        FactoryFluids.ITEMS.register(modEventBus);
        FactoryContent.BLOCKS.register(modEventBus);
        FactoryContent.ITEMS.register(modEventBus);
        FactoryContent.BLOCK_ENTITY_TYPES.register(modEventBus);
        FactoryContent.MENUS.register(modEventBus);
        FactoryWorldgen.FEATURES.register(modEventBus);
        FactoryWorldgen.PLACEMENT_MODIFIER_TYPES.register(modEventBus);

        // Register ourselves for server and other game events we are interested in.
        // Note that this is necessary if and only if we want *this* class (BuildCraft) to respond directly to events.
        // Do not add this line if there are no @SubscribeEvent-annotated functions in this class, like onServerStarting() below.
        NeoForge.EVENT_BUS.register(this);

        // Register our mod's ModConfigSpec so that FML can create and load the config file for us
        modContainer.registerConfig(ModConfig.Type.COMMON, Config.SPEC);
    }

    private void commonSetup(FMLCommonSetupEvent event) {
        // Some common setup code
        LOGGER.info("HELLO FROM COMMON SETUP");

        if (Config.LOG_DIRT_BLOCK.getAsBoolean()) {
            LOGGER.info("DIRT BLOCK >> {}", BuiltInRegistries.BLOCK.getKey(Blocks.DIRT));
        }

        LOGGER.info("{}{}", Config.MAGIC_NUMBER_INTRODUCTION.get(), Config.MAGIC_NUMBER.getAsInt());

        Config.ITEM_STRINGS.get().forEach((item) -> LOGGER.info("ITEM >> {}", item));
    }

    // Expose the Quarry's internal energy buffer as an FE capability, so any FE-compatible cable/machine can charge it
    private void registerCapabilities(RegisterCapabilitiesEvent event) {
        event.registerBlockEntity(Capabilities.Energy.BLOCK, BuildersContent.QUARRY_BLOCK_ENTITY.get(),
                (blockEntity, side) -> ((QuarryBlockEntity) blockEntity).getEnergyHandler(side));

        // Expose every pipe tier as an item-transfer target, so hoppers, other mods' machines, and the Quarry's
        // own drop-routing (which already generically checks Capabilities.Item.BLOCK on its neighbors) can push
        // items into a pipe, not just other pipes via PipeBlockEntity.acceptItem.
        registerPipeItemCapability(event, TransportContent.PIPE_COBBLESTONE_BLOCK_ENTITY);
        registerPipeItemCapability(event, TransportContent.PIPE_STONE_BLOCK_ENTITY);
        registerPipeItemCapability(event, TransportContent.PIPE_IRON_BLOCK_ENTITY);
        registerPipeItemCapability(event, TransportContent.PIPE_GOLD_BLOCK_ENTITY);
        registerPipeItemCapability(event, TransportContent.PIPE_VOID_BLOCK_ENTITY);
        registerPipeItemCapability(event, TransportContent.PIPE_QUARTZ_BLOCK_ENTITY);
        registerPipeItemCapability(event, TransportContent.PIPE_SANDSTONE_BLOCK_ENTITY);
        registerPipeItemCapability(event, TransportContent.PIPE_CLAY_BLOCK_ENTITY);
        registerPipeItemCapability(event, TransportContent.PIPE_STRUCTURE_BLOCK_ENTITY);
        registerPipeItemCapability(event, TransportContent.PIPE_WOOD_BLOCK_ENTITY);
        registerPipeItemCapability(event, TransportContent.PIPE_DIAMOND_BLOCK_ENTITY);
        registerPipeItemCapability(event, TransportContent.PIPE_LAPIS_BLOCK_ENTITY);
        registerPipeItemCapability(event, TransportContent.PIPE_DAIZULI_BLOCK_ENTITY);
        registerPipeItemCapability(event, TransportContent.PIPE_OBSIDIAN_BLOCK_ENTITY);
        registerPipeItemCapability(event, TransportContent.PIPE_WOOD_DIAMOND_BLOCK_ENTITY);
        registerPipeItemCapability(event, TransportContent.PIPE_STRIPES_BLOCK_ENTITY);
        registerPipeItemCapability(event, TransportContent.PIPE_EMZULI_BLOCK_ENTITY);

        // Obsidian pulls dropped item entities via generic FE (see ObsidianBehaviour's javadoc) - same pattern
        // as Wood's own energy capability above.
        event.registerBlockEntity(Capabilities.Energy.BLOCK, TransportContent.PIPE_OBSIDIAN_BLOCK_ENTITY.get(),
                (blockEntity, side) -> blockEntity.getBehaviour().getEnergyHandler());

        // Wood pulls items via generic FE (see WoodBehaviour's javadoc) - expose its buffer so any FE-compatible
        // cable/machine can charge it, same pattern as the Quarry's own energy capability above.
        event.registerBlockEntity(Capabilities.Energy.BLOCK, TransportContent.PIPE_WOOD_BLOCK_ENTITY.get(),
                (blockEntity, side) -> blockEntity.getBehaviour().getEnergyHandler());
        event.registerBlockEntity(Capabilities.Energy.BLOCK, TransportContent.PIPE_WOOD_DIAMOND_BLOCK_ENTITY.get(),
                (blockEntity, side) -> blockEntity.getBehaviour().getEnergyHandler());
        event.registerBlockEntity(Capabilities.Energy.BLOCK, TransportContent.PIPE_STRIPES_BLOCK_ENTITY.get(),
                (blockEntity, side) -> blockEntity.getBehaviour().getEnergyHandler());
        event.registerBlockEntity(Capabilities.Energy.BLOCK, TransportContent.PIPE_EMZULI_BLOCK_ENTITY.get(),
                (blockEntity, side) -> blockEntity.getBehaviour().getEnergyHandler());

        // The Combustion Engine's fuel/coolant/residue tanks - real pipes/buckets/other fluid handlers can fill
        // (fuel/coolant) or drain (residue) it through this. Every other engine tier pushes FE out but doesn't
        // expose an Energy.BLOCK capability of its own (they're push-only power sources, matching source).
        event.registerBlockEntity(Capabilities.Fluid.BLOCK, EnergyContent.ENGINE_COMBUSTION_BLOCK_ENTITY.get(),
                (blockEntity, side) -> blockEntity.getFluidHandler());

        // The Refinery's oil-in/fuel-out tanks and its FE buffer - real pipes/buckets fill the oil side and
        // drain the fuel side through the fluid capability; an Engine (any tier, pushed continuously - the
        // Refinery isn't a PulsedEnergyReceiver, matching source using a plain RF battery) powers it via energy.
        event.registerBlockEntity(Capabilities.Fluid.BLOCK, FactoryContent.REFINERY_BLOCK_ENTITY.get(),
                (blockEntity, side) -> blockEntity.getFluidHandler());
        event.registerBlockEntity(Capabilities.Energy.BLOCK, FactoryContent.REFINERY_BLOCK_ENTITY.get(),
                (blockEntity, side) -> blockEntity.getEnergyHandler());

        // The Tank's own fluid slot, exposed on every side so pipes/buckets/other machines can fill or drain it
        // through any one block of a connected vertical stack - the handler itself already spans the whole
        // stack (see TankBlockEntity.getFluidHandler's javadoc), matching real source's TileTank implementing
        // IFluidHandler itself, backed by getTanks().
        event.registerBlockEntity(Capabilities.Fluid.BLOCK, FactoryContent.TANK_BLOCK_ENTITY.get(),
                (blockEntity, side) -> blockEntity.getFluidHandler());

        // Fluid pipes (Stage 1: Cobblestone/Iron/Void) - expose each tier's own buffer as a real fluid-transfer
        // target, so tanks/pumps/other mods' machines can push into or pull from a pipe directly, not just
        // through pipe-to-pipe handoff (FluidPipeBlockEntity.acceptFluid).
        event.registerBlockEntity(Capabilities.Fluid.BLOCK, TransportContent.PIPE_COBBLESTONE_FLUID_BLOCK_ENTITY.get(),
                (blockEntity, side) -> blockEntity.getFluidHandler(side));
        event.registerBlockEntity(Capabilities.Fluid.BLOCK, TransportContent.PIPE_IRON_FLUID_BLOCK_ENTITY.get(),
                (blockEntity, side) -> blockEntity.getFluidHandler(side));
        event.registerBlockEntity(Capabilities.Fluid.BLOCK, TransportContent.PIPE_VOID_FLUID_BLOCK_ENTITY.get(),
                (blockEntity, side) -> blockEntity.getFluidHandler(side));

        // Fluid pipes Stage 2 (Gold/Stone/Quartz/Sandstone/Clay/Wood/Diamond-Wood/Diamond).
        event.registerBlockEntity(Capabilities.Fluid.BLOCK, TransportContent.PIPE_GOLD_FLUID_BLOCK_ENTITY.get(),
                (blockEntity, side) -> blockEntity.getFluidHandler(side));
        event.registerBlockEntity(Capabilities.Fluid.BLOCK, TransportContent.PIPE_STONE_FLUID_BLOCK_ENTITY.get(),
                (blockEntity, side) -> blockEntity.getFluidHandler(side));
        event.registerBlockEntity(Capabilities.Fluid.BLOCK, TransportContent.PIPE_QUARTZ_FLUID_BLOCK_ENTITY.get(),
                (blockEntity, side) -> blockEntity.getFluidHandler(side));
        event.registerBlockEntity(Capabilities.Fluid.BLOCK, TransportContent.PIPE_SANDSTONE_FLUID_BLOCK_ENTITY.get(),
                (blockEntity, side) -> blockEntity.getFluidHandler(side));
        event.registerBlockEntity(Capabilities.Fluid.BLOCK, TransportContent.PIPE_CLAY_FLUID_BLOCK_ENTITY.get(),
                (blockEntity, side) -> blockEntity.getFluidHandler(side));
        event.registerBlockEntity(Capabilities.Fluid.BLOCK, TransportContent.PIPE_WOOD_FLUID_BLOCK_ENTITY.get(),
                (blockEntity, side) -> blockEntity.getFluidHandler(side));
        event.registerBlockEntity(Capabilities.Fluid.BLOCK, TransportContent.PIPE_WOOD_DIAMOND_FLUID_BLOCK_ENTITY.get(),
                (blockEntity, side) -> blockEntity.getFluidHandler(side));
        event.registerBlockEntity(Capabilities.Fluid.BLOCK, TransportContent.PIPE_DIAMOND_FLUID_BLOCK_ENTITY.get(),
                (blockEntity, side) -> blockEntity.getFluidHandler(side));

        // Mining Well/Pump - real source's shared TileMiner MJ battery, exposed as generic FE (same substitution
        // category as the Quarry/Engines/pipes' own FE-for-MJ swaps).
        event.registerBlockEntity(Capabilities.Energy.BLOCK, FactoryContent.MINING_WELL_BLOCK_ENTITY.get(),
                (blockEntity, side) -> blockEntity.getEnergyHandler());
        event.registerBlockEntity(Capabilities.Energy.BLOCK, FactoryContent.PUMP_BLOCK_ENTITY.get(),
                (blockEntity, side) -> blockEntity.getEnergyHandler());
        // The Pump's own collected-fluid buffer - real pipes/tanks/buckets can drain it through this (the Pump
        // itself never actively pushes outward - see PumpBlockEntity's javadoc for why that's simpler here).
        event.registerBlockEntity(Capabilities.Fluid.BLOCK, FactoryContent.PUMP_BLOCK_ENTITY.get(),
                (blockEntity, side) -> blockEntity.getFluidHandler());

        // The Auto Workbench's FE buffer (an Engine can charge it directly, see AutoWorkbenchBlockEntity's
        // javadoc for why external power isn't gated by canCraft() the way its own passive trickle is) and its
        // combined materials-insert/result-extract item handler, so pipes/hoppers can feed ingredients in and
        // pull finished items out through any side.
        event.registerBlockEntity(Capabilities.Energy.BLOCK, FactoryContent.AUTO_WORKBENCH_BLOCK_ENTITY.get(),
                (blockEntity, side) -> blockEntity.getEnergyHandler());
        event.registerBlockEntity(Capabilities.Item.BLOCK, FactoryContent.AUTO_WORKBENCH_BLOCK_ENTITY.get(),
                (blockEntity, side) -> blockEntity.getItemHandler());
    }

    private void registerPipeItemCapability(RegisterCapabilitiesEvent event,
            DeferredHolder<BlockEntityType<?>, BlockEntityType<PipeBlockEntity>> pipeBlockEntityType) {
        event.registerBlockEntity(Capabilities.Item.BLOCK, pipeBlockEntityType.get(),
                (blockEntity, side) -> blockEntity.getItemHandler(side));
    }

    // Lets the Quarry keep its working area loaded and ticking even when no player is nearby
    private void registerTicketControllers(RegisterTicketControllersEvent event) {
        event.register(BuildersContent.QUARRY_TICKET_CONTROLLER);
    }

    // You can use SubscribeEvent and let the Event Bus discover methods to call
    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        // Do something when the server starts
        LOGGER.info("HELLO from server starting");
    }
}
