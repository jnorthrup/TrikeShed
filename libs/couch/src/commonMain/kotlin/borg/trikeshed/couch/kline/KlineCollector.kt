package borg.trikeshed.couch.kline

import borg.trikeshed.userspace.concurrency.Channel
import java.util.LinkedList

/**
 * KlineCollector: drains a Channel<Kline> into sealed KlineBlocks of fixed capacity.
 *
 * Donor pattern: TradePairEventMuxer accumulates CandlestickEvents in an LinkedList
 * and flushes to ISAM when episodeCutoff is reached. Here we seal blocks at a
 * configurable capacity and hand them off via callback — no ISAM dependency.
 *
 * Usage:
 * ```
 *   val collector = KlineCollector(blockCapacity = 100)
 *   collector.collect(channel) { sealedBlock ->
 *       // handle sealed block (persist, analyze, etc.)
 *   }
 * ```
 */
class KlineCollector(
   val blockCapacity: Int = 100,
) {
    /**
     * Drain [channel] into sealed blocks, invoking [onBlock] for each sealed block.
     *
     * Blocks are sealed and delivered as soon as they reach [blockCapacity],
     * or when the channel closes (partial final block).
     *
     * Suspends until the channel is closed.
     */
    suspend fun collect(channel: Channel<Kline>, onBlock: (KlineBlock) -> Unit) {
        var block = KlineBlock.mutable()

        while (true) {
            val result = channel.recv()
            val kline = result.getOrNull()
            if (kline == null) {
                // Channel closed — seal and deliver any partial block
                if (block.rowCount > 0) {
                    block.seal()
                    onBlock(block)
                }
                break
            }

            block.append(kline)
            if (block.rowCount >= blockCapacity) {
                block.seal()
                onBlock(block)
                block = KlineBlock.mutable()
            }
        }
    }
}
