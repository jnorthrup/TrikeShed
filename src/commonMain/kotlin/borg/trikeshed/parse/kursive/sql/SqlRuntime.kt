package borg.trikeshed.parse.kursive.sql

import borg.trikeshed.cas.Analyzer
import borg.trikeshed.cursor.RowVec
import borg.trikeshed.cursor.values
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.get
import borg.trikeshed.parse.confix.JsPathElement
import kotlin.jvm.JvmStatic

/**
 * The scalar semantics of every SQL operator, one static method each. The closure compiler
 * ([compile]) and any generated-bytecode compiler call these and nothing else, so a statement
 * means the same thing however it was compiled. All methods are null-in null-out except where
 * SQL says otherwise: `AND` / `OR` / `NOT` are three-valued (null is unknown), `IS` never answers
 * null, and the caller short-circuits `AND` on [isFalse] and `OR` on [isTrue].
 *
 * Statics, not closures: a generated class links to them directly and the JIT inlines them.
 */
object SqlRuntime {
    /** Cell [ordinal] of [row], a RowVec. */
    @JvmStatic fun cell(row: Any?, ordinal: Int): Any? = (row as RowVec).values[ordinal]

    /** Only a true Boolean admits a row; null (unknown) and false both reject. */
    @JvmStatic fun isTrue(value: Any?): Boolean = value == true

    /** The left operand that decides `AND` alone. */
    @JvmStatic fun isFalse(value: Any?): Boolean = truth(value) == false

    /** A predicate value as SQL truth: null is unknown, a non-Boolean is false. */
    private fun truth(value: Any?): Boolean? = if (value == null) null else value == true

    @JvmStatic fun and(a: Any?, b: Any?): Any? {
        val x = truth(a)
        val y = truth(b)
        return if (x == false || y == false) false else if (x == null || y == null) null else true
    }

    @JvmStatic fun or(a: Any?, b: Any?): Any? {
        val x = truth(a)
        val y = truth(b)
        return if (x == true || y == true) true else if (x == null || y == null) null else false
    }

    @JvmStatic fun not(a: Any?): Any? = truth(a)?.not()

    /** `IS NULL`. */
    @JvmStatic fun isNull(value: Any?): Any? = value == null

    /** `IS TRUE` / `IS FALSE`. */
    @JvmStatic fun isBoolean(value: Any?, expected: Boolean): Any? = truth(value) == expected

    /** `IN (items)`: a match is true; otherwise a null item (or a null [value]) leaves it unknown. */
    @JvmStatic fun inList(value: Any?, items: Array<Any?>): Any? {
        if (value == null) return null
        var unknown = false
        for (item in items) if (item == null) unknown = true else if (compareCells(value, item) == 0) return true
        return if (unknown) null else false
    }

    @JvmStatic fun eq(a: Any?, b: Any?): Any? = if (a == null || b == null) null else compareCells(a, b) == 0
    @JvmStatic fun ne(a: Any?, b: Any?): Any? = if (a == null || b == null) null else compareCells(a, b) != 0
    @JvmStatic fun lt(a: Any?, b: Any?): Any? = if (a == null || b == null) null else compareCells(a, b) < 0
    @JvmStatic fun le(a: Any?, b: Any?): Any? = if (a == null || b == null) null else compareCells(a, b) <= 0
    @JvmStatic fun gt(a: Any?, b: Any?): Any? = if (a == null || b == null) null else compareCells(a, b) > 0
    @JvmStatic fun ge(a: Any?, b: Any?): Any? = if (a == null || b == null) null else compareCells(a, b) >= 0

    /** `LIKE` with a pattern known at run time. */
    @JvmStatic fun like(subject: Any?, pattern: Any?): Any? =
        if (subject == null || pattern == null) null else likeRegex(pattern.toString()).matches(subject.toString())

    /** `LIKE` against a pattern compiled once from a literal; [regex] is a [Regex]. */
    @JvmStatic fun likeCompiled(subject: Any?, regex: Any?): Any? =
        if (subject == null) null else (regex as Regex).matches(subject.toString())

    /** `ILIKE` (pg): `LIKE` ignoring case, with a pattern known at run time. */
    @JvmStatic fun ilike(subject: Any?, pattern: Any?): Any? =
        if (subject == null || pattern == null) null else likeRegex(pattern.toString(), ignoreCase = true).matches(subject.toString())

    /** pg `~` / `~*` (`!~` / `!~*` negate): the pattern is found somewhere in the subject, a POSIX regex search. */
    @JvmStatic fun regexMatch(subject: Any?, pattern: Any?, ignoreCase: Boolean, negated: Boolean): Any? =
        if (subject == null || pattern == null) null else regexCompiled(subject, regexOf(scalarText(pattern), ignoreCase), negated)

    /** `~` against a pattern compiled once from a literal; [regex] is a [Regex]. */
    @JvmStatic fun regexCompiled(subject: Any?, regex: Any?, negated: Boolean): Any? =
        if (subject == null) null else (regex as Regex).containsMatchIn(scalarText(subject)) != negated

    /** `CASE` over its parsed arguments: `operand, when1, then1, …, else`, read lazily; see [SqlParser.parseCase]. */
    @JvmStatic fun case(operand: Any?, searched: Boolean, arms: Array<() -> Any?>): Any? {
        var i = 0
        while (i + 1 < arms.size) {
            val test = arms[i]()
            if (if (searched) isTrue(test) else test != null && operand != null && compareCells(operand, test) == 0) return arms[i + 1]()
            i += 2
        }
        return arms[arms.size - 1]()
    }

    /** A scalar function by [name] over its evaluated [args]; the table is [SqlFunctions]. */
    @JvmStatic fun call(name: String, args: Array<Any?>): Any? = SqlFunctions.call(name, args)

    /** Evoke `document @@ 'query text'`: the document holds a query term (a local match, not a corpus score). */
    @JvmStatic fun matches(document: Any?, query: Any?): Any? {
        if (document == null || query == null) return null
        val terms = Analyzer().tokens(scalarText(query)).toHashSet()
        return Analyzer().tokens(scalarText(document)).any { it in terms }
    }

    @JvmStatic fun concat(a: Any?, b: Any?): Any? = if (a == null || b == null) null else a.toString() + b.toString()

    /** Integral operands stay Long (`/` truncates, as pg); any fraction makes the result Double; null propagates. */
    @JvmStatic fun add(a: Any?, b: Any?): Any? {
        if (a !is Number || b !is Number) return null
        return if (integral(a) && integral(b)) a.toLong() + b.toLong() else a.toDouble() + b.toDouble()
    }

    @JvmStatic fun sub(a: Any?, b: Any?): Any? {
        if (a !is Number || b !is Number) return null
        return if (integral(a) && integral(b)) a.toLong() - b.toLong() else a.toDouble() - b.toDouble()
    }

    @JvmStatic fun mul(a: Any?, b: Any?): Any? {
        if (a !is Number || b !is Number) return null
        return if (integral(a) && integral(b)) a.toLong() * b.toLong() else a.toDouble() * b.toDouble()
    }

    @JvmStatic fun div(a: Any?, b: Any?): Any? {
        if (a !is Number || b !is Number) return null
        return if (integral(a) && integral(b)) a.toLong() / b.toLong() else a.toDouble() / b.toDouble()
    }

    @JvmStatic fun mod(a: Any?, b: Any?): Any? {
        if (a !is Number || b !is Number) return null
        return if (integral(a) && integral(b)) a.toLong() % b.toLong() else a.toDouble() % b.toDouble()
    }

    /** pgvector `<=>`: 1 − cosine similarity (a zero vector is similarity 0). Dimensions must agree. */
    @JvmStatic fun cosineDistance(a: Any?, b: Any?): Any? = distance(a, b) { x, y, out -> borg.trikeshed.lib.floatp.cosine(x, 1, x.size, y, out); 1.0 - out[0] }

    /** pgvector `<->`: Euclidean distance. */
    @JvmStatic fun l2Distance(a: Any?, b: Any?): Any? =
        distance(a, b) { x, y, out -> borg.trikeshed.lib.floatp.l2Squared(x, 1, x.size, y, out); kotlin.math.sqrt(out[0].toDouble()) }

    /** pgvector `<#>`: the negative inner product, so ascending order is most similar first. */
    @JvmStatic fun negInnerProduct(a: Any?, b: Any?): Any? = distance(a, b) { x, y, out -> borg.trikeshed.lib.floatp.dot(x, 1, x.size, y, out); -out[0].toDouble() }

    private inline fun distance(a: Any?, b: Any?, kernel: (FloatArray, FloatArray, FloatArray) -> Double): Any? {
        val x = vectorOf(a) ?: return null
        val y = vectorOf(b) ?: return null
        require(x.size == y.size) { "different vector dimensions ${x.size} and ${y.size}" }
        return kernel(x, y, FloatArray(1))
    }

    /** `value::type` for the types the SQL surfaces share; null casts to null. */
    @JvmStatic fun cast(value: Any?, type: String): Any? = castTo(value, type)

    /** pg `->` / `->>`, DuckDB `->`, Snowflake `:`; [path] is a `JsPath` (`Series<JsPathElement>`). */
    @Suppress("UNCHECKED_CAST")
    @JvmStatic fun path(value: Any?, path: Any?, text: Boolean): Any? = navigate(value, path as Series<JsPathElement>, text)
}
