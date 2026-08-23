@file:OptIn(ExperimentalEncodingApi::class)

package borg.trikeshed.vm

import borg.trikeshed.job.ContentId
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.math.floor

/**
 * Teleported state — the ONLY shape that crosses a VM boundary, on every target.
 *
 * A guest value is bound to its context; it cannot be handed to another context, another thread
 * concurrently, a child process, or a browser Worker. So every crossing (guest→host delegate call,
 * host→guest call, guest→leaf-isolate, process RPC, Worker postMessage) first projects the value
 * into this closed sealed tree, which has a canonical byte encoding and therefore a [cid]. The cid
 * is what receipts, memo tables and the blackboard key on.
 *
 * Anything that has no faithful projection (functions, host objects, foreign opaque objects)
 * becomes [Opaque] carrying its `toString`; a leaf whose arguments or results are opaque is never
 * promoted to delegation. Graal-specific projections (`of(Value)`, `toGuest()`) live in jvmMain
 * `graal/subvm/Teleport.kt` as extensions; this file is pure Kotlin.
 */
sealed class Teleported {
    object Null : Teleported()
    data class Bool(val v: Boolean) : Teleported()
    data class Num(val v: Long) : Teleported()
    data class Real(val v: Double) : Teleported()
    data class Str(val v: String) : Teleported()
    data class Bytes(val v: ByteArray) : Teleported() {
        override fun equals(other: Any?) = other is Bytes && v.contentEquals(other.v)
        override fun hashCode() = v.contentHashCode()
    }
    data class Arr(val v: List<Teleported>) : Teleported()
    data class Obj(val v: Map<String, Teleported>) : Teleported()
    data class Opaque(val repr: String) : Teleported()

    val isOpaque: Boolean
        get() = when (this) {
            is Opaque -> true
            is Arr -> v.any { it.isOpaque }
            is Obj -> v.values.any { it.isOpaque }
            else -> false
        }

    /** Canonical encoding: JSON with sorted object keys, no whitespace, bytes as base64 under a marker. */
    fun canonical(): String = buildString { encode(this@Teleported, this) }

    val cid: ContentId get() = ContentId.of(canonical().encodeToByteArray())

    companion object {
        /** Project a plain Kotlin value (the common subset every target has). */
        fun ofHost(o: Any?): Teleported = when (o) {
            null -> Null
            is Teleported -> o
            is Boolean -> Bool(o)
            is Long -> Num(o)
            // Kotlin/JS: every number is both `Int` and `Double`; decide by fractional part there, exact types on the JVM.
            is Float, is Double -> { val d = (o as Number).toDouble(); if (o is Int && d == floor(d) && !d.isInfinite()) Num(d.toLong()) else Real(d) }
            is Byte, is Short, is Int -> Num((o as Number).toLong())
            is String -> Str(o)
            is ByteArray -> Bytes(o)
            is List<*> -> Arr(o.map { ofHost(it) })
            is Array<*> -> Arr(o.map { ofHost(it) })
            is Map<*, *> -> Obj(o.entries.associate { it.key.toString() to ofHost(it.value) }.toList().sortedBy { it.first }.toMap())
            else -> Opaque(o.toString())
        }

        /** Build an envelope object from host values. */
        fun obj(vararg fields: Pair<String, Any?>): Obj = Obj(fields.associate { (k, v) -> k to ofHost(v) })

        /**
         * Exact inverse of [canonical]. A generic JSON parser turns every number into a Double and
         * loses Num/Real; this one keeps the distinction by token shape (no '.', 'e', 'E' ⇒ Num) and
         * restores the `$bytes` / `$opaque` markers. It is the only parser allowed on the wire.
         */
        fun parseCanonical(s: String): Teleported {
            var i = 0
            fun ws() { while (i < s.length && s[i] <= ' ') i++ }
            fun expect(c: Char) { ws(); require(i < s.length && s[i] == c) { "canonical: expected '$c' at $i in $s" }; i++ }
            fun str(): String {
                expect('"'); val sb = StringBuilder()
                while (true) {
                    val c = s[i++]
                    when (c) {
                        '"' -> return sb.toString()
                        '\\' -> when (val e = s[i++]) {
                            'n' -> sb.append('\n'); 'r' -> sb.append('\r'); 't' -> sb.append('\t'); 'b' -> sb.append('\b'); 'f' -> sb.append('')
                            'u' -> { sb.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4 }
                            else -> sb.append(e)
                        }
                        else -> sb.append(c)
                    }
                }
            }
            fun value(): Teleported {
                ws()
                return when (val c = s[i]) {
                    'n' -> { i += 4; Null }
                    't' -> { i += 4; Bool(true) }
                    'f' -> { i += 5; Bool(false) }
                    '"' -> Str(str())
                    '[' -> {
                        i++; val out = ArrayList<Teleported>(); ws()
                        if (s[i] == ']') { i++ } else {
                            while (true) { out += value(); ws(); if (s[i] == ',') { i++; continue }; expect(']'); break }
                        }
                        Arr(out)
                    }
                    '{' -> {
                        i++; val out = LinkedHashMap<String, Teleported>(); ws()
                        if (s[i] == '}') { i++ } else {
                            while (true) { val k = str(); expect(':'); out[k] = value(); ws(); if (s[i] == ',') { i++; ws(); continue }; expect('}'); break }
                        }
                        when {
                            out.size == 1 && out.containsKey("\$bytes") -> Bytes(Base64.decode((out["\$bytes"] as Str).v))
                            out.size == 1 && out.containsKey("\$opaque") -> Opaque((out["\$opaque"] as Str).v)
                            else -> Obj(out)
                        }
                    }
                    else -> {
                        require(c == '-' || c in '0'..'9') { "canonical: unexpected '$c' at $i in $s" }
                        val start = i; while (i < s.length && (s[i] in '0'..'9' || s[i] == '-' || s[i] == '+' || s[i] == '.' || s[i] == 'e' || s[i] == 'E')) i++
                        val tok = s.substring(start, i)
                        if (tok.any { it == '.' || it == 'e' || it == 'E' }) Real(tok.toDouble()) else Num(tok.toLong())
                    }
                }
            }
            try {
                val v = value(); ws(); require(i == s.length) { "canonical: trailing input at $i in $s" }
                return v
            } catch (e: IndexOutOfBoundsException) {
                throw IllegalArgumentException("canonical: truncated input at $i in $s", e)
            } catch (e: NumberFormatException) {
                throw IllegalArgumentException("canonical: bad number at $i in $s", e)
            }
        }

        private fun encode(t: Teleported, sb: StringBuilder) {
            when (t) {
                Null -> sb.append("null")
                is Bool -> sb.append(t.v)
                is Num -> sb.append(t.v)
                is Real -> sb.append(if (t.v == floor(t.v) && !t.v.isInfinite()) t.v.toLong().toString() + ".0" else t.v.toString())
                is Str -> quote(t.v, sb)
                is Bytes -> { sb.append("{\"\$bytes\":"); quote(Base64.encode(t.v), sb); sb.append('}') }
                is Arr -> { sb.append('['); t.v.forEachIndexed { i, e -> if (i > 0) sb.append(','); encode(e, sb) }; sb.append(']') }
                is Obj -> {
                    sb.append('{')
                    var first = true
                    for (k in t.v.keys.sorted()) { if (!first) sb.append(','); first = false; quote(k, sb); sb.append(':'); encode(t.v.getValue(k), sb) }
                    sb.append('}')
                }
                is Opaque -> { sb.append("{\"\$opaque\":"); quote(t.repr, sb); sb.append('}') }
            }
        }

        private fun quote(s: String, sb: StringBuilder) {
            sb.append('"')
            for (c in s) when (c) {
                '"' -> sb.append("\\\""); '\\' -> sb.append("\\\\"); '\n' -> sb.append("\\n"); '\r' -> sb.append("\\r"); '\t' -> sb.append("\\t")
                else -> if (c < ' ') sb.append("\\u").append(c.code.toString(16).padStart(4, '0')) else sb.append(c)
            }
            sb.append('"')
        }
    }
}

// ── envelope helpers: a Teleported.Obj is also the wire envelope of SubVmProtocol ──
fun Teleported.str(key: String): String? = (this as? Teleported.Obj)?.v?.get(key)?.let { (it as? Teleported.Str)?.v }
fun Teleported.int(key: String): Int? = (this as? Teleported.Obj)?.v?.get(key)?.let { (it as? Teleported.Num)?.v?.toInt() }
fun Teleported.bool(key: String): Boolean? = (this as? Teleported.Obj)?.v?.get(key)?.let { (it as? Teleported.Bool)?.v }
fun Teleported.field(key: String): Teleported? = (this as? Teleported.Obj)?.v?.get(key)
