package borg.trikeshed.dreamer


enum class Mode { LIVE, SHADOW }

// Minimal models for standalone testing

data class PortfolioRow(
    val Symbol: String,
    val Quantity: Double,
    val Price: Double,
    val Value: Double,
    val Baseline: Double? = null,
    val usdValueNum: Double = Value
)

data class Holding(var rawQuantity: Double)

data class EngineResult(
    val anyTradesThisCycle: Boolean,
    val harvestedAmount: Double,
    val tradedSymbols: List<String> = emptyList(),
    val postMortemEvents: List<Any?> = emptyList(),
    val killMe: Boolean = false,
    val stateChanged: Boolean = false
)

class Genome(val backing: MutableMap<String, Any?> = mutableMapOf()) {
    operator fun get(key: String): Any? = backing[key]
    operator fun set(key: String, value: Any?) { backing[key] = value }

    fun getDouble(key: String, default: Double = 0.0): Double {
        val v = backing[key] ?: return default
        return when (v) {
            is Number -> v.toDouble()
            is String -> v.toDoubleOrNull() ?: default
            else -> default
        }
    }

    fun getBoolean(key: String, default: Boolean = false): Boolean {
        val v = backing[key] ?: return default
        return when (v) {
            is Boolean -> v
            is String -> v.equals("true", ignoreCase = true)
            is Number -> v.toInt() != 0
            else -> default
        }
    }

    /**
     * Safely look up per-symbol parameter overrides.
     *
     * Tolerates any [Map] subtype for the outer overrides map — including
     * [Map] implementations returned by JSON parsers (e.g. [LinkedHashMap]) that
     * are not [MutableMap].  The inner symbol-level map is also accessed via
     * safe cast, so no [ClassCastException] can propagate from this method.
     */
    fun overridesFor(symbol: String): MutableMap<String, Any?>? {
        val overrides = backing["overrides"] ?: return null
        // Safe cast: tolerate any Map subtype for outer (type erasure makes cast always succeed at runtime)
        @Suppress("UNCHECKED_CAST")
        val outer = overrides as? Map<String, Map<String, Any?>> ?: return null
        val inner = outer[symbol] ?: return null
        // Safe cast: return value of Map.get() is Map<String, Any?>, not MutableMap
        // Coerce to MutableMap to satisfy return type while preserving read access
        @Suppress("UNCHECKED_CAST")
        return inner as? MutableMap<String, Any?> ?: HashMap(inner)
    }
}

fun defaultGenome(): Genome {
    val g = Genome(mutableMapOf())
    g["FLAT_HARVEST_TRIGGER_PERCENT"] = 0.035
    g["FLAT_REBALANCE_TRIGGER_PERCENT"] = 0.035
    g["HARVEST_TAKE_PERCENT"] = 0.70
    g["FORCED_HARVEST_TIMEOUT"] = 45 * 60 * 1000
    g["REFRESH_INTERVAL"] = 8000
    g["ENABLE_PORTFOLIO_HARVEST"] = true
    g["MIN_ASSET_SURPLUS_FOR_PORTFOLIO_HARVEST"] = 0.10
    g["MIN_SURPLUS_FOR_HARVEST"] = 0.25
    g["MIN_SURPLUS_FOR_FORCED_HARVEST"] = 1.00
    g["MIN_PARTIAL_REBALANCE_USD"] = 0.25
    g["MAX_REBALANCE_ATTEMPTS"] = 3
    g["REBALANCE_COOLDOWN"] = 30 * 60 * 1000
    g["CRASH_FUND_THRESHOLD_PERCENT"] = 0.10
    g["ENABLE_CRASH_PROTECTION"] = true
    g["CP_TRIGGER_ASSET_PERCENT"] = 0.7
    g["CP_TRIGGER_MIN_NEGATIVE_DEV_PERCENT"] = -0.07
    return g
}
