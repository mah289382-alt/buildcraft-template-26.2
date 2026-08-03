package com.buildcraft.factory.blockentity;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Containers;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.ShapelessRecipe;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.neoforged.neoforge.transfer.CombinedResourceHandler;
import net.neoforged.neoforge.transfer.ResourceHandler;
import net.neoforged.neoforge.transfer.energy.EnergyHandler;
import net.neoforged.neoforge.transfer.energy.SimpleEnergyHandler;
import net.neoforged.neoforge.transfer.item.ItemResource;
import net.neoforged.neoforge.transfer.item.ItemStacksResourceHandler;
import net.neoforged.neoforge.transfer.transaction.Transaction;
import net.neoforged.neoforge.transfer.transaction.TransactionContext;
import org.jspecify.annotations.Nullable;

import com.buildcraft.Config;
import com.buildcraft.factory.FactoryContent;
import com.buildcraft.factory.menu.AutoWorkbenchMenu;

/**
 * Ports {@code TileAutoWorkbenchBase}/{@code TileAutoWorkbenchItems} (real source: {@code lib/tile/craft/
 * WorkbenchCrafting.java} + {@code factory/tile/TileAutoWorkbenchBase.java}) - a "ghost recipe" auto-crafter.
 * The player sets a 3x3 example pattern in {@link #blueprint} (phantom slots, see {@code PhantomSlot}/{@code
 * AutoWorkbenchMenu}); this block entity finds the matching vanilla {@link CraftingRecipe} and repeatedly
 * crafts it from real materials fed into {@link #materials} by pipes/hoppers/the player.
 * <p>
 * <b>Real improvement over source, not a deviation</b>: real source only ever finishes the "exact stack"
 * matching path ({@code WorkbenchCrafting.craftExact}) - the fuzzy "match by the recipe's real ingredient list"
 * path ({@code craftByIngredients}) is present in source but commented out/never shipped, meaning real
 * BuildCraft's Auto Workbench only ever pulls the ONE exact item type used to build the blueprint from its
 * materials buffer, even when the underlying vanilla recipe would accept any tag-matching item (e.g. any
 * plank). This port finishes that unshipped path: {@link #computeSlotIngredients} resolves the REAL {@link
 * Ingredient} (tag-aware) governing each blueprint cell from the matched recipe itself, and {@link #materials}'
 * per-slot filter accepts anything satisfying that Ingredient, not just the one exemplar item the blueprint
 * happens to show.
 */
public class AutoWorkbenchBlockEntity extends BlockEntity implements MenuProvider {
    private static final int GRID_SIZE = 9;
    private static final int GRID_WIDTH = 3;
    private static final int GRID_HEIGHT = 3;

    private final SimpleContainer blueprint = new SimpleContainer(GRID_SIZE) {
        @Override
        public void setChanged() {
            super.setChanged();
            blueprintDirty = true;
        }
    };

    private final ItemStacksResourceHandler materials = new ItemStacksResourceHandler(GRID_SIZE) {
        @Override
        public boolean isValid(int index, ItemResource resource) {
            Ingredient required = slotIngredients[index];
            return required != null && required.test(resource.toStack(1));
        }

        @Override
        protected void onContentsChanged(int index, ItemStack previousContents) {
            setChanged();
            materialsDirty = true;
        }
    };

    /** isValid deliberately left at the default (unrestricted) - {@link #craft}/{@link #canAcceptResult} insert
     * the machine's own crafted output directly into this object and MUST NOT be blocked. Real bug found via
     * live log data: an earlier version hardcoded isValid=false here to keep external pipes/players from
     * placing items into the output slot, but that identical check also gated this class's OWN internal
     * insert() call - the machine could never accept its own product, so canCraft() was permanently false and
     * energy never even started charging, no matter how correct the recipe/materials were. External access is
     * instead blocked via {@link #extractOnlyResult} below, matching the same "wrap the exposed capability,
     * don't cripple the real object" pattern already used for {@link #insertOnlyMaterials}. */
    private final ItemStacksResourceHandler result = new ItemStacksResourceHandler(1) {
        @Override
        protected void onContentsChanged(int index, ItemStack previousContents) {
            setChanged();
        }
    };

    /** Real fix: external pipes/hoppers must only ever be able to INSERT into {@link #materials} (feed
     * ingredients in), never EXTRACT from it (pull raw, unconsumed ingredients back out) - otherwise an
     * actively-pulling pipe tier (e.g. Wood) sitting on the materials side yanks an ingredient back out before
     * the machine's own charge-up finishes consuming it, and it never sits still long enough to actually craft.
     * {@link #materials} itself is untouched and fully extractable - {@link #craft}/{@link #hasRequiredMaterials}
     * use it directly, bypassing this wrapper entirely. */
    private final ResourceHandler<ItemResource> insertOnlyMaterials = new ResourceHandler<>() {
        @Override
        public int size() {
            return materials.size();
        }

        @Override
        public ItemResource getResource(int index) {
            return materials.getResource(index);
        }

        @Override
        public long getAmountAsLong(int index) {
            return materials.getAmountAsLong(index);
        }

        @Override
        public long getCapacityAsLong(int index, ItemResource resource) {
            return materials.getCapacityAsLong(index, resource);
        }

        @Override
        public boolean isValid(int index, ItemResource resource) {
            return materials.isValid(index, resource);
        }

        @Override
        public int insert(int index, ItemResource resource, int amount, TransactionContext transaction) {
            return materials.insert(index, resource, amount, transaction);
        }

        @Override
        public int extract(int index, ItemResource resource, int amount, TransactionContext transaction) {
            return 0;
        }
    };

    /** Mirror image of {@link #insertOnlyMaterials}: external pipes/players may only ever EXTRACT the finished
     * product, never INSERT into the output slot. {@link #result} itself stays fully insertable so internal
     * crafting logic (which uses {@link #result} directly, not this wrapper) is unaffected. */
    private final ResourceHandler<ItemResource> extractOnlyResult = new ResourceHandler<>() {
        @Override
        public int size() {
            return result.size();
        }

        @Override
        public ItemResource getResource(int index) {
            return result.getResource(index);
        }

        @Override
        public long getAmountAsLong(int index) {
            return result.getAmountAsLong(index);
        }

        @Override
        public long getCapacityAsLong(int index, ItemResource resource) {
            return result.getCapacityAsLong(index, resource);
        }

        @Override
        public boolean isValid(int index, ItemResource resource) {
            return false;
        }

        @Override
        public int insert(int index, ItemResource resource, int amount, TransactionContext transaction) {
            return 0;
        }

        @Override
        public int extract(int index, ItemResource resource, int amount, TransactionContext transaction) {
            return result.extract(index, resource, amount, transaction);
        }
    };

    private final ResourceHandler<ItemResource> combinedItems = new CombinedResourceHandler<>(insertOnlyMaterials, extractOnlyResult);

    private final SimpleEnergyHandler energy = new SimpleEnergyHandler(
            Config.AUTO_WORKBENCH_ENERGY_REQUIRED.get(), Config.AUTO_WORKBENCH_MAX_FE_PER_TICK.get(), 0);

    /** Per-ABSOLUTE-blueprint-slot (0..8, row-major) required {@link Ingredient} - null means that slot isn't
     * part of the matched recipe at all. Recomputed whenever the blueprint changes, see {@link #recomputeRecipe}. */
    private final Ingredient[] slotIngredients = new Ingredient[GRID_SIZE];

    private @Nullable RecipeHolder<CraftingRecipe> currentRecipe;
    private ItemStack assumedResult = ItemStack.EMPTY;
    private boolean blueprintDirty = true;
    private boolean materialsDirty = true;
    private boolean cachedHasMaterials = false;

    public AutoWorkbenchBlockEntity(BlockPos pos, BlockState state) {
        super(FactoryContent.AUTO_WORKBENCH_BLOCK_ENTITY.get(), pos, state);
    }

    public SimpleContainer getBlueprint() {
        return blueprint;
    }

    public ItemStacksResourceHandler getMaterials() {
        return materials;
    }

    public ItemStacksResourceHandler getResult() {
        return result;
    }

    /** For the GUI's result slot's placement check - see {@link #extractOnlyResult}'s javadoc for why the raw
     * {@link #result} object itself must NOT be used for this (it needs to stay freely insertable internally). */
    public ResourceHandler<ItemResource> getResultView() {
        return extractOnlyResult;
    }

    public ResourceHandler<ItemResource> getItemHandler() {
        return combinedItems;
    }

    public EnergyHandler getEnergyHandler() {
        return energy;
    }

    public ItemStack getAssumedResult() {
        return assumedResult;
    }

    /** For the GUI's power-bar display. */
    public int getEnergyPercent() {
        long required = Config.AUTO_WORKBENCH_ENERGY_REQUIRED.get();
        return (int) (100 * energy.getAmountAsLong() / required);
    }

    public static void tick(Level level, BlockPos pos, BlockState state, AutoWorkbenchBlockEntity be) {
        if (level.isClientSide()) {
            return;
        }
        if (be.blueprintDirty) {
            be.recomputeRecipe(level);
            be.blueprintDirty = false;
            // assumedResult is a plain field, not a container slot - it doesn't sync to the client through the
            // menu's normal slot-sync mechanism (that's why the ghost item you PLACE shows up fine, but the
            // preview of what it'll CRAFT never did). Force a real block-entity resync whenever it changes.
            level.sendBlockUpdated(pos, state, state, 3);
        }

        long required = Config.AUTO_WORKBENCH_ENERGY_REQUIRED.get();
        if (be.checkCanCraft()) {
            if (be.energy.getAmountAsLong() >= required) {
                if (be.craft(level)) {
                    be.energy.set(0);
                    be.materialsDirty = true;
                }
            } else {
                int passive = Config.AUTO_WORKBENCH_PASSIVE_FE_PER_TICK.get();
                be.energy.set((int) Math.min(required, be.energy.getAmountAsLong() + passive));
            }
        } else {
            int lost = Config.AUTO_WORKBENCH_ENERGY_LOST_PER_TICK.get();
            be.energy.set((int) Math.max(0, be.energy.getAmountAsLong() - lost));
        }
    }

    // ---- Recipe matching ----

    /** Public so {@link AutoWorkbenchMenu} can force an immediate recompute right when a blueprint slot is
     * clicked, instead of waiting for the next server tick's dirty-flag check - see that class for why (real
     * vanilla {@code CraftingMenu.slotsChanged} does the same: recompute immediately, then push the result into
     * a normal, already-synced Slot, rather than relying on block-entity-level NBT sync for a live preview). */
    public void recomputeRecipe(Level level) {
        // The set of required ingredients is about to change (or be cleared) - any cached "do I have the
        // materials" answer from before this call is now stale regardless of which branch below is taken.
        materialsDirty = true;
        List<ItemStack> blueprintItems = new ArrayList<>(GRID_SIZE);
        for (int i = 0; i < GRID_SIZE; i++) {
            ItemStack stack = blueprint.getItem(i);
            blueprintItems.add(stack.isEmpty() ? ItemStack.EMPTY : stack.copyWithCount(1));
        }
        Arrays.fill(slotIngredients, null);

        CraftingInput.Positioned positioned = CraftingInput.ofPositioned(GRID_WIDTH, GRID_HEIGHT, blueprintItems);
        if (positioned.input().isEmpty()) {
            currentRecipe = null;
            assumedResult = ItemStack.EMPTY;
            return;
        }
        Optional<RecipeHolder<CraftingRecipe>> match = ((ServerLevel) level).recipeAccess()
                .getRecipeFor(RecipeType.CRAFTING, positioned.input(), level);
        if (match.isEmpty()) {
            currentRecipe = null;
            assumedResult = ItemStack.EMPTY;
            return;
        }
        currentRecipe = match.get();
        assumedResult = currentRecipe.value().assemble(positioned.input());
        computeSlotIngredients(currentRecipe.value(), positioned);
    }

    /** Resolves the real per-absolute-slot {@link Ingredient}s for the matched recipe - see the class javadoc
     * for why this (not the blueprint's own exemplar item) is what actually gates {@link #materials}. */
    private void computeSlotIngredients(CraftingRecipe recipe, CraftingInput.Positioned positioned) {
        if (recipe instanceof ShapedRecipe shaped) {
            int pw = shaped.pattern.width();
            int ph = shaped.pattern.height();
            List<Optional<Ingredient>> patternIngredients = shaped.pattern.ingredients();
            CraftingInput input = positioned.input();
            boolean flip = !matchesOrientation(patternIngredients, pw, ph, input, false)
                    && matchesOrientation(patternIngredients, pw, ph, input, true);
            for (int y = 0; y < ph; y++) {
                for (int x = 0; x < pw; x++) {
                    int localX = flip ? pw - x - 1 : x;
                    Optional<Ingredient> ingredient = patternIngredients.get(localX + y * pw);
                    if (ingredient.isPresent()) {
                        int absX = positioned.left() + x;
                        int absY = positioned.top() + y;
                        if (absX < GRID_WIDTH && absY < GRID_HEIGHT) {
                            slotIngredients[absX + absY * GRID_WIDTH] = ingredient.get();
                        }
                    }
                }
            }
        } else if (recipe instanceof ShapelessRecipe) {
            // ShapelessRecipe has no fixed cell positions, so real source's own bipartite-matched ingredient list
            // (recipe.placementInfo().ingredients()) is assigned greedily to whichever non-empty blueprint slot
            // it satisfies first - a simplification of a full stable bipartite match, but since the blueprint was
            // already confirmed to satisfy this exact recipe (getRecipeFor found it), a valid assignment always
            // exists for any non-adversarial pattern.
            List<Ingredient> pool = new ArrayList<>(recipe.placementInfo().ingredients());
            for (int i = 0; i < GRID_SIZE; i++) {
                ItemStack stack = blueprint.getItem(i);
                if (stack.isEmpty()) {
                    continue;
                }
                for (int p = 0; p < pool.size(); p++) {
                    if (pool.get(p).test(stack)) {
                        slotIngredients[i] = pool.remove(p);
                        break;
                    }
                }
            }
        } else {
            // Custom/special recipe (e.g. firework star, banner duplicate) with no fixed ingredient list -
            // matches real source's own EXACT_STACKS fallback when getIngredients() is empty.
            for (int i = 0; i < GRID_SIZE; i++) {
                ItemStack stack = blueprint.getItem(i);
                if (!stack.isEmpty()) {
                    slotIngredients[i] = Ingredient.of(stack.getItem());
                }
            }
        }
    }

    /** Mirrors {@code ShapedRecipePattern.matches(CraftingInput, boolean)}'s private per-cell comparison, since
     * that method itself isn't exposed - needed to know which of the 2 possible alignments (normal/x-flipped)
     * the already-confirmed match actually used, so slot positions line up correctly. */
    private static boolean matchesOrientation(List<Optional<Ingredient>> patternIngredients, int width, int height,
            CraftingInput input, boolean xFlip) {
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                Optional<Ingredient> expected = xFlip
                        ? patternIngredients.get(width - x - 1 + y * width)
                        : patternIngredients.get(x + y * width);
                if (!Ingredient.testOptionalIngredient(expected, input.getItem(x, y))) {
                    return false;
                }
            }
        }
        return true;
    }

    // ---- Crafting ----

    private boolean checkCanCraft() {
        if (currentRecipe == null || !canAcceptResult()) {
            return false;
        }
        if (materialsDirty) {
            materialsDirty = false;
            cachedHasMaterials = hasRequiredMaterials();
        }
        return cachedHasMaterials;
    }

    private boolean canAcceptResult() {
        if (assumedResult.isEmpty()) {
            return false;
        }
        ItemResource resource = ItemResource.of(assumedResult);
        try (Transaction tx = Transaction.openRoot()) {
            return result.insert(0, resource, assumedResult.getCount(), tx) >= assumedResult.getCount();
        }
    }

    private boolean hasRequiredMaterials() {
        try (Transaction tx = Transaction.openRoot()) {
            for (int i = 0; i < GRID_SIZE; i++) {
                if (slotIngredients[i] != null && extractMatching(i, tx).isEmpty()) {
                    return false;
                }
            }
            return true; // never committed - pure simulation
        }
    }

    private ItemStack extractMatching(int index, Transaction tx) {
        Ingredient required = slotIngredients[index];
        if (required == null) {
            return ItemStack.EMPTY;
        }
        ItemResource resource = materials.getResource(index);
        if (resource.isEmpty() || !required.test(resource.toStack(1))) {
            return ItemStack.EMPTY;
        }
        int extracted = materials.extract(index, resource, 1, tx);
        return extracted > 0 ? resource.toStack(extracted) : ItemStack.EMPTY;
    }

    /** Ports {@code WorkbenchCrafting.craftExact}, adapted for tag-aware ingredient matching (see class javadoc) -
     * moves one matching item per required slot out of {@link #materials} into a temporary crafting grid, runs
     * the real recipe, inserts the output into {@link #result}, and returns any "remaining items" (e.g. empty
     * buckets) back to {@link #materials} (dropped in-world if there's no room, matching source's own
     * {@code InventoryUtil.addToBestAcceptor} fallback, simplified here to a direct drop). */
    private boolean craft(Level level) {
        if (currentRecipe == null) {
            return false;
        }
        List<ItemStack> gridItems = new ArrayList<>(GRID_SIZE);
        for (int i = 0; i < GRID_SIZE; i++) {
            gridItems.add(ItemStack.EMPTY);
        }
        List<ItemStack> leftoverDrops = new ArrayList<>();

        try (Transaction tx = Transaction.openRoot()) {
            for (int i = 0; i < GRID_SIZE; i++) {
                if (slotIngredients[i] == null) {
                    continue;
                }
                ItemStack extracted = extractMatching(i, tx);
                if (extracted.isEmpty()) {
                    return false;
                }
                gridItems.set(i, extracted);
            }

            CraftingInput craftGrid = CraftingInput.of(GRID_WIDTH, GRID_HEIGHT, gridItems);
            if (!currentRecipe.value().matches(craftGrid, level)) {
                return false;
            }
            ItemStack output = currentRecipe.value().assemble(craftGrid);
            if (output.isEmpty()) {
                return false;
            }
            ItemResource outResource = ItemResource.of(output);
            if (result.insert(0, outResource, output.getCount(), tx) < output.getCount()) {
                return false;
            }

            NonNullList<ItemStack> remaining = currentRecipe.value().getRemainingItems(craftGrid);
            for (int i = 0; i < remaining.size() && i < GRID_SIZE; i++) {
                ItemStack remainder = remaining.get(i);
                if (remainder.isEmpty()) {
                    continue;
                }
                ItemResource remResource = ItemResource.of(remainder);
                int inserted = materials.insert(i, remResource, remainder.getCount(), tx);
                if (inserted < remainder.getCount()) {
                    leftoverDrops.add(remainder.copyWithCount(remainder.getCount() - inserted));
                }
            }
            tx.commit();
        }
        setChanged();
        for (ItemStack drop : leftoverDrops) {
            Containers.dropItemStack(level, worldPosition.getX(), worldPosition.getY(), worldPosition.getZ(), drop);
        }
        return true;
    }

    @Override
    public @Nullable AbstractContainerMenu createMenu(int containerId, Inventory playerInventory, Player player) {
        return new AutoWorkbenchMenu(FactoryContent.AUTO_WORKBENCH_MENU.get(), containerId, playerInventory, this);
    }

    @Override
    public Component getDisplayName() {
        return Component.translatable("block.buildcraft.auto_workbench");
    }

    @Override
    public @Nullable Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        return saveCustomOnly(registries);
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        net.minecraft.world.ContainerHelper.saveAllItems(output.child("blueprint"), blueprint.getItems());
        materials.serialize(output.child("materials"));
        result.serialize(output.child("result"));
        energy.serialize(output.child("energy"));
        output.store("assumedResult", ItemStack.OPTIONAL_CODEC, assumedResult);
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        input.child("blueprint").ifPresent(child -> net.minecraft.world.ContainerHelper.loadAllItems(child, blueprint.getItems()));
        input.child("materials").ifPresent(materials::deserialize);
        input.child("result").ifPresent(result::deserialize);
        input.child("energy").ifPresent(energy::deserialize);
        assumedResult = input.read("assumedResult", ItemStack.OPTIONAL_CODEC).orElse(ItemStack.EMPTY);
        blueprintDirty = true;
        materialsDirty = true;
    }
}
