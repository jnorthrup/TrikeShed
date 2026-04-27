package dreamer.dashboard

/**
 * Horizontal split-pane layout: left (trading) | right (training).
 * Both are blessed Box containers consuming half the terminal width.
 */
class SplitPane(screen: Screen) {
    val left: Box
    val right: Box

    init {
        val halfWidth = screen.width / 2

        left = Box.box(js("({ top: 0, left: 0, width: $halfWidth, height: '100%'," +
            " border: { type: 'line' }, label: ' TRADING '," +
            " tags: true, style: { border: { fg: 'green' } } })"))

        right = Box.box(js("({ top: 0, left: ${halfWidth + 1}, width: ${halfWidth - 2}, height: '100%'," +
            " border: { type: 'line' }, label: ' TRAINING '," +
            " tags: true, style: { border: { fg: 'cyan' } } })"))

        screen.append(left)
        screen.append(right)
    }
}

/**
 * Left pane — Robinhood live trading status.
 */
class TradingPane(private val box: Box) {
    private val log: BlessedList
    private val glyphs = arrayOf(
        "{yellow-fg}◌ CREATED{/yellow-fg}",
        "{white-fg}○ OPEN{/white-fg}",
        "{green-fg}◉ ACTIVE{/green-fg}",
        "{yellow-fg}◐ DRAINING{/yellow-fg}",
        "{red-fg}● CLOSED{/red-fg}",
    )

    init {
        log = BlessedList.list(js("({ top: 11, left: 1, width: '100%-2', height: '100%-12'," +
            " tags: true, scrollable: true, mouse: true," +
            " style: { selected: { bg: 'blue' }, item: { fg: 'white' } } })"))
        box.append(log)
    }

    fun render(state: DashboardState) {
        val g = glyphs.getOrElse(state.tradingLifecycle) { "?" }
        val holdingsTotal = state.holdings.entries.sumOf { (sym, qty) ->
            qty * (state.prices[sym] ?: 0.0)
        }
        val totalValue = state.cashBalance + holdingsTotal

        val htable = state.holdings.entries.joinToString("\n") { (sym, qty) ->
            val px = state.prices[sym] ?: 0.0
            "  $sym  ${qty.fmt(4)} @ ${px.fmt(0)} = ${(qty * px).fmt()}"
        }

        box.setContent("""
            $g  |  total ${totalValue.fmt()}
            ──────────────────────────────
            Balance:      ${state.cashBalance.fmt()}
            Holdings:     ${state.holdings.size} assets (${holdingsTotal.fmt()})
            Harvested:    ${state.totalHarvested.fmt()}
            Trades:       ${state.totalTrades}
            Drawdown:     ${(state.maxDrawdownPercent * 100).fmt(1)}%
            ──────────────────────────────
$htable
        """.trimIndent())

        while (state.tradeLogRendered < state.tradeLog.size) {
            log.addItem("{green-fg}TRADE{/green-fg} ${state.tradeLog[state.tradeLogRendered]}")
            state.tradeLogRendered++
        }
        log.scroll(1)
    }
}

/**
 * Right pane — Binance training + genome optimization status.
 */
class TrainingPane(private val box: Box) {
    private val log: BlessedList
    private val glyphs = arrayOf(
        "{yellow-fg}◌ CREATED{/yellow-fg}",
        "{white-fg}○ OPEN{/white-fg}",
        "{cyan-fg}◉ ACTIVE{/cyan-fg}",
        "{yellow-fg}◐ DRAINING{/yellow-fg}",
        "{red-fg}● CLOSED{/red-fg}",
    )

    init {
        log = BlessedList.list(js("({ top: 11, left: 1, width: '100%-2', height: '100%-12'," +
            " tags: true, scrollable: true, mouse: true," +
            " style: { selected: { bg: 'blue' }, item: { fg: 'white' } } })"))
        box.append(log)
    }

    fun render(state: DashboardState) {
        val g = glyphs.getOrElse(state.trainingLifecycle) { "?" }

        box.setContent("""
            $g  |  bars ${state.barsReplayed.fmt()}
            ──────────────────────────────
            Genome:      v${state.genomeVersion}
            Take %:      ${state.genomeTakePercent}%
            Best PnL:    ${state.bestPnl.fmt()}
            ──────────────────────────────
            ISAM:        zstd blocks (RED)
            Cursor:      in-memory fallback
            Strategy:    harvest+rebalance
        """.trimIndent())

        while (state.trainingLogRendered < state.trainingLog.size) {
            log.addItem("{cyan-fg}GENOME{/cyan-fg} ${state.trainingLog[state.trainingLogRendered]}")
            state.trainingLogRendered++
        }
        log.scroll(1)
    }
}
