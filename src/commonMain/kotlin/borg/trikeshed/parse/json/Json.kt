package borg.trikeshed.parse.json

import borg.trikeshed.collections.text.asSeries
import borg.trikeshed.cursor.RowVec
import borg.trikeshed.lib.*
import borg.trikeshed.parse.confix.*
import borg.trikeshed.parse.evidence.TypeEvidence
import borg.trikeshed.parse.evidence.toRowVec

/**
 * Thin re-export layer over the confix JSON stack.
 * All actual parsing is delegated to JsonScan + Reify from borg.trikeshed.parse.confix.
 *
 * Backward-compatible API: callers still use JsonParser.reify(text.toSeries())
 * or parse(text) — behaviour unchanged, engine now backed by confix.
 */

/* ─── type aliases (formerly in this package) ──────────────────────────── */
typealias JsElement = borg.trikeshed.parse.confix.JsElement
typealias JsIndex = borg.trikeshed.parse.confix.JsIndex
typealias JsContext = borg.trikeshed.parse.confix.JsContext
typealias JsPathElement = borg.trikeshed.parse.confix.JsPathElement
typealias JsPath = borg.trikeshed.parse.confix.JsPath

/* ─── public API ─────────────────────────────────────────────────────────── */

object JsonParser {
    fun reify(src: Series<Char>): Any? {
        val ctx = contextOf(Syntax.JSON, src)
        val tag = Combinators.tagOf(ctx.a, ctx.b)
        return materialize(Combinators.reify(ctx, Syntax.JSON), tag)
    }

    /** Scan JSON and return the top-level JsElement (index 0). */
    fun index(src: Series<Char>, data: MutableList<Int>? = null, top: Int? = null): JsElement {
        val elems = JsonScan.scan(src)
        val idx = top ?: 0
        return elems[idx]
    }

    /** Reify with optional TypeEvidence collector and RowVec callback. */
    fun reify(src: Series<Char>, evidence: MutableList<TypeEvidence>?, callback: ((RowVec) -> Unit)?): Any? {
        val ctx = contextOf(Syntax.JSON, src)
        if (evidence != null || callback != null) {
            collectJsonEvidence(ctx.a, src, isKey = false, evidence, callback)
        }
        val result = Combinators.reify(ctx, Syntax.JSON)
        val tag = Combinators.tagOf(ctx.a, ctx.b)
        return materialize(result, tag)
    }

    private fun collectJsonEvidence(
        e: JsElement,
        src: Series<Char>,
        isKey: Boolean,
        evidence: MutableList<TypeEvidence>?,
        callback: ((RowVec) -> Unit)?,
    ) {
        if (isKey) return
        val ev = TypeEvidence.sample(src.slice(e.a.a, e.a.b + 1))
        evidence?.add(ev)
        callback?.invoke(ev.toRowVec())
        val tag = Combinators.tagOf(e, src)
        when (tag.kind) {
            0 -> { // OBJECT: children alternate key/value
                val all = Combinators.realCommas(e, src)
                for (i in 0 until all.size) {
                    val childElem = JsonScan.parseOne(src, all[i])
                    collectJsonEvidence(childElem, src, isKey = (i % 2 == 0), evidence, callback)
                }
            }
            1 -> { // ARRAY: all children are values
                val all = Combinators.realCommas(e, src)
                for (i in 0 until all.size) {
                    val childElem = JsonScan.parseOne(src, all[i])
                    collectJsonEvidence(childElem, src, isKey = false, evidence, callback)
                }
            }
        }
    }

    /** Path traversal over JSON context. */
    fun jsPath(ctx: JsContext, path: JsPath, flat: Boolean = false, data: List<Int>? = null): Any? {
        val resolved = Path.resolve(ctx, path)
        return if (resolved != null) {
            val reified = Combinators.reify(resolved, Syntax.JSON)
            materialize(reified, Combinators.tagOf(resolved.a, resolved.b))
        } else {
            null
        }
    }

    fun parse(text: String): Map<String, Any?> {
        val ctx = contextOf(Syntax.JSON, text.asSeries())
        @Suppress("UNCHECKED_CAST")
        return materialize(Combinators.reify(ctx, Syntax.JSON), Combinators.tagOf(ctx.a, ctx.b)) as? Map<String, Any?> ?: emptyMap()
    }
}

/** Materialize a confix reified value into stdlib collections.
 *
 * Reify returns:
 *   - Series2<String,Any?>  (a Series of key-value Join pairs) → LinkedHashMap
 *   - Series<Any?>          (a Series of values)             → ArrayList
 *   - primitive             (String, Number, Boolean, null)   → identity
 *
 * Series<A> = Join<Int, (Int)→A>
 * Series2<A,B> = Series<Join<A,B>> = Join<Int, (Int)→Join<A,B>>
 *
 * We detect which by observing the second field of the outer Join:
 *   if it's an (Int)→Join<A,B>  → Series2 (map)
 *   if it's an (Int)→A          → Series  (list)
 */
@Suppress("UNCHECKED_CAST")
fun materialize(node: Any?, tag: Tag? = null): Any? {
    if (node == null) return null

    // node = Join<Int, F> where F is either (Int)->Join<A,B> (map) or (Int)->A (list)
    val join = node as? Join<Int, *> ?: return node
    val second = join.second ?: return node
    val size = join.first

    return when (second) {
        is Function1<*, *> -> {
            if (size == 0) return when (tag) {
                Tag.ARRAY -> ArrayList<Any?>(0)
                Tag.OBJECT -> LinkedHashMap<String, Any?>(0)
                else -> LinkedHashMap<String, Any?>(0)
            }
            val fn = second as (Int) -> Any?
            val at0 = fn(0)
            if (at0 is Join<*, *> && (at0 as Join<*, *>).a is String) {
                // Series2: key-value pairs
                @Suppress("UNCHECKED_CAST")
                val mapFn = second as (Int) -> Join<String, Any?>
                val map = LinkedHashMap<String, Any?>(size * 2)
                var i = 0
                while (i < size) {
                    val pair: Join<String, Any?> = mapFn(i)
                    map[pair.first] = materialize(pair.second)
                    i++
                }
                map
            } else {
                // Series: list
                val list = ArrayList<Any?>(size)
                var i = 0
                while (i < size) { list.add(materialize(fn(i))); i++ }
                list
            }
        }
        else -> node
    }
}

/** Path query over JSON using confix Path */
fun queryPath(ctx: JsContext, path: JsPath): JsContext? = Path.resolve(ctx, path)

/** Parse a JSON string to a stdlib Map (convenience, used by OpenApiRawParser) */
fun parse(text: String): Map<String, Any?> {
    val ctx = contextOf(Syntax.JSON, text.asSeries())
    @Suppress("UNCHECKED_CAST")
    return materialize(Combinators.reify(ctx, Syntax.JSON)) as? Map<String, Any?> ?: emptyMap()
}
