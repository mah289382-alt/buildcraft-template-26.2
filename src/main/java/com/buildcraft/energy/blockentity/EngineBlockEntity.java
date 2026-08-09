package com.buildcraft.energy.blockentity;

import java.util.EnumMap;
import java.util.Map;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.RedStoneWireBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.neoforged.neoforge.capabilities.BlockCapabilityCache;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.transfer.energy.EnergyHandler;
import net.neoforged.neoforge.transfer.transaction.Transaction;
import org.jspecify.annotations.Nullable;

import com.buildcraft.Config;
import com.buildcraft.transport.pipe.PulsedEnergyReceiver;

/**
 * Ports {@code TileEngineBase_BC8}: heat/power-stage mechanics, redstone-signal gating, single-face
 * wrench-rotatable power output, and animated piston progress, shared by every engine tier. Real source
 * constants/formulas (verified against {@code TileEngineBase_BC8.java}):
 * <ul>
 * <li>{@code HEAT_PER_FE}/{@code MIN_HEAT}/{@code IDEAL_HEAT}/{@code MAX_HEAT} = 0.0023/20/100/250 (source's
 * {@code HEAT_PER_MJ} constant reused verbatim as a pure tuning multiplier - the unit substitution from MJ to
 * FE doesn't change its role).
 * <li>Power stage thresholds on {@code (heat-MIN_HEAT)/(MAX_HEAT-MIN_HEAT)}: &lt;0.25 BLUE, &lt;0.5 GREEN,
 * &lt;0.75 YELLOW, &lt;0.85 RED, else OVERHEAT.
 * <li>Generic piston-speed-per-stage curve BLUE=0.02/GREEN=0.04/YELLOW=0.08/RED=0.12/OVERHEAT=0 (per-tier
 * subclasses may override, matching e.g. Iron/RF's flatter curve in source).
 * <li>OVERHEAT means {@link #burn} is skipped (the engine stalls) until heat drops back down - source defines
 * an {@code explosionRange()} per tier but the actual explosion call is commented-out/unimplemented in the real
 * BC8 source, so this port matches that (stall, no explosion) rather than guessing at unshipped behaviour.
 * <li>Whenever NOT redstone-powered, the buffer is force-drained by a flat amount per tick (regardless of the
 * tier's own capacity) until empty - source drains a flat {@code MjAPI.MJ}; see
 * {@link Config#ENGINE_UNPOWERED_DRAIN_PER_TICK} for this port's FE-scaled equivalent.
 * </ul>
 * Redstone signal is required for EVERY tier to do anything at all (burn fuel, push power) - not just the
 * Redstone Engine - confirmed via every tile's {@code burn()} gating on {@code isRedstonePowered}.
 */
public abstract class EngineBlockEntity extends BlockEntity {
    public static boolean DEBUG_LOG = true;

    public enum PowerStage {
        BLUE, GREEN, YELLOW, RED, OVERHEAT
    }

    protected static final double HEAT_PER_FE = 0.0023;
    protected static final double MIN_HEAT = 20;
    protected static final double IDEAL_HEAT = 100;
    protected static final double MAX_HEAT = 250;

    protected double heat = MIN_HEAT;
    protected long power = 0;
    protected float progress = 0f;
    protected boolean redstonePowered = false;
    // Diagnostic-only: last tick's isPumping() result, for [ENGINE_PUMPING_DEBUG]'s transition logging.
    private boolean prevPumping = false;

    /** Real perf fix (2026-07-31 FPS/TPS audit): {@link #pushPower} runs every server tick this engine has power
     * to send, and the raw {@code level.getCapability(...)} it used to call directly re-walks the target block's
     * whole provider list AND re-invokes the provider (allocating a fresh handler) on EVERY call - confirmed via
     * NeoForge's own {@code BlockCapability.getCapability} source, which does no caching itself.
     * {@link BlockCapabilityCache} is NeoForge's own purpose-built fix for exactly this (its own javadoc: "a
     * cache for block capabilities, to be used to track capabilities at a specific position"). Keyed per
     * {@code Direction} (not a single field) so wrench-rotating {@code FACING} naturally starts using a
     * different, independently-correct cache entry instead of needing explicit invalidation. */
    private final Map<Direction, BlockCapabilityCache<EnergyHandler, Direction>> powerCapCache = new EnumMap<>(Direction.class);

    protected EngineBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    public abstract long getMaxPower();

    /** This tier's body texture (used for both the static base model and the renderer's animated piston head). */
    public abstract Identifier getBodyTexture();

    /** Produces power into {@link #power} (and updates any tier-specific fuel state) - only called outside OVERHEAT. */
    protected abstract void burn(Level level, BlockPos pos, BlockState state);

    /** Whether the piston should currently be actively pumping (drives forward vs. decaying progress). */
    protected boolean isPumping() {
        return redstonePowered;
    }

    /**
     * The default/generic heat formula (used as-is by the Stirling Engine in source): heat is derived fresh
     * every tick from how full the buffer is, not smoothed or independently tracked. Redstone/Combustion
     * override this with their own tier-specific heat update logic.
     */
    protected void updateHeat() {
        heat = MIN_HEAT + (MAX_HEAT - MIN_HEAT) * Mth.clamp((double) power / getMaxPower(), 0, 1);
    }

    /**
     * Ports {@code TileEngineBase_BC8#maxPowerExtracted()}: the max amount pushable to a receiver in a single
     * tick - independent of the buffer's total capacity ({@link #getMaxPower()}). Real source ratios
     * (maxPowerExtracted/getMaxPower, unit-agnostic): Redstone 4/1 (exceeds its own buffer, i.e. no meaningful
     * throttle - the generic default here), Stirling 100/1000 = 1/10, Iron/Combustion 500/10_000 = 1/20 (both
     * overridden by their tiers). Without this cap the whole buffer would dump in a single tick as long as the
     * receiver's own capability accepted it, which source deliberately prevents via this separate per-tick cap.
     */
    protected long getMaxPowerExtracted() {
        return getMaxPower();
    }

    /** The real sustained per-tick delivery cap to a CONTINUOUS (non-pulsed) receiver - e.g. a Quarry, Refinery,
     * or Mining Well/Pump, which pull every tick rather than once per piston stroke. Deliberately separate from
     * {@link #getMaxPowerExtracted}, which is a one-time-per-pulse burst cap for {@link PulsedEnergyReceiver}s
     * only - see {@link #pushPower}'s own javadoc for the real bug this split fixes. Defaults to the pulse cap
     * for safety/back-compat (the Creative Engine, which has no tier-specific throttle of its own, keeps its
     * prior unthrottled-by-design behavior); every real fuel-burning tier overrides this with its own genuine
     * production-rate figure. */
    protected long getSustainedFePerTick() {
        return getMaxPowerExtracted();
    }

    protected float getPistonSpeed(PowerStage stage) {
        return switch (stage) {
            case BLUE -> 0.02f;
            case GREEN -> 0.04f;
            case YELLOW -> 0.08f;
            case RED -> 0.12f;
            case OVERHEAT -> 0f;
        };
    }

    public PowerStage getPowerStage() {
        double level = (heat - MIN_HEAT) / (MAX_HEAT - MIN_HEAT);
        if (level < 0.25) {
            return PowerStage.BLUE;
        } else if (level < 0.5) {
            return PowerStage.GREEN;
        } else if (level < 0.75) {
            return PowerStage.YELLOW;
        } else if (level < 0.85) {
            return PowerStage.RED;
        }
        return PowerStage.OVERHEAT;
    }

    public double getHeat() {
        return heat;
    }

    public long getPower() {
        return power;
    }

    public float getProgress() {
        return progress;
    }

    public static void tick(Level level, BlockPos pos, BlockState state, EngineBlockEntity be) {
        if (level.isClientSide()) {
            return;
        }
        boolean prevRedstonePowered = be.redstonePowered;
        // Real design decision (2026-08-08, explicit user request): plain hasNeighborSignal() alone isn't enough
        // here. Confirmed via direct testing + [ENGINE_REDSTONE_DEBUG]: a redstone torch powers the engine fine
        // (torches are unconditional signal sources, powered from every side), but a straight run of redstone
        // dust running PAST the engine (not bent/pointed directly at it) does not - RedStoneWireBlock.getSignal()
        // only returns nonzero toward sides the wire has actually connected a visible arm to (its own
        // NORTH/SOUTH/EAST/WEST RedstoneSide state), zero toward any side it just runs straight past. That's
        // real, correct vanilla physics (the same limitation applies to furnaces/pistons/etc., not unique to
        // engines) - but the user explicitly wants engines to be powered by ANY adjacent lit dust regardless of
        // its connection shape, matching how a Powered Rail behaves with an ISOLATED ("dot") piece of dust,
        // generalized to every dust shape. So: read RedStoneWireBlock's own POWER blockstate property (0-15)
        // directly from each neighbor, bypassing its connection-shape gating entirely, in addition to the
        // normal hasNeighborSignal() check (still needed for torches/levers/repeaters/indirect power).
        be.redstonePowered = level.hasNeighborSignal(pos) || hasAdjacentLitDust(level, pos);

        if (DEBUG_LOG && be.redstonePowered != prevRedstonePowered) {
            StringBuilder sb = new StringBuilder();
            for (Direction dir : Direction.values()) {
                BlockPos neighborPos = pos.relative(dir);
                int signal = level.getSignal(neighborPos, dir);
                BlockState neighborState = level.getBlockState(neighborPos);
                String dustPower = neighborState.getBlock() instanceof RedStoneWireBlock
                        ? ", dustPower=" + neighborState.getValue(RedStoneWireBlock.POWER)
                        : "";
                sb.append(String.format("%s(neighbor=%s, signalTo-%s=%d%s) ", dir, neighborState.getBlock(), dir, signal, dustPower));
            }
            com.buildcraft.BuildCraft.LOGGER.info("[ENGINE_REDSTONE_DEBUG] pos={} redstonePowered {} -> {} | {}",
                    pos, prevRedstonePowered, be.redstonePowered, sb);
        }

        if (!be.redstonePowered) {
            be.power = Math.max(0, be.power - Config.ENGINE_UNPOWERED_DRAIN_PER_TICK.get());
        }

        boolean overheat = be.getPowerStage() == PowerStage.OVERHEAT;
        if (!overheat) {
            be.burn(level, pos, state);
        }
        be.updateHeat();

        PowerStage stage = be.getPowerStage();
        float prevProgress = be.progress;
        boolean pumpingNow = be.isPumping();
        if (DEBUG_LOG && pumpingNow != be.prevPumping) {
            com.buildcraft.BuildCraft.LOGGER.info(
                    "[ENGINE_PUMPING_DEBUG] pos={} tier={} isPumping {} -> {} | redstonePowered={} power={} heat={}",
                    pos, be.getClass().getSimpleName(), be.prevPumping, pumpingNow, be.redstonePowered, be.power, be.heat);
        }
        be.prevPumping = pumpingNow;
        if (pumpingNow) {
            be.progress += be.getPistonSpeed(stage);
            if (be.progress > 1f) {
                be.progress -= 1f;
            }
        } else {
            be.progress = Math.max(0f, be.progress - 0.01f);
        }
        // Ports the real "pulsedPower" trigger: the piston stroke's rising phase just crossed its midpoint -
        // the single moment per full pump a PulsedEnergyReceiver (e.g. a Wood pipe) actually gets pushed power.
        boolean justPulsed = pumpingNow && prevProgress < 0.5f && be.progress >= 0.5f;

        be.pushPower(level, pos, state, justPulsed);
        be.setChanged();

        if (DEBUG_LOG && level.getGameTime() % 20 == 0) {
            com.buildcraft.BuildCraft.LOGGER.info(
                    "[ENGINE_DEBUG] pos={} tier={} redstonePowered={} power={}/{} heat={} stage={} progress={} pistonSpeed={}",
                    pos, be.getClass().getSimpleName(), be.redstonePowered, be.power, be.getMaxPower(), be.heat,
                    stage, be.progress, be.getPistonSpeed(stage));
        }
        // Progress/heat change every tick, so - unlike the pipe/quarry modules' change-triggered syncs - this
        // just syncs unconditionally each tick, matching how continuously-animated state needs to reach nearby
        // clients continuously. No extra client-side prev/current interpolation layer on top (unlike Quarry's
        // drill position double-buffering) - a documented simplification, not expected to look choppy at a
        // 20/sec sync rate but flagged as a possible future polish item if it does.
        level.sendBlockUpdated(pos, state, state, 2);
    }

    /** See {@link #tick}'s javadoc comment for why this exists: a redstone wire neighbor's own visible connection
     * shape (which side(s) it's bent/wired toward) doesn't limit whether it counts here, only whether it's
     * actually carrying a signal (its {@code POWER} state, 0-15). */
    private static boolean hasAdjacentLitDust(Level level, BlockPos pos) {
        for (Direction dir : Direction.values()) {
            BlockState neighborState = level.getBlockState(pos.relative(dir));
            if (neighborState.getBlock() instanceof RedStoneWireBlock && neighborState.getValue(RedStoneWireBlock.POWER) > 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * Demand-driven: only pushes as much as the neighbor's capability actually accepts, via a real transaction.
     * Ports {@code TileEngineBase_BC8.update()}'s {@code pulsedPower} split: a {@link PulsedEnergyReceiver} (a
     * Wood-family pipe) only ever gets pushed power on the one tick per full piston stroke that
     * {@code justPulsed} is true, matching source's "only send power once, at the top of the pump, to an
     * {@code IMjRedstoneReceiver}" - anything else (e.g. the Quarry) keeps receiving continuously every tick.
     */
    protected void pushPower(Level level, BlockPos pos, BlockState state, boolean justPulsed) {
        // Real bug fixed (2026-08-08, user-reported): this had NO redstonePowered check at all - it pushed
        // whatever was left in the buffer every tick regardless of current signal state, only ever shrinking via
        // its own consumption here plus the separate flat ENGINE_UNPOWERED_DRAIN_PER_TICK in tick(). For a small
        // buffer (Redstone, 1000 FE) that decay was fast enough to look instant; for Combustion's 200,000 FE cap
        // it could keep actively pushing real power - now at getSustainedFePerTick()'s full production rate,
        // 300-600 FE/tick, since that fix correctly stopped throttling it down - for many seconds after the
        // redstone signal (and any dust) was already gone, which is exactly the "stays powering / way too much
        // output" symptom reported: the piston correctly stops animating right away (isPumping() tracks
        // burnTime/redstonePowered directly), but the buffer kept draining itself into the receiver regardless,
        // an engine that LOOKS dead while still actively working. An unpowered engine should hold onto (or
        // slow-drain, via the flat per-tick constant) whatever's left, not keep actively dispatching it.
        if (!redstonePowered || power <= 0) {
            return;
        }
        Direction facing = state.getValue(BlockStateProperties.FACING);
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }
        BlockCapabilityCache<EnergyHandler, Direction> cache = powerCapCache.computeIfAbsent(facing,
                dir -> BlockCapabilityCache.create(Capabilities.Energy.BLOCK, serverLevel, pos.relative(dir), dir.getOpposite(),
                        () -> !isRemoved(), () -> {}));
        EnergyHandler receiver = cache.getCapability();
        if (receiver == null) {
            return;
        }
        boolean pulsed = receiver instanceof PulsedEnergyReceiver;
        if (pulsed && !justPulsed) {
            return;
        }
        // Real bug fixed (2026-08-08): every tier's own *_MAX_PULSE_OUTPUT config was explicitly documented as
        // "max FE deliverable in a SINGLE PULSE to a pulsed receiver" (e.g. a Wood pipe), but getMaxPowerExtracted
        // was being applied here unconditionally to EVERY receiver, pulsed or not - so a continuous receiver
        // (Quarry, Refinery, Mining Well/Pump) got that same generous burst-per-pulse number delivered EVERY
        // SINGLE TICK, sustained forever, not just once per piston stroke. That's what let a single Redstone
        // Engine run a Quarry at near-full speed - confirmed via direct FE/tick log comparisons this session.
        // getSustainedFePerTick() is the real fix: a separate, genuinely conservative continuous-delivery cap,
        // used for anything that ISN'T a PulsedEnergyReceiver; getMaxPowerExtracted() now only ever applies to
        // the actual once-per-stroke pulsed-receiver burst it was documented for.
        long cap = pulsed ? getMaxPowerExtracted() : getSustainedFePerTick();
        int toSend = (int) Math.min(Math.min(power, cap), Integer.MAX_VALUE);
        try (Transaction tx = Transaction.openRoot()) {
            int inserted = receiver.insert(toSend, tx);
            if (inserted > 0) {
                tx.commit();
                power -= inserted;
                if (DEBUG_LOG) {
                    com.buildcraft.BuildCraft.LOGGER.info("[ENGINE_DEBUG] pos={} pushed {} FE to {} (justPulsed={})",
                            pos, inserted, pos.relative(facing), justPulsed);
                }
            }
        }
    }

    /**
     * Wrench right-click: cycle to the next face that has a valid power receiver, matching
     * {@code TileEngineBase_BC8.attemptRotation} - refuses to rotate onto a face with nothing to receive power.
     */
    public boolean onWrenchClick(Level level, BlockPos pos, BlockState state) {
        Direction current = state.getValue(BlockStateProperties.FACING);
        Direction[] all = Direction.values();
        int start = current.ordinal();
        for (int i = 1; i <= all.length; i++) {
            Direction candidate = all[(start + i) % all.length];
            if (candidate == current) {
                continue;
            }
            if (hasReceiver(level, pos, candidate)) {
                level.setBlockAndUpdate(pos, state.setValue(BlockStateProperties.FACING, candidate));
                if (DEBUG_LOG) {
                    com.buildcraft.BuildCraft.LOGGER.info("[ENGINE_DEBUG] pos={} wrench-rotated FACING {} -> {}", pos, current, candidate);
                }
                return true;
            }
        }
        if (DEBUG_LOG) {
            com.buildcraft.BuildCraft.LOGGER.info("[ENGINE_DEBUG] pos={} wrench-rotate found no valid receiver face, staying {}", pos, current);
        }
        return false;
    }

    public static boolean hasReceiver(Level level, BlockPos pos, Direction facing) {
        BlockPos targetPos = pos.relative(facing);
        return level.getCapability(Capabilities.Energy.BLOCK, targetPos, facing.getOpposite()) != null;
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
        output.putDouble("heat", heat);
        output.putLong("power", power);
        output.putFloat("progress", progress);
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        heat = input.getDoubleOr("heat", MIN_HEAT);
        power = input.getLongOr("power", 0);
        progress = input.getFloatOr("progress", 0f);
    }
}
