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
    val root = this.root ?: return splatAttributesOf(key to value)
    val kids = root.kids
    val map = mutableMapOf<String, Any?>()
    for (kv in kids) {
        val k = (kv.row.kids.firstOrNull()?.reify(kv.src) as? String) ?: ""
        val v = kv.row.kids.getOrNull(1)?.reify(kv.src)
        map[k] = v
    }
    map[key] = value
    return splatAttributesOf(*map.toList())
}

fun SplatAttributes.without(key: String): SplatAttributes = this.with(key, null)

inline fun <reified T> SplatAttributes.get(key: String): T? {
    val cell = this.docAt(key) ?: return null
    return cell.reify() as? T
}

fun SplatAttributes.velocityFacet(): Series<Double>? {
    val arr = this.get<List<Double>>("velocity") ?: return null
    return arr.size.toLong().j { i -> arr[i] }
}

fun SplatAttributes.colorFacet(): String? = this.get<String>("color")
fun SplatAttributes.labelFacet(): String? = this.get<String>("label")
fun SplatAttributes.uncertaintyFacet(): Double? = this.get<Double>("uncertainty")
fun SplatAttributes.role(): String? = this.get<String>("role")

private fun toJson(value: Any?): String = when (value) {
    null -> "null"; is Boolean -> value.toString(); is Number -> value.toString();
    is String -> "\"${value.replace("\"", "\\\"")}\""; is Series<*> -> "[${value.view.map { toJson(it) }.joinToString(",")}]";
    is List<*> -> "[${value.map { toJson(it) }.joinToString(",")}]";
    is Map<*, *> -> "{${value.map { \"${it.key}\": ${toJson(it.value)}\" }.joinToString(", ")}}";
    else -> toJson(value.toString())
}