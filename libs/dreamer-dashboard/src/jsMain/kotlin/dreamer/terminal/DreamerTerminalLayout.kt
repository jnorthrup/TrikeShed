package dreamer.terminal

/**
 * Horizontal split-pane layout: Dreamer paper validation | bag/span training.
 */
class SplitPane(screen: Screen) {
    val left: Box
    val right: Box

    init {
        val halfWidth = screen.width / 2

        left = box(makeBoxOpts(0, 0, halfWidth, " DREAMER PAPER ", "green"))
        right = box(makeBoxOpts(halfWidth + 1, 0, halfWidth - 2, " DREAMER TRAINING ", "cyan"))

        screen.append(left)
        screen.append(right)
    }

    fun makeBoxOpts(top: Int, _left: Int, width: Int, label: String, borderColor: String): dynamic {
        val opts = js("({})")
        opts.top = top
        opts.left = _left
        opts.width = width
        opts.height = "100%"
        opts.border = js("({ type: 'line' })")
        opts.label = label
        opts.tags = true
        val style = js("({})")
        style.border = js("({ fg: '' })")
        style.border.fg = borderColor
        opts.style = style
        return opts
    }
}

/**
 * Left pane: paper replay measurement from the current champion.
 */
class TradingPane(private val box: Box) {
    val glyphs = arrayOf(
        "{yellow-fg}[CREATED]{/yellow-fg}",
        "{white-fg}[OPEN]{/white-fg}",
        "{green-fg}[ACTIVE]{/green-fg}",
        "{yellow-fg}[DRAINING]{/yellow-fg}",
        "{red-fg}[CLOSED]{/red-fg}",
    )

    fun render(state: DreamerTerminalState) {
        val g = glyphs.getOrElse(state.tradingLifecycle) { "?" }

        box.setContent("""
            $g  |  generation ${state.genomeVersion}
            ------------------------------
            Start cash:   ${state.initialCapital.fmt()}
            Paper value:  ${state.finalTotalValue.fmt()}
            Paper PnL:    ${state.bestPnl.fmt()}
            Drawdown:     ${state.bestDrawdown.percent()}
            Trades:       ${state.totalTrades}
            Cycles:       ${state.totalCycles.fmt()}
            Pairs:        ${state.pairCount}
            Rows/pair:    ${state.rowsPerSeries.fmt()}
            ------------------------------
            Active span:  ${state.activeSpan}
            Pair span:    ${state.activePairSpan}
        """.trimIndent())
    }
}

/**
 * Right pane: stochastic bag/span training and champion parameters.
 */
class TrainingPane(private val box: Box) {
    val glyphs = arrayOf(
        "{yellow-fg}[CREATED]{/yellow-fg}",
        "{white-fg}[OPEN]{/white-fg}",
        "{cyan-fg}[ACTIVE]{/cyan-fg}",
        "{yellow-fg}[DRAINING]{/yellow-fg}",
        "{red-fg}[CLOSED]{/red-fg}",
    )

    fun render(state: DreamerTerminalState) {
        val g = glyphs.getOrElse(state.trainingLifecycle) { "?" }

        box.setContent("""
            $g  |  bars ${state.barsReplayed.fmt()}
            ------------------------------
            Population:  ${state.populationSize}
            Fitness:     ${state.bestFitness.num()}
            Genome:      v${state.genomeVersion}
            Take:        ${state.genomeTakePercent.num()}
            Min surplus: ${state.genomeMinSurplus.num()}
            Rebalance:   ${state.genomeRebalanceTrigger.num()}
            ------------------------------
            Feed:        ${state.feed}
            Pairs:       ${state.pairList}
            Windows:     ${state.totalWindows.fmt()}
            Spans:       ${state.totalSpans.fmt()}
            Strategy:    Dreamer 1.2 paper
        """.trimIndent())
    }
}

// Shared factory for list options
fun makeListOpts(top: Int, height: String): dynamic {
    val opts = js("({})")
    opts.top = top
    opts.left = 1
    opts.width = "100%-2"
    opts.height = height
    opts.tags = true
    opts.scrollable = true
    opts.mouse = false
    opts.style = js("({ selected: { bg: 'blue' }, item: { fg: 'white' } })")
    return opts
}
