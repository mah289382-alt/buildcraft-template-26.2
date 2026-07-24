package com.buildcraft.transport.pipe.behaviour;

import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.transfer.energy.EnergyHandler;
import net.neoforged.neoforge.transfer.energy.SimpleEnergyHandler;
import net.neoforged.neoforge.transfer.transaction.Transaction;
import org.jspecify.annotations.Nullable;

import com.buildcraft.Config;
import com.buildcraft.transport.block.PipeBlock;
import com.buildcraft.transport.blockentity.PipeBlockEntity;
import com.buildcraft.transport.pipe.PipeBehaviour;

/**
 * Ports {@code PipeBehaviourObsidian}: only works with exactly one connected side (the "open face" is the
 * OPPOSITE side - the one dead-end face reaches out into open space to vacuum up nearby dropped item entities).
 * Each tick, scans a box growing outward from that open face (1 to 4 blocks, matching {@code getSuckingBox}'s
 * per-distance AABB) and pulls in the first {@code ItemEntity} found, at an FE cost that scales with distance -
 * a generic substitute for the original's MJ (see {@link com.buildcraft.transport.pipe.behaviour.WoodBehaviour}
 * for the same documented deviation). Simplified vs. source: pulls a whole item entity's stack at once per
 * tick rather than partially draining it across multiple ticks at a fractional power budget, and doesn't track
 * a drop-cooldown per entity (this port's {@code dropItem} only fires on a failed hand-off, which an Obsidian
 * pipe with a single open connection essentially never hits, so the original's re-suck-loop guard isn't
 * load-bearing here). {@code canConnect(face, PipeBehaviourObsidian)} always returning false in the original
 * (two Obsidian pipes can never connect to each other) is ported as {@link #canConnectToPipe}.
 */
public final class ObsidianBehaviour implements PipeBehaviour {
    private static final int MAX_DISTANCE = 4;

    private final SimpleEnergyHandler energy = new SimpleEnergyHandler(
            Config.PIPE_POWERED_ENERGY_CAPACITY.get(), Config.PIPE_POWERED_MAX_FE_PER_TICK.get());

    @Override
    public boolean canConnectToPipe(PipeBehaviour other) {
        return !(other instanceof ObsidianBehaviour);
    }

    @Override
    public void tick(Level level, BlockPos pipePos, PipeBlockEntity pipe) {
        Direction openFace = getOpenFace(level, pipePos);
        if (openFace == null) {
            return;
        }
        for (int distance = 1; distance <= MAX_DISTANCE; distance++) {
            AABB box = getSuckingBox(pipePos, openFace, distance);
            List<ItemEntity> entities = level.getEntitiesOfClass(ItemEntity.class, box);
            for (ItemEntity entity : entities) {
                if (!entity.isAlive()) {
                    continue;
                }
                double realDistance = Math.sqrt(entity.distanceToSqr(
                        pipePos.getX() + 0.5, pipePos.getY() + 0.5, pipePos.getZ() + 0.5));
                int cost = Config.PIPE_OBSIDIAN_FE_PER_ITEM.get()
                        + (int) (Math.max(1, realDistance) * Config.PIPE_OBSIDIAN_FE_PER_METRE.get());
                if (energy.getAmountAsLong() < cost) {
                    continue;
                }
                ItemStack stack = entity.getItem();
                if (stack.isEmpty()) {
                    continue;
                }
                try (Transaction tx = Transaction.openRoot()) {
                    if (energy.extract(cost, tx) > 0) {
                        tx.commit();
                    } else {
                        continue;
                    }
                }
                pipe.acceptItem(stack.copy(), openFace);
                entity.discard();
                return;
            }
        }
    }

    /** Only sucks when exactly one side is connected - the open (unconnected) face is the opposite side. */
    private static @Nullable Direction getOpenFace(Level level, BlockPos pipePos) {
        Direction connected = null;
        for (Direction dir : Direction.values()) {
            if (PipeBlock.isConnectable(level, pipePos, dir)) {
                if (connected != null) {
                    return null;
                }
                connected = dir;
            }
        }
        return connected == null ? null : connected.getOpposite();
    }

    private static AABB getSuckingBox(BlockPos pipePos, Direction openFace, int distance) {
        double cx = pipePos.getX() + 0.5;
        double cy = pipePos.getY() + 0.5;
        double cz = pipePos.getZ() + 0.5;
        AABB base = new AABB(cx - 0.4, cy - 0.4, cz - 0.4, cx + 0.4, cy + 0.4, cz + 0.4);
        return switch (openFace) {
            case WEST -> base.move(-distance, 0, 0).inflate(0.5, distance, distance);
            case EAST -> base.move(distance, 0, 0).inflate(0.5, distance, distance);
            case DOWN -> base.move(0, -distance, 0).inflate(distance, 0.5, distance);
            case UP -> base.move(0, distance, 0).inflate(distance, 0.5, distance);
            case NORTH -> base.move(0, 0, -distance).inflate(distance, distance, 0.5);
            case SOUTH -> base.move(0, 0, distance).inflate(distance, distance, 0.5);
        };
    }

    @Override
    public @Nullable EnergyHandler getEnergyHandler() {
        return energy;
    }

    @Override
    public void save(ValueOutput output) {
        energy.serialize(output.child("energy"));
    }

    @Override
    public void load(ValueInput input) {
        input.child("energy").ifPresent(energy::deserialize);
    }
}
