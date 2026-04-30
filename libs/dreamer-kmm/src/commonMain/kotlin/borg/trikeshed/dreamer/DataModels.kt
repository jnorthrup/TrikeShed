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

/**
 * Dreamer 1.2 genome packed as a fixed-width [DoubleArray].
 *
 * The legacy string lookup surface is intentionally kept as a migration
 * adapter for older harness code, but numeric strategy parameters are stored
 * in [doubles] and addressed by stable ordinals.
 */
class Genome(
    val doubles: DoubleArray = DEFAULT_DOUBLES.copyOf(),
    val overrides: MutableMap<String, DoubleArray> = mutableMapOf(),
    initialBacking: MutableMap<String, Any?> = mutableMapOf(),
) {
    constructor(backing: MutableMap<String, Any?>) : this(
        doubles = DEFAULT_DOUBLES.copyOf(),
        overrides = mutableMapOf(),
        initialBacking = backing,
    )

    val backing: MutableMap<String, Any?> = mutableMapOf()

    init {
        require(doubles.size == WIDTH) { "Genome doubles must have exactly $WIDTH elements, got ${doubles.size}" }
        initialBacking.forEach { (key, value) -> this[key] = value }
        overrides.forEach { (symbol, values) ->
            require(values.size == WIDTH) { "Override for $symbol must have exactly $WIDTH elements, got ${values.size}" }
        }
    }

    operator fun get(key: String): Any? {
        val ordinal = ordinalOf(key)
        return if (ordinal >= 0) doubles[ordinal] else backing[key]
    }

    operator fun set(key: String, value: Any?) {
        val ordinal = ordinalOf(key)
        if (ordinal >= 0 && value is Number) {
            doubles[ordinal] = value.toDouble()
        }
        backing[key] = value
    }

    fun getDouble(key: String, default: Double = 0.0): Double {
        val ordinal = ordinalOf(key)
        if (ordinal >= 0) return doubles[ordinal]
        val v = backing[key] ?: return default
        return when (v) {
            is Number -> v.toDouble()
            is String -> v.toDoubleOrNull() ?: default
            else -> default
        }
    }

    fun getBoolean(key: String, default: Boolean = false): Boolean {
        if (key == "ENABLE_PORTFOLIO_HARVEST") return backing[key]?.let { booleanValue(it, default) } ?: true
        if (key == "ENABLE_CRASH_PROTECTION") return backing[key]?.let { booleanValue(it, default) } ?: true
        val v = backing[key] ?: return default
        return booleanValue(v, default)
    }

    public fun booleanValue(v: Any?, default: Boolean): Boolean {
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
        val packed = overrides[symbol]
        if (packed != null) {
            val resolved = mutableMapOf<String, Any?>()
            for (i in 0 until WIDTH) {
                val value = packed[i]
                if (!value.isNaN()) resolved[PARAM_NAMES[i]] = value
            }
            return resolved
        }

        val legacyOverrides = backing["overrides"] ?: return null
        @Suppress("UNCHECKED_CAST")
        val outer = legacyOverrides as? Map<String, Map<String, Any?>> ?: return null
        val inner = outer[symbol] ?: return null
        @Suppress("UNCHECKED_CAST")
        return inner as? MutableMap<String, Any?> ?: HashMap(inner)
    }

    fun resolveInto(symbol: String, out: DoubleArray): DoubleArray {
        require(out.size >= WIDTH) { "out must have at least $WIDTH elements" }
        val packed = overrides[symbol]
        if (packed == null) {
            doubles.copyInto(out, 0, 0, WIDTH)
            return out
        }
        for (i in 0 until WIDTH) {
            val value = packed[i]
            out[i] = if (value.isNaN()) doubles[i] else value
        }
        return out
    }

    fun withOverride(symbol: String, key: String, value: Double): Genome {
        val ordinal = ordinalOf(key)
        require(ordinal >= 0) { "Unknown genome parameter: $key" }
        val nextOverrides = overrides.mapValuesTo(mutableMapOf()) { it.value.copyOf() }
        val target = nextOverrides[symbol] ?: DoubleArray(WIDTH) { Double.NaN }
        target[ordinal] = value
        nextOverrides[symbol] = target
        return Genome(doubles.copyOf(), nextOverrides, backing.toMutableMap())
    }

    fun copyGenome(): Genome = Genome(doubles.copyOf(), overrides.mapValuesTo(mutableMapOf()) { it.value.copyOf() }, backing.toMutableMap())

    companion object {
        const val WIDTH = 44

        const val TARGET_ADJUST_PERCENT = 0
        const val FLAT_HARVEST_TRIGGER_PERCENT = 1
        const val HARVEST_TAKE_PERCENT = 2
        const val HARVEST_CYCLE_THRESHOLD = 3
        const val MIN_SURPLUS_FOR_HARVEST = 4
        const val MIN_SURPLUS_FOR_FORCED_HARVEST = 5
        const val FORCED_HARVEST_TIMEOUT_MS = 6
        const val PORTFOLIO_HARVEST_TRIGGER_DEVIATION_PERCENT = 7
        const val PORTFOLIO_HARVEST_CONFIRMATION_CYCLES = 8
        const val MIN_ASSET_SURPLUS_FOR_PORTFOLIO_HARVEST = 9
        const val HARVEST_ALLOC_BTC_PERCENT = 10
        const val HARVEST_ALLOC_ETH_PERCENT = 11
        const val HARVEST_ALLOC_REINVEST_PERCENT = 12
        const val HARVEST_ALLOC_CASH_PERCENT = 13
        const val CRASH_FUND_THRESHOLD_PERCENT = 14
        const val MIN_HARVEST_TO_ALLOCATE = 15
        const val MIN_NEGATIVE_DEVIATION_FOR_REINVEST = 16
        const val MIN_REINVEST_BUY_USD = 17
        const val REINVEST_BASELINE_GROWTH_FACTOR = 18
        const val MIN_BTC_BUY_USD = 19
        const val MIN_ETH_BUY_USD = 20
        const val FLAT_REBALANCE_TRIGGER_PERCENT = 21
        const val PARTIAL_RECOVERY_PERCENT = 22
        const val REBALANCE_POSITIVE_THRESHOLD = 23
        const val MAX_REBALANCE_ATTEMPTS = 24
        const val REBALANCE_COOLDOWN_MS = 25
        const val FORCE_REBALANCE_TIMEOUT_MS = 26
        const val FORCE_REBALANCE_SHORTFALL_PERCENT = 27
        const val MIN_PARTIAL_REBALANCE_USD = 28
        const val MIN_FORCED_REBALANCE_USD = 29
        const val SPAR_DRAG_COEFFICIENT = 30
        const val CP_TRIGGER_ASSET_PERCENT = 31
        const val CP_TRIGGER_MIN_NEGATIVE_DEV_PERCENT = 32
        const val CRASH_PROTECTION_THRESHOLD_INCREASE = 33
        const val CRASH_PROTECTION_PARTIAL_RECOVERY_PERCENT = 34
        const val REFRESH_INTERVAL_MS = 35
        const val ALLOCATION_MODE = 36
        const val REINVEST_WEIGHT_EXPONENT = 37
        const val FITNESS_DRAWDOWN_PENALTY = 38
        const val MIN_TRADES_FOR_PROMOTION = 39
        const val EVOLUTION_CONSISTENCY_COUNT = 40
        const val ORACLE_TREND_THRESHOLD = 41
        const val ORACLE_VOLATILITY_THRESHOLD = 42
        const val EVOLUTION_INTERVAL_MINUTES = 43

        val PARAM_NAMES = arrayOf(
            "TARGET_ADJUST_PERCENT",
            "FLAT_HARVEST_TRIGGER_PERCENT",
            "HARVEST_TAKE_PERCENT",
            "HARVEST_CYCLE_THRESHOLD",
            "MIN_SURPLUS_FOR_HARVEST",
            "MIN_SURPLUS_FOR_FORCED_HARVEST",
            "FORCED_HARVEST_TIMEOUT",
            "PORTFOLIO_HARVEST_TRIGGER_DEVIATION_PERCENT",
            "PORTFOLIO_HARVEST_CONFIRMATION_CYCLES",
            "MIN_ASSET_SURPLUS_FOR_PORTFOLIO_HARVEST",
            "HARVEST_ALLOC_BTC_PERCENT",
            "HARVEST_ALLOC_ETH_PERCENT",
            "HARVEST_ALLOC_REINVEST_PERCENT",
            "HARVEST_ALLOC_CASH_PERCENT",
            "CRASH_FUND_THRESHOLD_PERCENT",
            "MIN_HARVEST_TO_ALLOCATE",
            "MIN_NEGATIVE_DEVIATION_FOR_REINVEST",
            "MIN_REINVEST_BUY_USD",
            "REINVEST_BASELINE_GROWTH_FACTOR",
            "MIN_BTC_BUY_USD",
            "MIN_ETH_BUY_USD",
            "FLAT_REBALANCE_TRIGGER_PERCENT",
            "PARTIAL_RECOVERY_PERCENT",
            "REBALANCE_POSITIVE_THRESHOLD",
            "MAX_REBALANCE_ATTEMPTS",
            "REBALANCE_COOLDOWN",
            "FORCE_REBALANCE_TIMEOUT",
            "FORCE_REBALANCE_SHORTFALL_PERCENT",
            "MIN_PARTIAL_REBALANCE_USD",
            "MIN_FORCED_REBALANCE_USD",
            "SPAR_DRAG_COEFFICIENT",
            "CP_TRIGGER_ASSET_PERCENT",
            "CP_TRIGGER_MIN_NEGATIVE_DEV_PERCENT",
            "CRASH_PROTECTION_THRESHOLD_INCREASE",
            "CRASH_PROTECTION_PARTIAL_RECOVERY_PERCENT",
            "REFRESH_INTERVAL",
            "ALLOCATION_MODE",
            "REINVEST_WEIGHT_EXPONENT",
            "FITNESS_DRAWDOWN_PENALTY",
            "MIN_TRADES_FOR_PROMOTION",
            "EVOLUTION_CONSISTENCY_COUNT",
            "ORACLE_TREND_THRESHOLD",
            "ORACLE_VOLATILITY_THRESHOLD",
            "EVOLUTION_INTERVAL_MINUTES",
        )

        public val ORDINALS: Map<String, Int> = PARAM_NAMES.withIndex().associate { it.value to it.index } +
            mapOf(
                "FORCED_HARVEST_TIMEOUT_MS" to FORCED_HARVEST_TIMEOUT_MS,
                "REBALANCE_COOLDOWN_MS" to REBALANCE_COOLDOWN_MS,
                "FORCE_REBALANCE_TIMEOUT_MS" to FORCE_REBALANCE_TIMEOUT_MS,
                "REFRESH_INTERVAL_MS" to REFRESH_INTERVAL_MS,
            )

        public const val MIN_45_MS = 45.0 * 60.0 * 1000.0
        public const val MIN_30_MS = 30.0 * 60.0 * 1000.0
        public const val MIN_25_MS = 25.0 * 60.0 * 1000.0

        val DEFAULT_DOUBLES = doubleArrayOf(
            0.001, 0.035, 0.70, 3.0, 0.25, 1.00, MIN_45_MS,
            0.035, 3.0, 0.10,
            0.25, 0.25, 0.25, 0.25,
            0.10, 0.25, -0.010, 0.25, 0.50, 0.10,
            0.25, 0.035,
            0.70, 3.0, 3.0, MIN_30_MS, MIN_25_MS, 0.25, 0.25, 0.25,
            0.999968,
            0.70, -0.07, 2.0, 0.33,
            8000.0, 1.0, 1.50,
            1.00, 1.0, 3.0, 0.8, 2.0, 5.0,
        )

        fun ordinalOf(key: String): Int = ORDINALS[key] ?: -1
    }
}

fun defaultGenome(): Genome {
    val g = Genome()
    g["ENABLE_PORTFOLIO_HARVEST"] = true
    g["ENABLE_CRASH_PROTECTION"] = true
    return g
}
