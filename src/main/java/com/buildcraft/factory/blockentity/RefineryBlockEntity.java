package com.buildcraft.factory.blockentity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.neoforged.neoforge.transfer.CombinedResourceHandler;
import net.neoforged.neoforge.transfer.ResourceHandler;
import net.neoforged.neoforge.transfer.ResourceHandlerUtil;
import net.neoforged.neoforge.transfer.access.ItemAccess;
import net.neoforged.neoforge.transfer.energy.EnergyHandler;
import net.neoforged.neoforge.transfer.energy.SimpleEnergyHandler;
import net.neoforged.neoforge.transfer.fluid.BucketResourceHandler;
import net.neoforged.neoforge.transfer.fluid.FluidResource;
import net.neoforged.neoforge.transfer.fluid.FluidStacksResourceHandler;
import net.neoforged.neoforge.transfer.item.ItemResource;
import net.neoforged.neoforge.transfer.item.ItemStacksResourceHandler;
import net.neoforged.neoforge.transfer.transaction.Transaction;
import org.jspecify.annotations.Nullable;

import com.buildcraft.BuildCraft;
import com.buildcraft.Config;
import com.buildcraft.factory.FactoryContent;
import com.buildcraft.factory.FactoryFluids;
import com.buildcraft.factory.menu.RefineryMenu;

/**
 * Ports {@code TileRefinery} (the classic simple Refinery - see the Fuel-module memory entry for the real
 * "Factory not loaded" 2-fluid scope this matches). Real recipe from {@code BuildCraftEnergy.java}:
 * 1mB Oil -&gt; 1mB Fuel, {@link Config#REFINERY_ENERGY_PER_MB} FE (source: 120 RF), attempted every tick
 * (source: {@code craftingTime=1}) - a steady, continuous conversion, not a slow batch process. Draws power
 * continuously (NOT a {@code PulsedEnergyReceiver} - source's Refinery uses a plain RF battery, not the special
 * {@code IMjRedstoneReceiver} pulsed-delivery interface Wood-family pipes use), so an Engine feeds it every
 * tick like the Quarry.
 * <p>
 * Real source's {@code animationSpeed}/{@code animationStage} fields (confirmed via {@code TileRefinery.update}/
 * {@code increaseAnimation}/{@code decreaseAnimation}) drive 2 visible "magnet" pistons in
 * {@code RenderRefinery} that speed up while actively converting with power and wind down to a stop when idle -
 * ported here as a purely visual analogue (this port's simplified continuous-conversion tick doesn't have
 * source's discrete per-craft energyCost/craftingTime cycle to hook into, so speed just ramps toward a target
 * based on whether a conversion happened THIS tick, using the same 1-5 speed range and 0-300 stage-loop as
 * source). Synced to the client every tick via {@link #getUpdatePacket()}/{@link #getUpdateTag}, matching
 * {@code EngineBlockEntity}'s existing pattern in this project, since the renderer needs live values every frame.
 */
public class RefineryBlockEntity extends BlockEntity implements MenuProvider {
    public static boolean DEBUG_LOG = true;
    private long debugFeAccum = 0;
    private int debugMbAccum = 0;

    private final FluidStacksResourceHandler oilTank = new FluidStacksResourceHandler(1, Config.REFINERY_TANK_CAPACITY.get()) {
        @Override
        public boolean isValid(int index, FluidResource resource) {
            return resource.getFluid() == FactoryFluids.OIL_SOURCE.get();
        }

        @Override
        protected void onContentsChanged(int index, net.neoforged.neoforge.fluids.FluidStack previousContents) {
            setChanged();
        }
    };
    /** THE critical bug (found, root-caused, fixed): this used to hardcode {@code isValid=false} for every
     * resource, meaning "block manual bucket-filling, output-only". But the real library's own
     * {@code StacksResourceHandler.getCapacityAsLong} ALSO gates on {@code isValid} - {@code return
     * resource.isEmpty() || isValid(index, resource) ? getCapacity(...) : 0;} - confirmed by reading that method
     * directly. With {@code isValid} always false, {@code getCapacityAsLong} always returned 0, which made
     * {@code tryConvert()}'s "is the output already full?" check ({@code amount >= capacity} -> {@code 0 >= 0})
     * true IMMEDIATELY, every tick, regardless of power or oil - conversion could never start. Worse:
     * {@code StacksResourceHandler.insert} ALSO gates on {@code isValid}, so even bypassing that check, the
     * conversion's OWN internal {@code fuelTank.insert(...)} call would have failed too - the tank could never
     * receive fuel from anything, including its own conversion. This is why NO engine tier could ever "power"
     * the animation/conversion - power was never actually the bottleneck. Fixed by making {@code isValid} check
     * for the real Fuel resource (matching the oil tank's own pattern) instead of hardcoding false - this
     * correctly unblocks both the capacity check and internal insert. External bucket-filling of Fuel still
     * doesn't happen in practice: {@link #tryFillOrDrain} was never written to attempt a fill-fuel-from-bucket
     * move in the first place, only fill-oil and drain-either, so "output-only" still holds through the
     * fill/drain logic's own design, not through this broken isValid hack. */
    private final FluidStacksResourceHandler fuelTank = new FluidStacksResourceHandler(1, Config.REFINERY_TANK_CAPACITY.get()) {
        @Override
        public boolean isValid(int index, FluidResource resource) {
            return resource.getFluid() == FactoryFluids.FUEL_SOURCE.get();
        }

        @Override
        protected void onContentsChanged(int index, net.neoforged.neoforge.fluids.FluidStack previousContents) {
            setChanged();
        }
    };
    private final ResourceHandler<FluidResource> combinedTanks = new CombinedResourceHandler<>(oilTank, fuelTank);
    private final SimpleEnergyHandler energy = new SimpleEnergyHandler(
            Config.REFINERY_ENERGY_CAPACITY.get(), Config.REFINERY_MAX_FE_PER_TICK.get());

    /** A single combined bucket slot for the GUI, ported from real source's {@code BlockRefinery.onBlockActivated}
     * + {@code TankUtils.handleRightClick} (real source has BOTH a direct right-click-in-hand interaction AND,
     * implicitly, this same fill-or-drain logic reused for the GUI - neither existed in this port until now).
     * Real logic: holding a FILLED container tries to FILL the tile; holding an EMPTY container tries to DRAIN
     * it - real {@code TankManager.fill}/{@code .drain} try every internal tank in order, so filling naturally
     * only succeeds against the oil tank (the only one that accepts input) and draining can pull from whichever
     * tank actually has content (oil, if nothing's been converted yet, or fuel once it has) - ported exactly via
     * {@link #tryFillOrDrain}, reused by both the GUI slot (every tick, see {@link #tick}) and the block's
     * direct right-click ({@link #tryFillOrDrainFromHand}). {@code isValid} accepts Oil/Fuel buckets and the
     * empty bucket - the empty bucket is required for both directions to complete (filling swaps to an empty
     * bucket, draining needs an empty bucket to swap FROM) - same real bug/fix as the Combustion Engine's slot. */
    private final ItemStacksResourceHandler bucketSlot = new ItemStacksResourceHandler(1) {
        @Override
        public boolean isValid(int index, ItemResource resource) {
            return resource.is(FactoryFluids.OIL_BUCKET.get()) || resource.is(FactoryFluids.FUEL_BUCKET.get()) || resource.is(Items.BUCKET);
        }

        @Override
        protected void onContentsChanged(int index, ItemStack previousContents) {
            setChanged();
        }
    };

    private float animationSpeed = 1;
    private short animationStage = 0;

    public RefineryBlockEntity(BlockPos pos, BlockState state) {
        super(FactoryContent.REFINERY_BLOCK_ENTITY.get(), pos, state);
    }

    public ResourceHandler<FluidResource> getFluidHandler() {
        return combinedTanks;
    }

    public EnergyHandler getEnergyHandler() {
        return energy;
    }

    /** Backs the GUI's single combined bucket slot. */
    public ItemStacksResourceHandler getBucketSlot() {
        return bucketSlot;
    }

    /** 0=oil, 1=fuel - fill percentage (0-100), for the GUI's tank bars. */
    public int getTankFillPercent(int tankIndex) {
        FluidStacksResourceHandler tank = tankIndex == 0 ? oilTank : fuelTank;
        return (int) (100 * tank.getAmountAsLong(0) / Config.REFINERY_TANK_CAPACITY.get());
    }

    /** How fast the renderer's 2 magnet pistons cycle (1-5, real source range) - see class javadoc. */
    public float getAnimationSpeed() {
        return animationSpeed;
    }

    /** Real source's 0-300 looping animation position (see {@code TileRefinery.getAnimationStage}). */
    public int getAnimationStage() {
        return animationStage;
    }

    public static void tick(Level level, BlockPos pos, BlockState state, RefineryBlockEntity be) {
        if (level.isClientSide()) {
            be.simpleAnimationIterate();
            return;
        }

        be.tryFillOrDrain(ItemAccess.forHandlerIndex(be.bucketSlot, 0));

        boolean converted = be.tryConvert();
        if (converted) {
            be.increaseAnimation();
        } else {
            be.decreaseAnimation();
        }
        level.sendBlockUpdated(pos, state, state, 2);

        if (DEBUG_LOG && level.getGameTime() % 100 == 0) {
            BuildCraft.LOGGER.info(
                    "[REFINERY_DEBUG] pos={} stored={}/{} FE consumed last 100t={} FE ({}/t avg) oilConverted last 100t={} mB",
                    pos, be.energy.getAmountAsLong(), Config.REFINERY_ENERGY_CAPACITY.get(), be.debugFeAccum,
                    be.debugFeAccum / 100.0, be.debugMbAccum);
            be.debugFeAccum = 0;
            be.debugMbAccum = 0;
        }
    }

    /** Ports {@code TankUtils.handleRightClick}: tries to FILL first (only the oil tank actually accepts
     * anything), then tries to DRAIN (from whichever tank has content - real source's {@code TankManager}
     * tries tanks in registration order, oil then fuel, so this can pull back un-converted oil if the fuel
     * tank happens to be empty, matching real behavior rather than assuming "always drain fuel"). Reused by
     * both the GUI slot (every tick) and direct right-click-in-hand.
     * <p>
     * The fill attempt explicitly caps the move amount at the oil tank's REMAINING room (not
     * {@code Integer.MAX_VALUE}) - a defensive, always-correct guard: read through
     * {@code ResourceHandlerUtil.move}/the underlying {@code insert}/{@code extract} logic directly and
     * couldn't find an actual bug in the capacity math (a bucket's own all-or-nothing amount tracking already
     * makes a partial/over-capacity transfer fail cleanly), but this closes the gap either way without relying
     * on that trace being exhaustive.
     * @return true if anything moved. */
    private boolean tryFillOrDrain(ItemAccess access) {
        ResourceHandler<FluidResource> bucket = new BucketResourceHandler(access);
        long oilRoom = oilTank.getCapacityAsLong(0, FluidResource.of(FactoryFluids.OIL_SOURCE.get())) - oilTank.getAmountAsLong(0);
        if (oilRoom > 0) {
            try (Transaction tx = Transaction.openRoot()) {
                if (ResourceHandlerUtil.move(bucket, oilTank, r -> true, (int) Math.min(Integer.MAX_VALUE, oilRoom), tx) > 0) {
                    tx.commit();
                    return true;
                }
            }
        }
        try (Transaction tx = Transaction.openRoot()) {
            if (ResourceHandlerUtil.move(oilTank, bucket, r -> true, Integer.MAX_VALUE, tx) > 0) {
                tx.commit();
                return true;
            }
        }
        try (Transaction tx = Transaction.openRoot()) {
            if (ResourceHandlerUtil.move(fuelTank, bucket, r -> true, Integer.MAX_VALUE, tx) > 0) {
                tx.commit();
                return true;
            }
        }
        return false;
    }

    /** Real source has {@code BlockRefinery.onBlockActivated} try this BEFORE opening the GUI - ported the same
     * way as the Combustion Engine's equivalent. */
    public boolean tryFillOrDrainFromHand(Player player, InteractionHand hand) {
        return tryFillOrDrain(ItemAccess.forPlayerInteraction(player, hand));
    }

    /** Cheap, side-effect-free, client-safe check: is this a bucket type the Refinery would ever accept or
     * produce? See {@code RefineryBlock.useItemOn}'s javadoc (mirroring {@code EngineBlock}'s) for why this
     * specific check - not the real tank-mutating fill/drain - needs to run identically on both sides. */
    public boolean isRelevantBucket(ItemStack stack) {
        return stack.is(FactoryFluids.OIL_BUCKET.get()) || stack.is(FactoryFluids.FUEL_BUCKET.get()) || stack.is(Items.BUCKET);
    }

    private boolean tryConvert() {
        int energyPerMb = Config.REFINERY_ENERGY_PER_MB.get();
        if (energy.getAmountAsLong() < energyPerMb) {
            return false;
        }
        FluidResource oil = oilTank.getResource(0);
        if (oil.isEmpty() || oilTank.getAmountAsLong(0) < 1) {
            return false;
        }
        FluidResource fuelResource = FluidResource.of(FactoryFluids.FUEL_SOURCE.get());
        int fuelCapacity = (int) fuelTank.getCapacityAsLong(0, fuelResource);
        if (fuelTank.getAmountAsLong(0) >= fuelCapacity) {
            return false;
        }
        try (Transaction tx = Transaction.openRoot()) {
            int extracted = oilTank.extract(0, oil, 1, tx);
            if (extracted <= 0) {
                return false;
            }
            int inserted = fuelTank.insert(0, fuelResource, extracted, tx);
            if (inserted <= 0) {
                return false;
            }
            int energyExtracted = energy.extract(energyPerMb, tx);
            if (energyExtracted < energyPerMb) {
                return false;
            }
            tx.commit();
            debugFeAccum += energyExtracted;
            debugMbAccum += extracted;
        }
        setChanged();
        return true;
    }

    /** Ports {@code TileRefinery.simpleAnimationIterate} - client-side-only replay of the synced speed/stage. */
    private void simpleAnimationIterate() {
        if (animationSpeed > 1) {
            animationStage += animationSpeed;
            if (animationStage > 300) {
                animationStage = 100;
            }
        } else if (animationStage > 0) {
            animationStage--;
        }
    }

    /** Ports {@code TileRefinery.increaseAnimation}. */
    private void increaseAnimation() {
        if (animationSpeed < 2) {
            animationSpeed = 2;
        } else if (animationSpeed <= 5) {
            animationSpeed += 0.1f;
        }
        animationStage += animationSpeed;
        if (animationStage > 300) {
            animationStage = 100;
        }
    }

    /** Ports {@code TileRefinery.decreaseAnimation}. */
    private void decreaseAnimation() {
        if (animationSpeed >= 1) {
            animationSpeed -= 0.1f;
            animationStage += animationSpeed;
            if (animationStage > 300) {
                animationStage = 100;
            }
        } else if (animationStage > 0) {
            animationStage--;
        }
    }

    @Override
    public @Nullable AbstractContainerMenu createMenu(int containerId, Inventory playerInventory, Player player) {
        return new RefineryMenu(FactoryContent.REFINERY_MENU.get(), containerId, playerInventory, this);
    }

    @Override
    public Component getDisplayName() {
        return Component.translatable("block.buildcraft.refinery");
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
        oilTank.serialize(output.child("oilTank"));
        fuelTank.serialize(output.child("fuelTank"));
        output.putInt("energy", (int) energy.getAmountAsLong());
        output.putFloat("animationSpeed", animationSpeed);
        output.putShort("animationStage", animationStage);
        bucketSlot.serialize(output.child("bucketSlot"));
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        input.child("oilTank").ifPresent(oilTank::deserialize);
        input.child("fuelTank").ifPresent(fuelTank::deserialize);
        energy.set(input.getIntOr("energy", 0));
        animationSpeed = input.getFloatOr("animationSpeed", 1f);
        animationStage = (short) input.getIntOr("animationStage", 0);
        input.child("bucketSlot").ifPresent(bucketSlot::deserialize);
    }
}
