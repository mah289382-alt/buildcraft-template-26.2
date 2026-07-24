package com.buildcraft.builders.blockentity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.jspecify.annotations.Nullable;

import com.buildcraft.builders.BuildersContent;

/**
 * Two Markers automatically link to their nearest unlinked partner (within {@link #MAX_LINK_RANGE}) once placed.
 * A Quarry placed so that a linked pair sits directly in its working direction will use the pair's bounding box
 * as its mining area instead of the fixed default size.
 */
public class MarkerBlockEntity extends BlockEntity {
    private static final int MAX_LINK_RANGE = 64;
    // Tracks markers that are waiting for a partner, per-dimension. Cleared naturally as markers link or are removed.
    private static final Map<ResourceKey<Level>, List<BlockPos>> UNLINKED = new HashMap<>();

    private boolean linkAttempted = false;
    private @Nullable BlockPos linkedPos;

    public MarkerBlockEntity(BlockPos pos, BlockState state) {
        super(BuildersContent.MARKER_BLOCK_ENTITY.get(), pos, state);
    }

    public static void tick(Level level, BlockPos pos, BlockState state, MarkerBlockEntity be) {
        // linkAttempted is intentionally not persisted (see saveAdditional), so a marker that was still
        // waiting for a partner when the world was last saved gets exactly one fresh retry after reload,
        // rebuilding the in-memory waiting pool. Already-linked markers (linkedPos != null) never retry,
        // so a reload can't clobber an existing valid link.
        if (!level.isClientSide() && !be.linkAttempted && be.linkedPos == null) {
            be.linkAttempted = true;
            be.tryLink(level);
        }
    }

    public boolean isLinked() {
        return linkedPos != null;
    }

    public @Nullable BlockPos getLinkedPos() {
        return linkedPos;
    }

    private void tryLink(Level level) {
        List<BlockPos> unlinked = UNLINKED.computeIfAbsent(level.dimension(), k -> new ArrayList<>());

        BlockPos best = null;
        double bestDistSq = Double.MAX_VALUE;
        Iterator<BlockPos> it = unlinked.iterator();
        while (it.hasNext()) {
            BlockPos candidate = it.next();
            if (candidate.equals(worldPosition)) {
                continue;
            }
            if (!(level.getBlockEntity(candidate) instanceof MarkerBlockEntity other) || other.isLinked()) {
                it.remove();
                continue;
            }
            double distSq = candidate.distSqr(worldPosition);
            if (distSq <= (double) MAX_LINK_RANGE * MAX_LINK_RANGE && distSq < bestDistSq) {
                bestDistSq = distSq;
                best = candidate;
            }
        }

        if (best != null) {
            MarkerBlockEntity other = (MarkerBlockEntity) level.getBlockEntity(best);
            this.linkedPos = best;
            other.linkedPos = worldPosition.immutable();
            other.linkAttempted = true;
            unlinked.remove(best);
            setChanged();
            other.setChanged();
        } else {
            unlinked.add(worldPosition.immutable());
        }
    }

    @Override
    public void setRemoved() {
        super.setRemoved();
        if (level != null && !level.isClientSide()) {
            List<BlockPos> unlinked = UNLINKED.get(level.dimension());
            if (unlinked != null) {
                unlinked.remove(worldPosition);
            }
            if (linkedPos != null && level.getBlockEntity(linkedPos) instanceof MarkerBlockEntity other) {
                other.linkedPos = null;
                other.linkAttempted = false;
                other.setChanged();
            }
        }
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        // linkAttempted is deliberately NOT saved: it must reset to false on load so an unlinked marker
        // gets a fresh chance to find a partner (the waiting-pool it would rely on is in-memory only).
        if (linkedPos != null) {
            output.store("linkedPos", BlockPos.CODEC, linkedPos);
        }
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        linkedPos = input.read("linkedPos", BlockPos.CODEC).orElse(null);
    }
}
