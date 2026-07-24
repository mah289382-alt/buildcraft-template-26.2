package com.buildcraft.transport.pipe;

/**
 * A lightweight marker a {@code BlockEntity} implements to say "pipes should visually connect to me," independent
 * of whether it actually exposes an item-transfer capability. This is the modern, decoupled equivalent of the
 * original's {@code ItemTransactorHelper.getTransactor(oTile, ...) != NoSpaceTransactor.INSTANCE} connection
 * check - the original's transactor abstraction covered "produces/accepts items in some way," which doesn't map
 * 1:1 onto the modern transfer API's item-storage-shaped {@code ResourceHandler<ItemResource>}.
 * <p>
 * The Quarry is the motivating case: it never buffers items (it pushes mined drops directly via its own
 * neighbor-capability lookup the instant a block breaks - see {@code QuarryBlockEntity.routeDrop}), so it has no
 * real item storage to expose as a {@code ResourceHandler}. But a pipe placed next to it should still visually
 * connect and be a valid target for that push. Actual item transport into a pipe goes through
 * {@code Capabilities.Item.BLOCK} (which {@code PipeBlockEntity} exposes) - this interface only governs whether
 * {@link com.buildcraft.transport.block.PipeBlock#isConnectable} draws a connector arm toward a neighbor.
 */
public interface PipeConnectable {
}
