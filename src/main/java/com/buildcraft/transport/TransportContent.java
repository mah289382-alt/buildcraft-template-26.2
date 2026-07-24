package com.buildcraft.transport;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

import com.buildcraft.BuildCraft;
import com.buildcraft.core.item.WrenchItem;
import com.buildcraft.transport.block.FluidPipeBlock;
import com.buildcraft.transport.block.PipeBlock;
import com.buildcraft.transport.block.WoodPipeBlock;
import com.buildcraft.transport.blockentity.FluidPipeBlockEntity;
import com.buildcraft.transport.blockentity.PipeBlockEntity;
import com.buildcraft.transport.menu.DiamondFilterMenu;
import com.buildcraft.transport.menu.EmzuliFilterMenu;
import com.buildcraft.transport.menu.WoodDiamondFilterMenu;
import com.buildcraft.transport.pipe.behaviour.ClayBehaviour;
import com.buildcraft.transport.pipe.behaviour.DaizuliBehaviour;
import com.buildcraft.transport.pipe.behaviour.DiamondBehaviour;
import com.buildcraft.transport.pipe.behaviour.EmzuliBehaviour;
import com.buildcraft.transport.pipe.behaviour.IronBehaviour;
import com.buildcraft.transport.pipe.behaviour.LapisBehaviour;
import com.buildcraft.transport.pipe.behaviour.ObsidianBehaviour;
import com.buildcraft.transport.pipe.behaviour.PassiveBehaviour;
import com.buildcraft.transport.pipe.behaviour.SandstoneBehaviour;
import com.buildcraft.transport.pipe.behaviour.StripesBehaviour;
import com.buildcraft.transport.pipe.behaviour.StructureBehaviour;
import com.buildcraft.transport.pipe.behaviour.VoidBehaviour;
import com.buildcraft.transport.pipe.behaviour.WoodBehaviour;
import com.buildcraft.transport.pipe.behaviour.WoodDiamondBehaviour;

/**
 * Registers one {@link PipeBlock}/item/block-entity-type triple per pipe material. Currently the 5 tiers that
 * don't need a GUI or external power to work correctly (see {@link com.buildcraft.transport.pipe.PipeBehaviour}
 * for the extensibility point future tiers hook into):
 * <ul>
 * <li>Cobblestone - the true baseline passive router (the original's actual "plain pipe"; a previous pass in
 * this project incorrectly called this behaviour "Wood" - real Wood is a directional MJ/FE-powered extractor,
 * staged for once Engines exist to power it).
 * <li>Stone - same passive routing, a different (slightly slower) speed tier.
 * <li>Iron - forced single-direction valve, wrench-cycled.
 * <li>Gold - passive routing, faster speed tier.
 * <li>Void - destroys anything that reaches its center.
 * </ul>
 * Not yet started: Diamond/WoodDiamond (need a real filter GUI), Wood/Emzuli (need Engine power), Lapis/Daizuli
 * (colour-tagging), Obsidian/Stripes/Limiter (advanced/niche), fluid pipes, power pipes, facades, wires/gates.
 * <p>
 * Note on the registration pattern below: {@code PIPE_X_BLOCK}'s constructor lambda references
 * {@code PIPE_X_BLOCK_ENTITY} (declared further down) and vice versa. This is safe ONLY because both references
 * are inside lambda bodies (deferred field reads, resolved when the lambda actually runs - long after every
 * static field here is assigned), matching the same self-referential pattern already used by
 * {@code BuildersContent.QUARRY_BLOCK_ENTITY}. A method reference like {@code PIPE_X_BLOCK_ENTITY::get} would
 * NOT be safe here (it binds to the field's value immediately, which is still null at that point in the file).
 */
public final class TransportContent {
    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(BuildCraft.MODID);
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(BuildCraft.MODID);
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITY_TYPES =
            DeferredRegister.create(BuiltInRegistries.BLOCK_ENTITY_TYPE, BuildCraft.MODID);
    public static final DeferredRegister<MenuType<?>> MENUS = DeferredRegister.create(Registries.MENU, BuildCraft.MODID);

    public static final DeferredHolder<MenuType<?>, MenuType<DiamondFilterMenu>> DIAMOND_FILTER_MENU =
            MENUS.register("diamond_filter", () -> IMenuTypeExtension.create((windowId, inv, buf) ->
                    new DiamondFilterMenu(TransportContent.DIAMOND_FILTER_MENU.get(), windowId, inv,
                            new SimpleContainer(DiamondBehaviour.TOTAL_FILTER_SLOTS))));

    public static final DeferredHolder<MenuType<?>, MenuType<WoodDiamondFilterMenu>> WOOD_DIAMOND_FILTER_MENU =
            MENUS.register("wood_diamond_filter", () -> IMenuTypeExtension.create((windowId, inv, buf) ->
                    new WoodDiamondFilterMenu(TransportContent.WOOD_DIAMOND_FILTER_MENU.get(), windowId, inv,
                            new SimpleContainer(9))));

    public static final DeferredHolder<MenuType<?>, MenuType<EmzuliFilterMenu>> EMZULI_FILTER_MENU =
            MENUS.register("emzuli_filter", () -> IMenuTypeExtension.create((windowId, inv, buf) ->
                    new EmzuliFilterMenu(TransportContent.EMZULI_FILTER_MENU.get(), windowId, inv,
                            new SimpleContainer(4))));

    // registerItem (not plain register) is required here: it passes an already-ID-bound Item.Properties into the
    // factory, which a manually-constructed "new Item.Properties()" doesn't have - confirmed via the actual
    // crash this produced ("Item id not set" NPE in Item.Properties.itemIdOrThrow) when using plain register().
    public static final DeferredItem<WrenchItem> WRENCH_ITEM = ITEMS.registerItem("wrench",
            props -> new WrenchItem(props.stacksTo(1)));

    public static final DeferredBlock<PipeBlock> PIPE_COBBLESTONE_BLOCK = BLOCKS.registerBlock("pipe_cobblestone",
            props -> new PipeBlock(props, () -> TransportContent.PIPE_COBBLESTONE_BLOCK_ENTITY.get(), () -> PassiveBehaviour.COBBLESTONE),
            () -> BlockBehaviour.Properties.of().mapColor(MapColor.STONE).strength(0.25F, 3.0F).noOcclusion());
    public static final DeferredItem<BlockItem> PIPE_COBBLESTONE_ITEM =
            ITEMS.registerSimpleBlockItem("pipe_cobblestone", PIPE_COBBLESTONE_BLOCK);
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<PipeBlockEntity>> PIPE_COBBLESTONE_BLOCK_ENTITY =
            BLOCK_ENTITY_TYPES.register("pipe_cobblestone", () -> new BlockEntityType<>(
                    (pos, state) -> new PipeBlockEntity(TransportContent.PIPE_COBBLESTONE_BLOCK_ENTITY.get(), pos, state, () -> PassiveBehaviour.COBBLESTONE),
                    PIPE_COBBLESTONE_BLOCK.get()));

    public static final DeferredBlock<PipeBlock> PIPE_STONE_BLOCK = BLOCKS.registerBlock("pipe_stone",
            props -> new PipeBlock(props, () -> TransportContent.PIPE_STONE_BLOCK_ENTITY.get(), () -> PassiveBehaviour.STONE),
            () -> BlockBehaviour.Properties.of().mapColor(MapColor.STONE).strength(0.25F, 3.0F).noOcclusion());
    public static final DeferredItem<BlockItem> PIPE_STONE_ITEM =
            ITEMS.registerSimpleBlockItem("pipe_stone", PIPE_STONE_BLOCK);
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<PipeBlockEntity>> PIPE_STONE_BLOCK_ENTITY =
            BLOCK_ENTITY_TYPES.register("pipe_stone", () -> new BlockEntityType<>(
                    (pos, state) -> new PipeBlockEntity(TransportContent.PIPE_STONE_BLOCK_ENTITY.get(), pos, state, () -> PassiveBehaviour.STONE),
                    PIPE_STONE_BLOCK.get()));

    public static final DeferredBlock<PipeBlock> PIPE_IRON_BLOCK = BLOCKS.registerBlock("pipe_iron",
            props -> new PipeBlock(props, () -> TransportContent.PIPE_IRON_BLOCK_ENTITY.get(), IronBehaviour::new),
            () -> BlockBehaviour.Properties.of().mapColor(MapColor.METAL).strength(0.25F, 3.0F).noOcclusion());
    public static final DeferredItem<BlockItem> PIPE_IRON_ITEM =
            ITEMS.registerSimpleBlockItem("pipe_iron", PIPE_IRON_BLOCK);
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<PipeBlockEntity>> PIPE_IRON_BLOCK_ENTITY =
            BLOCK_ENTITY_TYPES.register("pipe_iron", () -> new BlockEntityType<>(
                    (pos, state) -> new PipeBlockEntity(TransportContent.PIPE_IRON_BLOCK_ENTITY.get(), pos, state, IronBehaviour::new),
                    PIPE_IRON_BLOCK.get()));

    public static final DeferredBlock<PipeBlock> PIPE_GOLD_BLOCK = BLOCKS.registerBlock("pipe_gold",
            props -> new PipeBlock(props, () -> TransportContent.PIPE_GOLD_BLOCK_ENTITY.get(), () -> PassiveBehaviour.GOLD),
            () -> BlockBehaviour.Properties.of().mapColor(MapColor.GOLD).strength(0.25F, 3.0F).noOcclusion());
    public static final DeferredItem<BlockItem> PIPE_GOLD_ITEM =
            ITEMS.registerSimpleBlockItem("pipe_gold", PIPE_GOLD_BLOCK);
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<PipeBlockEntity>> PIPE_GOLD_BLOCK_ENTITY =
            BLOCK_ENTITY_TYPES.register("pipe_gold", () -> new BlockEntityType<>(
                    (pos, state) -> new PipeBlockEntity(TransportContent.PIPE_GOLD_BLOCK_ENTITY.get(), pos, state, () -> PassiveBehaviour.GOLD),
                    PIPE_GOLD_BLOCK.get()));

    public static final DeferredBlock<PipeBlock> PIPE_VOID_BLOCK = BLOCKS.registerBlock("pipe_void",
            props -> new PipeBlock(props, () -> TransportContent.PIPE_VOID_BLOCK_ENTITY.get(), () -> VoidBehaviour.INSTANCE),
            () -> BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_BLACK).strength(0.25F, 3.0F).noOcclusion());
    public static final DeferredItem<BlockItem> PIPE_VOID_ITEM =
            ITEMS.registerSimpleBlockItem("pipe_void", PIPE_VOID_BLOCK);
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<PipeBlockEntity>> PIPE_VOID_BLOCK_ENTITY =
            BLOCK_ENTITY_TYPES.register("pipe_void", () -> new BlockEntityType<>(
                    (pos, state) -> new PipeBlockEntity(TransportContent.PIPE_VOID_BLOCK_ENTITY.get(), pos, state, () -> VoidBehaviour.INSTANCE),
                    PIPE_VOID_BLOCK.get()));

    public static final DeferredBlock<PipeBlock> PIPE_QUARTZ_BLOCK = BLOCKS.registerBlock("pipe_quartz",
            props -> new PipeBlock(props, () -> TransportContent.PIPE_QUARTZ_BLOCK_ENTITY.get(), () -> PassiveBehaviour.QUARTZ),
            () -> BlockBehaviour.Properties.of().mapColor(MapColor.QUARTZ).strength(0.25F, 3.0F).noOcclusion());
    public static final DeferredItem<BlockItem> PIPE_QUARTZ_ITEM =
            ITEMS.registerSimpleBlockItem("pipe_quartz", PIPE_QUARTZ_BLOCK);
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<PipeBlockEntity>> PIPE_QUARTZ_BLOCK_ENTITY =
            BLOCK_ENTITY_TYPES.register("pipe_quartz", () -> new BlockEntityType<>(
                    (pos, state) -> new PipeBlockEntity(TransportContent.PIPE_QUARTZ_BLOCK_ENTITY.get(), pos, state, () -> PassiveBehaviour.QUARTZ),
                    PIPE_QUARTZ_BLOCK.get()));

    public static final DeferredBlock<PipeBlock> PIPE_SANDSTONE_BLOCK = BLOCKS.registerBlock("pipe_sandstone",
            props -> new PipeBlock(props, () -> TransportContent.PIPE_SANDSTONE_BLOCK_ENTITY.get(), () -> SandstoneBehaviour.INSTANCE),
            () -> BlockBehaviour.Properties.of().mapColor(MapColor.SAND).strength(0.25F, 3.0F).noOcclusion());
    public static final DeferredItem<BlockItem> PIPE_SANDSTONE_ITEM =
            ITEMS.registerSimpleBlockItem("pipe_sandstone", PIPE_SANDSTONE_BLOCK);
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<PipeBlockEntity>> PIPE_SANDSTONE_BLOCK_ENTITY =
            BLOCK_ENTITY_TYPES.register("pipe_sandstone", () -> new BlockEntityType<>(
                    (pos, state) -> new PipeBlockEntity(TransportContent.PIPE_SANDSTONE_BLOCK_ENTITY.get(), pos, state, () -> SandstoneBehaviour.INSTANCE),
                    PIPE_SANDSTONE_BLOCK.get()));

    public static final DeferredBlock<PipeBlock> PIPE_CLAY_BLOCK = BLOCKS.registerBlock("pipe_clay",
            props -> new PipeBlock(props, () -> TransportContent.PIPE_CLAY_BLOCK_ENTITY.get(), () -> ClayBehaviour.INSTANCE),
            () -> BlockBehaviour.Properties.of().mapColor(MapColor.CLAY).strength(0.25F, 3.0F).noOcclusion());
    public static final DeferredItem<BlockItem> PIPE_CLAY_ITEM =
            ITEMS.registerSimpleBlockItem("pipe_clay", PIPE_CLAY_BLOCK);
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<PipeBlockEntity>> PIPE_CLAY_BLOCK_ENTITY =
            BLOCK_ENTITY_TYPES.register("pipe_clay", () -> new BlockEntityType<>(
                    (pos, state) -> new PipeBlockEntity(TransportContent.PIPE_CLAY_BLOCK_ENTITY.get(), pos, state, () -> ClayBehaviour.INSTANCE),
                    PIPE_CLAY_BLOCK.get()));

    public static final DeferredBlock<PipeBlock> PIPE_STRUCTURE_BLOCK = BLOCKS.registerBlock("pipe_structure",
            props -> new PipeBlock(props, () -> TransportContent.PIPE_STRUCTURE_BLOCK_ENTITY.get(), () -> StructureBehaviour.INSTANCE),
            () -> BlockBehaviour.Properties.of().mapColor(MapColor.STONE).strength(0.25F, 3.0F).noOcclusion());
    public static final DeferredItem<BlockItem> PIPE_STRUCTURE_ITEM =
            ITEMS.registerSimpleBlockItem("pipe_structure", PIPE_STRUCTURE_BLOCK);
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<PipeBlockEntity>> PIPE_STRUCTURE_BLOCK_ENTITY =
            BLOCK_ENTITY_TYPES.register("pipe_structure", () -> new BlockEntityType<>(
                    (pos, state) -> new PipeBlockEntity(TransportContent.PIPE_STRUCTURE_BLOCK_ENTITY.get(), pos, state, () -> StructureBehaviour.INSTANCE),
                    PIPE_STRUCTURE_BLOCK.get()));

    public static final DeferredBlock<PipeBlock> PIPE_WOOD_BLOCK = BLOCKS.registerBlock("pipe_wood",
            props -> new WoodPipeBlock(props, () -> TransportContent.PIPE_WOOD_BLOCK_ENTITY.get(), WoodBehaviour::new),
            () -> BlockBehaviour.Properties.of().mapColor(MapColor.WOOD).strength(0.25F, 3.0F).noOcclusion());
    public static final DeferredItem<BlockItem> PIPE_WOOD_ITEM =
            ITEMS.registerSimpleBlockItem("pipe_wood", PIPE_WOOD_BLOCK);
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<PipeBlockEntity>> PIPE_WOOD_BLOCK_ENTITY =
            BLOCK_ENTITY_TYPES.register("pipe_wood", () -> new BlockEntityType<>(
                    (pos, state) -> new PipeBlockEntity(TransportContent.PIPE_WOOD_BLOCK_ENTITY.get(), pos, state, WoodBehaviour::new),
                    PIPE_WOOD_BLOCK.get()));

    public static final DeferredBlock<PipeBlock> PIPE_DIAMOND_BLOCK = BLOCKS.registerBlock("pipe_diamond",
            props -> new PipeBlock(props, () -> TransportContent.PIPE_DIAMOND_BLOCK_ENTITY.get(), DiamondBehaviour::new),
            () -> BlockBehaviour.Properties.of().mapColor(MapColor.DIAMOND).strength(0.25F, 3.0F).noOcclusion());
    public static final DeferredItem<BlockItem> PIPE_DIAMOND_ITEM =
            ITEMS.registerSimpleBlockItem("pipe_diamond", PIPE_DIAMOND_BLOCK);
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<PipeBlockEntity>> PIPE_DIAMOND_BLOCK_ENTITY =
            BLOCK_ENTITY_TYPES.register("pipe_diamond", () -> new BlockEntityType<>(
                    (pos, state) -> new PipeBlockEntity(TransportContent.PIPE_DIAMOND_BLOCK_ENTITY.get(), pos, state, DiamondBehaviour::new),
                    PIPE_DIAMOND_BLOCK.get()));

    public static final DeferredBlock<PipeBlock> PIPE_LAPIS_BLOCK = BLOCKS.registerBlock("pipe_lapis",
            props -> new PipeBlock(props, () -> TransportContent.PIPE_LAPIS_BLOCK_ENTITY.get(), LapisBehaviour::new),
            () -> BlockBehaviour.Properties.of().mapColor(MapColor.LAPIS).strength(0.25F, 3.0F).noOcclusion());
    public static final DeferredItem<BlockItem> PIPE_LAPIS_ITEM =
            ITEMS.registerSimpleBlockItem("pipe_lapis", PIPE_LAPIS_BLOCK);
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<PipeBlockEntity>> PIPE_LAPIS_BLOCK_ENTITY =
            BLOCK_ENTITY_TYPES.register("pipe_lapis", () -> new BlockEntityType<>(
                    (pos, state) -> new PipeBlockEntity(TransportContent.PIPE_LAPIS_BLOCK_ENTITY.get(), pos, state, LapisBehaviour::new),
                    PIPE_LAPIS_BLOCK.get()));

    public static final DeferredBlock<PipeBlock> PIPE_DAIZULI_BLOCK = BLOCKS.registerBlock("pipe_daizuli",
            props -> new PipeBlock(props, () -> TransportContent.PIPE_DAIZULI_BLOCK_ENTITY.get(), DaizuliBehaviour::new),
            () -> BlockBehaviour.Properties.of().mapColor(MapColor.LAPIS).strength(0.25F, 3.0F).noOcclusion());
    public static final DeferredItem<BlockItem> PIPE_DAIZULI_ITEM =
            ITEMS.registerSimpleBlockItem("pipe_daizuli", PIPE_DAIZULI_BLOCK);
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<PipeBlockEntity>> PIPE_DAIZULI_BLOCK_ENTITY =
            BLOCK_ENTITY_TYPES.register("pipe_daizuli", () -> new BlockEntityType<>(
                    (pos, state) -> new PipeBlockEntity(TransportContent.PIPE_DAIZULI_BLOCK_ENTITY.get(), pos, state, DaizuliBehaviour::new),
                    PIPE_DAIZULI_BLOCK.get()));

    public static final DeferredBlock<PipeBlock> PIPE_OBSIDIAN_BLOCK = BLOCKS.registerBlock("pipe_obsidian",
            props -> new PipeBlock(props, () -> TransportContent.PIPE_OBSIDIAN_BLOCK_ENTITY.get(), ObsidianBehaviour::new),
            () -> BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_BLACK).strength(0.25F, 3.0F).noOcclusion());
    public static final DeferredItem<BlockItem> PIPE_OBSIDIAN_ITEM =
            ITEMS.registerSimpleBlockItem("pipe_obsidian", PIPE_OBSIDIAN_BLOCK);
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<PipeBlockEntity>> PIPE_OBSIDIAN_BLOCK_ENTITY =
            BLOCK_ENTITY_TYPES.register("pipe_obsidian", () -> new BlockEntityType<>(
                    (pos, state) -> new PipeBlockEntity(TransportContent.PIPE_OBSIDIAN_BLOCK_ENTITY.get(), pos, state, ObsidianBehaviour::new),
                    PIPE_OBSIDIAN_BLOCK.get()));

    public static final DeferredBlock<PipeBlock> PIPE_WOOD_DIAMOND_BLOCK = BLOCKS.registerBlock("pipe_wood_diamond",
            props -> new WoodPipeBlock(props, () -> TransportContent.PIPE_WOOD_DIAMOND_BLOCK_ENTITY.get(), WoodDiamondBehaviour::new),
            () -> BlockBehaviour.Properties.of().mapColor(MapColor.WOOD).strength(0.25F, 3.0F).noOcclusion());
    public static final DeferredItem<BlockItem> PIPE_WOOD_DIAMOND_ITEM =
            ITEMS.registerSimpleBlockItem("pipe_wood_diamond", PIPE_WOOD_DIAMOND_BLOCK);
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<PipeBlockEntity>> PIPE_WOOD_DIAMOND_BLOCK_ENTITY =
            BLOCK_ENTITY_TYPES.register("pipe_wood_diamond", () -> new BlockEntityType<>(
                    (pos, state) -> new PipeBlockEntity(TransportContent.PIPE_WOOD_DIAMOND_BLOCK_ENTITY.get(), pos, state, WoodDiamondBehaviour::new),
                    PIPE_WOOD_DIAMOND_BLOCK.get()));

    public static final DeferredBlock<PipeBlock> PIPE_STRIPES_BLOCK = BLOCKS.registerBlock("pipe_stripes",
            props -> new PipeBlock(props, () -> TransportContent.PIPE_STRIPES_BLOCK_ENTITY.get(), StripesBehaviour::new),
            () -> BlockBehaviour.Properties.of().mapColor(MapColor.GOLD).strength(0.25F, 3.0F).noOcclusion());
    public static final DeferredItem<BlockItem> PIPE_STRIPES_ITEM =
            ITEMS.registerSimpleBlockItem("pipe_stripes", PIPE_STRIPES_BLOCK);
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<PipeBlockEntity>> PIPE_STRIPES_BLOCK_ENTITY =
            BLOCK_ENTITY_TYPES.register("pipe_stripes", () -> new BlockEntityType<>(
                    (pos, state) -> new PipeBlockEntity(TransportContent.PIPE_STRIPES_BLOCK_ENTITY.get(), pos, state, StripesBehaviour::new),
                    PIPE_STRIPES_BLOCK.get()));

    public static final DeferredBlock<PipeBlock> PIPE_EMZULI_BLOCK = BLOCKS.registerBlock("pipe_emzuli",
            props -> new PipeBlock(props, () -> TransportContent.PIPE_EMZULI_BLOCK_ENTITY.get(), EmzuliBehaviour::new),
            () -> BlockBehaviour.Properties.of().mapColor(MapColor.WOOD).strength(0.25F, 3.0F).noOcclusion());
    public static final DeferredItem<BlockItem> PIPE_EMZULI_ITEM =
            ITEMS.registerSimpleBlockItem("pipe_emzuli", PIPE_EMZULI_BLOCK);
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<PipeBlockEntity>> PIPE_EMZULI_BLOCK_ENTITY =
            BLOCK_ENTITY_TYPES.register("pipe_emzuli", () -> new BlockEntityType<>(
                    (pos, state) -> new PipeBlockEntity(TransportContent.PIPE_EMZULI_BLOCK_ENTITY.get(), pos, state, EmzuliBehaviour::new),
                    PIPE_EMZULI_BLOCK.get()));

    // Fluid ("waterproof") pipes - a genuinely separate, parallel pipe network from the item pipes above (real
    // source design, not a unified dual-purpose pipe - see FluidPipeBlock's javadoc). Stage 1 of this feature:
    // 3 representative tiers (Cobblestone=baseline passive, Iron=directional, Void=destroyer), reusing the SAME
    // PipeBehaviour instances as their item-pipe counterparts (matching real source registering the identical
    // Java class for both flavours of every material except Diamond).
    public static final DeferredItem<Item> PIPE_SEALANT_ITEM = ITEMS.registerSimpleItem("pipe_sealant");

    public static final DeferredBlock<FluidPipeBlock> PIPE_COBBLESTONE_FLUID_BLOCK = BLOCKS.registerBlock("pipe_cobblestone_fluid",
            props -> new FluidPipeBlock(props, () -> TransportContent.PIPE_COBBLESTONE_FLUID_BLOCK_ENTITY.get(), () -> PassiveBehaviour.COBBLESTONE),
            () -> BlockBehaviour.Properties.of().mapColor(MapColor.STONE).strength(0.25F, 3.0F).noOcclusion());
    public static final DeferredItem<BlockItem> PIPE_COBBLESTONE_FLUID_ITEM =
            ITEMS.registerSimpleBlockItem("pipe_cobblestone_fluid", PIPE_COBBLESTONE_FLUID_BLOCK);
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<FluidPipeBlockEntity>> PIPE_COBBLESTONE_FLUID_BLOCK_ENTITY =
            BLOCK_ENTITY_TYPES.register("pipe_cobblestone_fluid", () -> new BlockEntityType<>(
                    (pos, state) -> new FluidPipeBlockEntity(TransportContent.PIPE_COBBLESTONE_FLUID_BLOCK_ENTITY.get(), pos, state, () -> PassiveBehaviour.COBBLESTONE),
                    PIPE_COBBLESTONE_FLUID_BLOCK.get()));

    public static final DeferredBlock<FluidPipeBlock> PIPE_IRON_FLUID_BLOCK = BLOCKS.registerBlock("pipe_iron_fluid",
            props -> new FluidPipeBlock(props, () -> TransportContent.PIPE_IRON_FLUID_BLOCK_ENTITY.get(), IronBehaviour::new),
            () -> BlockBehaviour.Properties.of().mapColor(MapColor.METAL).strength(0.25F, 3.0F).noOcclusion());
    public static final DeferredItem<BlockItem> PIPE_IRON_FLUID_ITEM =
            ITEMS.registerSimpleBlockItem("pipe_iron_fluid", PIPE_IRON_FLUID_BLOCK);
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<FluidPipeBlockEntity>> PIPE_IRON_FLUID_BLOCK_ENTITY =
            BLOCK_ENTITY_TYPES.register("pipe_iron_fluid", () -> new BlockEntityType<>(
                    (pos, state) -> new FluidPipeBlockEntity(TransportContent.PIPE_IRON_FLUID_BLOCK_ENTITY.get(), pos, state, IronBehaviour::new),
                    PIPE_IRON_FLUID_BLOCK.get()));

    public static final DeferredBlock<FluidPipeBlock> PIPE_VOID_FLUID_BLOCK = BLOCKS.registerBlock("pipe_void_fluid",
            props -> new FluidPipeBlock(props, () -> TransportContent.PIPE_VOID_FLUID_BLOCK_ENTITY.get(), () -> VoidBehaviour.INSTANCE),
            () -> BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_BLACK).strength(0.25F, 3.0F).noOcclusion());
    public static final DeferredItem<BlockItem> PIPE_VOID_FLUID_ITEM =
            ITEMS.registerSimpleBlockItem("pipe_void_fluid", PIPE_VOID_FLUID_BLOCK);
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<FluidPipeBlockEntity>> PIPE_VOID_FLUID_BLOCK_ENTITY =
            BLOCK_ENTITY_TYPES.register("pipe_void_fluid", () -> new BlockEntityType<>(
                    (pos, state) -> new FluidPipeBlockEntity(TransportContent.PIPE_VOID_FLUID_BLOCK_ENTITY.get(), pos, state, () -> VoidBehaviour.INSTANCE),
                    PIPE_VOID_FLUID_BLOCK.get()));

    private TransportContent() {}
}
