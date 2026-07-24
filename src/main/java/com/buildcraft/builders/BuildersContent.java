package com.buildcraft.builders;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.neoforge.common.world.chunk.TicketController;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

import com.buildcraft.BuildCraft;
import com.buildcraft.builders.block.FrameBlock;
import com.buildcraft.builders.block.MarkerBlock;
import com.buildcraft.builders.block.QuarryBlock;
import com.buildcraft.builders.blockentity.MarkerBlockEntity;
import com.buildcraft.builders.blockentity.QuarryBlockEntity;

public final class BuildersContent {
    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(BuildCraft.MODID);
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(BuildCraft.MODID);
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITY_TYPES =
            DeferredRegister.create(BuiltInRegistries.BLOCK_ENTITY_TYPE, BuildCraft.MODID);

    public static final DeferredBlock<QuarryBlock> QUARRY_BLOCK = BLOCKS.registerBlock("quarry", QuarryBlock::new,
            () -> BlockBehaviour.Properties.of().mapColor(MapColor.METAL).strength(5.0F, 10.0F).requiresCorrectToolForDrops());
    public static final DeferredItem<BlockItem> QUARRY_ITEM = ITEMS.registerSimpleBlockItem("quarry", QUARRY_BLOCK);

    public static final DeferredBlock<FrameBlock> FRAME_BLOCK = BLOCKS.registerBlock("frame", FrameBlock::new,
            () -> BlockBehaviour.Properties.of().mapColor(MapColor.METAL).strength(0.0F).noLootTable());
    public static final DeferredItem<BlockItem> FRAME_ITEM = ITEMS.registerSimpleBlockItem("frame", FRAME_BLOCK);

    public static final DeferredBlock<MarkerBlock> MARKER_BLOCK = BLOCKS.registerBlock("marker", MarkerBlock::new,
            () -> BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_LIGHT_BLUE).noCollision().strength(0.0F));
    public static final DeferredItem<BlockItem> MARKER_ITEM = ITEMS.registerSimpleBlockItem("marker", MARKER_BLOCK);

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<QuarryBlockEntity>> QUARRY_BLOCK_ENTITY =
            BLOCK_ENTITY_TYPES.register("quarry", () -> new BlockEntityType<>(QuarryBlockEntity::new, QUARRY_BLOCK.get()));
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<MarkerBlockEntity>> MARKER_BLOCK_ENTITY =
            BLOCK_ENTITY_TYPES.register("marker", () -> new BlockEntityType<>(MarkerBlockEntity::new, MARKER_BLOCK.get()));

    public static final TicketController QUARRY_TICKET_CONTROLLER =
            new TicketController(Identifier.fromNamespaceAndPath(BuildCraft.MODID, "quarry"));

    private BuildersContent() {}
}
