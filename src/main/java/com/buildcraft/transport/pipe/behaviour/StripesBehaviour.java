package com.buildcraft.transport.pipe.behaviour;

import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.neoforged.neoforge.transfer.energy.EnergyHandler;
import net.neoforged.neoforge.transfer.energy.SimpleEnergyHandler;
import net.neoforged.neoforge.transfer.transaction.Transaction;
import org.jspecify.annotations.Nullable;

import com.buildcraft.Config;
import com.buildcraft.transport.block.PipeBlock;
import com.buildcraft.transport.blockentity.PipeBlockEntity;
import com.buildcraft.transport.pipe.PipeBehaviour;

/**
 * Ports {@code PipeBehaviourStripes}: an FE-powered (generic substitute for the original's MJ battery, same
 * deviation as {@link WoodBehaviour}/{@link ObsidianBehaviour}) pipe that breaks the block directly in front of
 * a wrench-set (or, like {@link ObsidianBehaviour}, auto-detected single-open-face) direction, accumulating
 * power across ticks via the same {@code cost = C * (hardness + 1)} formula the Quarry uses
 * ({@link Config#PIPE_STRIPES_ENERGY_PER_HARDNESS_POINT}), then routes the drops back into itself.
 * {@code canConnect(face, PipeBehaviourStripes)} always returning false in the original (two Stripes pipes can
 * never connect) is ported as {@link #canConnectToPipe}.
 * <p>
 * Not ported: the original's SECOND capability - handing a dropped item to a {@code FakePlayer} and looking it
 * up in an {@code IStripesActivator} registry (per-item-type world actions: tilling with a hoe, planting a
 * seed, filling/emptying a bucket, etc.) so items exiting the pipe can interact with the world instead of just
 * dropping. That registry is itself a whole separate extensible subsystem with no equivalent infrastructure in
 * this port yet - an explicit, documented scope reduction; this pipe only does the block-breaking half.
 */
public final class StripesBehaviour implements PipeBehaviour {
    private @Nullable Direction wrenchDirection;
    private long progress;
    private final SimpleEnergyHandler energy = new SimpleEnergyHandler(
            Config.PIPE_POWERED_ENERGY_CAPACITY.get(), Config.PIPE_POWERED_MAX_FE_PER_TICK.get());

    @Override
    public boolean canConnectToPipe(PipeBehaviour other) {
        return !(other instanceof StripesBehaviour);
    }

    @Override
    public boolean onWrenchClick(Level level, BlockPos pipePos, @Nullable Direction clickedFace) {
        if (clickedFace != null && !PipeBlock.isConnectable(level, pipePos, clickedFace)) {
            wrenchDirection = clickedFace;
            progress = 0;
            return true;
        }
        return false;
    }

    private @Nullable Direction resolveDirection(Level level, BlockPos pipePos) {
        if (wrenchDirection != null && !PipeBlock.isConnectable(level, pipePos, wrenchDirection)) {
            return wrenchDirection;
        }
        Direction connected = null;
        int count = 0;
        for (Direction dir : Direction.values()) {
            if (PipeBlock.isConnectable(level, pipePos, dir)) {
                connected = dir;
                count++;
            }
        }
        return count == 1 ? connected.getOpposite() : null;
    }

    @Override
    public void tick(Level level, BlockPos pipePos, PipeBlockEntity pipe) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }
        Direction direction = resolveDirection(level, pipePos);
        if (direction == null) {
            progress = 0;
            return;
        }
        BlockPos target = pipePos.relative(direction);
        BlockState state = level.getBlockState(target);
        float hardness = state.getDestroySpeed(level, target);
        if (hardness < 0) {
            progress = 0;
            return;
        }
        long cost = (long) (Config.PIPE_STRIPES_ENERGY_PER_HARDNESS_POINT.get() * (hardness + 1));
        if (cost <= 0) {
            cost = 1;
        }
        if (progress < cost) {
            int available = (int) Math.min(cost - progress, Integer.MAX_VALUE);
            try (Transaction tx = Transaction.openRoot()) {
                int drawn = energy.extract(available, tx);
                if (drawn > 0) {
                    tx.commit();
                    progress += drawn;
                }
            }
            return;
        }
        List<ItemStack> drops = Block.getDrops(state, serverLevel, target, level.getBlockEntity(target));
        level.destroyBlock(target, false, null, 512);
        progress = 0;
        for (ItemStack drop : drops) {
            pipe.acceptItem(drop, direction);
        }
    }

    @Override
    public @Nullable EnergyHandler getEnergyHandler() {
        return energy;
    }

    @Override
    public void save(ValueOutput output) {
        if (wrenchDirection != null) {
            output.store("wrenchDirection", Direction.CODEC, wrenchDirection);
        }
        output.putLong("progress", progress);
        energy.serialize(output.child("energy"));
    }

    @Override
    public void load(ValueInput input) {
        wrenchDirection = input.read("wrenchDirection", Direction.CODEC).orElse(null);
        progress = input.getLongOr("progress", 0);
        input.child("energy").ifPresent(energy::deserialize);
    }
}
