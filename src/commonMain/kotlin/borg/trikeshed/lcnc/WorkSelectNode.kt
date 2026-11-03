package borg.trikeshed.lcnc

import borg.trikeshed.lib.*

/** A quote is a declaration supplied by the author, not a reservation or authority to spend. */
object WorkSelectNode {
    const val TYPE = "work.select"
    const val MODEL = "work.model"
    const val MAX_OFFERS = 256
    const val MAX_EXACT = 9007199254740991L

    private fun whole(value: Any?): Long? {
        val number = value as? Number ?: return null
        val double = number.toDouble()
        if (!double.isFinite() || double < 0 || double > MAX_EXACT.toDouble()) return null
        val long = double.toLong()
        return long.takeIf { it.toDouble() == double }
    }

    private fun micros(value: Any?): Long? {
        val digits = value as? String ?: return null
        if (digits.isEmpty() || digits.length > 16 || (digits.length > 1 && digits[0] == '0') ||
            digits.any { it !in '0'..'9' }) return null
        return digits.toLongOrNull()?.takeIf { it <= MAX_EXACT }
    }

    private fun text(value: Any?): String? = (value as? String)?.takeIf {
        it.isNotBlank() && it.length <= 256 && it.none(Char::isISOControl)
    }

    fun runner(clock: () -> Long): LcncNodeRunner = LcncNodeRunner { _, inputs ->
        val offers = inputs["offers"] as? List<*> ?: error("offers must be a list")
        require(offers.size <= MAX_OFFERS) { "too many offers" }
        val feature = text(inputs["feature"]) ?: error("feature is required")
        val currency = text(inputs["currency"]) ?: error("currency is required")
        val ceiling = micros(inputs["ceilingMicros"]) ?: error("ceilingMicros must be canonical nonnegative decimal digits")
        val now = clock()
        require(now in 0..MAX_EXACT) { "clock must be an exact nonnegative integer" }
        val ids = HashSet<String>()
        val decisions = ArrayList<Map<String, Any?>>(offers.size)
        var selected: Map<String, Any?>? = null
        var lowest = Long.MAX_VALUE
        for ((index, value) in offers.withIndex()) {
            val offer = value as? Map<*, *>
            val id = text(offer?.get("id"))
            require(id == null || ids.add(id)) { "duplicate offer id: $id" }
            val kind = offer?.get("kind") as? String
            val price = micros(offer?.get("totalMicros"))
            val expires = whole(offer?.get("expiresAt"))
            val source = text(offer?.get("source"))
            val endpoint = text(offer?.get("endpoint"))
            val model = text(offer?.get("model"))
            val reason = when {
                offer == null || id == null -> "invalid_id"
                kind != "model" && kind != "service" -> "invalid_kind"
                kind == "model" && (endpoint == null || model == null || endpoint != endpoint.trim() || model != model.trim()) -> "model_route_missing"
                text(offer["feature"]) != feature -> "feature_mismatch"
                text(offer["currency"]) != currency -> "currency_mismatch"
                offer["available"] != true -> "availability_not_declared"
                source == null -> "quote_source_missing"
                price == null -> "price_unknown"
                expires == null || expires <= now -> "quote_expired_or_invalid"
                price > ceiling -> "above_ceiling"
                else -> null
            }
            decisions.add(_m["index" j index, "id" j id, "eligible" j (reason == null), "reason" j reason])
            if (reason == null && price!! < lowest) {
                lowest = price
                selected = _m[
                    "id" j id, "kind" j kind, "feature" j feature, "currency" j currency,
                    "totalMicros" j price.toString(), "expiresAt" j expires, "source" j source,
                    "endpoint" j endpoint, "model" j model,
                ]
            }
        }
        _m[
            "selection" j selected,
            "model" j (selected?.get("kind") == "model"),
            "service" j (selected?.get("kind") == "service"),
            "decisions" j decisions,
            "criterion" j "lowest_declared_total_within_ceiling",
            "status" j "planning_only_unverified_quotes",
            "asOf" j now,
        ]
    }
}
