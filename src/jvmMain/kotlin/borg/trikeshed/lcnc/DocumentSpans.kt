package borg.trikeshed.lcnc

import borg.trikeshed.job.*
import borg.trikeshed.lib.*
import borg.trikeshed.narsese.DocumentCuratorCodec
import borg.trikeshed.narsese.DocumentSource
import borg.trikeshed.parse.jsonOf

/** Exact text windows with independent UTF-16 ranges and serialized batch identity. */
object DocumentSpans {
    const val TYPE = "document.spans"

    fun runner(cas: CasStore, corpus: ProjectCorpus): LcncNodeRunner =
        boundLcnc(CasStoreKey(cas), boundLcnc(ProjectCorpusKey(corpus)) { owner, node, inputs ->
            val cas = CasStoreKey.require()
            val source = DocumentCurationLegos.retainSource(cas, inputs["source"])
            require(DocumentCurationLegos.current(owner.value, source)) { "Source revision is no longer current" }
            DocumentCurationLegos.consumed(source)
            val manifest = cas.put(CanonicalCbor.encodeMap(DocumentCuratorCodec.source(source) - "text"))
            fun integer(name: String, default: Int, range: IntRange): Int {
                val text = node.params[name] ?: return default
                return requireNotNull(text.toIntOrNull()?.takeIf { it in range }) { "Invalid $TYPE.$name" }
            }
            val offset = integer("offset", 0, 0..source.text.length)
            val count = integer("count", 2, 1..16)
            val width = integer("spanChars", 512, 2..4096)
            val limit = integer("maxBytes", 16384, 1024..32768)
            val batch = batch(source, manifest, offset, count, width, limit)
            val bytes = jsonOf(batch).encodeToByteArray()
            val cid = cas.put(bytes)
            cas.sync()
            _m["data" j bytes.decodeToString(), "batchCid" j cid.value, "sourceCid" j manifest.value,
                "count" j (batch["spans"] as List<*>).size, "begin" j offset, "end" j batch["end"],
                "nextOffset" j batch["nextOffset"], "complete" j batch["complete"], "bytes" j bytes.size]
        })

    fun batch(source: DocumentSource, manifest: ContentId, offset: Int, count: Int, width: Int, limit: Int): Map<String, Any?> {
        require(offset in 0..source.text.length && count in 1..16 && width in 2..4096 && limit in 1024..32768)
        require(offset == 0 || offset == source.text.length ||
            !(source.text[offset].isLowSurrogate() && source.text[offset - 1].isHighSurrogate())) { "Offset cuts a UTF-16 surrogate pair" }
        val spans = mutableListOf<Map<String, Any?>>()
        var position = offset
        fun envelope(end: Int, selected: List<Map<String, Any?>>): Map<String, Any?> = _m[
            "schema" j "document-spans/1", "originalCid" j source.originalCid.value,
            "extractedTextCid" j source.extractedTextCid.value, "sourceManifestCid" j manifest.value,
            "offsetUnit" j "utf16", "rangeConvention" j "half-open", "coverage" j "extracted-text-window",
            "begin" j offset, "end" j end, "textLength" j source.text.length,
            "complete" j (end == source.text.length), "nextOffset" j end.takeIf { it < source.text.length }, "spans" j selected]
        while (position < source.text.length && spans.size < count) {
            var end = (position + width).coerceAtMost(source.text.length)
            if (end < source.text.length && source.text[end].isLowSurrogate() && source.text[end - 1].isHighSurrogate()) end--
            // Prefer complete retained lines; long lines remain explicit bounded text windows.
            if (end < source.text.length) source.text.lastIndexOf('\n', end - 1).takeIf { it >= position }?.let { end = it + 1 }
            val candidate = spans + _m["index" j spans.size, "quotation" j source.quotation(position, end)]
            if (jsonOf(envelope(end, candidate)).encodeToByteArray().size > limit) {
                require(spans.isNotEmpty()) { "First passage exceeds batch byte budget; reduce spanChars or increase maxBytes" }
                break
            }
            spans.add(candidate.last()); position = end
        }
        return envelope(position, spans).also { require(jsonOf(it).encodeToByteArray().size <= limit) { "Batch metadata exceeds byte budget" } }
    }
}
