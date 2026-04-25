package borg.trikeshed.couch.kline

import borg.trikeshed.couch.miniduck.*
import borg.trikeshed.lib.*
import borg.trikeshed.userspace.concurrency.Channel
import borg.trikeshed.userspace.concurrency.ChannelCapacity
import kotlin.test.*
import kotlinx.coroutines.*

/**
 * Red test: Continuous kline (OHLCV candle) production → channel → BlockRowVec → MiniCursor.
 *
 * The feature under test: a streaming pipeline where any number of klines
 * at any timespan are produced into a channelized observable, which feeds
 * the MiniDuck dataframe abstraction as a continuous series of sealed blocks.
 *
 * Will fail to compile until the following types exist:
 *   - borg.trikeshed.couch.kline.Kline  (OHLCV candle)
 *   - borg.trikeshed.couch.kline.KlineBlock  (sealed block of klines as MiniCursor)
 *   - borg.trikeshed.couch.kline.KlineProducer  (emits Kline into Channel)
 *   - borg.trikeshed.couch.kline.KlineCollector  (drains Channel into KlineBlocks)
 *
 * Donor patterns:
 *   - EC copy-on-write → KlineBlock sealing (BlockRowVec pattern)
 *   - go-stopper channel semaphore → backpressure on producer
 *   - Pony "destructive read" → seal() as irreversible handoff
 *   - BlockRowVec.append/seal → each block is a chunk of rows
 */

class KlineStreamTest {

    // ── 1. A single kline round-trips through a channel ──────────────────

    @Test
    fun singleKlineThroughChannel() = runBlocking {
        val ch: Channel<Kline> = Channel.buffered(64)
        val kline = Kline(
            symbol = "BTC-USD",
            timespan = TimeSpan.Minutes1,
            openTime = 1714000000L,
            open = 65000.0,
            high = 65100.0,
            low = 64900.0,
            close = 65050.0,
            volume = 123.45
        )

        ch.send(kline)
        ch.close()

        val received = ch.recv().getOrNull()
        assertNotNull(received)
        assertEquals("BTC-USD", received.symbol)
        assertEquals(65000.0, received.open)
        assertEquals(65100.0, received.high)
        assertEquals(64900.0, received.low)
        assertEquals(65050.0, received.close)
        assertEquals(123.45, received.volume)
        assertEquals(TimeSpan.Minutes1, received.timespan)
    }

    // ── 2. Kline converts to DocRowVec with standard OHLCV keys ─────────

    @Test
    fun klineToDocRowVec() {
        val kline = Kline(
            symbol = "ETH-USD",
            timespan = TimeSpan.Minutes5,
            openTime = 1714000000L,
            open = 3400.0,
            high = 3420.0,
            low = 3390.0,
            close = 3410.0,
            volume = 456.0
        )

        val row: DocRowVec = kline.toDocRowVec()
        assertEquals(8, row.size)
        assertEquals("ETH-USD", row["symbol"])
        assertEquals(TimeSpan.Minutes5, row["timespan"])
        assertEquals(1714000000L, row["openTime"])
        assertEquals(3400.0, row["open"])
        assertEquals(3420.0, row["high"])
        assertEquals(3390.0, row["low"])
        assertEquals(3410.0, row["close"])
        assertEquals(456.0, row["volume"])
    }

    // ── 3. KlineBlock seals and presents as MiniCursor ───────────────────

    @Test
    fun klineBlockSealsAsCursor() {
        val block = KlineBlock.mutable()
        for (i in 0 until 5) {
            block.append(Kline(
                symbol = "BTC-USD",
                timespan = TimeSpan.Minutes1,
                openTime = 1714000000L + i * 60,
                open = 65000.0 + i,
                high = 65100.0 + i,
                low = 64900.0 + i,
                close = 65050.0 + i,
                volume = 100.0 + i
            ))
        }

        assertEquals(5, block.rowCount)
        assertEquals(KlineBlock.State.MUTABLE, block.state)

        val sealed = block.seal()
        assertEquals(KlineBlock.State.SEALED, sealed.state)

        // Present as MiniCursor
        val cursor: MiniCursor = sealed.asCursor()
        assertEquals(5, cursor.size)

        // First row
        val first = cursor at 0
        assertEquals("BTC-USD", (first as DocRowVec)["symbol"])
        assertEquals(65000.0, first["open"])

        // Last row
        val last = cursor at -1
        assertEquals(65004.0, (last as DocRowVec)["open"])
    }

    // ── 4. Sealed block rejects further appends ──────────────────────────

    @Test
    fun sealedBlockRejectsAppend() {
        val block = KlineBlock.mutable()
        block.append(Kline(
            symbol = "BTC-USD",
            timespan = TimeSpan.Minutes1,
            openTime = 0L,
            open = 1.0, high = 2.0, low = 0.5, close = 1.5, volume = 10.0
        ))
        block.seal()

        assertFailsWith<IllegalStateException> {
            block.append(Kline(
                symbol = "BTC-USD",
                timespan = TimeSpan.Minutes1,
                openTime = 1L,
                open = 2.0, high = 3.0, low = 1.5, close = 2.5, volume = 20.0
            ))
        }
    }

    // ── 5. Collector drains channel into sealed blocks of fixed capacity ─

    @Test
    fun collectorDrainsIntoBlocks() = runBlocking {
        val ch: Channel<Kline> = Channel.buffered(128)
        val collector = KlineCollector(blockCapacity = 3)

        // Launch collector in background — it drains channel into blocks
        val blocks = mutableListOf<KlineBlock>()
        val collectorJob = launch(Dispatchers.Default) {
            collector.collect(ch) { block ->
                blocks.add(block)
            }
        }

        // Produce 10 klines
        for (i in 0 until 10) {
            ch.send(Kline(
                symbol = "BTC-USD",
                timespan = TimeSpan.Minutes1,
                openTime = i.toLong() * 60,
                open = 65000.0 + i,
                high = 65100.0 + i,
                low = 64900.0 + i,
                close = 65050.0 + i,
                volume = 100.0
            ))
        }
        ch.close()

        collectorJob.join()

        // 10 klines / blockCapacity=3 → 4 blocks (3, 3, 3, 1)
        assertEquals(4, blocks.size)
        blocks.forEach { assertEquals(KlineBlock.State.SEALED, it.state) }
        assertEquals(3, blocks[0].rowCount)
        assertEquals(3, blocks[1].rowCount)
        assertEquals(3, blocks[2].rowCount)
        assertEquals(1, blocks[3].rowCount)
    }

    // ── 6. Mixed timespan rejection — block is single-timespan ───────────

    @Test
    fun blockRejectsMixedTimespan() {
        val block = KlineBlock.mutable(TimeSpan.Minutes1)
        block.append(Kline(
            symbol = "BTC-USD",
            timespan = TimeSpan.Minutes1,
            openTime = 0L,
            open = 1.0, high = 2.0, low = 0.5, close = 1.5, volume = 10.0
        ))

        assertFailsWith<IllegalArgumentException> {
            block.append(Kline(
                symbol = "BTC-USD",
                timespan = TimeSpan.Minutes5,
                openTime = 60L,
                open = 2.0, high = 3.0, low = 1.5, close = 2.5, volume = 20.0
            ))
        }
    }

    // ── 7. Continuous production — blocks emitted as stream ──────────────

    @Test
    fun continuousProductionStreamsMultipleBlocks() = runBlocking {
        val ch: Channel<Kline> = Channel.buffered(256)
        val collector = KlineCollector(blockCapacity = 4)

        val blocks = mutableListOf<KlineBlock>()
        val collectorJob = launch(Dispatchers.Default) {
            collector.collect(ch) { block ->
                blocks.add(block)
            }
        }

        // Produce klines in two bursts with a gap (simulating exchange WebSocket)
        for (i in 0 until 6) {
            ch.send(Kline(
                symbol = "BTC-USD",
                timespan = TimeSpan.Seconds30,
                openTime = i.toLong() * 30,
                open = 65000.0 + i,
                high = 65100.0 + i,
                low = 64900.0 + i,
                close = 65050.0 + i,
                volume = 50.0 + i
            ))
        }
        // gap — no klines for a while
        delay(50)
        for (i in 6 until 10) {
            ch.send(Kline(
                symbol = "BTC-USD",
                timespan = TimeSpan.Seconds30,
                openTime = i.toLong() * 30,
                open = 65000.0 + i,
                high = 65100.0 + i,
                low = 64900.0 + i,
                close = 65050.0 + i,
                volume = 50.0 + i
            ))
        }
        ch.close()

        collectorJob.join()

        // 10 klines / blockCapacity=4 → 3 blocks (4, 4, 2)
        assertEquals(3, blocks.size)
        assertEquals(4, blocks[0].rowCount)
        assertEquals(4, blocks[1].rowCount)
        assertEquals(2, blocks[2].rowCount)

        // Verify ordering across blocks
        val cursor0: MiniCursor = blocks[0].asCursor()
        val cursor2: MiniCursor = blocks[2].asCursor()
        assertEquals(0L, (cursor0.at(0) as DocRowVec)["openTime"])
        assertEquals(3L * 30, (cursor0.at(-1) as DocRowVec)["openTime"])
        assertEquals(8L * 30, (cursor2.at(0) as DocRowVec)["openTime"])
        assertEquals(9L * 30, (cursor2.at(-1) as DocRowVec)["openTime"])
    }

    // ── 8. Empty production yields no blocks ─────────────────────────────

    @Test
    fun emptyProductionYieldsNoBlocks() = runBlocking {
        val ch: Channel<Kline> = Channel.buffered(16)
        val collector = KlineCollector(blockCapacity = 4)

        val blocks = mutableListOf<KlineBlock>()
        val collectorJob = launch(Dispatchers.Default) {
            collector.collect(ch) { block -> blocks.add(block) }
        }

        ch.close()  // no klines sent
        collectorJob.join()

        assertEquals(0, blocks.size)
    }

    // ── 9. KlineBlock cursor is a proper Series — supports projections ───

    @Test
    fun cursorSupportsProjection() {
        val block = KlineBlock.mutable()
        for (i in 0 until 4) {
            block.append(Kline(
                symbol = "BTC-USD",
                timespan = TimeSpan.Minutes1,
                openTime = i.toLong() * 60,
                open = 65000.0 + i,
                high = 65100.0 + i,
                low = 64900.0 + i,
                close = 65050.0 + i,
                volume = 100.0
            ))
        }
        block.seal()

        val cursor: MiniCursor = block.asCursor()

        // Project close prices as a Series<Double>
        val closes: Series<Double> = cursor.size j { i ->
            val row = cursor.at(i) as DocRowVec
            row["close"] as Double
        }
        assertEquals(4, closes.size)
        assertEquals(65050.0, closes[0])
        assertEquals(65053.0, closes[3])

        // Project to DoubleArray for finance math
        val closeArr = DoubleArray(closes.size) { closes[it] }
        assertEquals(65050.0, closeArr[0])
    }
}
