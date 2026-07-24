package com.buildcraft.transport.pipe;

/**
 * Marker for an {@code EnergyHandler} that should only be pushed power once per full piston stroke, not
 * continuously every tick - ports {@code TileEngineBase_BC8.update()}'s real
 * {@code pulsedPower = receiver instanceof IMjRedstoneReceiver} distinction (source: only Wood-family pipes
 * implement that interface; a generic {@code IMjReceiver} like the Quarry gets continuous power every tick).
 * Without this, an engine dumps its entire per-tick output into a Wood pipe's buffer continuously, which then
 * drains at 1 item/tick as fast as the buffer allows - looking like "one pump extracts dozens of items at once"
 * instead of the real "roughly one pulse's worth per full piston stroke" pacing.
 */
public interface PulsedEnergyReceiver {
}
