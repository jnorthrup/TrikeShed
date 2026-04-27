package dreamer.dashboard

/** Number formatting for Kotlin/JS (no String.format). */
fun Double.fmt(d: Int = 2): String {
    val fixed = this.toFixed(d)
    // Add commas: 12345.67 → 12,345.67
    val parts = fixed.split('.')
    val intPart = parts[0].reversed().chunked(3).joinToString(",").reversed()
    return if (parts.size > 1) "$$intPart.${parts[1]}" else "$$intPart"
}

fun Long.fmt(): String {
    val s = this.toString().reversed().chunked(3).joinToString(",").reversed()
    return s
}

// Kotlin/JS polyfill for toFixed
fun Double.toFixed(digits: Int): String {
    val mult = when (digits) {
        0 -> 1.0; 1 -> 10.0; 2 -> 100.0; 3 -> 1000.0; else -> 1.0
    }
    val rounded = kotlin.math.round(this * mult) / mult
    val str = rounded.toString()
    val dot = str.indexOf('.')
    return if (dot < 0) "$str.${"0".repeat(digits)}"
    else {
        val decimals = str.substring(dot + 1)
        if (decimals.length >= digits) str.substring(0, dot + 1 + digits)
        else "$str${"0".repeat(digits - decimals.length)}"
    }
}

/**
 * Self-contained demo state for the dashboard.
 * Simulates Robinhood trading + Binance training without
 * requiring dreamer-kmm's engine types to exist yet.
 *
 * When dreamer-kmm's SharedEngineState/TradingSupervisorJob
 * are implemented, swap this out for the real thing.
 */
class DashboardState {
    // ── Trading (left pane) ────────────────────────────────────────────
    var cashBalance: Double = 12345.67
    val holdings = mutableMapOf(
        "BTC" to 0.42, "ETH" to 2.10, "SOL" to 15.0
    )
    val prices = mutableMapOf(
        "BTC" to 69100.0, "ETH" to 3820.0, "SOL" to 142.0
    )
    var totalTrades: Int = 0
    var totalHarvested: Double = 0.0
    var maxDrawdownPercent: Double = 0.0
    var tradingLifecycle: Int = 2  // 2 = ACTIVE
    val tradeLog = mutableListOf<String>()
    var tradeLogRendered: Int = 0

    // ── Training (right pane) ──────────────────────────────────────────
    var barsReplayed: Long = 0L
    var genomeTakePercent: Int = 70
    var genomeVersion: Int = 1
    var bestPnl: Double = 0.0
    var trainingLifecycle: Int = 2  // 2 = ACTIVE
    val trainingLog = mutableListOf<String>()
    var trainingLogRendered: Int = 0

    // ── Simulation tick ────────────────────────────────────────────────
    fun tick() {
        if (kotlin.random.Random.nextDouble() < 0.3) {
            totalTrades++
            val harvested = kotlin.random.Random.nextDouble() * 50 + 10
            totalHarvested += harvested
            cashBalance += harvested - kotlin.random.Random.nextDouble() * 5
            tradeLog.add("cycle=$totalTrades harvested=${harvested.fmt()} cash=${cashBalance.fmt()}")
            if (tradeLog.size > 50) tradeLog.removeAt(0)
        }

        for ((sym, price) in prices.toMap()) {
            prices[sym] = price * (1.0 + (kotlin.random.Random.nextDouble() - 0.5) * 0.002)
        }

        barsReplayed += (100..500).random().toLong()
        if (kotlin.random.Random.nextDouble() < 0.15) {
            genomeVersion++
            genomeTakePercent = (65..95).random()
            bestPnl += kotlin.random.Random.nextDouble() * 100
            trainingLog.add("genome_v$genomeVersion take=${genomeTakePercent}% pnl=${bestPnl.fmt()} bars=${barsReplayed.fmt()}")
            if (trainingLog.size > 50) trainingLog.removeAt(0)
        }
    }
}
