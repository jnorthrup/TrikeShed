Design: Single canonical architecture (combined from RED tests)

Goal
----
Converge the RED-test-driven "empty-space" specifications into a single, coherent architecture where one module owns the algebra and low-level primitives and the domain module implements the trading behaviors that the RED tests specify.

High-level architecture
-----------------------
- Core algebra & block primitives (canonical): libs/couch
  - Join/Series/RowVec/Cursor abstractions
  - MiniDuck: MiniCursor, DocRowVec, KlineBlock, sealing semantics
  - Numeric kernels: borg.trikeshed.couch.finance (mean, variance, stdDev, ema, windowSum)
  - Small adapters: toPriceCursor(), doubleSeries()

- Domain layer (consumer): libs/dreamer-kmm
  - SimWallet, WalletMath (realized/unrealized PnL, caching), MuxIo/Pancake transforms, KlineMuxer/KlineStreamer, DreamerKernelTransformer, Genome/optimizer/harness
  - Re-exports or uses numeric kernels from libs/couch; contains domain-only kernels (sparDrag, portfolioDeviation, reinvestScore) if they are strictly domain-specific.

- Glue/adapters
  - Minimal adapters in dreamer-kmm that convert DoubleArray<T> ↔ MiniCursor and call couch.finance primitives.

APIs (minimal, test-driven signatures)
--------------------------------------
- SimWallet (dreamer.wallet)
  - data class Order(val id: String, val base: String, val quote: String, val side: OrderSide, val type: OrderType, val price: Double, val quantity: Double, val placedAt: Long)
  - fun record(symbol: String, quantity: Double, costBasis: Double = 0.0)
  - fun placeOrder(base:String, quote:String, side: OrderSide, type: OrderType, price: Double, quantity: Double): Order?
  - fun cancelOrder(orderId: String)
  - fun processBar(symbol: String, high: Double, low: Double, close: Double): List<FilledOrder>
  - fun pendingOrders(symbol: String? = null): List<Order>
  - fun freeBalance(symbol: String): Double
  - fun lockedBalance(symbol: String): Double
  - fun netQuantity(symbol: String): Double
  - fun realizedPnl(symbol: String): Double
  - fun unrealizedPnl(symbol: String, prices: Map<String,Double>): Double
  - fun worth(prices: Map<String,Double>): Double
  - fun peakNetValue(): Double
  - fun autoDrawdown(): Double
  - fun reset()

- MuxIo / Pancake (dreamer.stream)
  - fun extractOhlcvSlice(cursor: MiniCursor): MiniCursor
  - fun horizonWindow(cursor: MiniCursor, rowIndex: Int, width: Int): MiniCursor
  - fun pancake(cursor: MiniCursor): MiniCursor
  - fun slidingPancake(cursor: MiniCursor, windowSize: Int): MiniCursor
  - fun assembleRow(cursor: MiniCursor, wallet: WalletState, horizonWidth: Int, rowIndex: Int): DocRowVec

- KlineMuxer / KlineStreamer (dreamer.stream)
  - class KlineMuxer(symbol: String, episodeSize: Int) {
      var onOverflow: ((KlineBlock) -> Unit)?
      val episodeCount: Int
      val intraCount: Int
      fun push(kline: Kline, isBarFinal: Boolean = true)
      fun realtime(): MiniCursor
    }
  - object KlineStreamer { suspend fun drive(ch: Channel<Kline>, muxer: KlineMuxer) }

- Dreamer kernel harness
  - class DreamerKernelTransformer { fun transform(cursor: MiniCursor, params: Map<String, Any>): MiniCursor; fun prescriptiveRegimePolicy(params: Map<String,Any>): PrescriptivePolicy }
  - fun executeDreamerKernelHarness(symbols: List<String>, timeframes: List<String>, searchSpace: List<Map<String,Any>>, cacheProvider: (String,String) -> MiniCursor): List<Policy>

- evaluateGenome extensions
  - fun List<Tick>.evaluateGenome(symbol: String, genome: Genome, depthMode: DepthMode): SimulationResult
  - fun MiniCursor.evaluateGenome(symbol: String, genome: Genome, depthMode: DepthMode): SimulationResult

File-by-file quick map (high-value)
-----------------------------------
- libs/couch/src/commonMain/kotlin/borg/trikeshed/couch/miniduck/* → canonical MiniDuck + DocRowVec + Cursor adaptors
- libs/couch/src/commonMain/kotlin/borg/trikeshed/couch/finance/DoubleArrayMath.kt → canonical numeric kernels
- libs/couch/src/commonMain/kotlin/borg/trikeshed/couch/finance/MiniCursorFinanceExtensions.kt → adapters (toPriceCursor, doubleSeries)
- libs/dreamer-kmm/src/commonMain/kotlin/dreamer/* → domain code (SimWallet, stream, wallet, transformer, exchange)
- libs/dreamer-kmm/src/commonTest/kotlin/dreamer/* → RED tests driving domain API shapes

Prioritized implementation plan
-------------------------------
1. Centralize algebra (libs/couch) — ensure minimal public API and docs (OWNER.md)
2. Dedupe numeric kernels — keep couch.finance as canonical; have dreamer re-export domain-specific helpers
3. Implement SimWallet core APIs (placeOrder, processBar, locks) and small test-driven loop to pass SimWalletTest & WalletLayoutTest
4. Implement Wallet financials: realized/unrealized PnL, worth caching, peak tracking
5. Implement MuxIo & Pancake transforms in dreamer-kmm (extractOhlcvSlice, pancake, slidingPancake, assembleRow)
6. Implement KlineMuxer/KlineStreamer and end-to-end streaming pipeline tests
7. Implement DreamerKernelTransformer and harness (search space executor)
8. Implement evaluateGenome adapter functions

Verification
------------
- Run targeted unit tests in libs/dreamer-kmm after each step (start with SimWallet & WalletMath tests)
- Keep changes surgical; when moving code, update imports and add small adapters to avoid large refactors

Assumptions
-----------
- RED tests in dreamer-kmm are authoritative specifications for domain behavior.
- libs/couch is the correct canonical owner for block/row algebra and numeric kernels.
- Backwards compatibility: prefer small adapters to preserve existing call sites.

Next steps (this repo)
----------------------
- Implement SimWallet core (first code change). Write minimal implementation to satisfy RED tests and iterate.
- After SimWallet, proceed to Wallet financials, then MuxIo/Pancake.

