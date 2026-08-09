package com.buildcraft.energy.blockentity;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.jspecify.annotations.Nullable;

import com.buildcraft.BuildCraft;
import com.buildcraft.Config;
import com.buildcraft.energy.EnergyContent;
import com.buildcraft.energy.menu.StirlingEngineMenu;

/**
 * Ports {@code TileEngineStone_BC8}: burns any vanilla furnace-fuel item (coal, planks, blaze rods, lava
 * buckets, ...) via the modern equivalent of {@code TileEntityFurnace.getItemBurnTime} -
 * {@code ItemStack.getBurnTime(RecipeType, FuelValues)} - not a BuildCraft-specific "biofuel" item. A new fuel
 * item only starts burning while redstone-powered (source gates the START of a new burn on the signal, even
 * though an already-burning item keeps burning down regardless per {@code engineUpdate}'s unconditional
 * decrement). Output uses the real PI-controller formula from source (target = 3/8 of the buffer, proportional
 * error + a clamped integral term, clamped between {@link Config#ENGINE_STIRLING_MIN_OUTPUT} and
 * {@link Config#ENGINE_STIRLING_MAX_OUTPUT}) rather than a flat number. Heat is NOT independently tracked here
 * either - it uses the generic base-class formula (derived from buffer fill ratio each tick), matching source
 * not overriding {@code updateHeatLevel()} for this tier.
 */
public class StirlingEngineBlockEntity extends EngineBlockEntity implements MenuProvider {
    private static final Identifier BODY_TEXTURE = Identifier.fromNamespaceAndPath(BuildCraft.MODID, "block/engine_stirling");
    private static final long E_LIMIT_TICKS = 20;

    private final SimpleContainer fuelSlot = new SimpleContainer(1);
    private int burnTime = 0;
    private int totalBurnTime = 0;
    private long errorSum = 0;

    public StirlingEngineBlockEntity(BlockPos pos, BlockState state) {
        super(EnergyContent.ENGINE_STIRLING_BLOCK_ENTITY.get(), pos, state);
    }

    public SimpleContainer getFuelSlot() {
        return fuelSlot;
    }

    /** 0-100, how much of the current fuel item's burn time remains - ports {@code deltaFuelLeft}, drives the GUI's flame icon. */
    public int getFuelPercent() {
        return totalBurnTime > 0 ? 100 * burnTime / totalBurnTime : 0;
    }

    @Override
    public long getMaxPower() {
        return Config.ENGINE_STIRLING_CAPACITY.get();
    }

    @Override
    public Identifier getBodyTexture() {
        return BODY_TEXTURE;
    }

    /**
     * Not ported: source puts a burned fuel item's container remainder (e.g. a lava bucket becoming an empty
     * bucket) back into the slot or a nearby inventory via {@code InventoryUtil.addToBestAcceptor}. Modern
     * Minecraft moved "what's left after using an item" to a {@code DataComponents.USE_REMAINDER}
     * component-based system built around player-driven "use" actions, not furnace-style fuel consumption - it
     * doesn't map cleanly onto this case, so fuel items are simply consumed with no remainder produced. A
     * documented scope reduction given the API mismatch.
     */
    @Override
    protected void burn(Level level, BlockPos pos, BlockState state) {
        // Real bug fixed (2026-08-08, user-reported): this used to let an already-burning fuel item keep
        // counting down and producing power regardless of CURRENT redstonePowered - only the START of a new
        // burn was gated on it. That was a deliberate, documented port of real source's own behavior, but live
        // testing showed it's not what's wanted here: the piston kept visibly pumping (isPumping() = burnTime>0)
        // for a long stretch after the redstone signal was already gone, which reads as "stuck on"/buggy rather
        // than a subtle fidelity choice. Now matches CombustionEngineBlockEntity's own equivalent behavior
        // exactly: losing redstone power halts everything immediately, no lingering burn-down.
        if (!redstonePowered) {
            burnTime = 0;
            return;
        }
        if (burnTime <= 0) {
            ItemStack stack = fuelSlot.getItem(0);
            int fuelValue = stack.isEmpty() ? 0 : stack.getBurnTime(null, level.fuelValues());
            if (fuelValue > 0) {
                stack.shrink(1);
                burnTime = fuelValue;
                totalBurnTime = fuelValue;
                fuelSlot.setChanged();
            }
        }
        if (burnTime > 0) {
            burnTime--;
            power = Math.min(getMaxPower(), power + getCurrentOutput());
        }
    }

    /**
     * Ports the exact real PI-controller formula from {@code getCurrentOutput()}: target buffer level is 3/8 of
     * capacity, output = clamp(error + integral/20, MIN_OUTPUT, MAX_OUTPUT), integral clamped to
     * {@code (MAX_OUTPUT-MIN_OUTPUT)*20}.
     */
    private long getCurrentOutput() {
        long maxOutput = Config.ENGINE_STIRLING_MAX_OUTPUT.get();
        long minOutput = Config.ENGINE_STIRLING_MIN_OUTPUT.get();
        long eLimit = (maxOutput - minOutput) * E_LIMIT_TICKS;
        long error = 3 * getMaxPower() / 8 - power;
        errorSum = Mth.clamp(errorSum + error, -eLimit, eLimit);
        return Mth.clamp(error + errorSum / E_LIMIT_TICKS, minOutput, maxOutput);
    }

    /** See {@link Config#ENGINE_STIRLING_MAX_PULSE_OUTPUT}'s javadoc: a small flat per-pulse cap, not source's
     * literal 1/10-of-buffer ratio (100MJ/1000MJ) - that ratio permits up to 100 items in one pulse at source's
     * own mjPerItem=1MJ, which is too large for good pacing at this port's FE scale and also (since heat is a
     * direct function of buffer-fill ratio) was the direct cause of heat visibly jumping stages per pump. */
    @Override
    protected long getMaxPowerExtracted() {
        return Config.ENGINE_STIRLING_MAX_PULSE_OUTPUT.get();
    }

    /** Real bug fixed (2026-08-08, same root cause as {@code RedstoneEngineBlockEntity}'s equivalent override):
     * {@link #getMaxPowerExtracted} (the pulsed-receiver burst cap, 80 FE) was also being used for continuous
     * receivers, letting Stirling push a sustained 80 FE/tick into a Quarry/Refinery/Mining Well. Reuses
     * {@link Config#ENGINE_STIRLING_MIN_OUTPUT} (this tier's own PI-controller floor output, 40) rather than a
     * new config - a genuinely conservative, already-tuned "what this engine reliably produces" figure, leaving
     * the higher {@code MAX_OUTPUT}/PI-controller ceiling (120) purely for keeping its own buffer topped off. */
    @Override
    protected long getSustainedFePerTick() {
        return Config.ENGINE_STIRLING_MIN_OUTPUT.get();
    }

    @Override
    protected boolean isPumping() {
        return burnTime > 0;
    }

    @Override
    public @Nullable AbstractContainerMenu createMenu(int containerId, Inventory playerInventory, Player player) {
        return new StirlingEngineMenu(EnergyContent.STIRLING_ENGINE_MENU.get(), containerId, playerInventory, this);
    }

    @Override
    public Component getDisplayName() {
        return Component.translatable("block.buildcraft.engine_stirling");
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        output.putInt("burnTime", burnTime);
        output.putInt("totalBurnTime", totalBurnTime);
        output.putLong("errorSum", errorSum);
        ValueOutput.TypedOutputList<ItemStack> list = output.list("fuel", ItemStack.OPTIONAL_CODEC);
        list.add(fuelSlot.getItem(0));
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        burnTime = input.getIntOr("burnTime", 0);
        totalBurnTime = input.getIntOr("totalBurnTime", 0);
        errorSum = input.getLongOr("errorSum", 0);
        input.list("fuel", ItemStack.OPTIONAL_CODEC).ifPresent(list -> {
            for (ItemStack stack : list) {
                fuelSlot.setItem(0, stack);
                break;
            }
        });
    }
}
