package com.buildcraft.core;

import net.minecraft.world.item.Item;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

import com.buildcraft.BuildCraft;

/**
 * Registers the 5 gear tiers (Wood/Stone/Iron/Gold/Diamond) - real source's {@code ItemBC_Neptune}-based
 * {@code gear_*} items ({@code BCCoreItems.java}). Pure crafting-ladder ingredients with no Java behaviour of
 * their own (no tool logic, no capability, no right-click handler in source either) - see {@code CoreContent}'s
 * sibling {@code *Content} classes for the same registration pattern used by every other module.
 */
public final class CoreContent {
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(BuildCraft.MODID);

    public static final DeferredItem<Item> GEAR_WOOD_ITEM = ITEMS.registerSimpleItem("gear_wood");
    public static final DeferredItem<Item> GEAR_STONE_ITEM = ITEMS.registerSimpleItem("gear_stone");
    public static final DeferredItem<Item> GEAR_IRON_ITEM = ITEMS.registerSimpleItem("gear_iron");
    public static final DeferredItem<Item> GEAR_GOLD_ITEM = ITEMS.registerSimpleItem("gear_gold");
    public static final DeferredItem<Item> GEAR_DIAMOND_ITEM = ITEMS.registerSimpleItem("gear_diamond");

    private CoreContent() {}
}
