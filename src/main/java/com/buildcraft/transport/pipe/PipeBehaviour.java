package com.buildcraft.transport.pipe;

import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.neoforged.neoforge.transfer.energy.EnergyHandler;
import net.neoforged.neoforge.transfer.fluid.FluidResource;
import org.jspecify.annotations.Nullable;

import com.buildcraft.transport.blockentity.FluidPipeBlockEntity;
import com.buildcraft.transport.blockentity.PipeBlockEntity;

/**
 * A modern, minimal stand-in for the original's {@code PipeBehaviour} + its {@code PipeEventItem} event-bus hooks
 * ({@code SideCheck}/{@code TryBounce}/{@code ModifySpeed} etc). Rather than a generic event bus (a large piece of
 * infrastructure that exists in the original to support things - gates/statements - not yet in scope here), this
 * exposes exactly the hook points the currently-implemented pipe tiers actually need. Extending this interface
 * further (more hook points) is the expected way to add new behaviours, matching the original's actual per-tier
 * class hierarchy (one {@code PipeBehaviour} subclass per material) rather than a single monolithic pipe type.
 */
public interface PipeBehaviour {
    /**
     * Ticks for an item to travel from an entry face to the pipe's center, or from center to an exit face. Lower
     * is faster. Placeholder pacing (like {@link com.buildcraft.builders.blockentity.QuarryBlockEntity}'s FE
     * constants) - the original's speed is a continuous blocks/tick float with no direct modern-FE-style unit to
     * derive an exact value from, so tiers are relative to each other (Gold faster, Cobble/Stone slower) rather
     * than tied to a specific real-world pace.
     */
    default int ticksPerPhase() {
        return 5;
    }

    /**
     * Called when an item reaches the pipe's center, to narrow which connected sides (excluding the one the item
     * entered from) it's allowed to leave through. Mirrors {@code PipeEventItem.SideCheck}: start from
     * {@code candidates} (all connected sides but the entry one) and return a subset - an empty result means
     * "nothing allowed," which the caller treats as a dropped/bounced item exactly like the original's
     * {@code TryBounce} fallback when {@code SideCheck} rejects everything. {@code colour} is this item's current
     * colour tag (see {@link #stampColour}), or {@code null} if untagged - used by Daizuli to additionally
     * require a colour match on top of its forced direction.
     */
    default Set<Direction> filterDestinations(Level level, BlockPos pipePos, Direction from, ItemStack stack,
            @Nullable DyeColor colour, Set<Direction> candidates) {
        return candidates;
    }

    /**
     * Called when an item reaches the pipe's center, to update its in-flight colour tag before a destination is
     * chosen - mirrors {@code PipeEventItem.ReachCenter.colour}. The tag travels with the item (not baked into
     * the {@code ItemStack} itself, matching the original's separate {@code ItemEntry.colour} field) across
     * however many further pipes it passes through. Default passthrough (preserves whatever tag arrived, or
     * stays untagged); Lapis/Daizuli override this to unconditionally stamp their own configured colour.
     */
    default @Nullable DyeColor stampColour(@Nullable DyeColor incoming) {
        return incoming;
    }

    /** True if any item reaching the center should be destroyed instead of routed (the Void pipe). */
    default boolean destroysItems() {
        return false;
    }

    /**
     * Fluid-transport parallel to {@link #filterDestinations} - called by {@link FluidPipeBlockEntity} to narrow
     * which connected sides a fluid may leave through. Same semantics: start from {@code candidates}, return a
     * subset; an empty result means nothing allowed (the fluid simply doesn't move that tick, there's no
     * fluid-drop equivalent of bouncing an item). Most behaviours never override this (a fluid pipe's routing is
     * driven far more by real per-tier flow rate than by destination filtering) - only Iron (forced single
     * direction, real {@code PipeBehaviourIron.fluidSideCheck}) and the Diamond family (real filter GUI,
     * fluid-identity comparison) need it.
     */
    default Set<Direction> filterFluidDestinations(Level level, BlockPos pipePos, Direction from,
            FluidResource fluid, Set<Direction> candidates) {
        return candidates;
    }

    /** Fluid-transport parallel to {@link #destroysItems} - real {@code PipeBehaviourVoid.moveFluidToCentre}
     * zeroes the fluid array before it reaches centre; ported here as "never accept fluid into this pipe at all"
     * (equivalent observable effect - nothing a Void pipe touches is ever seen again). */
    default boolean destroysFluids() {
        return false;
    }

    /**
     * How many mB this pipe tier moves per tick - real per-material values from
     * {@code BCTransportConfig.reloadConfig}'s {@code fluidTransfer(...)} calls (Wood/Cobblestone=10,
     * Stone/Sandstone=20, Clay/Iron/Quartz=40, Diamond/Diamond-Wood/Gold/Void=80). {@code 0} (the default) means
     * "not a fluid pipe tier" - {@link FluidPipeBlockEntity} never ticks flow for a behaviour that returns 0,
     * which in practice never happens since every {@code FluidPipeBlock} registration always supplies a real
     * fluid-flavoured behaviour instance.
     */
    default int fluidTransferPerTick() {
        return 0;
    }

    /**
     * Real {@code transferDelayMultiplier} - how many ticks between real source's own delayed-array flow steps.
     * This port doesn't reproduce that exact per-tick delayed-insertion simulation (see
     * {@link FluidPipeBlockEntity}'s javadoc for the deliberate simplification), but reuses the same real number
     * as a "only actually move fluid on 1-in-N ticks" pacing multiplier, so Gold's real {@code delay=2} (vs.
     * every other tier's {@code 10}) still reads as meaningfully faster, matching real relative pacing.
     */
    default int fluidTransferDelay() {
        return 10;
    }

    /**
     * False if this pipe should never connect to a non-pipe neighbor (inventories,
     * {@link com.buildcraft.transport.pipe.PipeConnectable}s) at all - only the Sandstone pipe overrides this,
     * matching {@code PipeBehaviourSandstone.canConnect(face, TileEntity)} always returning false in the original
     * (a pure pipe-to-pipe relay).
     */
    default boolean connectsToContainers() {
        return true;
    }

    /**
     * Whether this pipe may connect to a specific neighboring pipe, given the neighbor's own behaviour. Mirrors
     * {@code PipeBehaviour.canConnect(face, PipeBehaviour other)} - used by {@code PipeBehaviourSeparate} (the
     * Cobblestone/Stone/Quartz family) to only connect to another pipe of the exact same material, while still
     * connecting freely to every other (non-Separate) tier.
     */
    default boolean canConnectToPipe(PipeBehaviour other) {
        return true;
    }

    /**
     * Handles a wrench right-click on this pipe. {@code clickedFace} is the specific face clicked, or
     * {@code null} if the player clicked the pipe's central post - mirrors
     * {@code PipeBehaviourDirectional.onPipeActivate}'s {@code EnumPipePart.CENTER} vs. a specific face
     * distinction. Returns {@code true} if this behaviour did something with the click.
     */
    default boolean onWrenchClick(Level level, BlockPos pipePos, @Nullable Direction clickedFace) {
        return false;
    }

    /**
     * The single face that should show the "valve" texture instead of the plain pipe texture, or {@code null}
     * for none - ports {@code PipeBehaviourWood.getTextureData} returning a distinct {@code TEX_FILLED} sprite
     * only on {@code currentDir}. Only Wood/WoodDiamond have one; every other behaviour keeps the default.
     */
    default @Nullable Direction getValveDirection() {
        return null;
    }

    default void save(ValueOutput output) {}

    default void load(ValueInput input) {}

    /**
     * Runs every server tick regardless of whether the pipe currently has any items travelling through it -
     * the hook powered tiers (Wood, Obsidian, ...) use to pull new items/entities in on their own schedule,
     * rather than only reacting to items that are already moving.
     */
    default void tick(Level level, BlockPos pipePos, PipeBlockEntity pipe) {}

    /** Fluid-transport parallel to {@link #tick} - runs every server tick for a {@link FluidPipeBlockEntity},
     * regardless of whether it currently holds any fluid. Only Wood/Diamond-Wood (real FE-powered extraction
     * from an adjacent tank) need this; every other tier's fluid movement is fully handled by
     * {@link FluidPipeBlockEntity}'s own generic flow step. */
    default void fluidTick(Level level, BlockPos pipePos, FluidPipeBlockEntity pipe) {}

    /**
     * Non-null only for behaviours that actually store/consume FE (Wood, Obsidian, ...) - exposed by
     * {@code BuildCraft.registerCapabilities} as this pipe's {@code Capabilities.Energy.BLOCK}, a generic modern
     * substitute for the original's MJ (no Engines module exists yet to produce real MJ - see each such
     * behaviour's own javadoc for this documented deviation).
     */
    default @Nullable EnergyHandler getEnergyHandler() {
        return null;
    }
}
