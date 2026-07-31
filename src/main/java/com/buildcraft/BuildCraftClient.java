package com.buildcraft;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.fog.FogData;
import net.minecraft.client.renderer.fog.environment.FogEnvironment;
import org.joml.Vector4f;
import org.jspecify.annotations.Nullable;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraft.client.renderer.block.FluidModel;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.resources.Identifier;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterFluidModelsEvent;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;
import net.neoforged.neoforge.client.event.RegisterRenderPipelinesEvent;
import net.neoforged.neoforge.client.extensions.common.IClientFluidTypeExtensions;
import net.neoforged.neoforge.client.extensions.common.RegisterClientExtensionsEvent;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

import com.buildcraft.builders.BuildersContent;
import com.buildcraft.builders.client.render.QuarryBlockEntityRenderer;
import com.buildcraft.energy.EnergyContent;
import com.buildcraft.energy.client.menu.CombustionEngineScreen;
import com.buildcraft.energy.client.menu.StirlingEngineScreen;
import com.buildcraft.energy.client.render.EngineBlockEntityRenderer;
import com.buildcraft.factory.FactoryContent;
import com.buildcraft.factory.FactoryFluids;
import com.buildcraft.factory.client.menu.RefineryScreen;
import com.buildcraft.factory.client.render.MinerBlockEntityRenderer;
import com.buildcraft.factory.client.render.RefineryBlockEntityRenderer;
import com.buildcraft.factory.client.render.TankBlockEntityRenderer;
import com.buildcraft.transport.TransportContent;
import com.buildcraft.transport.client.menu.DiamondFilterScreen;
import com.buildcraft.transport.client.menu.EmzuliFilterScreen;
import com.buildcraft.transport.client.menu.WoodDiamondFilterScreen;
import com.buildcraft.transport.client.render.FluidPipeBlockEntityRenderer;
import com.buildcraft.transport.client.render.PipeBlockEntityRenderer;

// This class will not load on dedicated servers. Accessing client side code from here is safe.
@Mod(value = BuildCraft.MODID, dist = Dist.CLIENT)
// You can use EventBusSubscriber to automatically register all static methods in the class annotated with @SubscribeEvent
@EventBusSubscriber(modid = BuildCraft.MODID, value = Dist.CLIENT)
public class BuildCraftClient {
    public BuildCraftClient(ModContainer container) {
        // Allows NeoForge to create a config screen for this mod's configs.
        // The config screen is accessed by going to the Mods screen > clicking on your mod > clicking on config.
        // Do not forget to add translations for your config options to the en_us.json file.
        container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
    }

    @SubscribeEvent
    static void onClientSetup(FMLClientSetupEvent event) {
        // Some client setup code
        BuildCraft.LOGGER.info("HELLO FROM CLIENT SETUP");
        BuildCraft.LOGGER.info("MINECRAFT NAME >> {}", Minecraft.getInstance().getUser().getName());
    }

    @SubscribeEvent
    static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(BuildersContent.QUARRY_BLOCK_ENTITY.get(), QuarryBlockEntityRenderer::new);

        event.registerBlockEntityRenderer(TransportContent.PIPE_COBBLESTONE_BLOCK_ENTITY.get(), PipeBlockEntityRenderer::new);
        event.registerBlockEntityRenderer(TransportContent.PIPE_STONE_BLOCK_ENTITY.get(), PipeBlockEntityRenderer::new);
        event.registerBlockEntityRenderer(TransportContent.PIPE_IRON_BLOCK_ENTITY.get(), PipeBlockEntityRenderer::new);
        event.registerBlockEntityRenderer(TransportContent.PIPE_GOLD_BLOCK_ENTITY.get(), PipeBlockEntityRenderer::new);
        event.registerBlockEntityRenderer(TransportContent.PIPE_VOID_BLOCK_ENTITY.get(), PipeBlockEntityRenderer::new);
        event.registerBlockEntityRenderer(TransportContent.PIPE_QUARTZ_BLOCK_ENTITY.get(), PipeBlockEntityRenderer::new);
        event.registerBlockEntityRenderer(TransportContent.PIPE_SANDSTONE_BLOCK_ENTITY.get(), PipeBlockEntityRenderer::new);
        event.registerBlockEntityRenderer(TransportContent.PIPE_CLAY_BLOCK_ENTITY.get(), PipeBlockEntityRenderer::new);
        event.registerBlockEntityRenderer(TransportContent.PIPE_STRUCTURE_BLOCK_ENTITY.get(), PipeBlockEntityRenderer::new);
        event.registerBlockEntityRenderer(TransportContent.PIPE_WOOD_BLOCK_ENTITY.get(), PipeBlockEntityRenderer::new);
        event.registerBlockEntityRenderer(TransportContent.PIPE_DIAMOND_BLOCK_ENTITY.get(), PipeBlockEntityRenderer::new);
        event.registerBlockEntityRenderer(TransportContent.PIPE_LAPIS_BLOCK_ENTITY.get(), PipeBlockEntityRenderer::new);
        event.registerBlockEntityRenderer(TransportContent.PIPE_DAIZULI_BLOCK_ENTITY.get(), PipeBlockEntityRenderer::new);
        event.registerBlockEntityRenderer(TransportContent.PIPE_OBSIDIAN_BLOCK_ENTITY.get(), PipeBlockEntityRenderer::new);
        event.registerBlockEntityRenderer(TransportContent.PIPE_WOOD_DIAMOND_BLOCK_ENTITY.get(), PipeBlockEntityRenderer::new);
        event.registerBlockEntityRenderer(TransportContent.PIPE_STRIPES_BLOCK_ENTITY.get(), PipeBlockEntityRenderer::new);
        event.registerBlockEntityRenderer(TransportContent.PIPE_EMZULI_BLOCK_ENTITY.get(), PipeBlockEntityRenderer::new);

        event.registerBlockEntityRenderer(EnergyContent.ENGINE_REDSTONE_BLOCK_ENTITY.get(), EngineBlockEntityRenderer::new);
        event.registerBlockEntityRenderer(EnergyContent.ENGINE_STIRLING_BLOCK_ENTITY.get(), EngineBlockEntityRenderer::new);
        event.registerBlockEntityRenderer(EnergyContent.ENGINE_CREATIVE_BLOCK_ENTITY.get(), EngineBlockEntityRenderer::new);
        event.registerBlockEntityRenderer(EnergyContent.ENGINE_COMBUSTION_BLOCK_ENTITY.get(), EngineBlockEntityRenderer::new);

        event.registerBlockEntityRenderer(FactoryContent.REFINERY_BLOCK_ENTITY.get(), RefineryBlockEntityRenderer::new);
        event.registerBlockEntityRenderer(FactoryContent.TANK_BLOCK_ENTITY.get(), TankBlockEntityRenderer::new);

        event.registerBlockEntityRenderer(TransportContent.PIPE_COBBLESTONE_FLUID_BLOCK_ENTITY.get(), FluidPipeBlockEntityRenderer::new);
        event.registerBlockEntityRenderer(TransportContent.PIPE_IRON_FLUID_BLOCK_ENTITY.get(), FluidPipeBlockEntityRenderer::new);
        event.registerBlockEntityRenderer(TransportContent.PIPE_VOID_FLUID_BLOCK_ENTITY.get(), FluidPipeBlockEntityRenderer::new);
        event.registerBlockEntityRenderer(TransportContent.PIPE_GOLD_FLUID_BLOCK_ENTITY.get(), FluidPipeBlockEntityRenderer::new);
        event.registerBlockEntityRenderer(TransportContent.PIPE_STONE_FLUID_BLOCK_ENTITY.get(), FluidPipeBlockEntityRenderer::new);
        event.registerBlockEntityRenderer(TransportContent.PIPE_QUARTZ_FLUID_BLOCK_ENTITY.get(), FluidPipeBlockEntityRenderer::new);
        event.registerBlockEntityRenderer(TransportContent.PIPE_SANDSTONE_FLUID_BLOCK_ENTITY.get(), FluidPipeBlockEntityRenderer::new);
        event.registerBlockEntityRenderer(TransportContent.PIPE_CLAY_FLUID_BLOCK_ENTITY.get(), FluidPipeBlockEntityRenderer::new);
        event.registerBlockEntityRenderer(TransportContent.PIPE_WOOD_FLUID_BLOCK_ENTITY.get(), FluidPipeBlockEntityRenderer::new);
        event.registerBlockEntityRenderer(TransportContent.PIPE_WOOD_DIAMOND_FLUID_BLOCK_ENTITY.get(), FluidPipeBlockEntityRenderer::new);
        event.registerBlockEntityRenderer(TransportContent.PIPE_DIAMOND_FLUID_BLOCK_ENTITY.get(), FluidPipeBlockEntityRenderer::new);

        // Mining Well/Pump: TubeBlock itself is a pure invisible marker (see its own javadoc) - this renderer
        // draws the ENTIRE visible shaft as one continuous, flowing cosmetic beam (see its own class javadoc).
        // Mining Well gets the octagonal twisting "drilling" variant (explicit user request); Pump keeps the
        // plain square beam.
        event.registerBlockEntityRenderer(FactoryContent.MINING_WELL_BLOCK_ENTITY.get(),
                context -> new MinerBlockEntityRenderer(context, Identifier.fromNamespaceAndPath(BuildCraft.MODID, "textures/block/mining_well_tube.png"), true));
        event.registerBlockEntityRenderer(FactoryContent.PUMP_BLOCK_ENTITY.get(),
                context -> new MinerBlockEntityRenderer(context, Identifier.fromNamespaceAndPath(BuildCraft.MODID, "textures/block/pump_tube.png"), false));
    }

    // A custom "opaque + no directional shading" pipeline for the Quarry's laser geometry - see
    // QuarryBlockEntityRenderer.LASER_UNLIT_CUTOUT_PIPELINE's javadoc for why the built-in render types don't work.
    @SubscribeEvent
    static void registerPipelines(RegisterRenderPipelinesEvent event) {
        event.registerPipeline(QuarryBlockEntityRenderer.LASER_UNLIT_CUTOUT_PIPELINE);
    }

    // Modern NeoForge (this 26.2 beta) removed IClientFluidTypeExtensions' still/flowing texture methods that
    // older versions had - registering an instance is still required for a custom FluidType to render at all,
    // but texture resolution appears to now happen elsewhere (verified empirically via a client smoke test,
    // not found in any decompiled source for this exact version - flagged in engines/pipes-status-adjacent
    // memory as a real open question if this needs revisiting).
    @SubscribeEvent
    static void registerFluidExtensions(RegisterClientExtensionsEvent event) {
        event.registerFluidType(OIL_CLIENT_EXTENSIONS, FactoryFluids.OIL_TYPE.get());
        event.registerFluidType(IClientFluidTypeExtensions.DEFAULT, FactoryFluids.FUEL_TYPE.get());
    }

    /**
     * Real source doesn't touch fluid submersion rendering at all (crude Oil is just a plain fluid there, no
     * special camera effect) - a direct user request: the screen should go essentially black while the
     * camera is inside Oil, like being trapped somewhere opaque, distinct from water's blue tint or lava's
     * orange one. {@code modifyFogColor}/{@code modifyFogRender} are the real, documented extension points for
     * this on {@link IClientFluidTypeExtensions} (confirmed by reading the interface directly) - the same
     * mechanism vanilla uses internally for water/lava submersion, just exposed for a custom {@code FluidType}.
     * Fog values (start=0, end=2) mirror vanilla's own Powdered Snow "whiteout" effect
     * ({@code PowderedSnowFogEnvironment}, real source values) - the closest vanilla analogue to "the screen
     * goes solid" - just black instead of white.
     */
    private static final IClientFluidTypeExtensions OIL_CLIENT_EXTENSIONS = new IClientFluidTypeExtensions() {
        @Override
        public void modifyFogColor(Camera camera, float partialTick, ClientLevel level, int renderDistance, float darkenWorldAmount, Vector4f fluidFogColor) {
            fluidFogColor.set(0.0f, 0.0f, 0.0f, 1.0f);
        }

        @Override
        public void modifyFogRender(Camera camera, @Nullable FogEnvironment environment, float renderDistance, float partialTick, FogData fogData) {
            fogData.environmentalStart = 0.0f;
            fogData.environmentalEnd = 2.0f;
            fogData.skyEnd = fogData.environmentalEnd;
            fogData.cloudEnd = fogData.environmentalEnd;
        }
    };

    /**
     * This 26.2 beta replaced the older still/flow texture methods on {@link IClientFluidTypeExtensions} with a
     * brand-new event-driven system - {@code RegisterFluidModelsEvent}, firing per-{@code Fluid} on the mod bus
     * (client-only), needing a real {@link FluidModel.Unbaked} built from sprite {@link Material}s. Not found
     * documented anywhere in older references - confirmed empirically via a client smoke test surfacing
     * "Missing FluidModel for fluid ..." warnings once {@link #registerFluidExtensions} alone (the old-style
     * registration, kept since it's still required for a custom FluidType to work at all) wasn't enough.
     */
    @SubscribeEvent
    static void registerFluidModels(RegisterFluidModelsEvent event) {
        event.register(
                new FluidModel.Unbaked(
                        new Material(Identifier.fromNamespaceAndPath(BuildCraft.MODID, "block/oil_still")),
                        new Material(Identifier.fromNamespaceAndPath(BuildCraft.MODID, "block/oil_flow")),
                        null, null),
                FactoryFluids.OIL_SOURCE, FactoryFluids.OIL_FLOWING);
        event.register(
                new FluidModel.Unbaked(
                        new Material(Identifier.fromNamespaceAndPath(BuildCraft.MODID, "block/fuel_still")),
                        new Material(Identifier.fromNamespaceAndPath(BuildCraft.MODID, "block/fuel_flow")),
                        null, null),
                FactoryFluids.FUEL_SOURCE, FactoryFluids.FUEL_FLOWING);
    }

    @SubscribeEvent
    static void registerMenuScreens(RegisterMenuScreensEvent event) {
        event.register(TransportContent.DIAMOND_FILTER_MENU.get(), DiamondFilterScreen::new);
        event.register(TransportContent.WOOD_DIAMOND_FILTER_MENU.get(), WoodDiamondFilterScreen::new);
        event.register(TransportContent.EMZULI_FILTER_MENU.get(), EmzuliFilterScreen::new);
        event.register(EnergyContent.STIRLING_ENGINE_MENU.get(), StirlingEngineScreen::new);
        event.register(EnergyContent.COMBUSTION_ENGINE_MENU.get(), CombustionEngineScreen::new);
        event.register(FactoryContent.REFINERY_MENU.get(), RefineryScreen::new);
    }
}
