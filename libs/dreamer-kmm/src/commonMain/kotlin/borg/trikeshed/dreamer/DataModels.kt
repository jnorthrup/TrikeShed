package borg.trikeshed.dreamer


enum class Mode { LIVE, SHADOW }

enum class GenomeParam(
    val aliases: Set<String> = emptySet(),
) {
    TARGET_ADJUST_PERCENT,
    FLAT_HARVEST_TRIGGER_PERCENT,
    HARVEST_TAKE_PERCENT,
    HARVEST_CYCLE_THRESHOLD,
    MIN_SURPLUS_FOR_HARVEST,
    MIN_SURPLUS_FOR_FORCED_HARVEST,
    FORCED_HARVEST_TIMEOUT_MS( setOf("FORCED_HARVEST_TIMEOUT")),
    PORTFOLIO_HARVEST_TRIGGER_DEVIATION_PERCENT,
    PORTFOLIO_HARVEST_CONFIRMATION_CYCLES,
    MIN_ASSET_SURPLUS_FOR_PORTFOLIO_HARVEST,
    HARVEST_ALLOC_BTC_PERCENT,
    HARVEST_ALLOC_ETH_PERCENT,
    HARVEST_ALLOC_REINVEST_PERCENT,
    HARVEST_ALLOC_CASH_PERCENT,
    CRASH_FUND_THRESHOLD_PERCENT,
    MIN_HARVEST_TO_ALLOCATE,
    MIN_NEGATIVE_DEVIATION_FOR_REINVEST,
    MIN_REINVEST_BUY_USD,
    REINVEST_BASELINE_GROWTH_FACTOR,
    MIN_BTC_BUY_USD,
    MIN_ETH_BUY_USD,
    FLAT_REBALANCE_TRIGGER_PERCENT,
    PARTIAL_RECOVERY_PERCENT,
    REBALANCE_POSITIVE_THRESHOLD,
    MAX_REBALANCE_ATTEMPTS,
    REBALANCE_COOLDOWN_MS( setOf("REBALANCE_COOLDOWN_MS")),
    FORCE_REBALANCE_TIMEOUT_MS( setOf("FORCE_REBALANCE_TIMEOUT_MS")),
    FORCE_REBALANCE_SHORTFALL_PERCENT,
    MIN_PARTIAL_REBALANCE_USD,
    MIN_FORCED_REBALANCE_USD,
    SPAR_DRAG_COEFFICIENT,
    CP_TRIGGER_ASSET_PERCENT,
    CP_TRIGGER_MIN_NEGATIVE_DEV_PERCENT,
    CRASH_PROTECTION_THRESHOLD_INCREASE,
    CRASH_PROTECTION_PARTIAL_RECOVERY_PERCENT,
    REFRESH_INTERVAL_MS( setOf("REFRESH_INTERVAL_MS")),
    ALLOCATION_MODE,
    REINVEST_WEIGHT_EXPONENT,
    FITNESS_DRAWDOWN_PENALTY,
    MIN_TRADES_FOR_PROMOTION,
    EVOLUTION_CONSISTENCY_COUNT,
    ORACLE_TREND_THRESHOLD,
    ORACLE_VOLATILITY_THRESHOLD,
    EVOLUTION_INTERVAL_MINUTES;

    val storageKey: String get() = name ?: this@GenomeParam.name

    companion object {
        val byKey: Map<String, GenomeParam> = values().flatMap { param ->
            (setOf(param.name, param.storageKey) + param.aliases).map { key -> key to param }
        }.toMap()

        fun fromKey(key: String): GenomeParam? = byKey[key]
    }
}

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
        val param = GenomeParam.fromKey(key)
        return if (param != null) doubles[param.ordinal] else backing[key]
    }

    operator fun set(key: String, value: Any?) {
        val param = GenomeParam.fromKey(key)
        if (param != null && value is Number) {
            doubles[param.ordinal] = value.toDouble()
        }
        backing[key] = value
    }

    operator fun get(param: GenomeParam): Double = doubles[param.ordinal]

    operator fun set(param: GenomeParam, value: Double) {
        doubles[param.ordinal] = value
        backing[param.storageKey] = value
    }

    operator fun set(param: GenomeParam, value: Number) {
        this[param] = value.toDouble()
    }

    fun getDouble(key: String, default: Double = 0.0): Double {
        val param = GenomeParam.fromKey(key)
        if (param != null) return doubles[param.ordinal]
        val v = backing[key] ?: return default
        return when (v) {
            is Number -> v.toDouble()
            is String -> v.toDoubleOrNull() ?: default
            else -> default
        }
    }

    fun getDouble(param: GenomeParam): Double = doubles[param.ordinal]

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
            for (param in GenomeParam.values()) {
                val value = packed[param.ordinal]
                if (!value.isNaN()) resolved[param.storageKey] = value
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
        val param = GenomeParam.fromKey(key)
        require(param != null) { "Unknown genome parameter: $key" }
        return withOverride(symbol, param, value)
    }

    fun withOverride(symbol: String, param: GenomeParam, value: Double): Genome {
        val nextOverrides = overrides.mapValuesTo(mutableMapOf()) { it.value.copyOf() }
        val target = nextOverrides[symbol] ?: DoubleArray(WIDTH) { Double.NaN }
        target[param.ordinal] = value
        nextOverrides[symbol] = target
        return Genome(doubles.copyOf(), nextOverrides, backing.toMutableMap())
    }

    fun copyGenome(): Genome = Genome(doubles.copyOf(), overrides.mapValuesTo(mutableMapOf()) { it.value.copyOf() }, backing.toMutableMap())

    companion object {
        val WIDTH: Int = GenomeParam.values().size

        val PARAM_NAMES: Array<String> = GenomeParam.values().map { it.storageKey }.toTypedArray()

        val ORDINALS: Map<String, Int> = GenomeParam.byKey.mapValues { it.value.ordinal }

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

        fun ordinalOf(key: String): Int = GenomeParam.fromKey(key)?.ordinal ?: -1
    }
}

fun defaultGenome(): Genome {
    val g = Genome()
    g["ENABLE_PORTFOLIO_HARVEST"] = true
    g["ENABLE_CRASH_PROTECTION"] = true
    return g
}
