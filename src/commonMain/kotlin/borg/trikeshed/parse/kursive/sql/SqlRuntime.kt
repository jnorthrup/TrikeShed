package borg.trikeshed.parse.kursive.sql

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
 * SQL says otherwise (`AND` / `OR` are decided by the caller's short-circuit on [isTrue]).
 *
 * Statics, not closures: a generated class links to them directly and the JIT inlines them.
 */
object SqlRuntime {
    /** Cell [ordinal] of [row], a RowVec. */
    @JvmStatic fun cell(row: Any?, ordinal: Int): Any? = (row as RowVec).values[ordinal]

    /** Only a true Boolean admits a row; null (unknown) and false both reject. */
    @JvmStatic fun isTrue(value: Any?): Boolean = value == true

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
