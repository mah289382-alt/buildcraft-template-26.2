package com.buildcraft.factory.blockentity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.neoforged.neoforge.transfer.energy.EnergyHandler;
import net.neoforged.neoforge.transfer.energy.SimpleEnergyHandler;
import net.neoforged.neoforge.transfer.transaction.Transaction;
import org.jspecify.annotations.Nullable;

import com.buildcraft.BuildCraft;
import com.buildcraft.Config;

/**
 * Ports real {@code TileMiner} - the shared base of {@code TileMiningWell} and {@code TilePump} (confirmed both
 * extend it directly in source, sharing {@code progress}/the MJ battery/"still working?" state). Power: any
 * Engine pushes FE generically to whatever {@code Capabilities.Energy.BLOCK} it's facing
 * ({@code EngineBlockEntity.pushPower}, confirmed by reading it directly) - both subclasses' registered energy
 * capability (see {@code BuildCraft.registerCapabilities}) already makes them powerable with no extra wiring
 * needed here.
 * <p>
 * Real source's 2 small LED indicators (power level + active/complete status, drawn by a TESR) are NOT ported -
 * purely cosmetic.
 * <p>
 * DEVIATION FROM SOURCE (explicit, repeated user requests - see the growing chain of javadoc below and on
 * {@code TubeBlock}/{@code MinerBlockEntityRenderer}, verified against {@code TileMiner.updateLength()} /
 * {@code RenderTube.java} directly - real BuildCraft places the ENTIRE shaft instantly the same tick a target is
 * found, and starts working that same tick too - no paced growth, no probing, at all):
 * <ul>
 * <li>Growth is a real, continuous FRACTIONAL value ({@link #growthProgress}), not instant - the same technique
 * {@code QuarryBlockEntity} uses for its own smoothly-gliding drill ({@code drillPos}, moved a little every tick
 * and synced every tick via {@code sendBlockUpdated} - see {@link #tickShaftGrowth}).
 * <li>The physical {@link #getTubeBlock()} segments placed alongside that growth are pure invisible markers now
 * (see that class's own javadoc) - the ENTIRE visible shaft is {@code MinerBlockEntityRenderer}'s own single
 * continuous cosmetic beam, with no per-block "pop" to seam against.
 * <li>Growth is no longer target-driven by a pre-detected position at all: {@link #targetLength} auto-advances
 * to {@code wantedLength + 1} every tick the shaft ISN'T {@link #paused}, i.e. the shaft just keeps probing
 * deeper for as long as it's powered, with no upfront search - a subclass's {@code mine} sets {@link #paused}
 * true the moment the newly-reached tip position ({@link #getTipPos}) has real work to do (something to
 * break/drain), halting growth right there until that work finishes, then clears it to resume probing deeper.
 * {@link #stopped} is the one permanent halt (hit real {@link Config#QUARRY_MAX_MINE_DEPTH}).
 * </ul>
 */
public abstract class MinerBlockEntity extends BlockEntity {
    protected long progress = 0;

    protected final SimpleEnergyHandler energy;

    /** The next depth (in blocks below this machine) the shaft should grow to - auto-advances to
     * {@code wantedLength + 1} every tick the shaft isn't {@link #paused} (see {@link #tickShaftGrowth}), NOT
     * driven by any pre-detected target position (see class javadoc). */
    private int targetLength = 0;
    /** Server-authoritative CURRENT physical shaft depth - the real {@link #getTubeBlock()} pole reaches exactly
     * this far (whole blocks only; see {@link #growthProgress} for the partial block currently growing). */
    private int wantedLength = 0;
    /** How far (0..1) into growing the NEXT block below {@link #wantedLength} the shaft currently is - a real,
     * continuously-advancing fraction (see {@link #tickShaftGrowth}), not a discrete per-tick counter, so a
     * renderer can interpolate it smoothly frame-to-frame like {@code QuarryBlockEntity.drillPos}. */
    private double growthProgress = 0;
    /** Set by a subclass the moment {@link #getTipPos} has real work to do - halts further growth until the
     * subclass clears it again (work finished, or nothing to do here after all). */
    protected boolean paused = false;
    /** Permanent halt - real {@link Config#QUARRY_MAX_MINE_DEPTH} reached. Real source has no equivalent "resume"
     * path either (an obstruction/depth-limited Mining Well/Pump just sits there), so neither does this. */
    protected boolean stopped = false;

    // Client-render-only interpolation buffer (not persisted) - the same 1-tick-buffer + lerp technique
    // QuarryBlockEntity uses for clientDrillPos/prevClientDrillPos.
    private double clientGrowth;
    private double prevClientGrowth;

    protected MinerBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state, int energyCapacity, int maxFePerTick) {
        super(type, pos, state);
        this.energy = new SimpleEnergyHandler(energyCapacity, maxFePerTick, maxFePerTick) {
            @Override
            protected void onEnergyChanged(int previousAmount) {
                setChanged();
            }
        };
    }

    public EnergyHandler getEnergyHandler() {
        return energy;
    }

    /** Real {@code TileMiner.isComplete}: true once there's nothing left to do - here, once
     * {@link #stopped} (real max depth reached; see class javadoc for why this replaced the old
     * {@code currentPos == null} check now that there's no single pre-detected target position anymore). */
    public boolean isComplete() {
        return stopped;
    }

    /** True once the physical shaft has grown down to AT LEAST {@link #targetLength} - a subclass's {@code mine}
     * should not spend energy/break/drain at {@link #getTipPos} until this is true, so the hose visibly "arrives"
     * before anything happens. {@code >=} not {@code ==} defensively, though with growth now always advancing by
     * exactly 1 at a time this is normally an exact match. */
    protected boolean isFullyExtended() {
        return wantedLength >= targetLength;
    }

    /** The position the shaft's growth has most recently reached - {@code wantedLength} blocks straight down
     * from the machine. Only meaningful once {@link #isFullyExtended()} (otherwise the shaft hasn't actually
     * grown that far yet); a subclass checks this position for real work the moment growth reaches it. */
    protected BlockPos getTipPos(BlockPos ownPos) {
        return ownPos.below(wantedLength);
    }

    /** Real {@code TileMiner.mine()} - runs every server tick regardless of current state (idle machines still
     * need to periodically re-check for new work, e.g. a block placed/broken nearby, or new fluid flowing in).
     * Called AFTER {@link #tickShaftGrowth} each tick, so by the time this runs, {@link #isFullyExtended()}
     * already reflects this tick's growth. */
    protected abstract void mine(Level level, BlockPos pos);

    /** This machine's own real tube variant (real source shares ONE {@code tube} block between both machines,
     * distinguishing them only via a machine-specific rendering overlay this port doesn't replicate - see class
     * javadoc - so each machine here gets its own real, separately-textured block instead). Now a pure invisible
     * marker (see {@code TubeBlock}'s own javadoc) - placed purely so a subclass's own bookkeeping has something
     * concrete to check against if needed; the visible shaft is 100% {@code MinerBlockEntityRenderer}'s own
     * cosmetic beam. */
    protected abstract Block getTubeBlock();

    public static void tick(Level level, BlockPos pos, BlockState state, MinerBlockEntity be) {
        if (level.isClientSide()) {
            be.prevClientGrowth = be.clientGrowth;
            be.clientGrowth = be.wantedLength + be.growthProgress;
            return;
        }
        be.tickShaftGrowth(level, pos);
        be.mine(level, pos);
    }

    /** The interpolated total shaft length (whole blocks placed + the current partial block's fraction) a
     * renderer should draw this frame - mirrors {@code QuarryBlockEntity.getInterpolatedDrillPos} exactly. */
    public double getInterpolatedLength(float partialTicks) {
        return prevClientGrowth + (clientGrowth - prevClientGrowth) * partialTicks;
    }

    /** Advances {@link #growthProgress} by {@code 1 / Config.MINER_SHAFT_TICKS_PER_BLOCK} every server tick the
     * shaft isn't {@link #paused}/{@link #stopped} (real, continuous motion, not a discrete per-block pop),
     * placing the next real (invisible) {@link #getTubeBlock()} marker the moment that crosses 1.0 (carrying the
     * remainder over, so motion never stutters at the boundary). {@link #targetLength} auto-advances to
     * {@code wantedLength + 1} right here whenever growth has caught up and nothing has paused it - see class
     * javadoc: growth is no longer driven by any pre-detected target, it just keeps probing deeper on its own.
     * Syncs every tick it's actively growing (real {@code QuarryBlockEntity.syncToClients} pattern -
     * {@code sendBlockUpdated} every tick a change happened) so the client's own smoothing always has fresh data
     * to glide toward. */
    private void tickShaftGrowth(Level level, BlockPos ownPos) {
        if (stopped) {
            return;
        }
        if (!paused && wantedLength >= targetLength) {
            if (wantedLength >= Config.QUARRY_MAX_MINE_DEPTH.get()) {
                BuildCraft.LOGGER.info("Miner at {}: STOPPED (max depth {} reached)", ownPos, wantedLength);
                stopped = true;
                setChanged();
                return;
            }
            targetLength = wantedLength + 1;
        }
        if (wantedLength >= targetLength) {
            return;
        }
        // DEVIATION (explicit user request: "extend from power", not "just extend"): the shaft must actually
        // draw real FE to move at all - without this, growth advanced unconditionally on a pure tick timer
        // regardless of whether the machine had any power, which looked like an always-powered cheat state.
        int needed = Config.MINER_SHAFT_ENERGY_PER_TICK.get();
        if (needed > 0) {
            try (Transaction tx = Transaction.openRoot()) {
                int extracted = energy.extract(needed, tx);
                if (extracted < needed) {
                    return; // not enough power this tick - the shaft simply doesn't move
                }
                tx.commit();
            }
        }
        growthProgress += 1.0 / Config.MINER_SHAFT_TICKS_PER_BLOCK.get();
        if (growthProgress >= 1.0) {
            growthProgress -= 1.0;
            wantedLength++;
            BlockPos p = ownPos.below(wantedLength);
            level.setBlock(p, getTubeBlock().defaultBlockState(), 3);
        }
        setChanged();
        BlockState state = getBlockState();
        level.sendBlockUpdated(ownPos, state, state, 2);
    }

    /** Ports real {@code TileMiner.onRemove()}: clears any leftover tube segments below when the machine itself
     * is broken/replaced, so removing a Mining Well/Pump doesn't leave leftover invisible marker blocks behind.
     * {@code preRemoveSideEffects} (confirmed via decompiled {@code BlockEntity.java}: it's the same real hook
     * vanilla chests use to spill their contents when broken) fires while this block entity's own data - and
     * crucially, {@link #level} - is still valid, unlike the block-level {@code affectNeighborsAfterRemoval}
     * hook, which only runs after removal has already happened. This clears the WHOLE remaining shaft in one
     * shot regardless of the paced growth rate - there's no more ticking block entity left to pace it with. */
    @Override
    public void preRemoveSideEffects(BlockPos pos, BlockState state) {
        super.preRemoveSideEffects(pos, state);
        if (level != null) {
            clearShaft(level, pos);
        }
    }

    private void clearShaft(Level level, BlockPos ownPos) {
        Block tube = getTubeBlock();
        int maxDepth = Config.QUARRY_MAX_MINE_DEPTH.get();
        for (int y = ownPos.getY() - 1; y > ownPos.getY() - maxDepth; y--) {
            BlockPos p = new BlockPos(ownPos.getX(), y, ownPos.getZ());
            if (level.getBlockState(p).getBlock() == tube) {
                level.setBlock(p, Blocks.AIR.defaultBlockState(), 3);
            } else {
                break;
            }
        }
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
        energy.serialize(output.child("energy"));
        output.putLong("progress", progress);
        output.putInt("targetLength", targetLength);
        output.putInt("wantedLength", wantedLength);
        output.putDouble("growthProgress", growthProgress);
        output.putBoolean("paused", paused);
        output.putBoolean("stopped", stopped);
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        input.child("energy").ifPresent(energy::deserialize);
        progress = input.getLongOr("progress", 0L);
        targetLength = input.getIntOr("targetLength", 0);
        wantedLength = input.getIntOr("wantedLength", 0);
        growthProgress = input.getDoubleOr("growthProgress", 0.0);
        paused = input.getBooleanOr("paused", false);
        stopped = input.getBooleanOr("stopped", false);
    }
}
