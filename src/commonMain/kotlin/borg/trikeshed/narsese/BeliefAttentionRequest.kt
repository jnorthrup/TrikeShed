package borg.trikeshed.narsese

import borg.trikeshed.job.ContentId
import borg.trikeshed.lib.*
import borg.trikeshed.parse.reifyStrict
import kotlin.math.abs

/** The bounded attention request shared by HTTP and LCNC; it carries no evidence fields. */
object BeliefAttentionRequest {
    fun parse(text: String): BeliefIntake.Attend {
        require(text.length <= 4096 && text.encodeToByteArray(throwOnInvalidSequence = true).size <= 4096) {
            "Attention request exceeds 4KiB"
        }
        var depth = 0; var quoted = false; var escaped = false
        for (ch in text) if (quoted) {
            if (escaped) escaped = false else if (ch == '\\') escaped = true else if (ch == '"') quoted = false
        } else when (ch) {
            '"' -> quoted = true
            '{', '[' -> require(++depth <= 2) { "Attention nesting limit" }
            '}', ']' -> require(--depth >= 0) { "Malformed attention request" }
        }
        require(depth == 0 && !quoted) { "Malformed attention request" }
        val value = requireNotNull(reifyStrict(text) as? Map<*, *>) { "Attention object required" }
        val keys = mutableSetOf<Any?>()
        require(value.entries.all { it.key is String && keys.add(it.key) }) { "Duplicate attention fields" }
        return parse(value.entries.associate { it.key as String to it.value })
    }

    fun parse(value: Map<String, Any?>, requireProposition: Boolean = true): BeliefIntake.Attend {
        require(value.keys.all { it in _s["angular", "propositionCid", "p", "d", "q"] }) { "Unexpected attention fields" }
        val raw = value["angular"]
        val angular = when (raw) {
            is Long -> raw
            is Number -> raw.toDouble().let {
                require(it.isFinite() && it % 1.0 == 0.0 && abs(it) <= 9_007_199_254_740_991.0) {
                    "angular must be exact; use a decimal string for 64-bit coordinates"
                }
                it.toLong()
            }
            is String -> raw.takeIf { it.length in 1..20 }?.toLongOrNull()
            else -> null
        }
        requireNotNull(angular) { "angular required" }
        val pid = value["propositionCid"]?.let { ContentId(requireNotNull(it as? String) { "propositionCid must be text" }) }
        require(!requireProposition || pid != null) { "propositionCid required for exact attention" }
        fun part(key: String): Float? = value[key]?.let {
            val number = when (it) {
                is Number -> it.toDouble()
                is String -> it.takeIf { text -> text.length <= 64 }?.toDoubleOrNull()
                else -> null
            }
            require(number != null && number.isFinite()) { "$key must be finite" }
            return@let number.coerceIn(0.0, 1.0).toFloat()
        }
        return BeliefIntake.Attend(angular, propositionCid = pid, p = part("p"), d = part("d"), q = part("q"))
    }
}
