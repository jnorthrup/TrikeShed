package borg.trikeshed.loom

import borg.trikeshed.cursor.*
import borg.trikeshed.job.*
import borg.trikeshed.lib.*
import borg.trikeshed.parse.confix.*
import borg.trikeshed.util.*

/**
 * loom-mesh `preflight`: Confix exercised on deployment input before keys, network clients or
 * listeners. Strict JSON stays authoritative; the Confix index must agree with it span for span.
 */
class Report(val bytes: Int, val tokens: Int, val sha256: String) {
    /** `serde_json::to_string(&report)`. */
    override fun toString(): String = """{"bytes":$bytes,"tokens":$tokens,"sha256":"$sha256"}"""
}

/** Visits [value] within depth 16 and [budget] values; the budget left. */
fun bounded(value: Any, depth: Int, budget: Int): Int {
    if (depth > 16 || budget == 0) error("configuration complexity limit")
    var left = budget - 1
    when (value) {
        is List<*> -> for (item in value) left = bounded(item!!, depth + 1, left)
        is Map<*, *> -> for (item in value.values) left = bounded(item!!, depth + 1, left)
    }
    return left
}

fun agreement(row: RowVec, value: Any, src: ByteArray) {
    fun invalid(): Nothing = error("Confix configuration span disagreement")
    fun span(of: RowVec): ByteArray =
        if (of.close < src.size && of.open <= of.close + 1) src.copyOfRange(of.open, of.close + 1) else invalid()
    val selected = span(row)
    when (value) {
        is Map<*, *> -> {
            if (row.tag != IOMemento.IoObject || row.kids.size != value.size * 2) invalid()
            for (pair in 0 until value.size) {
                val key = json(span(row.kids[2 * pair]), unique = false) as? String ?: invalid()
                agreement(row.kids[2 * pair + 1], value[key] ?: invalid(), src)
            }
        }
        is List<*> -> {
            if (row.tag != IOMemento.IoArray || row.kids.size != value.size) invalid()
            for (i in value.indices) agreement(row.kids[i], value[i]!!, src)
        }
        // The selected source span decodes to the same value: exact integer money and
        // decoded escaped or empty keys, never a reified f64.
        else -> if (!same(json(selected, unique = false) ?: invalid(), value) || row.kids.size != 0) invalid()
    }
}

fun checked(bytes: ByteArray): Join<Any, Report> {
    if (bytes.isEmpty() || bytes.size > 128 * 1024) error("configuration size limit")
    // Confix is a lenient indexer: malformed input never reaches its scanner.
    val value = strictJson(bytes)
    bounded(value, 0, 2048)
    val doc = confixDoc(bytes, Syntax.JSON)
    if (doc.roots.size != 1) error("Confix configuration root disagreement")
    agreement(doc.root ?: error("Confix configuration root missing"), value, bytes)
    return value j Report(bytes.size, doc.index.facet(ConfixIndexK.Spans).size, sha256(bytes).toLowerHex())
}

fun assertConfix(bytes: ByteArray): Report = checked(bytes).b
