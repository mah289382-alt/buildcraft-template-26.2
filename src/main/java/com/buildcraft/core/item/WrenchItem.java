package com.buildcraft.core.item;

import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

import com.buildcraft.energy.blockentity.EngineBlockEntity;
import com.buildcraft.transport.blockentity.FluidPipeBlockEntity;
import com.buildcraft.transport.blockentity.PipeBlockEntity;

/**
 * A minimal wrench: right-clicking a pipe with it drives {@link PipeBehaviour#onWrenchClick}, matching the
 * original's {@code EntityUtil.getWrenchHand}/{@code PipeBehaviourDirectional.onPipeActivate} interaction (used
 * by Iron pipes to set/cycle their forced output direction, and eventually other directional tiers).
 * <p>
 * Simplification vs. source: the original distinguishes clicking the pipe's thin central post from clicking one
 * of its connector arms via a custom sub-part raytrace ({@code EnumPipePart}, down to which of the 7 boxes was
 * hit). Reimplementing that precisely needs manual ray/AABB intersection against each connected arm's box, which
 * isn't done yet - plain right-click sets the direction to whichever face was actually clicked (if connectable);
 * sneak + right-click always cycles to the next connected face, standing in for "click the center post."
 */
public class WrenchItem extends Item {
    public WrenchItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        if (level.isClientSide()) {
            return InteractionResult.SUCCESS;
        }
        BlockEntity blockEntity = level.getBlockEntity(context.getClickedPos());
        if (blockEntity instanceof PipeBlockEntity pipe) {
            boolean handled = context.isSecondaryUseActive()
                    ? pipe.onWrenchClick(null)
                    : pipe.onWrenchClick(context.getClickedFace());
            if (handled) {
                return InteractionResult.CONSUME;
            }
            // Real feature, not a source port (replaces source's standalone "Blocker" pluggable item, declined
            // by user in favor of this): a plain (non-sneak) wrench click on a tier that doesn't already consume
            // it (every tier except Iron/Wood's directional click) cycles the pipe's own per-face blocked state
            // instead - doesn't depend on which exact face was clicked at all (see PipeBlockEntity
            // .cycleBlockedFace's javadoc for why: precisely raycasting a specific arm is unreliable). Sneak-click
            // keeps its existing cycle-only meaning (Iron/Wood), unchanged.
            if (!context.isSecondaryUseActive()) {
                pipe.cycleBlockedFace(level);
                return InteractionResult.CONSUME;
            }
        }
        if (blockEntity instanceof FluidPipeBlockEntity fluidPipe) {
            boolean handled = context.isSecondaryUseActive()
                    ? fluidPipe.onWrenchClick(null)
                    : fluidPipe.onWrenchClick(context.getClickedFace());
            if (handled) {
                return InteractionResult.CONSUME;
            }
            if (!context.isSecondaryUseActive()) {
                fluidPipe.cycleBlockedFace(level);
                return InteractionResult.CONSUME;
            }
        }
        if (blockEntity instanceof EngineBlockEntity engine) {
            boolean handled = engine.onWrenchClick(level, context.getClickedPos(), blockEntity.getBlockState());
            if (handled) {
                return InteractionResult.CONSUME;
            }
        }
        return InteractionResult.PASS;
    }
}
