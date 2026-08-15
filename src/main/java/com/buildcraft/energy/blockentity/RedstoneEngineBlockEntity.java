package com.buildcraft.energy.blockentity;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import com.buildcraft.BuildCraft;
import com.buildcraft.Config;
import com.buildcraft.energy.EnergyContent;

/**
 * Ports {@code TileEngineRedstone_BC8}: the simplest, fuel-less tier. While redstone-powered, the buffer is
 * force-topped-off to max every single tick (not gradually filled), and heat climbs by a flat 4 every 16 ticks
 * (only while below 80% heat level) - both ported verbatim from {@code engineUpdate()}. Heat cooldown is its
 * own override too (a flat -0.2/tick passive cooldown, NOT the generic power-ratio formula every other tier
 * that doesn't override it uses). {@code getMaxChainLength()=0} (can't relay through another Redstone Engine)
 * and piston speed is half the generic curve (the slowest of all tiers) - both source-verified constants.
 * <p>
 * Not ported: source's {@code redstoneOnly} connector flag (only connects to a special "pulsed"
 * {@code IMjRedstoneReceiver} type, with power sent once per piston stroke instead of continuously) - this port
 * has no such receiver-type distinction in the modern FE capability system, so it pushes continuously every
 * tick like every other tier, a documented simplification.
 */
public class RedstoneEngineBlockEntity extends EngineBlockEntity {
    private static final Identifier BODY_TEXTURE = Identifier.fromNamespaceAndPath(BuildCraft.MODID, "block/engine_redstone");

    public RedstoneEngineBlockEntity(BlockPos pos, BlockState state) {
        super(EnergyContent.ENGINE_REDSTONE_BLOCK_ENTITY.get(), pos, state);
    }

    @Override
    public long getMaxPower() {
        return Config.ENGINE_REDSTONE_CAPACITY.get();
    }

    @Override
    public Identifier getBodyTexture() {
        return BODY_TEXTURE;
    }

    @Override
    protected void burn(Level level, BlockPos pos, BlockState state) {
        if (redstonePowered) {
            power = getMaxPower();
            if (level.getGameTime() % 16 == 0 && heatLevel() < 0.8) {
                heat = Math.min(MAX_HEAT, heat + 4);
            }
        } else {
            power = 0;
        }
    }

    private double heatLevel() {
        return (heat - MIN_HEAT) / (MAX_HEAT - MIN_HEAT);
    }

    @Override
    protected void updateHeat() {
        heat = Math.max(MIN_HEAT, heat - 0.2);
    }

    @Override
    protected float getPistonSpeed(PowerStage stage) {
        return super.getPistonSpeed(stage) / 2f;
    }

    /** Real source value (confirmed 2026-08-11 via BuildCraftAPI/BuildCraft GitHub source): 4 MJ = 40 FE at the
     * real 1 MJ = 10 FE ratio - practically never the binding constraint anyway, since {@link #getMaxPower} (the
     * buffer, 1 MJ = 10 FE, force-refilled to max every tick in {@link #burn}) is smaller than this cap, so real
     * sustained delivery is naturally limited to ~10 FE/tick regardless of what this returns - exactly matching
     * source's own actual dynamic (see {@code EngineBlockEntity.getMaxPowerExtracted}'s javadoc for the full
     * explanation of why this port no longer has a separate "sustained" number). */
    @Override
    protected long getMaxPowerExtracted() {
        return Config.ENGINE_REDSTONE_MAX_PULSE_OUTPUT.get();
    }
}
