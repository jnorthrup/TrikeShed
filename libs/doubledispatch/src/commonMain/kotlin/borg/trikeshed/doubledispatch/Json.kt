@file:Suppress("INLINE_CLASS_DEPRECATED")

package borg.trikeshed.doubledispatch

/** Inline class packing start/end offsets — no allocation at runtime. */
inline class Region(val packed: Long) {
    val start: Int get() = (packed ushr 32).toInt()
    val end: Int get() = packed.toInt()

    override fun toString(): String = "Region($start,$end)"

    companion object {
        fun of(start: Int, end: Int): Region = Region((start.toLong() shl 32) or (end.toLong() and 0xffff_ffffL))
    }
}

/** Inline class wrapping the force lambda — avoids vtable shim pointer.
 *  The phantom Type parameter carries monomorphic type witness at compile time.
 *  Dispatch is by static argument type: each factory function (reifyString, reifyNumber, etc.)
 *  hard-codes the argument type passed to Factory.reify(), selecting the correct overload
 *  at compile time. The phantom Type is never accessed at runtime. */
@Suppress("INLINE_CLASS_DEPRECATED")
inline class Reifier<R, out Type>(val _force: (Factory<R>) -> R) {
    fun force(factory: Factory<R>): R = _force(factory)
}

typealias Obj<R> = Map<String, Reifier<R, *>>
typealias Arr<R> = List<Reifier<R, *>>

/** Double-dispatch interface. Each overload is selected at compile time
 *  by the static argument type passed from the factory functions.
 *  All value types are non-nullable — the parser guarantees non-null values.
 *  Only reify(Nothing?) handles the JSON null literal. */
interface Factory<R> {
    fun reify(value: String, at: Region): R
    fun reify(value: Number, at: Region): R
    fun reify(value: Obj<R>, at: Region): R
    fun reify(value: Arr<R>, at: Region): R
    fun reify(value: Boolean, at: Region): R
    fun reify(value: Nothing?, at: Region): R
}

/** Monomorphic factory for double dispatch with full type witness fidelity.
 *  Each reify() overload is selected at compile time by static argument type.
 *  No null checks needed — the parser guarantees non-null values. */
object JsonFactory : Factory<String> {
    override fun reify(value: String, at: Region): String = value
    override fun reify(value: Number, at: Region): String = value.toString()
    override fun reify(value: Boolean, at: Region): String = value.toString()
    override fun reify(value: Nothing?, at: Region): String = "null"

    override fun reify(value: Obj<String>, at: Region): String {
        val fields = value.mapValues { (_, child) -> child.force(this) }
        return fields.entries.joinToString(",", "{", "}") { (k, v) -> "\"$k\":$v" }
    }

    override fun reify(value: Arr<String>, at: Region): String {
        val elements = value.map { child -> child.force(this) }
        return elements.joinToString(",", "[", "]")
    }
}

/** Memoizing wrapper that caches the reified result keyed by Region.
 *  Uses composition over inheritance since Reifier is an inline class (final).
 *  Since we scan for types (the Type parameter) and memoize the witness,
 *  the decoding is on-demand only - we look up by Region to get the cached result. */
class MemoizingReifier<R>(
    val delegate: Reifier<R, *>, val region: Region
) {
    var cached: R? = null

    /** Force reification, caching the result */
    fun force(factory: Factory<R>): R {
        return cached ?: delegate.force(factory).also { cached = it }
    }

    /** Decode the memoized value at this Region on demand */
    fun decode(): R? = cached
}

/** Extension to memoize a reifier with its source Region */
fun <R> Reifier<R, *>.memoize(region: Region): MemoizingReifier<R> = MemoizingReifier(this, region)

/** Scanner that collects Reifiers and their Regions during parsing.
 *  The parser threads a collector through the parse tree — no second walk
 *  needed, no walk-factory shim pointer allocation.
 *  Decoding is on-demand only — reify by Region without re-scanning. */
class RegionScanner<R>(
    val source: String
) {
    val reifiers = mutableMapOf<Region, Reifier<R, *>>()

    /** Scan the source and collect all Reifiers keyed by their Region.
     *  Parsing directly populates the region map — single pass, no walk factory. */
    fun scan(): Map<Region, Reifier<R, *>> {
        reifiers.clear()
        val parser = Parser(source)
        parser.parseDepth0<R> { region, reifier -> reifiers[region] = reifier }
        return reifiers
    }

    /** On-demand decode at a specific Region.
     *  The reification happens here — we look up the Reifier by Region and force it. */
    fun decodeAt(region: Region, factory: Factory<R>): R? = reifiers[region]?.force(factory)

    /** Decode all regions with a factory.
     *  Forces reification for all collected Reifiers. */
    fun decodeAll(factory: Factory<R>): Map<Region, R> = reifiers.mapValues { (_, reifier) -> reifier.force(factory) }

    /** Get the number of regions scanned */
    fun regionCount(): Int = reifiers.size

    /** Get all scanned regions */
    fun regions(): Set<Region> = reifiers.keys.toSet()
}

/** Entry point for double dispatch. The phantom Type parameter carries
 *  compile-time type witness but is never accessed at runtime.
 *  Dispatch is by static argument type in the factory functions. */
fun <R, Type> produce(
    factory: Factory<R>, payload: Reifier<R, Type>
): R = payload.force(factory)

/** Factory functions — each creates a Reifier with phantom Type witness.
 *  Dispatch is by static argument type: each function hard-codes which value type
 *  it passes to Factory.reify(), so the compiler selects the correct overload.
 *  The phantom Type parameter carries monomorphic witness at compile time only.
 *  All lazy lambdas return non-nullable values — the parser guarantees non-null. */
fun <R, Type> reifyString(
    at: Region, lazy: (Region) -> String
): Reifier<R, Type> = Reifier { factory -> factory.reify(lazy(at), at) }

fun <R, Type> reifyNumber(
    at: Region, lazy: (Region) -> Number
): Reifier<R, Type> = Reifier { factory -> factory.reify(lazy(at), at) }

fun <R, Type> reifyBoolean(
    at: Region, lazy: (Region) -> Boolean
): Reifier<R, Type> = Reifier { factory -> factory.reify(lazy(at), at) }

fun <R, Type> reifyObject(
    at: Region, lazy: (Region) -> Obj<R>
): Reifier<R, Type> = Reifier { factory -> factory.reify(lazy(at), at) }

fun <R, Type> reifyArray(
    at: Region, lazy: (Region) -> Arr<R>
): Reifier<R, Type> = Reifier { factory -> factory.reify(lazy(at), at) }

fun <R, Type> reifyNull(
    at: Region
): Reifier<R, Type> = Reifier { factory -> factory.reify(null, at) }

/** JSON parser that produces a lazy Reifier tree.
 *  Optionally threads a collector to capture Region→Reifier mappings during parse,
 *  eliminating the need for a second tree walk (no walk-factory shim pointer).
 *  Dispatch is by static argument type — each factory function hard-codes the value type
 *  passed to Factory.reify(), so the compiler selects the correct overload.
 *  All parse methods return Reifiers with non-nullable phantom types — the parser
 *  guarantees non-null values for strings, numbers, booleans, objects, and arrays. */
class Parser(val src: String) {
    var i: Int = 0

    /** Parse top-level value. Optionally collect Region→Reifier mappings via collector. */
    fun <R> parseDepth0(collector: ((Region, Reifier<R, *>) -> Unit)? = null): Reifier<R, *> {
        val payload: Reifier<R, *> = parseValue(collector)
        skipWs()
        if (i != src.length) fail("trailing input")
        return payload
    }

    /** THE ONE TRUE UNINLINABLE PART */
    fun <R> parseValue(collector: ((Region, Reifier<R, *>) -> Unit)? = null): Reifier<R, *> {
        skipWs()
        if (i >= src.length) fail("expected value")

        return when (src[i]) {
            '"' -> parseStringValue<R>(collector)
            '{' -> parseObject<R>(collector)
            '[' -> parseArray<R>(collector)
            't', 'f' -> parseBoolean<R>(collector)
            'n' -> parseNull<R>(collector)
            '-', in '0'..'9' -> parseNumber<R>(collector)
            else -> fail("expected value")
        }
    }

    fun <R> parseStringValue(collector: ((Region, Reifier<R, *>) -> Unit)? = null): Reifier<R, String> {
        val (value, at) = readString()
        val r: Reifier<R, String> = reifyString<R, String>(at) { value }
        collector?.invoke(at, r)
        return r
    }

    fun <R> parseNumber(collector: ((Region, Reifier<R, *>) -> Unit)? = null): Reifier<R, Number> {
        val start = i

        if (peek('-')) i++

        digits("number")

        if (peek('.')) {
            i++
            digits("fraction")
        }

        if (peek('e') || peek('E')) {
            i++
            if (peek('+') || peek('-')) i++
            digits("exponent")
        }

        val end = i
        val raw = src.substring(start, end)
        val at = Region.of(start, end)

        val r: Reifier<R, Number> = reifyNumber<R, Number>(at) {
            if (raw.any { it == '.' || it == 'e' || it == 'E' }) {
                raw.toDouble()
            } else {
                raw.toLong()
            }
        }
        collector?.invoke(at, r)
        return r
    }

    fun <R> parseBoolean(collector: ((Region, Reifier<R, *>) -> Unit)? = null): Reifier<R, Boolean> {
        val start = i

        val value: Boolean = if (src.startsWith("true", i)) {
            i += 4
            true
        } else if (src.startsWith("false", i)) {
            i += 5
            false
        } else {
            fail("expected boolean")
        }

        val at = Region.of(start, i)
        val r: Reifier<R, Boolean> = reifyBoolean<R, Boolean>(at) { value }
        collector?.invoke(at, r)
        return r
    }

    fun <R> parseNull(collector: ((Region, Reifier<R, *>) -> Unit)? = null): Reifier<R, Nothing?> {
        val start = i
        expectWord("null")
        val at = Region.of(start, i)
        val r: Reifier<R, Nothing?> = reifyNull<R, Nothing?>(at)
        collector?.invoke(at, r)
        return r
    }

    fun <R> parseArray(collector: ((Region, Reifier<R, *>) -> Unit)? = null): Reifier<R, Arr<R>> {
        val start = i
        expect('[')

        val items = ArrayList<Reifier<R, *>>()

        skipWs()
        if (tryChar(']')) {
            val at = Region.of(start, i)
            val r: Reifier<R, Arr<R>> = reifyArray<R, Arr<R>>(at) { items }
            collector?.invoke(at, r)
            return r
        }

        while (true) {
            items += parseValue<R>(collector)
            skipWs()

            if (tryChar(',')) continue

            expect(']')
            break
        }

        val at = Region.of(start, i)
        val r: Reifier<R, Arr<R>> = reifyArray<R, Arr<R>>(at) { items }
        collector?.invoke(at, r)
        return r
    }

    fun <R> parseObject(collector: ((Region, Reifier<R, *>) -> Unit)? = null): Reifier<R, Obj<R>> {
        val start = i
        expect('{')

        val fields = LinkedHashMap<String, Reifier<R, *>>()

        skipWs()
        if (tryChar('}')) {
            val at = Region.of(start, i)
            val r: Reifier<R, Obj<R>> = reifyObject<R, Obj<R>>(at) { fields }
            collector?.invoke(at, r)
            return r
        }

        while (true) {
            skipWs()
            val (key, _) = readString()

            skipWs()
            expect(':')

            fields[key] = parseValue<R>(collector)

            skipWs()
            if (tryChar(',')) continue

            expect('}')
            break
        }

        val at = Region.of(start, i)
        val r: Reifier<R, Obj<R>> = reifyObject<R, Obj<R>>(at) { fields }
        collector?.invoke(at, r)
        return r
    }

    fun readString(): Pair<String, Region> {
        val start = i

        if (!peek('"')) fail("expected string")
        i++

        val out = StringBuilder()

        while (i < src.length && src[i] != '"') {
            when (val c = src[i]) {
                '\\' -> {
                    i++
                    if (i >= src.length) fail("unexpected end")
                    out.append(
                        when (src[i]) {
                            '"' -> '"'
                            '\\' -> '\\'
                            '/' -> '/'
                            'b' -> '\b'
                            'f' -> '\u000C'
                            'n' -> '\n'
                            'r' -> '\r'
                            't' -> '\t'
                            'u' -> {
                                if (i + 4 >= src.length) fail("invalid unicode")
                                i++
                                val hex = src.substring(i, i + 4)
                                i += 3
                                hex.toInt(16).toChar()
                            }

                            else -> fail("invalid escape")
                        },
                    )
                }

                else -> out.append(c)
            }
            i++
        }

        if (i >= src.length || src[i] != '"') fail("unterminated string")
        i++

        return Pair(out.toString(), Region.of(start, i))
    }

    fun expect(c: Char) {
        skipWs()
        if (i >= src.length || src[i] != c) fail("expected '$c'")
        i++
    }

    fun tryChar(c: Char): Boolean {
        skipWs()
        if (i < src.length && src[i] == c) {
            i++
            return true
        }
        return false
    }

    fun expectWord(word: String) {
        if (!src.startsWith(word, i)) fail("expected $word")
        i += word.length
    }

    fun peek(c: Char): Boolean = i < src.length && src[i] == c

    fun digits(context: String) {
        var hadDigit = false
        while (i < src.length && src[i] in '0'..'9') {
            i++
            hadDigit = true
        }
        if (!hadDigit) fail("expected digits in $context")
    }

    fun skipWs() {
        while (i < src.length && src[i] in " \t\n\r") i++
    }

    fun fail(msg: String): Nothing = throw IllegalArgumentException("$msg at position $i in: ${src.take(i)}...")
}

/** Demonstrates compile-time double dispatch with phantom type witnesses.
 *  The scanner captures Reifiers keyed by Region during parsing — single pass.
 *  Decoding is on-demand only — no walk factory, no shim pointers.
 *  Double dispatch selects the correct Factory.reify() overload by static argument type.
 *  Pure double dispatch pattern — no sealed classes, no reified types, no runtime type checks. */
fun main() {
    val input = """
        {
          "name": "Ada",
          "age": 37,
          "ok": true,
          "xs": [1, null, "x"]
        }
    """.trimIndent()

    // Single-pass scan: parser collects all Reifiers by Region during parse
    val scanner = RegionScanner<String>(input)
    val reifiersByRegion = scanner.scan()

    println("Scanned ${reifiersByRegion.size} regions")
    reifiersByRegion.keys.forEach { region ->
        println("  Region: $region")
    }

    // On-demand decode at specific Region — no re-scan needed
    println("\nOn-demand decoding:")
    for (region in reifiersByRegion.keys) {
        scanner.decodeAt(region, JsonFactory)?.let { value ->
            println("  $region: $value")
        }
    }

    // Or decode all at once
    val allDecoded = scanner.decodeAll(JsonFactory)
    println("\nAll decoded values:")
    allDecoded.forEach { (region, value) ->
        println("  $region: $value")
    }
}
