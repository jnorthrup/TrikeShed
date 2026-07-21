@file:Suppress("UNCHECKED_CAST", "DEPRECATION")

package borg.trikeshed.parse.confix

import borg.trikeshed.cursor.Cursor
import borg.trikeshed.cursor.IOMemento
import borg.trikeshed.cursor.RowVec
import borg.trikeshed.lib.j
import borg.trikeshed.lib.size
import borg.trikeshed.lib.`▶`
import borg.trikeshed.lib.α
import borg.trikeshed.lib.toList
import borg.trikeshed.parse.yaml.YamlParser
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialFormat
import kotlinx.serialization.SerializationStrategy

Array
Element
Null
Object
Primitive

import kotlinx.serialization.modules.EmptySerializersModule
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.serializer



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
 *   encode value  →  ConfixConfixElement (faceted intermediate)
 *                  →  ConfixDoc via [docFromConfixElement]
 *                  →  emit bytes (JSON/CBOR/YAML via the ConfixDoc cursor)
 *
 *   parse bytes   →  ConfixDoc via [confixDoc] (the existing Syntax scanner)
 *                  →  ConfixConfixElement via [jsonElementFromDoc] (cursor walk)
 *                  →  decode value via kotlinx ConfixElement bridge
 *
 * The ConfixDoc's index facets ([ConfixIndexK.KeyToChild] for objects,
 * [ConfixIndexK.DirectChildren] for arrays) ARE the navigation surface —
 * the format owns no separate DOM. The kotlinx.serialization Encoder/Decoder
 * bridge to [ConfixElement] keeps `@Serializable` working unchanged.
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
        // value -> ConfixElement (via kotlinx Json)
        val element = ConfixJsonBridge.toConfixElement(serializer, value, configuration)
        // ConfixElement -> wire text. The cursor round-trip (ConfixElement → ConfixDoc
        // → text) is intentionally bypassed on the encode side because the
        // Syntax scanner reverses token order in its flat index; the decode
        // side uses the cursor facets for navigation, which is the facet-enabled
        // model. Emit directly from the well-ordered ConfixElement.
        return ConfixDocTextEmitter.emitElement(element, syntax, configuration)
    }

    override fun <T> decode(deserializer: DeserializationStrategy<T>, source: String): T {
        val doc = when (syntax) {
            ConfixSyntax.JSON -> confixDoc(source.encodeToByteArray(), Syntax.JSON)
            ConfixSyntax.YAML -> docFromConfixElement(YamlParser.reify(source).toConfixElement())
            ConfixSyntax.CBOR -> confixDoc(source.encodeToByteArray(), Syntax.JSON)
        }
        // ConfixDoc -> ConfixElement (cursor walk over the faceted index)
        val element = jsonElementFromDoc(doc)
        // ConfixElement -> value (via kotlinx Json)
        return ConfixJsonBridge.fromConfixElement(deserializer, element, configuration)
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
        val element = ConfixJsonBridge.toConfixElement(serializer, value, configuration)
        return ConfixCborEmitter.emit(element)
    }
    override fun <T> decode(deserializer: DeserializationStrategy<T>, source: ByteArray): T {
        val doc = confixDoc(source, Syntax.CBOR)
        val element = jsonElementFromDoc(doc)
        return ConfixJsonBridge.fromConfixElement(deserializer, element, configuration)
    }

    companion object { val Default: ConfixBinary get() = ConfixBinary() }
}

// ─────────────────────────────────────────────────────────────────────────────
// ConfixDoc <-> ConfixElement — cursor bridge
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Build a [ConfixDoc] from a [ConfixElement] by serializing the element to JSON
 * text and scanning it with [Syntax.JSON]. The resulting doc's facets
 * ([ConfixIndexK.KeyToChild], [ConfixIndexK.DirectChildren]) describe the
 * element's structure for cursor-driven navigation.
 */
internal fun docFromConfixElement(element: ConfixElement): ConfixDoc =
    confixDoc(element.toString().encodeToByteArray(), Syntax.JSON)

private fun Any?.toConfixElement(): ConfixElement = when (this) {
    null -> ConfixNull
    is Boolean -> ConfixPrimitive(this)
    is Number -> ConfixPrimitive(this)
    is String -> ConfixPrimitive(this)
    is List<*> -> ConfixArray(map { it.toConfixElement() })
    is Map<*, *> -> ConfixObject(entries.associate { (key, value) -> key.toString() to value.toConfixElement() })
    else -> ConfixPrimitive(toString())
}

/**
 * Walk a parsed [ConfixDoc]'s cursor tree into a kotlinx [ConfixElement].
 *
 * Object nodes use the [ConfixIndexK.KeyToChild] facet implicitly via
 * [RowVec.step]; array nodes use ordered children; scalars reify via
 * [RowVec.reify] over the source byte series. This is the cursor path —
 * no DOM is materialized by the format itself.
 */
internal fun jsonElementFromDoc(doc: ConfixDoc): ConfixElement {
    val root: RowVec? = doc.root ?: return ConfixNull
    return rowVecToJson(root!!, doc)
}

private fun rowVecToJson(rv: RowVec, doc: ConfixDoc): ConfixElement {
    return when (rv.tag) {
        IOMemento.IoObject -> {
            val kids = rv.kids
            val map = LinkedHashMap<String, ConfixElement>()
            var i = 0
            while (i + 1 < kids.size) {
                val keyRv = kids.b(i); val valRv = kids.b(i + 1)
                val key = reifyKey(keyRv, doc)
                map[key] = rowVecToJson(valRv, doc)
                i += 2
            }
            ConfixObject(map)
        }
        IOMemento.IoArray -> {
            val kids = rv.kids
            // α keeps the projection lazy; .toList returns an AbstractList view
            // (Series.kt:39) backed by the index function — no ArrayList alloc.
            ConfixArray((kids α { rowVecToJson(it, doc) }).toList())
        }
        else -> {
            val v = rv.reify(doc.src) ?: return ConfixNull
            when (v) {
                is Boolean -> ConfixPrimitive(v)
                is Number -> {
                    val dbl = v.toDouble()
                    if (dbl == dbl.toLong().toDouble()) ConfixPrimitive(v.toLong()) else ConfixPrimitive(dbl)
                }
                is String -> ConfixPrimitive(v)
                else -> ConfixPrimitive(v.toString())
            }
        }
    }
}

private fun reifyKey(rv: RowVec, doc: ConfixDoc): String {
    return rv.reify(doc.src)?.toString().orEmpty()
}

// ─────────────────────────────────────────────────────────────────────────────
// ConfixDocTextEmitter — ConfixDoc → JSON / YAML text (cursor walk)
// ─────────────────────────────────────────────────────────────────────────────

internal object ConfixDocTextEmitter {

    /**
     * Emit a [ConfixElement] directly to wire text without round-tripping through
     * [ConfixDoc]. This is the encode-side fast path used by [ConfixString.encode].
     */
    fun emitElement(element: ConfixElement, syntax: ConfixSyntax, cfg: ConfixConfig): String {
        val sb = StringBuilder()
        when (syntax) {
            ConfixSyntax.YAML -> emitYamlElement(sb, element, 0)
            else -> emitConfixElement(sb, element, if (cfg.prettyPrint) 0 else -1, cfg)
        }
        return sb.toString()
    }

    private fun emitYamlElement(sb: StringBuilder, element: ConfixElement, indent: Int) {
        when (element) {
            is ConfixObject -> {
                if (element.isEmpty()) { sb.append("{}"); return }
                element.entries.forEachIndexed { index, (key, value) ->
                    if (index > 0) sb.append('\n')
                    sb.append(" ".repeat(indent)).append(key).append(':')
                    if (value.isYamlScalar()) {
                        sb.append(' ')
                        emitYamlScalar(sb, value)
                    } else {
                        sb.append('\n')
                        emitYamlElement(sb, value, indent + 2)
                    }
                }
            }
            is ConfixArray -> {
                if (element.isEmpty()) { sb.append("[]"); return }
                element.forEachIndexed { index, value ->
                    if (index > 0) sb.append('\n')
                    sb.append(" ".repeat(indent)).append('-')
                    if (value.isYamlScalar()) {
                        sb.append(' ')
                        emitYamlScalar(sb, value)
                    } else {
                        sb.append('\n')
                        emitYamlElement(sb, value, indent + 2)
                    }
                }
            }
            else -> emitYamlScalar(sb, element)
        }
    }

    private fun ConfixElement.isYamlScalar(): Boolean =
        this is ConfixPrimitive || this === ConfixNull ||
            (this is ConfixObject && isEmpty()) || (this is ConfixArray && isEmpty())

    private fun emitYamlScalar(sb: StringBuilder, element: ConfixElement) {
        when (element) {
            ConfixNull -> sb.append("null")
            is ConfixPrimitive -> if (element.isString) sb.append(element.toString()) else sb.append(element.content)
            is ConfixObject -> sb.append("{}")
            is ConfixArray -> sb.append("[]")
        }
    }

    private fun emitConfixElement(sb: StringBuilder, element: ConfixElement, indent: Int, cfg: ConfixConfig) {
        when (element) {
            is ConfixObject -> {
                val pretty = indent >= 0
                sb.append("{")
                var written = 0
                for ((k, v) in element) {
                    if (written > 0) sb.append(",")
                    if (pretty) sb.append("\n").append(" ".repeat(indent + 2))
                    sb.append("\"").append(k.replace("\\", "\\\\").replace("\"", "\\\"")).append("\":")
                    if (pretty) sb.append(" ")
                    emitConfixElement(sb, v, if (pretty) indent + 2 else -1, cfg)
                    written++
                }
                if (pretty && written > 0) sb.append("\n").append(" ".repeat(indent))
                sb.append("}")
            }
            is ConfixArray -> {
                val pretty = indent >= 0
                sb.append("[")
                element.forEachIndexed { i, v ->
                    if (i > 0) sb.append(",")
                    if (pretty) sb.append("\n").append(" ".repeat(indent + 2))
                    emitConfixElement(sb, v, if (pretty) indent + 2 else -1, cfg)
                }
                if (pretty && element.isNotEmpty()) sb.append("\n").append(" ".repeat(indent))
                sb.append("]")
            }
            is ConfixPrimitive -> {
                if (element.isString) sb.append("\"").append(element.content
                    .replace("\\", "\\\\").replace("\"", "\\\"")
                    .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t"))
                    .append("\"")
                else sb.append(element.content)
            }
            ConfixNull -> sb.append("null")
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
                kids.`▶`.forEachIndexed { i, kid ->
                    if (i > 0) sb.append(",")
                    if (pretty) sb.append("\n").append(" ".repeat(indent + 2))
                    emitRow(sb, kid, doc, syntax, if (pretty) indent + 2 else -1, cfg)
                }
                if (pretty) sb.append("\n").append(" ".repeat(indent))
                sb.append("]")
            }
            ConfixSyntax.YAML -> {
                kids.`▶`.forEachIndexed { i, kid ->
                    if (i > 0 || indent > 0) sb.append("\n").append(" ".repeat(indent))
                    sb.append("- ")
                    when (kid.tag) {
                        IOMemento.IoObject, IOMemento.IoArray -> emitRow(sb, kid, doc, syntax, indent + 2, cfg)
                        else -> emitRow(sb, kid, doc, syntax, indent, cfg)
                    }
                }
            }
            ConfixSyntax.CBOR -> {
                sb.append("[")
                kids.`▶`.forEachIndexed { i, kid ->
                    if (i > 0) sb.append(",")
                    emitRow(sb, kid, doc, syntax, -1, cfg)
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
// ─────────────────────────────────────────────────────────────────────────────


// ─────────────────────────────────────────────────────────────────────────────
// ConfixJsonBridge — ConfixElement <-> @Serializable values (kotlinx Json core)
// ─────────────────────────────────────────────────────────────────────────────

internal object ConfixJsonBridge {

    fun <T> toConfixElement(serializer: SerializationStrategy<T>, value: T, cfg: ConfixConfig): ConfixElement {
        return encodeToConfixElement(serializer, value)
    }

    fun <T> fromConfixElement(deserializer: DeserializationStrategy<T>, element: ConfixElement, cfg: ConfixConfig): T {
        return decodeFromConfixElement(deserializer, element)
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
