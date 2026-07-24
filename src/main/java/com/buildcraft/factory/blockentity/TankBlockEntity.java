package com.buildcraft.factory.blockentity;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.neoforged.neoforge.transfer.ResourceHandler;
import net.neoforged.neoforge.transfer.ResourceHandlerUtil;
import net.neoforged.neoforge.transfer.access.ItemAccess;
import net.neoforged.neoforge.transfer.fluid.BucketResourceHandler;
import net.neoforged.neoforge.transfer.fluid.FluidResource;
import net.neoforged.neoforge.transfer.fluid.FluidStacksResourceHandler;
import net.neoforged.neoforge.transfer.transaction.Transaction;
import net.neoforged.neoforge.transfer.transaction.TransactionContext;
import org.jspecify.annotations.Nullable;

import com.buildcraft.Config;
import com.buildcraft.factory.FactoryContent;
import com.buildcraft.factory.block.TankBlock;

/**
 * Ports {@code TileTank} (real source: {@code common/buildcraft/factory/tile/TileTank.java}). Each tile stores
 * exactly ONE real fluid slot ({@link #tank}, generic - any fluid, not restricted like the Refinery), capacity
 * {@link Config#TANK_CAPACITY} (real: 16 buckets). The real standout feature: vertically stacked Tanks act as
 * ONE combined store - {@link #getTanks()} walks the connected chain (real {@code getTanks()}, up then down,
 * bottom-to-top order), and {@link #getFluidHandler()} exposes that whole chain as a single virtual slot (real
 * {@code fill}/{@code drain}/{@code getTankProperties}), filling the BOTTOM tank first and draining the TOP tank
 * first - real source's own liquid-specific ordering ({@code TileTank.fill}/{@code drain}: the gaseous-fluid
 * branch that would reverse this is skipped entirely here, since this project has no gaseous fluids - a
 * documented scope line, not an oversight).
 * <p>
 * One real simplification: source's {@code fill()} pre-checks EVERY tank in the chain and refuses the WHOLE
 * operation if even one holds a different fluid, before moving anything. This port's per-tank insert loop
 * (inside {@link #getFluidHandler()}'s handler) instead just skips whichever individual tank doesn't match
 * (each {@link FluidStacksResourceHandler#insert} already naturally rejects a mismatched non-empty tank) - only
 * different in the unusual edge case of a manually-assembled chain with pre-existing mixed fluids, which
 * {@link #balanceTankFluids()} (ported faithfully, real {@code TileTank.balanceTankFluids}) already refuses to
 * touch/settle in that same situation anyway.
 * <p>
 * No {@code FluidSmoother} client-interpolation is ported (real source smooths fast fluid-level changes over
 * several ticks for nicer rendering) - this port's renderer reads the raw synced value directly each frame, the
 * same simplification already used throughout this project's other fluid-holding blocks.
 * <p>
 * No GUI/menu (removed per direct user request - see {@code TankBlock}'s javadoc). {@link #tryFillOrDrainFromHand}
 * (via direct right-click, operating on the whole connected stack) is the only fill/drain path now.
 */
public class TankBlockEntity extends BlockEntity {
    private final FluidStacksResourceHandler tank = new FluidStacksResourceHandler(1, Config.TANK_CAPACITY.get()) {
        @Override
        protected void onContentsChanged(int index, net.neoforged.neoforge.fluids.FluidStack previousContents) {
            setChanged();
            checkComparator();
            // Real source syncs on a per-tick basis via NET_RENDER_DATA/smoothedTank - this port has no ticker
            // (see class javadoc), so the world renderer/GUI need an explicit push here instead, matching the
            // "send on every real change" intent without a tick loop to drive it from.
            if (level != null && !level.isClientSide()) {
                level.sendBlockUpdated(getBlockPos(), getBlockState(), getBlockState(), 2);
            }
        }
    };

    /** Presents the whole connected vertical chain (see {@link #getTanks()}) as a single virtual slot - real
     * {@code TileTank} implements {@code IFluidHandler} itself the same way, backed by {@code getTanks()}. Used
     * both for the block's exposed {@code Capabilities.Fluid.BLOCK} capability (so pipes/other machines interact
     * with the WHOLE stack through any one block in it) and for direct right-click bucket fill/drain. */
    private final ResourceHandler<FluidResource> stackHandler = new ResourceHandler<>() {
        @Override
        public int size() {
            return 1;
        }

        @Override
        public FluidResource getResource(int index) {
            for (TankBlockEntity t : getTanks()) {
                FluidResource r = t.tank.getResource(0);
                if (!r.isEmpty()) {
                    return r;
                }
            }
            return FluidResource.EMPTY;
        }

        @Override
        public long getAmountAsLong(int index) {
            long total = 0;
            for (TankBlockEntity t : getTanks()) {
                total += t.tank.getAmountAsLong(0);
            }
            return total;
        }

        @Override
        public long getCapacityAsLong(int index, FluidResource resource) {
            return (long) Config.TANK_CAPACITY.get() * getTanks().size();
        }

        @Override
        public boolean isValid(int index, FluidResource resource) {
            return true;
        }

        /** Real order: liquids fill the BOTTOM tank of the chain first (real {@code TileTank.fill}: the
         * gaseous-reverse branch never triggers for this project's fluids, so the natural bottom-to-top
         * {@link #getTanks()} order is used directly). */
        @Override
        public int insert(int index, FluidResource resource, int amount, TransactionContext transaction) {
            int total = 0;
            for (TankBlockEntity t : getTanks()) {
                total += t.tank.insert(0, resource, amount - total, transaction);
                if (total >= amount) {
                    break;
                }
            }
            return total;
        }

        /** Real order: liquids drain the TOP tank of the chain first (real {@code TileTank.drain}: the
         * non-gaseous branch reverses the bottom-to-top {@link #getTanks()} list). */
        @Override
        public int extract(int index, FluidResource resource, int amount, TransactionContext transaction) {
            List<TankBlockEntity> tanks = getTanks();
            int total = 0;
            for (int i = tanks.size() - 1; i >= 0; i--) {
                total += tanks.get(i).tank.extract(0, resource, amount - total, transaction);
                if (total >= amount) {
                    break;
                }
            }
            return total;
        }
    };

    private int lastComparatorLevel = -1;

    public TankBlockEntity(BlockPos pos, BlockState state) {
        super(FactoryContent.TANK_BLOCK_ENTITY.get(), pos, state);
    }

    public ResourceHandler<FluidResource> getFluidHandler() {
        return stackHandler;
    }

    /** 0-100, THIS tile's own individual fill level - real {@code WidgetFluidTank(this, tank.tank)} shows the
     * individual tile's tank in the GUI, not the combined connected-stack amount (matching
     * {@link TankBlock#getAnalogOutputSignal}'s same real per-tile quirk). */
    public int getFillPercent() {
        return (int) (100 * tank.getAmountAsLong(0) / Config.TANK_CAPACITY.get());
    }

    public FluidResource getFluidResource() {
        return tank.getResource(0);
    }

    /** Ports {@code RenderTank.isFullyConnected} - real source's own fully-liquid-specific reading (the
     * gaseous-reversed branch is skipped, matching the rest of this class - see class javadoc), used by the
     * renderer to decide whether to visually merge with the neighbour in the given direction (extend this
     * tank's own fluid-box boundary to the block edge and skip drawing that face) rather than showing a gap.
     * Real logic, confirmed directly in source: looking UP only requires the neighbour to share the same fluid
     * and have ANY amount (if a tank above holds any of the same liquid at all, gravity guarantees this tank
     * must already be full); looking DOWN additionally requires the neighbour to be COMPLETELY full (otherwise
     * there would be a visible gap between this tank's liquid and the neighbour's smaller amount). */
    public boolean isFullyConnected(Direction face) {
        if (level == null) {
            return false;
        }
        if (!(level.getBlockEntity(getBlockPos().relative(face)) instanceof TankBlockEntity other)) {
            return false;
        }
        FluidResource thisFluid = tank.getResource(0);
        FluidResource otherFluid = other.tank.getResource(0);
        if (thisFluid.isEmpty() || otherFluid.isEmpty() || !thisFluid.equals(otherFluid)) {
            return false;
        }
        if (other.tank.getAmountAsLong(0) <= 0) {
            return false;
        }
        return face == Direction.UP || other.tank.getAmountAsLong(0) >= Config.TANK_CAPACITY.get();
    }

    /** Ports {@code TileTank.getComparatorLevel} exactly. */
    public int getComparatorLevel() {
        long amount = tank.getAmountAsLong(0);
        long cap = Config.TANK_CAPACITY.get();
        return (int) (amount * 14 / cap + (amount > 0 ? 1 : 0));
    }

    private void checkComparator() {
        int comparatorLevel = getComparatorLevel();
        if (comparatorLevel != lastComparatorLevel) {
            lastComparatorLevel = comparatorLevel;
            if (level != null && !level.isClientSide()) {
                level.updateNeighborsAt(getBlockPos(), getBlockState().getBlock());
            }
        }
    }

    /** Ports {@code TileTank.getTanks} - walks up, then down, while the neighbour is also a Tank (real source
     * additionally checks a per-direction {@code canConnectTo}, which always returns {@code true} by default and
     * is never overridden anywhere in real source's own Tank - so a direct {@code instanceof} check is
     * equivalent here, and simpler). Bottom-to-top order. */
    private List<TankBlockEntity> getTanks() {
        if (level == null) {
            return List.of(this);
        }
        Deque<TankBlockEntity> tanks = new ArrayDeque<>();
        tanks.add(this);
        BlockPos pos = getBlockPos();
        BlockPos above = pos.above();
        while (level.getBlockEntity(above) instanceof TankBlockEntity tankAbove) {
            tanks.addLast(tankAbove);
            above = above.above();
        }
        BlockPos below = pos.below();
        while (level.getBlockEntity(below) instanceof TankBlockEntity tankBelow) {
            tanks.addFirst(tankBelow);
            below = below.below();
        }
        return new ArrayList<>(tanks);
    }

    /** Ports {@code TileTank.balanceTankFluids} - called once, right after placement (see
     * {@link TankBlock#setPlacedBy}), settling fluid down through the newly formed chain. Real source refuses to
     * touch anything if the chain already holds more than one distinct fluid (the mismatched-manual-fill edge
     * case - see class javadoc), ported literally. */
    public void balanceTankFluids() {
        if (level == null || level.isClientSide()) {
            return;
        }
        List<TankBlockEntity> tanks = getTanks();
        FluidResource fluid = null;
        for (TankBlockEntity t : tanks) {
            FluidResource r = t.tank.getResource(0);
            if (r.isEmpty()) {
                continue;
            }
            if (fluid == null) {
                fluid = r;
            } else if (!fluid.equals(r)) {
                return;
            }
        }
        if (fluid == null) {
            return;
        }
        TankBlockEntity prev = null;
        for (TankBlockEntity t : tanks) {
            if (prev != null) {
                try (Transaction tx = Transaction.openRoot()) {
                    if (ResourceHandlerUtil.move(t.tank, prev.tank, r -> true, Integer.MAX_VALUE, tx) > 0) {
                        tx.commit();
                    }
                }
            }
            prev = t;
        }
    }

    /** Cheap, side-effect-free, client-safe check: any vanilla-shaped bucket (real source's generic
     * {@code FluidUtil.getFluidHandler} accepts any {@code IFluidHandlerItem}-capable item, which in practice
     * means any {@code BucketItem} - water/lava/modded fluids included - plus the empty bucket needed to
     * complete a drain). See {@code TankBlock.useItemOn}'s javadoc for why this exact check needs to run
     * identically on both sides. */
    public boolean isRelevantBucket(ItemStack stack) {
        return stack.getItem() instanceof BucketItem || stack.is(Items.BUCKET);
    }

    /** Ports {@code FluidUtilBC.onTankActivated}'s real order: try FILL first, then DRAIN. Operates on the whole
     * connected stack ({@link #stackHandler}), matching real source (the tile implements {@code IFluidHandler}
     * itself, backed by {@code getTanks()}).
     * @return true if anything moved. */
    private boolean tryFillOrDrain(ItemAccess access) {
        ResourceHandler<FluidResource> bucket = new BucketResourceHandler(access);
        try (Transaction tx = Transaction.openRoot()) {
            if (ResourceHandlerUtil.move(bucket, stackHandler, r -> true, Integer.MAX_VALUE, tx) > 0) {
                tx.commit();
                return true;
            }
        }
        try (Transaction tx = Transaction.openRoot()) {
            if (ResourceHandlerUtil.move(stackHandler, bucket, r -> true, Integer.MAX_VALUE, tx) > 0) {
                tx.commit();
                return true;
            }
        }
        return false;
    }

    public boolean tryFillOrDrainFromHand(Player player, InteractionHand hand) {
        return tryFillOrDrain(ItemAccess.forPlayerInteraction(player, hand));
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
        tank.serialize(output.child("tank"));
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        input.child("tank").ifPresent(tank::deserialize);
    }
}
