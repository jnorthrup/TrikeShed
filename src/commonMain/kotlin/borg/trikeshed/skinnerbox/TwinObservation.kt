package borg.trikeshed.skinnerbox

import borg.trikeshed.job.ContentId
import borg.trikeshed.lib.*

data class TwinKey(val kind: String, val key: String) : Comparable<TwinKey> {
    init {
        require(kind.length in 1..32 && kind.first() in 'a'..'z' && kind.all { it in 'a'..'z' || it in '0'..'9' || it == '-' }) { "twin kind" }
        require(key.length in 1..256 && key.first().isLetterOrDigit() && key.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it in "._:/+-" }) { "twin key" }
    }

    override fun compareTo(other: TwinKey): Int = kind.compareTo(other.kind).takeIf { it != 0 } ?: key.compareTo(other.key)
}

enum class TwinSource(val wire: String) { PLANNED("planned"), CURATED("curated"), MEASURED("measured") }

/** All exposed properties derive from the same canonical bytes; no caller owns those bytes. */
class TwinObservation private constructor(
    val key: TwinKey,
    val source: TwinSource,
    val via: String,
    val atMs: Long,
    val legacyId: ContentId?,
    private val line: ByteArray,
    private val fieldBytes: ByteArray,
) {
    val id: ContentId = ContentId.of(line)
    val byteSize: Int get() = line.size
    fun bytes(): ByteArray = line.copyOf()
    @Suppress("UNCHECKED_CAST")
    fun fields(): Map<String, Any?> = TwinJson.parse(fieldBytes) as Map<String, Any?>

    /** Only compare observations of the same key; measurement time is not authentication. */
    fun compareRank(other: TwinObservation): Int {
        require(key == other.key) { "cannot rank different twins" }
        val strength = source.compareTo(other.source)
        if (strength != 0) return strength
        val time = if (key.kind == "hardware" && key.key.startsWith("revision/")) other.atMs.compareTo(atMs) else atMs.compareTo(other.atMs)
        return time.takeIf { it != 0 } ?: id.value.compareTo(other.id.value)
    }

    companion object {
        const val SCHEMA = "skinnerbox.twin/v1"
        const val MAX_BYTES = 16 * 1024

        fun create(key: TwinKey, source: TwinSource, via: String, atMs: Long, fields: Map<String, Any?>,
            legacyId: ContentId? = null): TwinObservation {
            require(atMs >= 0) { "twin time must be nonnegative" }
            val value = linkedMapOf<String, Any?>("twin" to SCHEMA, "kind" to key.kind, "key" to key.key,
                "source" to source.wire, "via" to via, "at_ms" to atMs, "fields" to fields)
            legacyId?.let { value["legacy"] = it.value }
            return parse(TwinJson.encode(value, MAX_BYTES))
        }

        fun parse(bytes: ByteArray): TwinObservation {
            require(bytes.size in 1..MAX_BYTES) { "twin observation byte bound" }
            val owned = bytes.copyOf()
            return decode(owned, SCHEMA, canonical = true)
        }

        /** Explicit migration: the legacy line's CID is provenance, never the successor's identity. */
        fun importLegacy(bytes: ByteArray): TwinObservation {
            require(bytes.size in 1..MAX_BYTES) { "twin observation byte bound" }
            val owned = bytes.copyOf()
            val old = decode(owned, "loom.twin/v1", canonical = false)
            return create(old.key, old.source, old.via, old.atMs, old.fields(), ContentId.of(owned))
        }

        @Suppress("UNCHECKED_CAST")
        private fun decode(bytes: ByteArray, schema: String, canonical: Boolean): TwinObservation {
            val value = TwinJson.parse(bytes, MAX_BYTES) as? Map<String, Any?> ?: error("twin observation must be an object")
            val expected = setOf("twin", "kind", "key", "source", "via", "at_ms", "fields") + if (value.containsKey("legacy") && schema == SCHEMA) setOf("legacy") else emptySet()
            require(value.keys == expected && value["twin"] == schema) { "twin observation schema" }
            fun text(name: String): String = value[name] as? String ?: error("twin observation $name must be text")
            val key = TwinKey(text("kind"), text("key"))
            val source = TwinSource.entries.firstOrNull { it.wire == text("source") } ?: error("twin source")
            val via = text("via")
            require(via.length <= 256 && via.none { it.code < 0x20 || it.code in 0x7f..0x9f }) { "twin provenance bound" }
            val at = value["at_ms"] as? Long ?: error("twin time must fit a signed 64-bit integer")
            require(at >= 0) { "twin time must be nonnegative" }
            val fields = value["fields"] as? Map<String, Any?> ?: error("twin fields must be an object")
            if (canonical) require(TwinJson.encode(value, MAX_BYTES).contentEquals(bytes)) { "twin observation is not canonical" }
            val legacy = value["legacy"]?.let { ContentId(it as? String ?: error("legacy twin CID must be text")) }
            return TwinObservation(key, source, via, at, legacy, bytes, TwinJson.encode(fields, MAX_BYTES))
        }
    }
}

/** Prefixes end at a hierarchy separator: `alice` authorizes `alice` and `alice/...`, never `alice-other`. */
data class TwinScope(val kind: String, val prefix: String) {
    init {
        require(prefix.length <= 256) { "twin grant prefix bound" }
        if (kind != "*") TwinKey(kind, "scope")
        if (prefix.isNotEmpty()) TwinKey("scope", prefix.trimEnd('/'))
    }
    fun contains(key: TwinKey): Boolean = (kind == "*" || kind == key.kind) &&
        (prefix.isEmpty() || key.key == prefix || key.key.startsWith(if (prefix.endsWith('/')) prefix else "$prefix/"))
}

/** Created by the authenticated transport/persistence owner, never deserialized from observation JSON. */
class TwinGrant(val author: String, sources: Set<TwinSource>, scopes: Series<TwinScope>) {
    private val sources = sources.toSet()
    private val scopes = Array(scopes.also { require(it.size in 1..64) { "twin grant scope count" } }.size) { scopes[it] }
    init { require(author.isNotBlank() && author.length <= 256 && this.sources.isNotEmpty() && this.scopes.isNotEmpty()) }
    fun authorize(observation: TwinObservation) {
        require(observation.source in sources && scopes.any { it.contains(observation.key) }) { "twin author/source grant refused" }
    }
}
