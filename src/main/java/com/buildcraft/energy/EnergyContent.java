package com.buildcraft.energy;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

import com.buildcraft.BuildCraft;
import com.buildcraft.energy.block.EngineBlock;
import com.buildcraft.energy.blockentity.CombustionEngineBlockEntity;
import com.buildcraft.energy.blockentity.CreativeEngineBlockEntity;
import com.buildcraft.energy.blockentity.RedstoneEngineBlockEntity;
import com.buildcraft.energy.blockentity.StirlingEngineBlockEntity;
import com.buildcraft.energy.menu.CombustionEngineMenu;
import com.buildcraft.energy.menu.StirlingEngineMenu;

/**
 * Registers one {@link EngineBlock}/item/block-entity-type per engine tier - see
 * {@link com.buildcraft.energy.blockentity.EngineBlockEntity} for the shared heat/power/piston architecture.
 * One block per tier (not source's single metadata-variant {@code BlockEngine_BC8}), matching how every other
 * module in this port already handles per-material variants.
 */
public final class EnergyContent {
    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(BuildCraft.MODID);
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(BuildCraft.MODID);
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITY_TYPES =
            DeferredRegister.create(BuiltInRegistries.BLOCK_ENTITY_TYPE, BuildCraft.MODID);
    public static final DeferredRegister<MenuType<?>> MENUS = DeferredRegister.create(Registries.MENU, BuildCraft.MODID);

    public static final DeferredBlock<EngineBlock> ENGINE_REDSTONE_BLOCK = BLOCKS.registerBlock("engine_redstone",
            props -> new EngineBlock(props, RedstoneEngineBlockEntity::new),
            () -> BlockBehaviour.Properties.of().mapColor(MapColor.WOOD).strength(0.5F, 3.0F).noOcclusion());
    public static final DeferredItem<BlockItem> ENGINE_REDSTONE_ITEM =
            ITEMS.registerSimpleBlockItem("engine_redstone", ENGINE_REDSTONE_BLOCK);
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<RedstoneEngineBlockEntity>> ENGINE_REDSTONE_BLOCK_ENTITY =
            BLOCK_ENTITY_TYPES.register("engine_redstone", () -> new BlockEntityType<>(
                    RedstoneEngineBlockEntity::new, ENGINE_REDSTONE_BLOCK.get()));

    public static final DeferredBlock<EngineBlock> ENGINE_STIRLING_BLOCK = BLOCKS.registerBlock("engine_stirling",
            props -> new EngineBlock(props, StirlingEngineBlockEntity::new),
            () -> BlockBehaviour.Properties.of().mapColor(MapColor.STONE).strength(1.0F, 5.0F).noOcclusion());
    public static final DeferredItem<BlockItem> ENGINE_STIRLING_ITEM =
            ITEMS.registerSimpleBlockItem("engine_stirling", ENGINE_STIRLING_BLOCK);
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<StirlingEngineBlockEntity>> ENGINE_STIRLING_BLOCK_ENTITY =
            BLOCK_ENTITY_TYPES.register("engine_stirling", () -> new BlockEntityType<>(
                    StirlingEngineBlockEntity::new, ENGINE_STIRLING_BLOCK.get()));

    public static final DeferredHolder<MenuType<?>, MenuType<StirlingEngineMenu>> STIRLING_ENGINE_MENU =
            MENUS.register("stirling_engine", () -> IMenuTypeExtension.create((windowId, inv, buf) ->
                    new StirlingEngineMenu(EnergyContent.STIRLING_ENGINE_MENU.get(), windowId, inv, new SimpleContainer(1))));

    public static final DeferredBlock<EngineBlock> ENGINE_CREATIVE_BLOCK = BLOCKS.registerBlock("engine_creative",
            props -> new EngineBlock(props, CreativeEngineBlockEntity::new),
            () -> BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_PURPLE).strength(1.0F, 5.0F).noOcclusion());
    public static final DeferredItem<BlockItem> ENGINE_CREATIVE_ITEM =
            ITEMS.registerSimpleBlockItem("engine_creative", ENGINE_CREATIVE_BLOCK);
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<CreativeEngineBlockEntity>> ENGINE_CREATIVE_BLOCK_ENTITY =
            BLOCK_ENTITY_TYPES.register("engine_creative", () -> new BlockEntityType<>(
                    CreativeEngineBlockEntity::new, ENGINE_CREATIVE_BLOCK.get()));

    public static final DeferredBlock<EngineBlock> ENGINE_COMBUSTION_BLOCK = BLOCKS.registerBlock("engine_combustion",
            props -> new EngineBlock(props, CombustionEngineBlockEntity::new),
            () -> BlockBehaviour.Properties.of().mapColor(MapColor.METAL).strength(2.0F, 8.0F).noOcclusion());
    public static final DeferredItem<BlockItem> ENGINE_COMBUSTION_ITEM =
            ITEMS.registerSimpleBlockItem("engine_combustion", ENGINE_COMBUSTION_BLOCK);
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<CombustionEngineBlockEntity>> ENGINE_COMBUSTION_BLOCK_ENTITY =
            BLOCK_ENTITY_TYPES.register("engine_combustion", () -> new BlockEntityType<>(
                    CombustionEngineBlockEntity::new, ENGINE_COMBUSTION_BLOCK.get()));

    public static final DeferredHolder<MenuType<?>, MenuType<CombustionEngineMenu>> COMBUSTION_ENGINE_MENU =
            MENUS.register("combustion_engine", () -> IMenuTypeExtension.create((windowId, inv, buf) ->
                    new CombustionEngineMenu(EnergyContent.COMBUSTION_ENGINE_MENU.get(), windowId, inv, null)));

    private EnergyContent() {}
}
