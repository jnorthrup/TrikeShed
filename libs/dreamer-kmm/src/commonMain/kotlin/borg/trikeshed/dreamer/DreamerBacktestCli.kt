package borg.trikeshed.dreamer

data class DreamerBacktestArgs(
    val csvPath: String,
    val symbol: String,
    val timespan: TimeSpan,
    val initialCapital: Double = 10_000.0,
)

private val dreamerBacktestOptionNames = setOf(
    "--csv",
    "--input",
    "--file",
    "--symbol",
    "--timespan",
    "--interval",
    "--initial-capital",
    "--capital",
    "-h",
    "--help",
)

private fun optionValue(rawArgs: List<String>, index: Int, flag: String): String {
    val value = rawArgs.getOrNull(index + 1)
        ?: throw IllegalArgumentException("Missing value for $flag\n${dreamerBacktestUsage()}")
    if (value in dreamerBacktestOptionNames) {
        throw IllegalArgumentException("Missing value for $flag\n${dreamerBacktestUsage()}")
    }
    return value
}

fun parseDreamerBacktestTimespan(value: String): TimeSpan {
    val trimmed = value.trim()
    TimeSpan.values().firstOrNull { it.binanceInterval == trimmed }?.let { return it }
    TimeSpan.values().firstOrNull { it.name.equals(trimmed, ignoreCase = true) }?.let { return it }
    TimeSpan.values().firstOrNull { it.binanceInterval.equals(trimmed, ignoreCase = true) }?.let { return it }

    throw IllegalArgumentException(
        "Unknown timespan '$value'. Expected one of: " +
            TimeSpan.values().joinToString { "${it.name}(${it.binanceInterval})" },
    )
}

fun parseDreamerBacktestArgs(rawArgs: List<String>): DreamerBacktestArgs {
    var csvPath: String? = null
    var symbol: String? = null
    var timespan: TimeSpan? = null
    var initialCapital = 10_000.0
    var initialCapitalSet = false
    val positionals = mutableListOf<String>()

    var index = 0
    while (index < rawArgs.size) {
        when (val arg = rawArgs[index]) {
            "--csv", "--input", "--file" -> {
                csvPath = optionValue(rawArgs, index, arg)
                index++
            }

            "--symbol" -> {
                symbol = optionValue(rawArgs, index, arg)
                index++
            }

            "--timespan", "--interval" -> {
                timespan = parseDreamerBacktestTimespan(optionValue(rawArgs, index, arg))
                index++
            }

            "--initial-capital", "--capital" -> {
                val value = optionValue(rawArgs, index, arg)
                initialCapital = value.toDouble()
                initialCapitalSet = true
                index++
            }

            "-h", "--help" -> throw IllegalArgumentException(dreamerBacktestUsage())

            else -> {
                if (arg.startsWith("-")) {
                    throw IllegalArgumentException("Unknown argument '$arg'\n${dreamerBacktestUsage()}")
                }
                positionals += arg
            }
        }
        index++
    }

    if (csvPath == null && positionals.isNotEmpty()) csvPath = positionals[0]
    if (symbol == null && positionals.size > 1) symbol = positionals[1]
    if (timespan == null && positionals.size > 2) timespan = parseDreamerBacktestTimespan(positionals[2])
    if (!initialCapitalSet && positionals.size > 3) initialCapital = positionals[3].toDouble()

    val resolvedCsvPath = csvPath ?: throw IllegalArgumentException("Missing CSV path.\n${dreamerBacktestUsage()}")
    val resolvedSymbol = symbol ?: throw IllegalArgumentException("Missing symbol.\n${dreamerBacktestUsage()}")
    val resolvedTimespan = timespan ?: throw IllegalArgumentException("Missing timespan.\n${dreamerBacktestUsage()}")

    return DreamerBacktestArgs(
        csvPath = resolvedCsvPath,
        symbol = resolvedSymbol,
        timespan = resolvedTimespan,
        initialCapital = initialCapital,
    )
}

fun dreamerBacktestUsage(): String = """
Usage:
  dreamer-backtest --csv <path> --symbol <symbol> --timespan <1h|Hours1> [--initial-capital <value>]
  dreamer-backtest <path> <symbol> <timespan> [initialCapital]

Examples:
  dreamer-backtest --csv data.csv --symbol BTCUSDT --timespan 1h
  dreamer-backtest data.csv BTCUSDT Hours1 10000
""".trimIndent()

fun runDreamerBacktest(csvText: String, args: DreamerBacktestArgs, genome: Genome = defaultGenome()): BacktestReport =
    SimulationReplay(
        genome = genome,
        mode = Mode.SHADOW,
        initialCapital = args.initialCapital,
    ).toBacktestReport(
        csvText = csvText,
        symbol = args.symbol,
        timespan = args.timespan,
    )

private fun Double.renderCli(): String =
    if (isFinite() && this % 1.0 == 0.0) "${toLong()}.0" else toString()

fun formatDreamerBacktestReport(args: DreamerBacktestArgs, report: BacktestReport): String = buildString {
    appendLine("dreamer backtest")
    appendLine("csvPath=${args.csvPath}")
    appendLine("symbol=${report.symbol}")
    appendLine("timespan=${args.timespan.name} (${args.timespan.binanceInterval})")
    appendLine("initialCapital=${report.initialCapital.renderCli()}")
    appendLine("finalEquity=${report.finalEquity.renderCli()}")
    appendLine("totalReturn=${report.totalReturn.renderCli()}")
    appendLine("sharpeRatio=${report.sharpeRatio.renderCli()}")
    appendLine("sortinoRatio=${report.sortinoRatio.renderCli()}")
    appendLine("maxDrawdown=${report.maxDrawdown.renderCli()}")
    appendLine("maxDrawdownTicks=${report.maxDrawdownTicks}")
    appendLine("totalTrades=${report.totalTrades}")
    appendLine("totalHarvested=${report.totalHarvested.renderCli()}")
    appendLine("totalTicks=${report.totalTicks}")
}
