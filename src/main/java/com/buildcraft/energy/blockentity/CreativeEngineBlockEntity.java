package com.buildcraft.energy.blockentity;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

import com.buildcraft.BuildCraft;
import com.buildcraft.energy.EnergyContent;

/**
 * Ports {@code TileEngineCreative}: an unlimited debug/testing power source, no recipe (creative-tab/command
 * only). Wrench right-click cycles its output level through a fixed 9-step scale (source: 1/2/4/8/16/32/64/128/
 * 256 MJ) instead of rotating facing - the one tier where wrenching does something other than rotation, ported
 * as an {@link #onWrenchClick} override that bypasses the base class's rotate-to-next-receiver behaviour
 * entirely. Useful for testing downstream machines (Quarry, powered pipes) without needing a real fuel chain,
 * matching this project's existing debug-tooling pattern ({@code Config.QUARRY_DEBUG_INFINITE_POWER}).
 */
public class CreativeEngineBlockEntity extends EngineBlockEntity {
    private static final Identifier BODY_TEXTURE = Identifier.fromNamespaceAndPath(BuildCraft.MODID, "block/engine_creative");
    private static final long[] OUTPUT_LEVELS = {1, 2, 4, 8, 16, 32, 64, 128, 256};
    private static final long OUTPUT_UNIT = 10; // FE per "MJ-equivalent" step - a placeholder base unit, not source-derived.

    private int outputIndex = 4;

    public CreativeEngineBlockEntity(BlockPos pos, BlockState state) {
        super(EnergyContent.ENGINE_CREATIVE_BLOCK_ENTITY.get(), pos, state);
    }

    private long currentOutput() {
        return OUTPUT_LEVELS[outputIndex] * OUTPUT_UNIT;
    }

    @Override
    public long getMaxPower() {
        return currentOutput() * 4;
    }

    /** Real source: {@code maxPowerExtracted() = 20 * getCurrentOutput()} - tied to output level, not buffer size. */
    @Override
    protected long getMaxPowerExtracted() {
        return 20 * currentOutput();
    }

    @Override
    public Identifier getBodyTexture() {
        return BODY_TEXTURE;
    }

    /**
     * Ports {@code TileEngineCreative.engineUpdate()}/{@code isBurning()}: power only maxes out while
     * redstone-powered, dropping to 0 immediately otherwise - the Creative Engine does still require a redstone
     * signal like every other tier, it's just not fuel/heat-limited once powered.
     */
    @Override
    protected void burn(Level level, BlockPos pos, BlockState state) {
        power = redstonePowered ? getMaxPower() : 0;
    }

    @Override
    protected void updateHeat() {
        heat = IDEAL_HEAT;
    }

    @Override
    public boolean onWrenchClick(Level level, BlockPos pos, BlockState state) {
        outputIndex = (outputIndex + 1) % OUTPUT_LEVELS.length;
        setChanged();
        return true;
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        output.putInt("outputIndex", outputIndex);
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        outputIndex = input.getIntOr("outputIndex", 4) % OUTPUT_LEVELS.length;
    }
}
