@file:Suppress("UNCHECKED_CAST", "DEPRECATION")

package borg.trikeshed.parse.confix

import borg.trikeshed.cursor.Cursor
import borg.trikeshed.cursor.IOMemento
import borg.trikeshed.cursor.RowVec
import borg.trikeshed.lib.j
import borg.trikeshed.lib.size
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialFormat
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.modules.EmptySerializersModule
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.serializer
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/**
 * Confix — a facet-enabled serialization provider backed by [ConfixDoc] cursors.
 *
 * Provides three syntaxes through a single cursor-based model:
 *  - [ConfixSyntax.JSON] — text, `{ ... }` objects
 *  - [ConfixSyntax.CBOR] — binary, self-describing TLV
 *  - [ConfixSyntax.YAML] — text, indentation-based mapping; the new facet path
 *
 * Round-trip contract per syntax:
 *
 *   encode value  →  ConfixJsonElement (faceted intermediate)
 *                  →  ConfixDoc via [docFromJsonElement]
 *                  →  emit bytes (JSON/CBOR/YAML via the ConfixDoc cursor)
 *
 *   parse bytes   →  ConfixDoc via [confixDoc] (the existing Syntax scanner)
 *                  →  ConfixJsonElement via [jsonElementFromDoc] (cursor walk)
 *                  →  decode value via kotlinx JsonElement bridge
 *
 * The ConfixDoc's index facets ([ConfixIndexK.KeyToChild] for objects,
 * [ConfixIndexK.DirectChildren] for arrays) ARE the navigation surface —
 * the format owns no separate DOM. The kotlinx.serialization Encoder/Decoder
 * bridge to [JsonElement] keeps `@Serializable` working unchanged.
 */
@OptIn(ExperimentalSerializationApi::class)
sealed class ConfixFormat<Repr> : SerialFormat {

    abstract val syntax: ConfixSyntax
    abstract val configuration: ConfixConfig
    final override val serializersModule: SerializersModule get() = configuration.serializersModule

    abstract fun <T> encode(serializer: SerializationStrategy<T>, value: T): Repr
    abstract fun <T> decode(deserializer: DeserializationStrategy<T>, source: Repr): T

    inline fun <reified T> encode(value: T): Repr = encode(serializer(), value)
    inline fun <reified T> decode(source: Repr): T = decode(serializer<T>(), source)
}

/** Configuration shared by all Confix syntaxes. */
data class ConfixConfig(
    val serializersModule: SerializersModule = EmptySerializersModule,
    val prettyPrint: Boolean = false,
    val ignoreUnknownKeys: Boolean = true,
    val isLenient: Boolean = true,
    val encodeDefaults: Boolean = true,
) {
    companion object { val DEFAULT = ConfixConfig() }
}

/** Syntax discriminant. */
enum class ConfixSyntax { JSON, CBOR, YAML }

// ─────────────────────────────────────────────────────────────────────────────
// String form (JSON / YAML)
// ─────────────────────────────────────────────────────────────────────────────

class ConfixString(
    override val syntax: ConfixSyntax,
    override val configuration: ConfixConfig = ConfixConfig.DEFAULT,
) : ConfixFormat<String>() {

    override fun <T> encode(serializer: SerializationStrategy<T>, value: T): String {
        // value -> JsonElement (via kotlinx Json)
        val element = ConfixJsonBridge.toJsonElement(serializer, value, configuration)
        // JsonElement -> wire text. The cursor round-trip (JsonElement → ConfixDoc
        // → text) is intentionally bypassed on the encode side because the
        // Syntax scanner reverses token order in its flat index; the decode
        // side uses the cursor facets for navigation, which is the facet-enabled
        // model. Emit directly from the well-ordered JsonElement.
        return ConfixDocTextEmitter.emitElement(element, syntax, configuration)
    }

    override fun <T> decode(deserializer: DeserializationStrategy<T>, source: String): T {
        // wire text -> ConfixDoc (existing Syntax scanner builds the faceted index)
        val bytes = source.encodeToByteArray()
        val doc = when (syntax) {
            ConfixSyntax.JSON -> confixDoc(bytes, Syntax.JSON)
            ConfixSyntax.YAML -> confixDoc(bytes, Syntax.YAML)
            ConfixSyntax.CBOR -> confixDoc(bytes, Syntax.JSON)
        }
        // ConfixDoc -> JsonElement (cursor walk over the faceted index)
        val element = jsonElementFromDoc(doc)
        // JsonElement -> value (via kotlinx Json)
        return ConfixJsonBridge.fromJsonElement(deserializer, element, configuration)
    }

    companion object {
        val Json: ConfixString get() = ConfixString(ConfixSyntax.JSON)
        val Yaml: ConfixString get() = ConfixString(ConfixSyntax.YAML)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Binary form (CBOR)
// ─────────────────────────────────────────────────────────────────────────────

class ConfixBinary(
    override val configuration: ConfixConfig = ConfixConfig.DEFAULT,
) : ConfixFormat<ByteArray>() {

    override val syntax: ConfixSyntax = ConfixSyntax.CBOR

    override fun <T> encode(serializer: SerializationStrategy<T>, value: T): ByteArray {
        val element = ConfixJsonBridge.toJsonElement(serializer, value, configuration)
        return ConfixCborEmitter.emit(element)
    }
    override fun <T> decode(deserializer: DeserializationStrategy<T>, source: ByteArray): T {
        val doc = confixDoc(source, Syntax.CBOR)
        val element = jsonElementFromDoc(doc)
        return ConfixJsonBridge.fromJsonElement(deserializer, element, configuration)
    }

    companion object { val Default: ConfixBinary get() = ConfixBinary() }
}

// ─────────────────────────────────────────────────────────────────────────────
// ConfixDoc <-> JsonElement — cursor bridge
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Build a [ConfixDoc] from a [JsonElement] by serializing the element to JSON
 * text and scanning it with [Syntax.JSON]. The resulting doc's facets
 * ([ConfixIndexK.KeyToChild], [ConfixIndexK.DirectChildren]) describe the
 * element's structure for cursor-driven navigation.
 */
internal fun docFromJsonElement(element: JsonElement): ConfixDoc =
    confixDoc(element.toString().encodeToByteArray(), Syntax.JSON)

/**
 * Walk a parsed [ConfixDoc]'s cursor tree into a kotlinx [JsonElement].
 *
 * Object nodes use the [ConfixIndexK.KeyToChild] facet implicitly via
 * [RowVec.step]; array nodes use ordered children; scalars reify via
 * [RowVec.reify] over the source byte series. This is the cursor path —
 * no DOM is materialized by the format itself.
 */
internal fun jsonElementFromDoc(doc: ConfixDoc): JsonElement {
    val root: RowVec? = doc.root ?: return JsonNull
    return rowVecToJson(root!!, doc)
}

private fun rowVecToJson(rv: RowVec, doc: ConfixDoc): JsonElement {
    return when (rv.tag) {
        IOMemento.IoObject -> {
            val kids = rv.kids
            val map = LinkedHashMap<String, JsonElement>()
            var i = 0
            while (i + 1 < kids.size) {
                val keyRv = kids.b(i); val valRv = kids.b(i + 1)
                val key = reifyKey(keyRv, doc)
                map[key] = rowVecToJson(valRv, doc)
                i += 2
            }
            JsonObject(map)
        }
        IOMemento.IoArray -> {
            val kids = rv.kids
            val items = ArrayList<JsonElement>(kids.size)
            for (i in 0 until kids.size) items.add(rowVecToJson(kids.b(i), doc))
            JsonArray(items)
        }
        else -> {
            val v = rv.reify(doc.src) ?: return JsonNull
            when (v) {
                is Boolean -> JsonPrimitive(v)
                is Number -> {
                    val dbl = v.toDouble()
                    if (dbl == dbl.toLong().toDouble()) JsonPrimitive(v.toLong()) else JsonPrimitive(dbl)
                }
                is String -> JsonPrimitive(v)
                else -> JsonPrimitive(v.toString())
            }
        }
    }
}

private fun reifyKey(rv: RowVec, doc: ConfixDoc): String {
    val src = doc.src
    return when (rv.tag) {
        IOMemento.IoString -> {
            val o = rv.open + 1; val c = rv.close - 1
            if (c >= o) CharArray(c - o + 1) { src.b(o + it).toInt().toChar() }.concatToString() else ""
        }
        else -> {
            val o = rv.open; val c = rv.close
            if (c >= o) CharArray(c - o + 1) { src.b(o + it).toInt().toChar() }.concatToString() else ""
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// ConfixDocTextEmitter — ConfixDoc → JSON / YAML text (cursor walk)
// ─────────────────────────────────────────────────────────────────────────────

internal object ConfixDocTextEmitter {

    /**
     * Emit a [JsonElement] directly to wire text without round-tripping through
     * [ConfixDoc]. This is the encode-side fast path used by [ConfixString.encode].
     */
    fun emitElement(element: JsonElement, syntax: ConfixSyntax, cfg: ConfixConfig): String {
        val sb = StringBuilder()
        emitJsonElement(sb, element, if (cfg.prettyPrint) 0 else -1, cfg)
        return sb.toString()
    }

    private fun emitJsonElement(sb: StringBuilder, element: JsonElement, indent: Int, cfg: ConfixConfig) {
        when (element) {
            is JsonObject -> {
                val pretty = indent >= 0
                sb.append("{")
                var written = 0
                for ((k, v) in element) {
                    if (written > 0) sb.append(",")
                    if (pretty) sb.append("\n").append(" ".repeat(indent + 2))
                    sb.append("\"").append(k.replace("\\", "\\\\").replace("\"", "\\\"")).append("\":")
                    if (pretty) sb.append(" ")
                    emitJsonElement(sb, v, if (pretty) indent + 2 else -1, cfg)
                    written++
                }
                if (pretty && written > 0) sb.append("\n").append(" ".repeat(indent))
                sb.append("}")
            }
            is JsonArray -> {
                val pretty = indent >= 0
                sb.append("[")
                element.forEachIndexed { i, v ->
                    if (i > 0) sb.append(",")
                    if (pretty) sb.append("\n").append(" ".repeat(indent + 2))
                    emitJsonElement(sb, v, if (pretty) indent + 2 else -1, cfg)
                }
                if (pretty && element.isNotEmpty()) sb.append("\n").append(" ".repeat(indent))
                sb.append("]")
            }
            is JsonPrimitive -> {
                if (element.isString) sb.append("\"").append(element.content
                    .replace("\\", "\\\\").replace("\"", "\\\"")
                    .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t"))
                    .append("\"")
                else sb.append(element.content)
            }
            JsonNull -> sb.append("null")
        }
    }

    fun emit(doc: ConfixDoc, syntax: ConfixSyntax, cfg: ConfixConfig): String {
        val sb = StringBuilder()
        val root: RowVec? = doc.root
        if (root == null) { sb.append("null"); return sb.toString() }
        emitRow(sb, root!!, doc, syntax, if (cfg.prettyPrint) 0 else -1, cfg)
        return sb.toString()
    }

    private fun emitRow(sb: StringBuilder, rv: RowVec, doc: ConfixDoc, syntax: ConfixSyntax, indent: Int, cfg: ConfixConfig) {
        when (rv.tag) {
            IOMemento.IoObject -> emitObj(sb, rv, doc, syntax, indent, cfg)
            IOMemento.IoArray -> emitArr(sb, rv, doc, syntax, indent, cfg)
            else -> emitScalar(sb, rv, doc)
        }
    }

    private fun emitObj(sb: StringBuilder, rv: RowVec, doc: ConfixDoc, syntax: ConfixSyntax, indent: Int, cfg: ConfixConfig) {
        val kids = rv.kids
        val pairs = kids.size / 2
        if (pairs == 0) { sb.append("{}"); return }
        val pretty = indent >= 0
        when (syntax) {
            ConfixSyntax.JSON -> {
                sb.append("{")
                var written = 0
                var i = 0
                while (i + 1 < kids.size) {
                    if (written > 0) sb.append(",")
                    if (pretty) sb.append("\n").append(" ".repeat(indent + 2))
                    sb.append(quote(reifyKey(kids.b(i), doc))).append(if (pretty) ": " else ":")
                    emitRow(sb, kids.b(i + 1), doc, syntax, if (pretty) indent + 2 else -1, cfg)
                    written++; i += 2
                }
                if (pretty) sb.append("\n").append(" ".repeat(indent))
                sb.append("}")
            }
            ConfixSyntax.YAML -> {
                var written = 0
                var i = 0
                while (i + 1 < kids.size) {
                    if (written > 0 || indent > 0) sb.append("\n").append(" ".repeat(indent))
                    sb.append(reifyKey(kids.b(i), doc)).append(":")
                    val v = kids.b(i + 1)
                    when (v.tag) {
                        IOMemento.IoObject, IOMemento.IoArray -> {
                            sb.append("\n").append(" ".repeat(indent + 2))
                            emitRow(sb, v, doc, syntax, indent + 2, cfg)
                        }
                        else -> { sb.append(" "); emitRow(sb, v, doc, syntax, indent, cfg) }
                    }
                    written++; i += 2
                }
            }
            ConfixSyntax.CBOR -> {
                // CBOR wire is binary; text fallback mirrors JSON
                sb.append("{")
                var written = 0; var i = 0
                while (i + 1 < kids.size) {
                    if (written > 0) sb.append(",")
                    sb.append(quote(reifyKey(kids.b(i), doc))).append(":")
                    emitRow(sb, kids.b(i + 1), doc, syntax, -1, cfg)
                    written++; i += 2
                }
                sb.append("}")
            }
        }
    }

    private fun emitArr(sb: StringBuilder, rv: RowVec, doc: ConfixDoc, syntax: ConfixSyntax, indent: Int, cfg: ConfixConfig) {
        val kids = rv.kids
        if (kids.size == 0) { sb.append("[]"); return }
        val pretty = indent >= 0
        when (syntax) {
            ConfixSyntax.JSON -> {
                sb.append("[")
                for (i in 0 until kids.size) {
                    if (i > 0) sb.append(",")
                    if (pretty) sb.append("\n").append(" ".repeat(indent + 2))
                    emitRow(sb, kids.b(i), doc, syntax, if (pretty) indent + 2 else -1, cfg)
                }
                if (pretty) sb.append("\n").append(" ".repeat(indent))
                sb.append("]")
            }
            ConfixSyntax.YAML -> {
                for (i in 0 until kids.size) {
                    if (i > 0 || indent > 0) sb.append("\n").append(" ".repeat(indent))
                    sb.append("- ")
                    val v = kids.b(i)
                    when (v.tag) {
                        IOMemento.IoObject, IOMemento.IoArray -> emitRow(sb, v, doc, syntax, indent + 2, cfg)
                        else -> emitRow(sb, v, doc, syntax, indent, cfg)
                    }
                }
            }
            ConfixSyntax.CBOR -> {
                sb.append("[")
                for (i in 0 until kids.size) {
                    if (i > 0) sb.append(",")
                    emitRow(sb, kids.b(i), doc, syntax, -1, cfg)
                }
                sb.append("]")
            }
        }
    }

    private fun emitScalar(sb: StringBuilder, rv: RowVec, doc: ConfixDoc) {
        when (rv.tag) {
            IOMemento.IoNothing -> sb.append("null")
            IOMemento.IoBoolean -> {
                val o = rv.open; val c = rv.close
                val s = if (c >= o) CharArray(c - o + 1) { doc.src.b(o + it).toInt().toChar() }.concatToString() else "false"
                sb.append(if (s.startsWith("t")) "true" else "false")
            }
            else -> {
                val o = rv.open; val c = rv.close
                val raw = if (c >= o) CharArray(c - o + 1) { doc.src.b(o + it).toInt().toChar() }.concatToString() else ""
                // IoString was quoted; emit raw value, re-quoted if it had quotes
                when (rv.tag) {
                    IOMemento.IoString -> sb.append(quote(raw.trim('"')))
                    else -> sb.append(raw)
                }
            }
        }
    }

    private fun quote(s: String): String {
        val sb = StringBuilder("\"")
        for (c in s) when (c) {
            '"' -> sb.append("\\\""); '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n"); '\r' -> sb.append("\\r"); '\t' -> sb.append("\\t")
            else -> if (c.code < 0x20) sb.append("\\u%04x".format(c.code)) else sb.append(c)
        }
        sb.append("\""); return sb.toString()
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// ConfixCborEmitter — JsonElement → CBOR bytes
// ─────────────────────────────────────────────────────────────────────────────

internal object ConfixCborEmitter {

    fun emit(element: JsonElement): ByteArray {
        val out = ByteArrayOutputStream()
        write(out, element)
        return out.toByteArray()
    }

    private fun write(out: ByteArrayOutputStream, e: JsonElement) {
        when (e) {
            JsonNull -> out.write(0xF6)
            is JsonPrimitive -> {
                val v = e.content
                val bool = e.booleanOrNull
                val long = v.toLongOrNull()
                val dbl = v.toDoubleOrNull()
                when {
                    bool != null -> out.write(if (bool) 0xF5 else 0xF4)
                    long != null -> writeHead(out, 0, long)
                    dbl != null -> { out.write(0xFB); out.write(ByteBuffer.allocate(8).putDouble(dbl).array()) }
                    else -> { val b = v.encodeToByteArray(); writeHead(out, 3, b.size.toLong()); out.write(b) }
                }
            }
            is JsonArray -> {
                writeHead(out, 4, e.size.toLong())
                e.forEach { write(out, it) }
            }
            is JsonObject -> {
                writeHead(out, 5, e.size.toLong())
                e.forEach { (k, v) ->
                    val kb = k.encodeToByteArray(); writeHead(out, 3, kb.size.toLong()); out.write(kb)
                    write(out, v)
                }
            }
        }
    }

    private fun writeHead(out: ByteArrayOutputStream, mt: Int, len: Long) {
        val base = mt shl 5
        when (len) {
            in 0..23 -> out.write(base or len.toInt())
            in 24..255 -> { out.write(base or 24); out.write(len.toInt()) }
            in 256..65535 -> { out.write(base or 25); out.write((len.toInt() ushr 8) and 0xFF); out.write(len.toInt() and 0xFF) }
            else -> { out.write(base or 26); for (s in 24 downTo 0 step 8) out.write((len ushr s).toInt() and 0xFF) }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// ConfixJsonBridge — JsonElement <-> @Serializable values (kotlinx Json core)
// ─────────────────────────────────────────────────────────────────────────────

internal object ConfixJsonBridge {

    fun <T> toJsonElement(serializer: SerializationStrategy<T>, value: T, cfg: ConfixConfig): JsonElement {
        val json = Json {
            ignoreUnknownKeys = cfg.ignoreUnknownKeys
            isLenient = cfg.isLenient
            encodeDefaults = cfg.encodeDefaults
            prettyPrint = false
        }
        return json.encodeToJsonElement(serializer, value)
    }

    fun <T> fromJsonElement(deserializer: DeserializationStrategy<T>, element: JsonElement, cfg: ConfixConfig): T {
        val json = Json {
            ignoreUnknownKeys = cfg.ignoreUnknownKeys
            isLenient = cfg.isLenient
            encodeDefaults = cfg.encodeDefaults
            prettyPrint = false
        }
        return json.decodeFromJsonElement(deserializer, element)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Top-level accessors
// ─────────────────────────────────────────────────────────────────────────────

/** Default JSON Confix format. */
val Confix: ConfixString get() = ConfixString.Json

/** Default YAML Confix format. */
val ConfixYaml: ConfixString get() = ConfixString.Yaml

/** Default CBOR Confix format. */
val ConfixCbor: ConfixBinary get() = ConfixBinary.Default
