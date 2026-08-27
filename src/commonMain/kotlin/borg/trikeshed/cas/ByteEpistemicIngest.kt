package borg.trikeshed.cas

import borg.trikeshed.job.CasStore
import borg.trikeshed.job.ContentId
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.emptySeriesOf
import borg.trikeshed.lib.get
import borg.trikeshed.lib.j
import borg.trikeshed.lib.size
import borg.trikeshed.lib.view
import borg.trikeshed.lib.α
import borg.trikeshed.lib.cascade.key
import borg.trikeshed.lib.cascade.shape
import borg.trikeshed.narsese.EvidenceCoord
import borg.trikeshed.narsese.SemanticSignal
import borg.trikeshed.narsese.RelationKind as SemanticRelationKind
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.log2

/**
 * Classify a single byte for Kolmogorov run-length schema.
 *
 * PDFs, images, archives — every byte gets one of five structural classes.
 * The run-length shape of these classes IS the organic comprehension:
 * a PDF's structural key looks like `PPPPPPWWPPPHHHHHWWPPPP...` without any parser.
 */
fun byteClassLabel(b: Byte): Char = when (b.toInt() and 0xFF) {
    0x00 -> '\u2400'                       // NUL — distinctive null marker
    0x09, 0x0A, 0x0D, 0x20 -> 'W'         // whitespace (tab, LF, CR, space)
    in 0x01..0x1F -> 'C'                   // control characters
    in 0x21..0x7E -> 'P'                   // printable ASCII
    else -> 'H'                             // high-bit / binary
}

/** Per-chunk byte metrics: Shannon entropy and class distribution. */
data class ByteMetrics(
    val bytes: Int,
    val shannonBitsPerByte: Double,
    val printableCount: Int,
    val highBitCount: Int,
    val whitespaceCount: Int,
    val controlCount: Int,
    val nullCount: Int,
) {
    val printableFraction: Double get() = if (bytes == 0) 0.0 else printableCount.toDouble() / bytes
}

/**
 * One chunk of a raw byte sequence, CAS-addressed and Kolmogorov-schema-signed.
 * The [structuralKey] is the RLE shape key of byte classes — the Kolmogorov run-length schema.
 */
data class ByteChunk(
    val index: Int,
    val startOffset: Int,
    val endOffset: Int,
    val cid: ContentId,
    val metrics: ByteMetrics,
    val structuralKey: String,
    val lzPhrases: Int,
    val descriptionBits: Long,
    /** AngularCodec coordinate of the RLE shape key — the byte-grain zoom code. */
    val code: Int = 0,
) {
    val normalizedComplexity: Double
        get() = if (metrics.bytes == 0) 0.0
        else (descriptionBits.toDouble() / (metrics.bytes.toDouble() * 8.0)).coerceIn(0.0, 1.0)
    /** The 8-bit top zoom ring of [code]: the code's HIGH byte (bits 15..8, the coarse prefix). */
    val codeRing8: Int get() = (code ushr 8) and 0xFF
}

/** A link between two byte chunks: CAUSALITY (adjacent in sequence) or ATTRACTION (schema similarity). */
data class ByteLink(
    val kind: RelationKind,
    val fromChunk: Int,
    val toChunk: Int,
    val weight: Double,
)

/**
 * Document-level byte epistemic surface: the organic comprehension of raw bytes.
 * No parser, no text extraction — just Kolmogorov run-length over byte classes.
 *
 * PDF structure emerges organically: object headers are 'P' runs, compressed streams
 * are 'H' runs, inter-object gaps are 'W'/'C' runs.
 */
data class ByteEpistemicSurface(
    val sourceCid: ContentId,
    val totalBytes: Int,
    val chunks: Series<ByteChunk>,
    val links: Series<ByteLink>,
    val signals: Series<SemanticSignal>,
    val documentSchema: KolmogorovSchemaSignature,
)

/**
 * Fanout→fanin CCEK ingest: chunk raw bytes, compute Kolmogorov run-length schema per chunk,
 * aggregate into a document-level epistemic surface.
 *
 * Pipeline:
 *  1. **Fanout**: split bytes into fixed-size chunks; each chunk → CAS citizen + byte-class RLE + LZ76.
 *  2. **Fanin**: chunk signatures aggregate into document schema; links + signals connect them.
 *
 * The structural key of each chunk is the RLE shape of byte classes (C/W/P/H/NUL).
 * The description length is LZ76 phrase count × (pointerBits + 8) over the class label string.
 */
object ByteEpistemicIngest {
    const val DEFAULT_CHUNK_SIZE = 1024
    const val ATTRACTION_THRESHOLD = 0.68

    fun ingest(
        cas: CasStore,
        bytes: ByteArray,
        chunkSize: Int = DEFAULT_CHUNK_SIZE,
    ): ByteEpistemicSurface {
        val sourceCid = cas.put(bytes)
        if (bytes.isEmpty()) return ByteEpistemicSurface(
            sourceCid, 0, emptySeriesOf(), emptySeriesOf(), emptySeriesOf(),
            KolmogorovSchemaSignature("_", 0, 0L, 0.0, sourceCid),
        )

        // ── Fanout: chunk bytes, compute per-chunk schema ──
        val chunkCount = (bytes.size + chunkSize - 1) / chunkSize
        val chunks: Series<ByteChunk> = chunkCount j { i ->
            val start = i * chunkSize
            val end = minOf(start + chunkSize, bytes.size)
            chunkSchema(cas, bytes, start, end, i)
        }

        // ── Fanin: links + signals + document schema ──
        val links = relateChunks(chunks)
        val signals = byteSignals(links, chunks, sourceCid)
        val docSchema = documentSchema(bytes, sourceCid)

        return ByteEpistemicSurface(sourceCid, bytes.size, chunks, links, signals, docSchema)
    }

    private fun chunkSchema(cas: CasStore, bytes: ByteArray, start: Int, end: Int, index: Int): ByteChunk {
        val len = end - start
        val cid = cas.put(bytes.copyOfRange(start, end))

        // Byte-class RLE → structural key (the Kolmogorov run-length schema)
        val classLabels: Series<Char> = len j { byteClassLabel(bytes[start + it]) }
        val shapeKey = classLabels.shape { it }.key.view.joinToString("")

        // Shannon entropy over byte histogram
        val freq = IntArray(256)
        for (j in start until end) freq[bytes[j].toInt() and 0xFF]++
        var entropy = 0.0
        for (c in freq) if (c > 0) {
            val p = c.toDouble() / len
            entropy -= p * log2(p)
        }

        // Class counts
        var printable = 0; var highBit = 0; var ws = 0; var ctrl = 0; var nul = 0
        for (j in start until end) when (byteClassLabel(bytes[j])) {
            'P' -> printable++
            'H' -> highBit++
            'W' -> ws++
            'C' -> ctrl++
            else -> nul++
        }

        // LZ76 on the full class label string (not the RLE-compressed key)
        val labelString = CharArray(len) { byteClassLabel(bytes[start + it]) }.concatToString()
        val phrases = byteLz76PhraseCount(labelString)
        val pointerBits = ceil(log2((labelString.length + 1).coerceAtLeast(2).toDouble())).toLong()
        val descriptionBits = phrases.toLong() * (pointerBits + 8L)

        return ByteChunk(
            index = index, startOffset = start, endOffset = end, cid = cid,
            metrics = ByteMetrics(len, entropy, printable, highBit, ws, ctrl, nul),
            structuralKey = shapeKey,
            lzPhrases = phrases,
            descriptionBits = descriptionBits,
            // The shape key is genuinely locality-preserving; its simhash is the zoom code.
            code = borg.trikeshed.narsese.AngularCodec.fragmentCode(shapeKey),
        )
    }

    private fun relateChunks(chunks: Series<ByteChunk>): Series<ByteLink> {
        if (chunks.size < 2) return emptySeriesOf()
        val links = ArrayList<ByteLink>()
        // Causal: adjacent chunks in sequence
        for (i in 0 until chunks.size - 1) {
            links += ByteLink(RelationKind.CAUSALITY, i, i + 1, 1.0)
        }
        // Attraction: schema similarity (O(n^2) but n is chunk count, typically small)
        for (i in 0 until chunks.size) {
            for (k in i + 1 until chunks.size) {
                val score = chunkAttraction(chunks[i], chunks[k])
                if (score >= ATTRACTION_THRESHOLD) {
                    links += ByteLink(RelationKind.ATTRACTION, i, k, score)
                }
            }
        }
        return links.size j { i -> links[i] }
    }

    private fun chunkAttraction(a: ByteChunk, b: ByteChunk): Double {
        if (a.structuralKey == b.structuralKey) return 1.0
        val schema = bytePrefixSimilarity(a.structuralKey, b.structuralKey)
        val complexity = byteCloseness(a.normalizedComplexity, b.normalizedComplexity)
        val entropy = byteCloseness(a.metrics.shannonBitsPerByte, b.metrics.shannonBitsPerByte)
        val density = byteCloseness(a.metrics.printableFraction, b.metrics.printableFraction)
        return (0.35 * schema + 0.25 * complexity + 0.25 * entropy + 0.15 * density).coerceIn(0.0, 1.0)
    }

    private fun byteSignals(
        links: Series<ByteLink>,
        chunks: Series<ByteChunk>,
        provenance: ContentId,
    ): Series<SemanticSignal> {
        if (links.size == 0) return emptySeriesOf()
        return links.size j { li ->
            val link = links[li]
            val from = chunks[link.fromChunk]
            val to = chunks[link.toChunk]
            val positive = (link.weight.coerceIn(0.0, 1.0) * 1000.0).toLong().coerceAtLeast(1L)
            SemanticSignal(
                angular = byteAngular(from, to, link.kind),
                evidence = EvidenceCoord(positive, 1000L - positive),
                relation = when (link.kind) {
                    RelationKind.ATTRACTION -> SemanticRelationKind.ATTRACTION
                    RelationKind.CAUSALITY -> SemanticRelationKind.CAUSALITY
                    else -> error("byte ingest emitted non-epistemic link ${link.kind}")
                },
                subjectCid = from.cid.hex,
                objectCid = to.cid.hex,
                provenanceCid = provenance.hex,
            )
        }
    }

    private fun documentSchema(bytes: ByteArray, sourceCid: ContentId): KolmogorovSchemaSignature {
        if (bytes.isEmpty()) return KolmogorovSchemaSignature("_", 0, 0L, 0.0, sourceCid)
        val classLabels: Series<Char> = bytes.size j { byteClassLabel(bytes[it]) }
        val structural = classLabels.shape { it }.key.view.joinToString("")
        val labelString = CharArray(bytes.size) { byteClassLabel(bytes[it]) }.concatToString()
        val phrases = byteLz76PhraseCount(labelString)
        val pointerBits = ceil(log2((labelString.length + 1).coerceAtLeast(2).toDouble())).toLong()
        val descriptionBits = phrases.toLong() * (pointerBits + 8L)
        val normalized = (descriptionBits.toDouble() / (bytes.size.toDouble() * 8.0)).coerceIn(0.0, 1.0)
        return KolmogorovSchemaSignature(structural, phrases, descriptionBits, normalized, sourceCid)
    }

    // ── helpers ──

    private fun bytePrefixSimilarity(left: String, right: String): Double {
        if (left.isEmpty() && right.isEmpty()) return 1.0
        val limit = minOf(left.length, right.length)
        var common = 0
        while (common < limit && left[common] == right[common]) common++
        return common.toDouble() / maxOf(left.length, right.length).coerceAtLeast(1)
    }

    private fun byteCloseness(left: Double, right: Double): Double =
        1.0 - (abs(left - right) / maxOf(abs(left), abs(right), 1.0)).coerceIn(0.0, 1.0)

    /**
     * The angular COORDINATE for a chunk-to-chunk link: [AngularCodec.encode] with
     * the chunks' RLE shape keys as subject/object surfaces. FNV-1a over cids lived
     * here and it was the documented anti-pattern — avalanche destroys locality, so
     * hamming distance between two FNV hashes is meaningless and `recallNear` over
     * them was exact-match-or-noise. FNV keeps its IDENTITY job; this is the
     * COORDINATE the bag keys on. Shape-key simhashes make same-schema links share
     * high bits, so near-identical structures land within small hamming distance.
     */
    private fun byteAngular(from: ByteChunk, to: ByteChunk, kind: RelationKind): Long =
        borg.trikeshed.narsese.AngularCodec.encode(
            relation = SemanticRelationKind.entries[kind.ordinal.coerceIn(0, SemanticRelationKind.entries.size - 1)],
            taxonomyKey = null,
            subjectTerm = from.structuralKey,
            objectTerm = to.structuralKey,
        )
}

/** LZ76 phrase count — upper-bound proxy for Kolmogorov complexity. Byte-level variant. */
internal fun byteLz76PhraseCount(text: String): Int {
    if (text.isEmpty()) return 0
    val dictionary = hashSetOf<String>()
    var phrases = 0
    var offset = 0
    while (offset < text.length) {
        var end = offset + 1
        while (end <= text.length && text.substring(offset, end) in dictionary) end++
        val phrase = text.substring(offset, minOf(end, text.length))
        dictionary += phrase
        phrases++
        offset += phrase.length
    }
    return phrases
}
