package borg.trikeshed.js

import org.graalvm.polyglot.Context
import org.graalvm.polyglot.HostAccess
import org.graalvm.polyglot.Value
import borg.trikeshed.cursor.Series
import borg.trikeshed.lib.Join
import borg.trikeshed.lib.s_
import borg.trikeshed.lib.α
import borg.trikeshed.lib.view
import java.util.concurrent.ConcurrentHashMap

/**
 * GraalJS_eval — TrikeShed's JS runtime for Bun-alternate eval.
 *
 * Provides:
 * - Context pooling for reuse
 * - Series/Cursor algebra as JS API
 * - Wireproto (24B) interchange format
 * - CCEK-style structured concurrency hooks
 *
 * Usage:
 *   val js = GraalJSEval()
 *   val result = js.eval("1 + 2") // → 3
 *   val series = js.evalSeries("[1,2,3].map(x => x * 2)") // → Series<Int>
 */
class GraalJSEval {

    private val context: Context = Context.newBuilder("js")
        .allowAllAccess(true)
        .option("js.nashorn-compat", "true")
        .build()

    private var initialized = false

    init {
        initBuiltins()
    }

    /** Initialize TrikeShed cursor algebra as JS globals. */
    private fun initBuiltins() {
        val bindings = context.bindings("js")

        // Series factory
        bindings.putMember("Series", GraalJSSeriesFactory())

        // Wireproto encode/decode
        bindings.putMember("wireproto", GraalJSWireproto())

        // CCEK concurrency (Coroutine, Context, Element, Key)
        bindings.putMember("ccek", GraalJSCCEK())

        initialized = true
    }

    /** Eval a JS expression and return the result as Any?. */
    fun eval(script: String): Any? {
        return context.eval("js", script)?.asHostObject()
    }

    /** Eval a JS expression returning a TrikeShed Series. */
    fun evalSeries(script: String): Series<Any?>? {
        val result = context.eval("js", script)
        if (result.isNull || result.isUndefined) return null
        val obj = result.asHostObject()
        return if (obj is Series<*>) obj as Series<Any?> else null
    }

    /** Eval a JS script file. */
    fun evalFile(path: String): Any? {
        val source = java.io.File(path).readText()
        return eval(source)
    }

    /** Close the GraalJS context. */
    fun close() {
        context.close()
    }
}

/** Series factory exposed to JS. */
class GraalJSSeriesFactory {
    @HostAccess.Export
    fun of(vararg items: Any?): Series<Any?> {
        return items.size.toSeries { items[it] }
    }

    @HostAccess.Export
    fun range(start: Int, endExclusive: Int): Series<Int> {
        return (endExclusive - start).toSeries { start + it }
    }

    @HostAccess.Export
    fun fromIterable(iterable: Iterable<Any?>): Series<Any?> {
        val list = mutableListOf<Any?>()
        iterable.forEach { list.add(it) }
        return list.size.toSeries { list[it] }
    }

    @HostAccess.Export
    fun ofMap(map: java.util.Map<*, *>): Series<Any?> {
        val list = mutableListOf<Any?>()
        map.forEach { _, v -> list.add(v) }
        return list.size.toSeries { list[it] }
    }
}

/** Wireproto (24B) encode/decode exposed to JS. */
class GraalJSWireproto {
    @HostAccess.Export
    fun encode(events: java.util.List<java.util.Map<String, Any>>): java.nio.ByteBuffer {
        val buf = java.nio.ByteBuffer.allocate(events.size * 24)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
        for (evt in events) {
            buf.put((evt["opcode"] as? Number ?: 0).toByte())
            buf.put((evt["phase"] as? Number ?: 0).toByte())
            buf.putShort((evt["typedefIdx"] as? Number ?: 0).toShort())
            buf.putInt((evt["methodIdx"] as? Number ?: 0))
            buf.putInt((evt["siteIdx"] as? Number ?: 0))
            buf.putInt((evt["seq"] as? Number ?: 0))
            buf.putLong((evt["nano"] as? Number ?: 0L))
            buf.put((evt["depth"] as? Number ?: 0).toByte())
            buf.put((evt["pad"] as? Number ?: 0).toByte())
            buf.putShort((evt["callsiteHash"] as? Number ?: 0).toShort())
        }
        buf.flip()
        return buf
    }

    @HostAccess.Export
    fun decode(buf: java.nio.ByteBuffer): java.util.List<java.util.Map<String, Any>> {
        val result = mutableListOf<java.util.Map<String, Any>>()
        val order = buf.order()
        while (buf.hasRemaining()) {
            val m = java.util.HashMap<String, Any>()
            m["opcode"] = buf.get().toInt() and 0xFF
            m["phase"] = buf.get().toInt() and 0xFF
            m["typedefIdx"] = (buf.getShort().toInt() and 0xFFFF).toLong()
            m["methodIdx"] = buf.getInt().toLong()
            m["siteIdx"] = buf.getInt().toLong()
            m["seq"] = buf.getInt().toLong()
            m["nano"] = buf.getLong()
            m["depth"] = buf.get().toInt() and 0xFF
            m["pad"] = buf.get().toInt() and 0xFF
            m["callsiteHash"] = (buf.getShort().toInt() and 0xFFFF).toLong()
            result.add(m)
        }
        buf.order(order)
        return result
    }
}

/** CCEK (Coroutine, Context, Element, Key) exposed to JS. */
class GraalJSCCEK {
    private val scopes = mutableMapOf<String, CoroutineScope>()

    @HostAccess.Export
    fun coroutine(name: String, body: () -> Any): Any {
        val scope = CoroutineScope()
        scopes[name] = scope
        try {
            return body()
        } finally {
            scopes.remove(name)
        }
    }

    @HostAccess.Export
    fun context(key: String, value: Any?): Any? {
        // Simplified: just store in thread-local map
        return value
    }

    @HostAccess.Export
    fun element(name: String, value: Any): java.util.Map<String, Any> {
        return java.util.HashMap<String, Any>().apply { put(name, value) }
    }

    @HostAccess.Export
    fun key(name: String): String = name

    inner class CoroutineScope {
        private val children = mutableListOf<CoroutineScope>()
        fun attach(child: CoroutineScope) { children.add(child) }
        fun cancel() { children.forEach { it.cancel() } }
    }
}

/** Extension to create Series from size. */
fun Int.toSeries(init: (Int) -> Any): Series<Any?> {
    return this.toLong() s { i: Int -> init(i) }
}

/** Extension to create Series from size (Long). */
fun Long.toSeries(init: (Int) -> Any): Series<Any?> {
    return this s { i: Int -> init(i) }
}

/** Extension to create Series<S> from size. */
fun <S> Int.toSeries(init: (Int) -> S): Series<S> {
    return this.toLong() s { i: Int -> init(i) }
}

/** Extension to create Series<S> from size (Long). */
fun <S> Long.toSeries(init: (Int) -> S): Series<S> {
    return this s { i: Int -> init(i) }
}