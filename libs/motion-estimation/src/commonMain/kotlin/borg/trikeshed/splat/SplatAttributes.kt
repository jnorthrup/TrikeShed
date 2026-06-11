package borg.trikeshed.splat

import borg.trikeshed.lib.Series
import borg.trikeshed.lib.j
import borg.trikeshed.lib.size
import borg.trikeshed.lib.α
import borg.trikeshed.lib.view
import borg.trikeshed.parse.confix.ConfixDoc
import borg.trikeshed.parse.confix.ConfixCell
import borg.trikeshed.parse.confix.ConfixIndex
import borg.trikeshed.parse.confix.Syntax
import borg.trikeshed.parse.confix.confixDoc
import borg.trikeshed.parse.confix.reify

// ── SplatAttributes (Confix Blackboard projection) ──────────────
/**
 * SplatAttributes — stateless Confix Blackboard projection.
 * Typealias for ConfixDoc = Join<ConfixIndex, Series<Byte>>
 * - Facade (ConfixIndex) = MetaSeries with TreeCursor, KeyToChild, Depths, childOf
 * - Body (Series<Byte>) = raw CBOR/JSON bytes, zero-copy swappable
 * - All mutations are pure projections returning new ConfixDoc
 */
typealias SplatAttributes = ConfixDoc

val EmptySplatAttributes: SplatAttributes get() = confixDoc("{}".toByteArray(), Syntax.JSON)

fun splatAttributesOf(vararg pairs: Pair<String, Any?>): SplatAttributes {
    val json = pairs.map { "\"${it.first}\": ${toJson(it.second)}" }.joinToString(", ", "{", "}")
    return confixDoc(json.toByteArray(), Syntax.JSON)
}

fun SplatAttributes.with(key: String, value: Any?): SplatAttributes {
    return splatAttributesOf(key to value)
}

fun SplatAttributes.without(key: String): SplatAttributes = this.with(key, null)

inline fun <reified T> SplatAttributes.get(key: String): T? = null

fun SplatAttributes.velocityFacet(): Series<Double>? = null

fun SplatAttributes.colorFacet(): String? = null

fun SplatAttributes.labelFacet(): String? = null

fun SplatAttributes.uncertaintyFacet(): Double? = null

fun SplatAttributes.role(): String? = null

private fun toJson(value: Any?): String = when (value) {
    null -> "null"
    is Boolean -> value.toString()
    is Number -> value.toString()
    is String -> "\"${value.replace("\"", "\\\"")}\""
    is java.util.Collection<*> -> {
        val sb = StringBuilder("[")
        val iterator = (value as java.util.Collection<*>).iterator()
        var first = true
        while (iterator.hasNext()) {
            if (!first) sb.append(",")
            sb.append(toJson(iterator.next()))
            first = false
        }
        sb.append("]").toString()
    }
    is java.util.Map<*, *> -> {
        val sb = StringBuilder("{")
        val iterator = (value as java.util.Map<*, *>).entrySet().iterator()
        var first = true
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (!first) sb.append(", ")
            sb.append("\"${entry.key}\": ${toJson(entry.value)}")
            first = false
        }
        sb.append("}").toString()
    }
    else -> toJson(value.toString())
}