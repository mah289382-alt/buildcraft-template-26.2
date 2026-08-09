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
 * <li>Growth is no longer target-driven by a pre-detected position at all: the shaft just keeps probing one
 * block deeper each time it isn't {@link #paused}, with no upfront search - a subclass's {@code mine} inspects
 * the UNTOUCHED real world state at {@link #getTipPos} (one block below the already-committed
 * {@link #wantedLength}, i.e. the position growth is about to enter next, not yet overwritten by anything) and
 * sets {@link #paused} true the moment there's real work to do (something to break/drain), halting growth right
 * there until that work finishes, then clears it to resume probing deeper.
 * {@link #stopped} is the one permanent halt (hit real {@link Config#QUARRY_MAX_MINE_DEPTH}, or a subclass's own
 * permanent obstruction).
 * </ul>
 * <p>
 * <b>Real bug fixed (root cause of "the Pump digs through solid ground instead of just sucking water" and "the
 * Mining Well doesn't drop the blocks it mines")</b>: an earlier version of this class had {@link #mine} run
 * AFTER growth had already committed the new depth - {@code tickShaftGrowth} unconditionally overwrote whatever
 * real block occupied a newly-reached depth with the invisible {@link #getTubeBlock()} marker, so by the time a
 * subclass's {@code mine} inspected {@code getTipPos}, it was always looking at its OWN just-placed tube, never
 * the real ground. For the Mining Well this meant {@code canBreak}/{@code mineAndRouteDrops} was structurally
 * unreachable (the tip was never a real breakable block by the time it was checked) - blocks vanished for free
 * with zero cost and zero drops. For the Pump it meant the "halt at a solid obstruction" check could likewise
 * never see a real obstruction (only ever its own tube) - the shaft tunnelled through solid rock (and would have
 * gone through bedrock too) hunting for fluid, instead of stopping the instant it wasn't air. Fixed by having
 * {@link #tick} call {@code mine} BEFORE {@code tickShaftGrowth} each tick, and by pointing {@link #getTipPos} at
 * {@code wantedLength + 1} (the next, still-untouched probe position) rather than {@code wantedLength} itself
 * (the already-committed, already-tube-occupied depth) - a subclass's {@code mine} now always sees genuine,
 * unmolested world state, and growth (gated by the now-unconditional {@link #paused} check at the very top of
 * {@link #tickShaftGrowth}) physically can't commit a new depth in the same tick a subclass just decided there's
 * real work to do there.
 */
public abstract class MinerBlockEntity extends BlockEntity {
    public static boolean DEBUG_LOG = true;
    private long debugShaftFeAccum = 0;

    protected long progress = 0;

    protected final SimpleEnergyHandler energy;

    /** Server-authoritative CURRENT physical shaft depth - the real {@link #getTubeBlock()} pole reaches exactly
     * this far (whole blocks only; see {@link #growthProgress} for the partial block currently growing). Only
     * ever advances onto real world positions a subclass's {@code mine} has already inspected and cleared this
     * same tick (see class javadoc) - never blindly overwrites unprocessed ground. */
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

    /** The position the shaft's growth is about to probe next - one block below the already-committed
     * {@link #wantedLength}, i.e. real, untouched world state that growth has NOT yet overwritten (see class
     * javadoc's bug-fix note) - a subclass's {@code mine} inspects this every tick to decide whether there's real
     * work to do before growth is allowed to commit it. */
    protected BlockPos getTipPos(BlockPos ownPos) {
        return ownPos.below(wantedLength + 1);
    }

    /** Real {@code TileMiner.mine()} - runs every server tick regardless of current state (idle machines still
     * need to periodically re-check for new work, e.g. a block placed/broken nearby, or new fluid flowing in).
     * Called BEFORE {@link #tickShaftGrowth} each tick (see class javadoc's bug-fix note) so a subclass always
     * inspects {@link #getTipPos} before growth has any chance to overwrite it. */
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
        // mine() FIRST (see class javadoc's bug-fix note): it must inspect getTipPos while it's still genuinely
        // untouched, before tickShaftGrowth gets any chance to commit/overwrite that position this same tick.
        be.mine(level, pos);
        be.tickShaftGrowth(level, pos);
    }

    /** The interpolated total shaft length (whole blocks placed + the current partial block's fraction) a
     * renderer should draw this frame - mirrors {@code QuarryBlockEntity.getInterpolatedDrillPos} exactly. */
    public double getInterpolatedLength(float partialTicks) {
        return prevClientGrowth + (clientGrowth - prevClientGrowth) * partialTicks;
    }

    /** Advances {@link #growthProgress} by {@code 1 / Config.MINER_SHAFT_TICKS_PER_BLOCK} every server tick the
     * shaft isn't {@link #paused}/{@link #stopped} (real, continuous motion, not a discrete per-block pop),
     * placing the next real (invisible) {@link #getTubeBlock()} marker the moment that crosses 1.0 (carrying the
     * remainder over, so motion never stutters at the boundary). {@link #paused} is now checked FIRST and
     * unconditionally (see class javadoc's bug-fix note) - growth genuinely cannot advance at all while paused,
     * so a subclass's {@code mine} (which runs first each tick, per {@link #tick}) always gets first look at a
     * new depth's real content before growth could ever commit/overwrite it. Syncs every tick it's actively
     * growing (real {@code QuarryBlockEntity.syncToClients} pattern - {@code sendBlockUpdated} every tick a
     * change happened) so the client's own smoothing always has fresh data to glide toward. */
    private void tickShaftGrowth(Level level, BlockPos ownPos) {
        if (stopped || paused) {
            return;
        }
        if (wantedLength >= Config.QUARRY_MAX_MINE_DEPTH.get()) {
            BuildCraft.LOGGER.info("Miner at {}: STOPPED (max depth {} reached)", ownPos, wantedLength);
            stopped = true;
            setChanged();
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
                    logShaftDebug(level, ownPos);
                    return; // not enough power this tick - the shaft simply doesn't move
                }
                tx.commit();
                debugShaftFeAccum += extracted;
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
        logShaftDebug(level, ownPos);
    }

    private void logShaftDebug(Level level, BlockPos ownPos) {
        if (DEBUG_LOG && level.getGameTime() % 100 == 0) {
            BuildCraft.LOGGER.info(
                    "[MINER_SHAFT_DEBUG] pos={} tier={} shaftLen={} stored={} FE consumed last 100t={} FE ({}/t avg)",
                    ownPos, getClass().getSimpleName(), wantedLength, energy.getAmountAsLong(),
                    debugShaftFeAccum, debugShaftFeAccum / 100.0);
            debugShaftFeAccum = 0;
        }
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
        wantedLength = input.getIntOr("wantedLength", 0);
        growthProgress = input.getDoubleOr("growthProgress", 0.0);
        paused = input.getBooleanOr("paused", false);
        stopped = input.getBooleanOr("stopped", false);
    }
}
