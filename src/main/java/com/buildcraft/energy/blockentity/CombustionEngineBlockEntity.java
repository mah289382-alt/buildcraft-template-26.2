package com.buildcraft.energy.blockentity;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.transfer.CombinedResourceHandler;
import net.neoforged.neoforge.transfer.ResourceHandler;
import net.neoforged.neoforge.transfer.ResourceHandlerUtil;
import net.neoforged.neoforge.transfer.access.ItemAccess;
import net.neoforged.neoforge.transfer.fluid.BucketResourceHandler;
import net.neoforged.neoforge.transfer.fluid.FluidResource;
import net.neoforged.neoforge.transfer.fluid.FluidStacksResourceHandler;
import net.neoforged.neoforge.transfer.item.ItemResource;
import net.neoforged.neoforge.transfer.item.ItemStacksResourceHandler;
import net.neoforged.neoforge.transfer.transaction.Transaction;
import org.jspecify.annotations.Nullable;

import com.buildcraft.BuildCraft;
import com.buildcraft.Config;
import com.buildcraft.energy.EnergyContent;
import com.buildcraft.energy.menu.CombustionEngineMenu;
import com.buildcraft.factory.FactoryFluids;

/**
 * Ports {@code TileEngineIron_BC8}: 3 fluid tanks (fuel/coolant/residue, real source capacity of 10000 mB each
 * - a literal fluid-volume unit, not FE-scaled), a 10-tick "penalty cooling" stall when the redstone signal
 * drops mid-burn (fully inert during it, matching {@code isActive() = penaltyCooling<=0}), and - critically -
 * heat that NEVER decreases without coolant once above {@link #IDEAL_HEAT} (only {@code updateHeatLevel}'s
 * coolant-drain path removes heat; the passive {@code COOLDOWN_RATE} cooldown only applies when unpowered/under
 * penalty) - so running dry just stalls it at OVERHEAT rather than exploding, per the user-confirmed "match
 * real source, no explosion" decision.
 * <p>
 * Real source coolant registration IS ported faithfully (water = {@link Config#ENGINE_COMBUSTION_COOLANT_DEGREES_PER_MB},
 * verbatim 0.0023 degrees/mB). Fuel tank now accepts BOTH real fluids from the Fuel module ({@link FactoryFluids#OIL_SOURCE}
 * and {@link FactoryFluids#FUEL_SOURCE}, see the fuel-status memory entry for the simple-2-fluid scope decision) -
 * each with its own real per-fuel-type power/duration (source: Oil 30 power/cycle over 5000 ticks per mB, Fuel
 * 60 power/cycle over 25000 ticks per mB - refined Fuel is 2x the power AND 5x the duration, 10x the total
 * energy - see {@link Config#ENGINE_COMBUSTION_OIL_FE_PER_TICK} etc. for the FE-scaled equivalents preserving
 * that exact ratio). No residue production (source's "dirty" fuels produce waste fluid on burn - neither Oil
 * nor plain Fuel in this port's simplified 2-fluid chain are "dirty"; the tank exists for capability
 * completeness only, matching the real distillation byproducts this port doesn't build).
 * <p>
 * Real GUI ported: 3 tank-fill bars at their exact real positions (confirmed against
 * {@code GuiEngineIron_BC8.java}: fuel (26,18,16,60), coolant (80,18,16,60), residue (134,18,16,60), window
 * 176x177) plus the player inventory. Real source has NO dedicated bucket slots at all (confirmed via
 * {@code ContainerEngineIron_BC8.transferStackInSlot}: "the only slots are player slots" - filling is
 * shift-click-only, auto-detecting any fluid container in the clicked player slot). This port ports that
 * shift-click behavior too ({@link CombustionEngineMenu#quickMoveStack}), but ALSO adds a real dedicated
 * combined GUI slot ({@link #bucketSlot}, auto-routing by fluid type) AND direct right-click-with-bucket
 * filling with no GUI needed at all ({@link #tryFillFromHand}) - deliberate, disclosed UX improvements beyond
 * a strict source port, since shift-click alone gave the player no visible indication that filling was even
 * possible.
 */
public class CombustionEngineBlockEntity extends EngineBlockEntity implements MenuProvider {
    private static final Identifier BODY_TEXTURE = Identifier.fromNamespaceAndPath(BuildCraft.MODID, "block/engine_combustion");
    private static final int TANK_CAPACITY = 10_000;
    private static final int MAX_COOLANT_PER_TICK = 40;
    private static final double COOLDOWN_RATE = 0.05;

    private final FluidStacksResourceHandler fuelTank = new FluidStacksResourceHandler(1, TANK_CAPACITY) {
        @Override
        public boolean isValid(int index, FluidResource resource) {
            return resource.getFluid() == FactoryFluids.OIL_SOURCE.get() || resource.getFluid() == FactoryFluids.FUEL_SOURCE.get();
        }

        @Override
        protected void onContentsChanged(int index, FluidStack previousContents) {
            setChanged();
        }
    };
    private final FluidStacksResourceHandler coolantTank = new FluidStacksResourceHandler(1, TANK_CAPACITY) {
        @Override
        public boolean isValid(int index, FluidResource resource) {
            return resource.getFluid() == Fluids.WATER;
        }

        @Override
        protected void onContentsChanged(int index, FluidStack previousContents) {
            setChanged();
        }
    };
    private final FluidStacksResourceHandler residueTank = new FluidStacksResourceHandler(1, TANK_CAPACITY) {
        @Override
        public boolean isValid(int index, FluidResource resource) {
            return false;
        }

        @Override
        protected void onContentsChanged(int index, FluidStack previousContents) {
            setChanged();
        }
    };
    private final ResourceHandler<FluidResource> combinedTanks =
            new CombinedResourceHandler<>(fuelTank, coolantTank, residueTank);

    /** A single combined bucket-input slot (positioned in the GUI on the real "+" icon baked into
     * {@code engine_combustion.png} between the fuel and coolant gauges - a real design element real source
     * itself just uses as decoration since source has no dedicated slot at all, see {@link CombustionEngineMenu}) -
     * auto-detects which tank to route to based on the bucket's contents (Oil/Fuel -> fuelTank, Water ->
     * coolantTank), rather than 2 separate fixed-purpose slots (an earlier, over-engineered first pass, per
     * direct user feedback). Auto-drains every tick in {@link #updateHeat} via the same {@link BucketResourceHandler}
     * idiom the menu already uses, so it works even with the GUI closed. {@code isValid} MUST accept the empty
     * bucket too, not just filled ones - {@link BucketResourceHandler}'s drain operation is an atomic
     * extract-then-insert-empty-bucket {@code exchange()} (confirmed by reading {@code ItemAccessResourceHandler}
     * source directly), so rejecting the empty-bucket half silently fails the WHOLE exchange - the real root
     * cause of the slots not filling anything at all in the first pass. */
    private final ItemStacksResourceHandler bucketSlot = new ItemStacksResourceHandler(1) {
        @Override
        public boolean isValid(int index, ItemResource resource) {
            return resource.is(FactoryFluids.OIL_BUCKET.get()) || resource.is(FactoryFluids.FUEL_BUCKET.get())
                    || resource.is(Items.WATER_BUCKET) || resource.is(Items.BUCKET);
        }

        @Override
        protected void onContentsChanged(int index, ItemStack previousContents) {
            setChanged();
        }
    };

    private int burnTime = 0;
    private int penaltyCooling = 0;
    /** Which fuel type the current burn cycle's power/duration values came from - set fresh each time a new mB is consumed. */
    private boolean burningRefinedFuel = false;

    public CombustionEngineBlockEntity(BlockPos pos, BlockState state) {
        super(EnergyContent.ENGINE_COMBUSTION_BLOCK_ENTITY.get(), pos, state);
    }

    public ResourceHandler<FluidResource> getFluidHandler() {
        return combinedTanks;
    }

    /** Backs the GUI's single combined bucket-input slot. */
    public ItemStacksResourceHandler getBucketSlot() {
        return bucketSlot;
    }

    /** 0=fuel, 1=coolant, 2=residue, matching {@code ContainerEngineIron_BC8}'s tank order for GUI interaction. */
    public ResourceHandler<FluidResource> getTank(int tankIndex) {
        return switch (tankIndex) {
            case 0 -> fuelTank;
            case 1 -> coolantTank;
            default -> residueTank;
        };
    }

    /** 0=fuel, 1=coolant, 2=residue - fill level in PERMILLE (0-1000, not 0-100), for the GUI's tank bars.
     * Real bug fixed (2026-08-10, user-reported "fuel gauge never moves"): the fuel tank genuinely was draining
     * correctly the whole time (1 mB consumed per burn cycle - confirmed via {@link #burn}), but at
     * {@link #TANK_CAPACITY}=10,000 mB, 1 mB is only 0.01% - an integer 0-100 percent literally cannot represent
     * a change that small, so the displayed number (and the 60px-tall bar, at ~0.6%/pixel) looked completely
     * static for 100+ burn cycles (100+ seconds for Oil, 500+ for refined Fuel) even though real consumption was
     * happening the entire time. Permille gives 10x the resolution - still coarse, but a visible pixel of
     * movement now takes ~10x fewer real seconds instead of being invisible for minutes. */
    public static boolean DEBUG_LOG = true;

    public int getTankFillPercent(int tankIndex) {
        FluidStacksResourceHandler tank = switch (tankIndex) {
            case 0 -> fuelTank;
            case 1 -> coolantTank;
            default -> residueTank;
        };
        long amount = tank.getAmountAsLong(0);
        int permille = (int) (1000 * amount / TANK_CAPACITY);
        if (DEBUG_LOG && tankIndex == 0 && level != null && level.getGameTime() % 20 == 0) {
            com.buildcraft.BuildCraft.LOGGER.info(
                    "[COMBUSTION_GUI_DEBUG] pos={} fuelTank amount={}mB permille={} burnTime={} burningRefinedFuel={}",
                    getBlockPos(), amount, permille, burnTime, burningRefinedFuel);
        }
        return permille;
    }

    /** The fuel tank accepts EITHER Oil or Fuel (see class javadoc) - the GUI needs to know which one is
     * actually in there to render the right texture/colour, since it previously always assumed Fuel regardless
     * of contents (the "adding Oil shows as Fuel" bug). 0=empty, 1=Oil, 2=Fuel. */
    public int getFuelTankFluidId() {
        FluidResource resource = fuelTank.getResource(0);
        if (resource.isEmpty()) {
            return 0;
        }
        return resource.getFluid() == FactoryFluids.FUEL_SOURCE.get() ? 2 : 1;
    }

    @Override
    public @Nullable AbstractContainerMenu createMenu(int containerId, Inventory playerInventory, Player player) {
        return new CombustionEngineMenu(EnergyContent.COMBUSTION_ENGINE_MENU.get(), containerId, playerInventory, this);
    }

    @Override
    public Component getDisplayName() {
        return Component.translatable("block.buildcraft.engine_combustion");
    }

    @Override
    public long getMaxPower() {
        return Config.ENGINE_COMBUSTION_CAPACITY.get();
    }

    @Override
    public Identifier getBodyTexture() {
        return BODY_TEXTURE;
    }

    @Override
    protected void burn(Level level, BlockPos pos, BlockState state) {
        if (penaltyCooling > 0) {
            penaltyCooling--;
            return;
        }
        if (!redstonePowered) {
            if (burnTime > 0) {
                burnTime = 0;
                penaltyCooling = 10;
            }
            return;
        }
        if (burnTime <= 0) {
            FluidResource fuelResource = fuelTank.getResource(0);
            if (!fuelResource.isEmpty()) {
                try (Transaction tx = Transaction.openRoot()) {
                    int extracted = fuelTank.extract(0, fuelResource, 1, tx);
                    if (extracted > 0) {
                        tx.commit();
                        burningRefinedFuel = fuelResource.getFluid() == FactoryFluids.FUEL_SOURCE.get();
                        burnTime = burningRefinedFuel
                                ? Config.ENGINE_COMBUSTION_FUEL_BURN_TICKS_PER_MB.get()
                                : Config.ENGINE_COMBUSTION_OIL_BURN_TICKS_PER_MB.get();
                    }
                }
            }
        }
        if (burnTime > 0) {
            burnTime--;
            int fePerTick = burningRefinedFuel
                    ? Config.ENGINE_COMBUSTION_FUEL_FE_PER_TICK.get()
                    : Config.ENGINE_COMBUSTION_OIL_FE_PER_TICK.get();
            power = Math.min(getMaxPower(), power + fePerTick);
        }
    }

    @Override
    protected void updateHeat() {
        // Runs every tick unconditionally (unlike burn(), which is skipped entirely during OVERHEAT) - so the
        // bucket slot keeps working exactly when coolant is most needed: while overheating.
        tryFillFrom(ItemAccess.forHandlerIndex(bucketSlot, 0));

        if (!redstonePowered || penaltyCooling > 0) {
            heat = Math.max(MIN_HEAT, heat - COOLDOWN_RATE);
            return;
        }
        if (burnTime > 0) {
            // Real source formula (TileEngineIron_BC8.burn(), confirmed 2026-08-11 via the actual BuildCraft
            // GitHub source): heat += powerPerCycle(µJ) * HEAT_PER_MJ / MjAPI.MJ. Since this port's fePerTick is
            // in FE (= real MJ x10, confirmed real ratio 1 MJ = 10 FE), converting back to source's own MJ scale
            // means dividing by 10 first, THEN applying HEAT_PER_FE (the same numeric constant as source's
            // HEAT_PER_MJ, reused as-is since it's a dimensionless ratio) - i.e. fePerTick * HEAT_PER_FE / 10.0,
            // not the earlier undocumented /100.0 guess (confirmed too slow by live testing: heat barely moved
            // after a minute of sustained full-power burning). Verified against real numbers: crude Oil (30
            // FE/tick = 3 MJ/tick real) -> 30*0.0023/10 = 0.0069 heat/tick, matching source's own real Oil-burn
            // heat gain exactly.
            int fePerTick = burningRefinedFuel
                    ? Config.ENGINE_COMBUSTION_FUEL_FE_PER_TICK.get()
                    : Config.ENGINE_COMBUSTION_OIL_FE_PER_TICK.get();
            heat = Math.min(MAX_HEAT, heat + fePerTick * HEAT_PER_FE / 10.0);
        }
        if (heat > IDEAL_HEAT) {
            long coolantAmount = coolantTank.getAmountAsLong(0);
            if (coolantAmount > 0) {
                FluidResource coolantResource = coolantTank.getResource(0);
                int toDrain = (int) Math.min(MAX_COOLANT_PER_TICK, coolantAmount);
                try (Transaction tx = Transaction.openRoot()) {
                    int drained = coolantTank.extract(0, coolantResource, toDrain, tx);
                    if (drained > 0) {
                        tx.commit();
                        heat = Math.max(MIN_HEAT, heat - drained * Config.ENGINE_COMBUSTION_COOLANT_DEGREES_PER_MB.get());
                    }
                }
            }
            // No coolant available: heat simply doesn't decrease this tick, matching source exactly.
        }
    }

    /** Drains a filled bucket at the given item location into whichever tank it actually matches (fuel/oil or
     * fuel-refined -> fuelTank, water -> coolantTank), swapping it for an empty bucket on success - the same
     * {@link BucketResourceHandler} idiom {@link CombustionEngineMenu#tryTransfer} already uses for shift-click/
     * gauge-click. Each tank's own {@code isValid} already only accepts its one matching fluid, so trying both in
     * sequence naturally routes to the right one (or does nothing if the bucket doesn't match either) - this is
     * the "auto-detect which tank" behavior, no separate per-fluid-type slot needed. Also enforces "no mixing"
     * for free: {@code FluidStacksResourceHandler}'s single-resource slot only accepts a fluid that MATCHES its
     * current non-empty contents (confirmed via {@code StacksResourceHandler.insert}'s real logic), so trying to
     * add Oil while the fuel tank already holds Fuel (or vice-versa) is already rejected automatically - real
     * source has no separate rule for this either, it's inherent to a single-fluid tank.
     * @return true if anything was moved. */
    private boolean tryFillFrom(ItemAccess access) {
        ResourceHandler<FluidResource> bucket = new BucketResourceHandler(access);
        try (Transaction tx = Transaction.openRoot()) {
            if (ResourceHandlerUtil.move(bucket, fuelTank, r -> true, Integer.MAX_VALUE, tx) > 0) {
                tx.commit();
                return true;
            }
        }
        try (Transaction tx = Transaction.openRoot()) {
            if (ResourceHandlerUtil.move(bucket, coolantTank, r -> true, Integer.MAX_VALUE, tx) > 0) {
                tx.commit();
                return true;
            }
        }
        return false;
    }

    /** Real source has no direct-right-click fill at all (bucket interaction is GUI-only, via shift-click) -
     * this is a deliberate, disclosed addition per direct user request: right-clicking the block with a Water
     * or Oil/Fuel bucket in hand fills the matching tank without needing to open the GUI at all, using the same
     * routing/no-mixing logic as the GUI slot ({@link #tryFillFrom}). */
    public boolean tryFillFromHand(Player player, InteractionHand hand) {
        return tryFillFrom(ItemAccess.forPlayerInteraction(player, hand));
    }

    /** Cheap, side-effect-free, client-safe check: is this a bucket type this engine would ever accept? See
     * {@link com.buildcraft.energy.block.EngineBlock#useItemOn}'s javadoc for why this specific check (not the
     * real tank-mutating fill) needs to run identically on both client and server. */
    public boolean isFillableBucket(ItemStack stack) {
        return stack.is(FactoryFluids.OIL_BUCKET.get()) || stack.is(FactoryFluids.FUEL_BUCKET.get()) || stack.is(Items.WATER_BUCKET);
    }

    /** Real source value (confirmed 2026-08-11 via BuildCraftAPI/BuildCraft GitHub source): 500 MJ = 5000 FE at
     * the real 1 MJ = 10 FE ratio. Same "buffer can burst above production rate, then throttles down" dynamic as
     * Stirling (see {@link StirlingEngineBlockEntity#getMaxPowerExtracted}'s javadoc) - Combustion's buffer
     * (100,000 FE) dwarfs its real production rate (30-40 FE/tick), so a well-charged engine can sustain this
     * full 5000 FE/tick burst for a while before draining down to its true production-limited pace. */
    @Override
    protected long getMaxPowerExtracted() {
        return Config.ENGINE_COMBUSTION_MAX_PULSE_OUTPUT.get();
    }

    /** Real bug fixed (2026-08-12, confirmed via the actual complete TileEngineIron_BC8.java source): this used
     * to tie pumping directly to {@code burnTime>0} - meaning the piston stopped the INSTANT fuel/burnTime
     * emptied, even if there was tons of stored power (up to 100,000 FE) left in the buffer. Real source's
     * actual pumping condition ({@code TileEngineBase_BC8.update()}: {@code isRedstonePowered && isActive() &&
     * getPowerToExtract(false) > 0}) has nothing to do with whether fuel/burnTime is currently active - it pumps
     * (and keeps sending power to a receiver) as long as it's powered, not in the post-signal-loss penalty
     * cooldown, AND still has real stored power to give. Running out of fuel while a full buffer remains is
     * genuinely NOT supposed to stop the engine immediately in real source - it keeps draining its reserve,
     * exactly the "why doesn't it turn off when fuel hits 0" behavior a player reported seeing. This doesn't
     * replicate source's exact receiver-lookup (that lives in {@link #pushPower}, not called from here), but
     * matches its real spirit: powered, not penalized, and something left to give. */
    @Override
    protected boolean isPumping() {
        return redstonePowered && penaltyCooling <= 0 && power > 0;
    }

    /** Real source (TileEngineIron_BC8.java, confirmed 2026-08-12): Combustion's own piston-speed curve is
     * completely different from the generic base-class curve (0.02/0.04/0.08/0.12) - a much flatter
     * 0.04/0.05/0.06/0.07 across BLUE/GREEN/YELLOW/RED, 0 at OVERHEAT. This override never existed before, so
     * Combustion was silently using the wrong (steeper, generic) curve the whole time - a real, confirmed bug,
     * not a documented deviation. */
    @Override
    protected float getPistonSpeed(PowerStage stage) {
        return switch (stage) {
            case BLUE -> 0.04f;
            case GREEN -> 0.05f;
            case YELLOW -> 0.06f;
            case RED -> 0.07f;
            case OVERHEAT -> 0f;
        };
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        output.putInt("burnTime", burnTime);
        output.putInt("penaltyCooling", penaltyCooling);
        fuelTank.serialize(output.child("fuelTank"));
        coolantTank.serialize(output.child("coolantTank"));
        residueTank.serialize(output.child("residueTank"));
        bucketSlot.serialize(output.child("bucketSlot"));
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        burnTime = input.getIntOr("burnTime", 0);
        penaltyCooling = input.getIntOr("penaltyCooling", 0);
        input.child("fuelTank").ifPresent(fuelTank::deserialize);
        input.child("coolantTank").ifPresent(coolantTank::deserialize);
        input.child("residueTank").ifPresent(residueTank::deserialize);
        input.child("bucketSlot").ifPresent(bucketSlot::deserialize);
    }
}
