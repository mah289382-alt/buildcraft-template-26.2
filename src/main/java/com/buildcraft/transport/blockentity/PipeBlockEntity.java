package com.buildcraft.transport.blockentity;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.transfer.ResourceHandler;
import net.neoforged.neoforge.transfer.item.ItemResource;
import net.neoforged.neoforge.transfer.transaction.Transaction;
import net.neoforged.neoforge.transfer.transaction.SnapshotJournal;
import net.neoforged.neoforge.transfer.transaction.TransactionContext;
import org.jspecify.annotations.Nullable;

import com.buildcraft.transport.block.PipeBlock;
import com.buildcraft.transport.pipe.PipeBehaviour;

/**
 * A deliberately simplified stand-in for {@code PipeFlowItems}/{@code TravellingItem}: items enter a pipe from a
 * side, travel to the center, then (via {@link PipeBehaviour#filterDestinations}) pick a connected side to travel
 * out of and either continue into the next pipe or insert into an inventory. Faithful to the original's two-phase
 * (toCenter / !toCenter) shape, its absolute-tick timing model (so the client can compute a smooth continuous
 * render position with no frame-to-frame buffering, exactly like {@code TravellingItem.getRenderPosition}), and
 * to per-tier behaviour via {@link PipeBehaviour} - but WITHOUT the original's generic pipe-behaviour event bus
 * (gates/statements aren't in scope yet), colour-tagging, item merging, or fluid/power flow.
 */
public class PipeBlockEntity extends BlockEntity {
    private static final class TravellingItem {
        static final Codec<TravellingItem> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                ItemStack.CODEC.fieldOf("stack").forGetter(i -> i.stack),
                Direction.CODEC.fieldOf("side").forGetter(i -> i.side),
                Codec.BOOL.fieldOf("toCenter").forGetter(i -> i.toCenter),
                Codec.LONG.fieldOf("tickStarted").forGetter(i -> i.tickStarted),
                Codec.LONG.fieldOf("tickFinished").forGetter(i -> i.tickFinished),
                DyeColor.CODEC.optionalFieldOf("colour").forGetter(i -> Optional.ofNullable(i.colour))
        ).apply(instance, TravellingItem::new));

        ItemStack stack;
        Direction side;
        boolean toCenter;
        long tickStarted;
        long tickFinished;
        @Nullable DyeColor colour;

        TravellingItem(ItemStack stack, Direction side, boolean toCenter, long tickStarted, long tickFinished, Optional<DyeColor> colour) {
            this.stack = stack;
            this.side = side;
            this.toCenter = toCenter;
            this.tickStarted = tickStarted;
            this.tickFinished = tickFinished;
            this.colour = colour.orElse(null);
        }

        TravellingItem(ItemStack stack, Direction side, boolean toCenter, long tickStarted, long tickFinished, @Nullable DyeColor colour) {
            this(stack, side, toCenter, tickStarted, tickFinished, Optional.ofNullable(colour));
        }
    }

    /** A single item ready to be drawn: its stack, its interpolated position (local to the pipe's origin), and
     * which way it's currently facing (for orienting the rendered itemstack). */
    public record RenderItem(ItemStack stack, Vec3 position, @Nullable Direction facing) {
    }

    private final PipeBehaviour behaviour;
    private final List<TravellingItem> items = new ArrayList<>();
    private int roundRobinCursor = 0;

    public PipeBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state, Supplier<PipeBehaviour> behaviourFactory) {
        super(type, pos, state);
        this.behaviour = behaviourFactory.get();
    }

    public PipeBehaviour getBehaviour() {
        return behaviour;
    }

    public static void tick(Level level, BlockPos pos, BlockState state, PipeBlockEntity be) {
        if (level.isClientSide()) {
            return;
        }
        be.behaviour.tick(level, pos, be);
        // currentDir can now change autonomously (see WoodBehaviour.tick's auto-advance), not just via a wrench
        // click, so the valve blockstate has to be kept in sync here too, not only from onWrenchClick.
        PipeBlock.syncValveState(level, pos, state, be.behaviour);
        if (be.items.isEmpty()) {
            return;
        }
        long now = level.getGameTime();
        boolean changed = false;
        Iterator<TravellingItem> it = be.items.iterator();
        while (it.hasNext()) {
            TravellingItem item = it.next();
            if (now < item.tickFinished) {
                continue;
            }
            changed = true;
            if (item.toCenter) {
                be.onReachCenter(level, now, item);
            } else if (be.onReachEnd(level, item)) {
                it.remove();
            }
        }
        if (changed) {
            be.setChanged();
            be.syncToClients(level);
        }
    }

    /** Entry point for external capability-based injection (no colour tag - the item is entering fresh). */
    public void acceptItem(ItemStack stack, Direction fromSide) {
        acceptItem(stack, fromSide, null);
    }

    /** Entry point for a neighbor pipe handing an item off, preserving whatever colour tag it was carrying. */
    public void acceptItem(ItemStack stack, Direction fromSide, @Nullable DyeColor colour) {
        long now = level != null ? level.getGameTime() : 0;
        items.add(new TravellingItem(stack, fromSide, true, now, now + behaviour.ticksPerPhase(), colour));
        setChanged();
        if (level != null) {
            syncToClients(level);
        }
    }

    /** Delegates a wrench right-click to this pipe's behaviour (e.g. Iron's forced-direction cycling). */
    public boolean onWrenchClick(@Nullable Direction clickedFace) {
        boolean handled = behaviour.onWrenchClick(getLevel(), worldPosition, clickedFace);
        if (handled) {
            setChanged();
            if (level != null) {
                syncToClients(level);
                PipeBlock.syncValveState(level, worldPosition, getBlockState(), behaviour);
            }
        }
        return handled;
    }

    /**
     * The original's {@code PipeFlowItems.getPipeLength}: how far (in blocks, from the pipe's own center) an
     * item travels toward a given side - a full connector-arm length to another pipe (0.5), a little further
     * into an inventory tile so it visually "pushes in" (0.5+0.25), or just short of the block edge if the side
     * isn't actually connected (0.25, a fallback that shouldn't normally be hit for a real destination).
     */
    private double getPipeLength(Level level, @Nullable Direction side) {
        if (side == null) {
            return 0;
        }
        if (!PipeBlock.isConnectable(level, worldPosition, side)) {
            return 0.25;
        }
        return level.getBlockEntity(worldPosition.relative(side)) instanceof PipeBlockEntity ? 0.5 : 0.5 + 0.25;
    }

    /**
     * Ports {@code TravellingItem.getRenderPosition}: linearly interpolates between the pipe's center and the
     * relevant face (based on {@link #getPipeLength}) using a smooth 0..1 fraction of elapsed real time between
     * this item's absolute start/finish tick - no frame-to-frame buffering needed, since the position is a pure
     * function of (now, partialTicks) and two fixed timestamps, exactly like the source.
     */
    public List<RenderItem> getRenderItems(float partialTicks) {
        if (items.isEmpty() || level == null) {
            return List.of();
        }
        long now = level.getGameTime();
        List<RenderItem> result = new ArrayList<>(items.size());
        for (TravellingItem item : items) {
            if (item.stack.isEmpty()) {
                continue;
            }
            long duration = Math.max(1, item.tickFinished - item.tickStarted);
            float interp = Mth.clamp((float) ((now - item.tickStarted) + partialTicks) / duration, 0.0F, 1.0F);

            Vec3 center = new Vec3(0.5, 0.5, 0.5);
            Vec3 sidePos = item.side == null ? center
                    : center.add(item.side.getUnitVec3().scale(getPipeLength(level, item.side)));

            Vec3 from = item.toCenter ? sidePos : center;
            Vec3 to = item.toCenter ? center : sidePos;
            Vec3 pos = from.lerp(to, interp);

            Direction facing = item.toCenter ? (item.side == null ? null : item.side.getOpposite()) : item.side;
            result.add(new RenderItem(item.stack, pos, facing));
        }
        return result;
    }

    /**
     * Exposes this pipe as a {@code Capabilities.Item.BLOCK} target so anything else in the modern item-transfer
     * ecosystem (hoppers, other mods' machines, and - critically - the Quarry's own drop-routing, which already
     * generically checks every neighbor for this exact capability) can push items into it, not just other pipes
     * via {@link #acceptItem}. This is what actually makes item transport work; {@code PipeConnectable} (see its
     * javadoc) only governs the visual connector arm.
     */
    public ResourceHandler<ItemResource> getItemHandler(@Nullable Direction side) {
        return new PipeItemHandler(side == null ? Direction.UP : side);
    }

    /**
     * A single "virtual slot" transfer endpoint feeding straight into {@link #items} - a pipe doesn't really
     * store items, it's a conveyor, so this always reports empty/no persistent contents and never supports
     * extraction. Extends {@link SnapshotJournal} (the API's own recommended base for a transaction-aware
     * handler) so an insert that's part of a transaction which later rolls back doesn't leave a phantom item.
     */
    private final class PipeItemHandler extends SnapshotJournal<List<TravellingItem>> implements ResourceHandler<ItemResource> {
        private final Direction side;

        PipeItemHandler(Direction side) {
            this.side = side;
        }

        @Override
        public int size() {
            return 1;
        }

        @Override
        public ItemResource getResource(int index) {
            return ItemResource.EMPTY;
        }

        @Override
        public long getAmountAsLong(int index) {
            return 0;
        }

        @Override
        public long getCapacityAsLong(int index, ItemResource resource) {
            return resource.isEmpty() ? 0 : 64;
        }

        @Override
        public boolean isValid(int index, ItemResource resource) {
            return !resource.isEmpty();
        }

        @Override
        public int insert(int index, ItemResource resource, int amount, TransactionContext transaction) {
            if (resource.isEmpty() || amount <= 0) {
                return 0;
            }
            updateSnapshots(transaction);
            long now = level != null ? level.getGameTime() : 0;
            items.add(new TravellingItem(resource.toStack(amount), side, true, now, now + behaviour.ticksPerPhase(), (DyeColor) null));
            return amount;
        }

        @Override
        public int extract(int index, ItemResource resource, int amount, TransactionContext transaction) {
            return 0;
        }

        @Override
        protected List<TravellingItem> createSnapshot() {
            return new ArrayList<>(items);
        }

        @Override
        protected void revertToSnapshot(List<TravellingItem> snapshot) {
            items.clear();
            items.addAll(snapshot);
        }

        @Override
        protected void onRootCommit(List<TravellingItem> originalState) {
            setChanged();
            if (level != null) {
                syncToClients(level);
            }
        }
    }

    /** Picks a connected side (other than where it came from), filtered by this pipe's behaviour. */
    private void onReachCenter(Level level, long now, TravellingItem item) {
        if (behaviour.destroysItems()) {
            item.stack = ItemStack.EMPTY;
            item.tickStarted = now;
            item.tickFinished = now + 1;
            item.toCenter = false;
            return;
        }

        item.colour = behaviour.stampColour(item.colour);

        Set<Direction> candidates = EnumSet.noneOf(Direction.class);
        for (Direction dir : Direction.values()) {
            if (dir != item.side && PipeBlock.isConnectable(level, worldPosition, dir)) {
                candidates.add(dir);
            }
        }
        Set<Direction> allowed = behaviour.filterDestinations(level, worldPosition, item.side, item.stack, item.colour, candidates);

        Direction chosen = null;
        Direction[] all = Direction.values();
        for (int i = 0; i < all.length; i++) {
            Direction candidate = all[(roundRobinCursor + i) % all.length];
            if (allowed.contains(candidate)) {
                chosen = candidate;
                roundRobinCursor = (roundRobinCursor + i + 1) % all.length;
                break;
            }
        }
        if (chosen == null) {
            // No allowed destination - matches the original's fallback (TryBounce failing) of dropping the item
            // as a physical entity rather than sending it back the way it came.
            dropItem(level, item.stack);
            item.stack = ItemStack.EMPTY;
            item.tickStarted = now;
            item.tickFinished = now + 1;
            item.toCenter = false;
            return;
        }
        item.side = chosen;
        item.toCenter = false;
        item.tickStarted = now;
        item.tickFinished = now + behaviour.ticksPerPhase();
    }

    /** @return true if the item should be removed from this pipe's list (it left, one way or another). */
    private boolean onReachEnd(Level level, TravellingItem item) {
        if (item.stack.isEmpty()) {
            return true;
        }
        BlockPos neighborPos = worldPosition.relative(item.side);
        if (level.getBlockEntity(neighborPos) instanceof PipeBlockEntity neighborPipe) {
            neighborPipe.acceptItem(item.stack, item.side.getOpposite(), item.colour);
            return true;
        }
        ResourceHandler<ItemResource> handler =
                level.getCapability(Capabilities.Item.BLOCK, neighborPos, item.side.getOpposite());
        if (handler != null) {
            ItemResource resource = ItemResource.of(item.stack);
            int inserted;
            try (Transaction tx = Transaction.openRoot()) {
                inserted = handler.insert(resource, item.stack.getCount(), tx);
                if (inserted > 0) {
                    tx.commit();
                }
            }
            if (inserted >= item.stack.getCount()) {
                return true;
            }
            if (inserted > 0) {
                item.stack.shrink(inserted);
            }
        }
        // Couldn't hand it off (no connection, or the inventory didn't take it all) - drop the remainder rather
        // than silently discarding it. A future pass should bounce this back toward the center to try another
        // side instead, matching the original more closely.
        dropItem(level, item.stack);
        return true;
    }

    private void dropItem(Level level, ItemStack stack) {
        if (stack.isEmpty()) {
            return;
        }
        ItemEntity entity = new ItemEntity(level, worldPosition.getX() + 0.5, worldPosition.getY() + 0.5, worldPosition.getZ() + 0.5, stack.copy());
        level.addFreshEntity(entity);
    }

    /** Pushes the current state (travelling items, for rendering) to nearby clients. */
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
        ValueOutput.TypedOutputList<TravellingItem> list = output.list("items", TravellingItem.CODEC);
        for (TravellingItem item : items) {
            list.add(item);
        }
        behaviour.save(output.child("behaviour"));
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        items.clear();
        input.list("items", TravellingItem.CODEC).ifPresent(list -> list.forEach(items::add));
        input.child("behaviour").ifPresent(behaviour::load);
    }
}
