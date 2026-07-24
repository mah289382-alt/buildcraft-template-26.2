package com.buildcraft.transport.blockentity;

import java.util.EnumSet;
import java.util.Set;
import java.util.function.Supplier;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.fluids.FluidType;
import net.neoforged.neoforge.transfer.ResourceHandler;
import net.neoforged.neoforge.transfer.ResourceHandlerUtil;
import net.neoforged.neoforge.transfer.fluid.FluidResource;
import net.neoforged.neoforge.transfer.fluid.FluidStacksResourceHandler;
import net.neoforged.neoforge.transfer.transaction.Transaction;
import net.neoforged.neoforge.transfer.transaction.TransactionContext;
import org.jspecify.annotations.Nullable;

import com.buildcraft.transport.block.FluidPipeBlock;
import com.buildcraft.transport.pipe.PipeBehaviour;

/**
 * A deliberately simplified stand-in for real {@code PipeFlowFluids}: instead of that class's per-side
 * {@code Section} buffers with delayed-insertion arrays and a 3-phase (moveFromPipe/moveFromCenter/moveToCenter)
 * simulation, each pipe holds ONE {@link FluidStacksResourceHandler} (capacity {@link #CAPACITY} = 1000 mB - real
 * source's own capacity is ALSO flat/non-scaling across every material, confirmed via
 * {@code PipeFlowFluids.capacity = max(BUCKET_VOLUME, transferPerTick*10)}: no tier's real transferPerTick is
 * high enough to exceed 1000, so it always resolves to exactly 1 bucket regardless of material - this port just
 * uses that same real number directly as a constant rather than re-deriving it from a rate every time). Each
 * tick, a pipe with fluid pushes some of it out through one connected side (respecting
 * {@link PipeBehaviour#filterFluidDestinations}/{@link PipeBehaviour#destroysFluids}, and never back out the
 * side it most recently received fluid from - a simple anti-oscillation rule, the same spirit as the item
 * system excluding an item's own entry side from its candidate list), then tries to pull fluid in from an
 * adjacent fluid-storing neighbour if it has room.
 * <p>
 * <b>A real, deliberate deviation from source</b>: real BuildCraft only lets Wood pipes/a dedicated Pump machine
 * actively extract fluid from a stationary tank - every other tier is a pure relay that only moves fluid it's
 * already been given (pushed in by something else). This project doesn't have a Pump machine yet and Wood's own
 * powered extraction is a later stage of this same feature, so EVERY tier here passively self-serves from
 * adjacent fluid-source neighbours (see {@link #pullIn}) - otherwise a Cobblestone-only fluid network would have
 * no way to ever receive fluid at all, making the whole feature untestable in isolation. This is flagged
 * honestly as a scope line, not presented as source-accurate.
 */
public class FluidPipeBlockEntity extends BlockEntity {
    public static final int CAPACITY = FluidType.BUCKET_VOLUME;

    private final PipeBehaviour behaviour;
    private final FluidStacksResourceHandler tank = new FluidStacksResourceHandler(1, CAPACITY) {
        @Override
        protected void onContentsChanged(int index, net.neoforged.neoforge.fluids.FluidStack previousContents) {
            setChanged();
        }
    };
    /** The side fluid most recently entered from - excluded from push-out candidates each tick, a simple
     * anti-oscillation rule (real source achieves the same effect via per-section direction-lock cooldowns).
     * Not persisted, same category as {@link PipeBlockEntity}'s own non-persisted round-robin cursor. */
    private @Nullable Direction lastFillSide;
    private int pushCursor = 0;

    public FluidPipeBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state, Supplier<PipeBehaviour> behaviourFactory) {
        super(type, pos, state);
        this.behaviour = behaviourFactory.get();
    }

    public PipeBehaviour getBehaviour() {
        return behaviour;
    }

    public FluidResource getFluidResource() {
        return tank.getResource(0);
    }

    public int getFillPercent() {
        return (int) (100 * tank.getAmountAsLong(0) / CAPACITY);
    }

    public static void tick(Level level, BlockPos pos, BlockState state, FluidPipeBlockEntity be) {
        if (level.isClientSide()) {
            return;
        }
        be.behaviour.fluidTick(level, pos, be);
        int rate = be.behaviour.fluidTransferPerTick();
        if (rate <= 0) {
            return;
        }
        int delay = Math.max(1, be.behaviour.fluidTransferDelay());
        if (level.getGameTime() % delay != 0) {
            return;
        }
        boolean changed = be.flowStep(level, pos, rate);
        if (changed) {
            be.setChanged();
            be.syncToClients(level);
        }
    }

    private boolean flowStep(Level level, BlockPos pos, int rate) {
        boolean changed = false;
        FluidResource resource = tank.getResource(0);
        if (!resource.isEmpty()) {
            if (behaviour.destroysFluids()) {
                long amount = tank.getAmountAsLong(0);
                if (amount > 0) {
                    try (Transaction tx = Transaction.openRoot()) {
                        tank.extract(0, resource, (int) Math.min(Integer.MAX_VALUE, amount), tx);
                        tx.commit();
                    }
                    lastFillSide = null;
                    changed = true;
                }
            } else {
                changed |= pushOut(level, pos, resource, rate);
            }
        }
        changed |= pullIn(level, pos, rate);
        return changed;
    }

    /** Pushes up to {@code rate} mB out through ONE allowed connected side (matching the item pipe system's own
     * single-destination-per-arrival simplicity, not real source's proportional simultaneous multi-way split). */
    private boolean pushOut(Level level, BlockPos pos, FluidResource resource, int rate) {
        Set<Direction> candidates = EnumSet.noneOf(Direction.class);
        for (Direction dir : Direction.values()) {
            if (dir != lastFillSide && FluidPipeBlock.isConnectable(level, pos, dir)) {
                candidates.add(dir);
            }
        }
        if (candidates.isEmpty()) {
            return false;
        }
        Set<Direction> allowed = behaviour.filterFluidDestinations(level, pos, lastFillSide, resource, candidates);
        if (allowed.isEmpty()) {
            return false;
        }
        Direction[] all = Direction.values();
        for (int i = 0; i < all.length; i++) {
            Direction dir = all[(pushCursor + i) % all.length];
            if (!allowed.contains(dir)) {
                continue;
            }
            long available = tank.getAmountAsLong(0);
            if (available <= 0) {
                return false;
            }
            int amount = (int) Math.min(rate, available);
            int pushed = pushTo(level, pos, dir, resource, amount);
            if (pushed > 0) {
                pushCursor = (pushCursor + i + 1) % all.length;
                return true;
            }
        }
        return false;
    }

    private int pushTo(Level level, BlockPos pos, Direction dir, FluidResource resource, int amount) {
        BlockPos neighborPos = pos.relative(dir);
        if (level.getBlockEntity(neighborPos) instanceof FluidPipeBlockEntity neighborPipe) {
            int accepted = neighborPipe.acceptFluid(resource, amount, dir.getOpposite());
            if (accepted > 0) {
                // accepted is always <= amount <= our own available (checked by the caller just before this),
                // so this extraction is guaranteed to succeed in full - no rollback bookkeeping needed.
                try (Transaction tx = Transaction.openRoot()) {
                    tank.extract(0, resource, accepted, tx);
                    tx.commit();
                }
            }
            return accepted;
        }
        ResourceHandler<FluidResource> handler = level.getCapability(Capabilities.Fluid.BLOCK, neighborPos, dir.getOpposite());
        if (handler == null) {
            return 0;
        }
        try (Transaction tx = Transaction.openRoot()) {
            int moved = ResourceHandlerUtil.move(tank, handler, r -> true, amount, tx);
            if (moved > 0) {
                tx.commit();
            }
            return moved;
        }
    }

    /** See the class javadoc's "deliberate deviation" note - every tier passively self-serves here, not just
     * Wood/a future Pump. Only pulls from real containers, never from a neighbouring pipe (those receive fluid
     * via {@link #pushTo}/{@link #acceptFluid} instead, so 2 adjacent empty-ish pipes don't pointlessly fight
     * over the same source). */
    private boolean pullIn(Level level, BlockPos pos, int rate) {
        long room = CAPACITY - tank.getAmountAsLong(0);
        if (room <= 0) {
            return false;
        }
        for (Direction dir : Direction.values()) {
            BlockPos neighborPos = pos.relative(dir);
            if (level.getBlockEntity(neighborPos) instanceof FluidPipeBlockEntity) {
                continue;
            }
            ResourceHandler<FluidResource> handler = level.getCapability(Capabilities.Fluid.BLOCK, neighborPos, dir.getOpposite());
            if (handler == null) {
                continue;
            }
            int amount = (int) Math.min(rate, room);
            try (Transaction tx = Transaction.openRoot()) {
                int moved = ResourceHandlerUtil.move(handler, tank, r -> true, amount, tx);
                if (moved > 0) {
                    tx.commit();
                    lastFillSide = dir;
                    return true;
                }
            }
        }
        return false;
    }

    /** Entry point for a neighbouring fluid pipe pushing fluid into this one - mirrors
     * {@code PipeBlockEntity.acceptItem}. Destroy-on-arrival tiers (Void) swallow anything offered without ever
     * actually storing it, matching real {@code PipeBehaviourVoid.moveFluidToCentre} zeroing the fluid array.
     * @return how much was actually accepted. */
    private int acceptFluid(FluidResource resource, int amount, Direction fromSide) {
        if (behaviour.destroysFluids()) {
            return amount;
        }
        int inserted;
        try (Transaction tx = Transaction.openRoot()) {
            inserted = tank.insert(0, resource, amount, tx);
            if (inserted > 0) {
                tx.commit();
            }
        }
        if (inserted > 0) {
            lastFillSide = fromSide;
            setChanged();
            if (level != null) {
                syncToClients(level);
            }
        }
        return inserted;
    }

    /** Exposes this pipe's own buffer as a real {@code Capabilities.Fluid.BLOCK} target, both directions - unlike
     * the item pipe's conveyor-style handler (items never really "sit" in a pipe, they travel), a fluid pipe
     * genuinely stores a resource in {@link #tank}, so external extraction (a future Pump, another mod) is a
     * sensible, real capability, not just insertion. */
    public ResourceHandler<FluidResource> getFluidHandler(@Nullable Direction side) {
        return new SidedFluidHandler(side == null ? Direction.UP : side);
    }

    private final class SidedFluidHandler implements ResourceHandler<FluidResource> {
        private final Direction side;

        SidedFluidHandler(Direction side) {
            this.side = side;
        }

        @Override
        public int size() {
            return 1;
        }

        @Override
        public FluidResource getResource(int index) {
            return tank.getResource(0);
        }

        @Override
        public long getAmountAsLong(int index) {
            return tank.getAmountAsLong(0);
        }

        @Override
        public long getCapacityAsLong(int index, FluidResource resource) {
            return tank.getCapacityAsLong(0, resource);
        }

        @Override
        public boolean isValid(int index, FluidResource resource) {
            return tank.isValid(0, resource);
        }

        @Override
        public int insert(int index, FluidResource resource, int amount, TransactionContext transaction) {
            if (behaviour.destroysFluids()) {
                return amount;
            }
            int inserted = tank.insert(0, resource, amount, transaction);
            if (inserted > 0) {
                lastFillSide = side;
            }
            return inserted;
        }

        @Override
        public int extract(int index, FluidResource resource, int amount, TransactionContext transaction) {
            return tank.extract(0, resource, amount, transaction);
        }
    }

    private void syncToClients(Level level) {
        BlockState state = getBlockState();
        level.sendBlockUpdated(worldPosition, state, state, 2);
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
        behaviour.save(output.child("behaviour"));
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        input.child("tank").ifPresent(tank::deserialize);
        input.child("behaviour").ifPresent(behaviour::load);
    }
}
