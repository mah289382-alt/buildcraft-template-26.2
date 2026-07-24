package com.buildcraft.factory;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
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
import com.buildcraft.factory.block.MiningWellBlock;
import com.buildcraft.factory.block.PumpBlock;
import com.buildcraft.factory.block.RefineryBlock;
import com.buildcraft.factory.block.TankBlock;
import com.buildcraft.factory.block.TubeBlock;
import com.buildcraft.factory.blockentity.MiningWellBlockEntity;
import com.buildcraft.factory.blockentity.PumpBlockEntity;
import com.buildcraft.factory.blockentity.RefineryBlockEntity;
import com.buildcraft.factory.blockentity.TankBlockEntity;
import com.buildcraft.factory.menu.RefineryMenu;

/** Registers the Refinery, Tank, Mining Well, and Pump blocks/items/block-entities/menus - see
 * {@link RefineryBlockEntity} for the real Refinery recipe, {@link TankBlockEntity} for the Tank's real
 * vertical-stacking behaviour, and {@link MiningWellBlockEntity}/{@link PumpBlockEntity} for the real shared
 * {@code TileMiner} mining/pumping mechanic. */
public final class FactoryContent {
    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(BuildCraft.MODID);
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(BuildCraft.MODID);
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITY_TYPES =
            DeferredRegister.create(BuiltInRegistries.BLOCK_ENTITY_TYPE, BuildCraft.MODID);
    public static final DeferredRegister<MenuType<?>> MENUS = DeferredRegister.create(Registries.MENU, BuildCraft.MODID);

    public static final DeferredBlock<RefineryBlock> REFINERY_BLOCK = BLOCKS.registerBlock("refinery",
            RefineryBlock::new,
            // .noOcclusion() is required for RenderShape.INVISIBLE + a full-cube getShape() - otherwise
            // neighbors cull their faces against it, the same "xray" bug found and fixed for engines earlier.
            () -> BlockBehaviour.Properties.of().mapColor(MapColor.METAL).strength(2.0F, 8.0F).noOcclusion());
    public static final DeferredItem<BlockItem> REFINERY_ITEM =
            ITEMS.registerSimpleBlockItem("refinery", REFINERY_BLOCK);
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<RefineryBlockEntity>> REFINERY_BLOCK_ENTITY =
            BLOCK_ENTITY_TYPES.register("refinery", () -> new BlockEntityType<>(
                    RefineryBlockEntity::new, REFINERY_BLOCK.get()));

    public static final DeferredHolder<MenuType<?>, MenuType<RefineryMenu>> REFINERY_MENU =
            MENUS.register("refinery", () -> IMenuTypeExtension.create((windowId, inv, buf) ->
                    new RefineryMenu(FactoryContent.REFINERY_MENU.get(), windowId, inv, null)));

    // .noOcclusion() since the real bounding box (2/16..14/16 x 0..16 x 2/16..14/16) isn't a full cube - same
    // "xray" bug category already found and fixed for the Refinery/Frame if this were omitted.
    public static final DeferredBlock<TankBlock> TANK_BLOCK = BLOCKS.registerBlock("tank",
            TankBlock::new,
            () -> BlockBehaviour.Properties.of().mapColor(MapColor.METAL).strength(2.0F, 8.0F).noOcclusion());
    public static final DeferredItem<BlockItem> TANK_ITEM =
            ITEMS.registerSimpleBlockItem("tank", TANK_BLOCK);
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<TankBlockEntity>> TANK_BLOCK_ENTITY =
            BLOCK_ENTITY_TYPES.register("tank", () -> new BlockEntityType<>(
                    TankBlockEntity::new, TANK_BLOCK.get()));

    public static final DeferredBlock<MiningWellBlock> MINING_WELL_BLOCK = BLOCKS.registerBlock("mining_well",
            MiningWellBlock::new,
            () -> BlockBehaviour.Properties.of().mapColor(MapColor.METAL).strength(2.0F, 8.0F));
    public static final DeferredItem<BlockItem> MINING_WELL_ITEM =
            ITEMS.registerSimpleBlockItem("mining_well", MINING_WELL_BLOCK);
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<MiningWellBlockEntity>> MINING_WELL_BLOCK_ENTITY =
            BLOCK_ENTITY_TYPES.register("mining_well", () -> new BlockEntityType<>(
                    MiningWellBlockEntity::new, MINING_WELL_BLOCK.get()));

    public static final DeferredBlock<PumpBlock> PUMP_BLOCK = BLOCKS.registerBlock("pump",
            PumpBlock::new,
            () -> BlockBehaviour.Properties.of().mapColor(MapColor.METAL).strength(2.0F, 8.0F));
    public static final DeferredItem<BlockItem> PUMP_ITEM =
            ITEMS.registerSimpleBlockItem("pump", PUMP_BLOCK);
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<PumpBlockEntity>> PUMP_BLOCK_ENTITY =
            BLOCK_ENTITY_TYPES.register("pump", () -> new BlockEntityType<>(
                    PumpBlockEntity::new, PUMP_BLOCK.get()));

    // Real BlockTube, ported per-machine (see TubeBlock's own javadoc for why 2 registrations of one shared
    // class instead of real source's single shared block) - block-only, no item/BlockEntity: real source's
    // block is genuinely player-unbreakable and only ever placed/removed by its owning MinerBlockEntity
    // directly, never obtainable or meaningfully player-placeable.
    public static final DeferredBlock<TubeBlock> MINING_WELL_TUBE_BLOCK = BLOCKS.registerBlock("mining_well_tube",
            TubeBlock::new,
            () -> BlockBehaviour.Properties.of().mapColor(MapColor.METAL).strength(-1.0F, 3_600_000.0F).noOcclusion().noLootTable());
    public static final DeferredBlock<TubeBlock> PUMP_TUBE_BLOCK = BLOCKS.registerBlock("pump_tube",
            TubeBlock::new,
            () -> BlockBehaviour.Properties.of().mapColor(MapColor.METAL).strength(-1.0F, 3_600_000.0F).noOcclusion().noLootTable());

    private FactoryContent() {}
}
