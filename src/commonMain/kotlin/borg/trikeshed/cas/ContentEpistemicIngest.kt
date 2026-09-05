package borg.trikeshed.cas

import borg.trikeshed.collections.LineAperture
import borg.trikeshed.job.CasStore
import borg.trikeshed.job.ContentId
import borg.trikeshed.lib.Join
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

/** Region coordinate is the inverse map back into one immutable source spine. */
data class EpistemicRegionCoordinate(
    val spineCid: ContentId,
    val aperture: LineAperture,
    val index: Int,
    val startInclusive: Int,
    val endExclusive: Int,
) {
    val size: Int get() = endExclusive - startInclusive
}

data class TextualMetrics(
    val bytes: Int,
    val characters: Int,
    val lines: Int,
    val tokens: Int,
    val uniqueTokens: Int,
    val shannonBitsPerByte: Double,
    val selfInformationBits: Double,
    val meanLineLength: Double,
)

/**
 * A computable schema/description-length signature. True Kolmogorov complexity is uncomputable;
 * [descriptionBits] is an explicit LZ76 upper-bound proxy, never represented as ground truth.
 */
data class KolmogorovSchemaSignature(
    val structuralKey: String,
    val lzPhraseCount: Int,
    val descriptionBits: Long,
    val normalizedComplexity: Double,
    val cid: ContentId,
)

/** coordinate × (region identity × (metrics × (schema signature × line identities))). */
typealias EpistemicRegion = Join<
    EpistemicRegionCoordinate,
    Join<ContentId, Join<TextualMetrics, Join<KolmogorovSchemaSignature, Series<ContentId>>>>
>

val EpistemicRegion.coordinate: EpistemicRegionCoordinate get() = a
val EpistemicRegion.regionCid: ContentId get() = b.a
val EpistemicRegion.metrics: TextualMetrics get() = b.b.a
val EpistemicRegion.schema: KolmogorovSchemaSignature get() = b.b.b.a
val EpistemicRegion.lineCids: Series<ContentId> get() = b.b.b.b

data class EpistemicLink(
    val kind: RelationKind,
    val from: EpistemicRegionCoordinate,
    val to: EpistemicRegionCoordinate,
    val weight: Double,
)

data class ContentEpistemicSurface(
    val spineCid: ContentId,
    val treeRoot: ContentId,
    val spine: LineSpine,
    val regions: Series<EpistemicRegion>,
    val links: Series<EpistemicLink>,
    val signals: Series<SemanticSignal>,
)

/**
 * The Narsese surface of one of this surface's [signals]: the structural keys
 * of the two regions the link joins, under the relation's copula, prefixed by
 * [source] (the document the regions came from). A minted belief must SHOW as
 * an expression on the blackboard, never as a bare angular — this is what the
 * ingest sites hand to [borg.trikeshed.narsese.BeliefIntake.Mint.gloss].
 */
fun epistemicGloss(surface: ContentEpistemicSurface, signal: SemanticSignal, source: String, text: String? = null): String {
    var from: EpistemicRegion? = null
    var to: EpistemicRegion? = null
    val regions = surface.regions
    for (i in 0 until regions.size) {
        val r = regions[i]
        val hex = r.regionCid.hex
        if (hex == signal.subjectCid) from = r
        if (hex == signal.objectCid) to = r
    }
    val lines = text?.lines()
    // caption a region by its first non-blank line when the document text is at
    // hand; otherwise by its structural key (the codeable surface it carries)
    fun caption(r: EpistemicRegion?, cid: String?): String {
        if (r == null) return cid?.take(12) ?: "?"
        if (lines != null) {
            var i = r.coordinate.startInclusive
            while (i < r.coordinate.endExclusive && i < lines.size) {
                val l = lines[i].trim()
                if (l.isNotEmpty()) return "«" + (if (l.length > 56) l.take(55) + "…" else l) + "»"
                i++
            }
        }
        return r.schema.structuralKey
    }
    val copula = when (signal.relation) {
        SemanticRelationKind.CAUSALITY -> "==>"
        SemanticRelationKind.ATTRACTION -> "<->"
        else -> "-->"
    }
    return "$source: ${caption(from, signal.subjectCid)} $copula ${caption(to, signal.objectCid)}"
}

fun EpistemicRegion.linesOf(spine: LineSpine): LineSpine {
    require(LineCas.spineCid(spine) == coordinate.spineCid) { "region belongs to another spine" }
    require(coordinate.startInclusive >= 0 && coordinate.endExclusive <= spine.size)
    return coordinate.size j { i: Int -> spine[coordinate.startInclusive + i] }
}

/** Region ↔ source-range isomorph: projection must recover exactly the region identity. */
fun EpistemicRegion.isomorphicTo(spine: LineSpine): Boolean =
    LineCas.spineCid(linesOf(spine)) == regionCid

/** Pure text→CAS→region→metric/signature→link ingest. The caller owns [cas]. */
object ContentEpistemicIngest {
    fun ingest(
        cas: CasStore,
        text: String,
        aperture: LineAperture = LineAperture.L1,
        attractionThreshold: Double = 0.68,
    ): ContentEpistemicSurface {
        val spine = LineCas.spineInto(cas, text)
        val spineCid = LineCas.spineCid(spine)
        if (spine.size == 0) return ContentEpistemicSurface(
            spineCid,
            TreeCas.rootOf(spine),
            spine,
            emptySeriesOf(),
            emptySeriesOf(),
            emptySeriesOf(),
        )
        val regions = regions(cas, spine, spineCid, aperture)
        val links = relate(regions, attractionThreshold)
        return ContentEpistemicSurface(
            spineCid = spineCid,
            treeRoot = TreeCas.rootOf(spine),
            spine = spine,
            regions = regions,
            links = links,
            signals = semanticSignals(links, regions, spineCid),
        )
    }

    fun relate(regions: Series<EpistemicRegion>, attractionThreshold: Double = 0.68): Series<EpistemicLink> {
        val links = ArrayList<EpistemicLink>()
        // Ordered regions in one source carry direct causality.
        for (i in 0 until regions.size - 1) {
            val left = regions[i]
            val right = regions[i + 1]
            if (left.coordinate.spineCid == right.coordinate.spineCid) {
                links += EpistemicLink(RelationKind.CAUSALITY, left.coordinate, right.coordinate, 1.0)
            }
        }
        // Schema/content similarity is attraction evidence, not causality.
        for (i in 0 until regions.size) {
            for (j in i + 1 until regions.size) {
                val score = attraction(regions[i], regions[j])
                if (score >= attractionThreshold) {
                    links += EpistemicLink(RelationKind.ATTRACTION, regions[i].coordinate, regions[j].coordinate, score)
                }
            }
        }
        return links.size j { i: Int -> links[i] }
    }

    private fun regions(
        cas: CasStore,
        spine: LineSpine,
        spineCid: ContentId,
        aperture: LineAperture,
    ): Series<EpistemicRegion> {
        val requested = when (aperture) {
            LineAperture.L0 -> 1
            LineAperture.L1 -> 4
            LineAperture.L2 -> 16
            LineAperture.L3 -> 64
        }
        val count = minOf(requested, spine.size)
        return count j { regionIndex: Int ->
            val start = regionIndex * spine.size / count
            val end = (regionIndex + 1) * spine.size / count
            val coordinate = EpistemicRegionCoordinate(spineCid, aperture, regionIndex, start, end)
            val regionSpine: LineSpine = (end - start) j { i: Int -> spine[start + i] }
            val textLines: Series<String> = regionSpine α { node ->
                cas.get(node.contentCid)?.decodeToString()
                    ?: error("CAS line missing for ${node.contentCid.hex}")
            }
            val metrics = textualMetrics(textLines)
            val signature = kolmogorovSchemaSignature(textLines, metrics)
            val lineCids = regionSpine α { it.contentCid }
            coordinate j (LineCas.spineCid(regionSpine) j (metrics j (signature j lineCids)))
        }
    }

    private fun semanticSignals(
        links: Series<EpistemicLink>,
        regions: Series<EpistemicRegion>,
        provenance: ContentId,
    ): Series<SemanticSignal> {
        val byCoordinate = linkedMapOf<EpistemicRegionCoordinate, EpistemicRegion>()
        for (region in regions.view) byCoordinate[region.coordinate] = region
        // indexed projection, not α-inlining: the JVM verified an inlined lambda
        // calling a private member of this object as bad invokespecial (Kotlin 2.x)
        return links.size j { i: Int ->
            val link = links[i]
            val from = byCoordinate.getValue(link.from)
            val to = byCoordinate.getValue(link.to)
            val positive = (link.weight.coerceIn(0.0, 1.0) * 1000.0).toLong().coerceAtLeast(1L)
            SemanticSignal(
                angular = angular(from, to, link.kind),
                evidence = EvidenceCoord(positive, 1000L - positive),
                relation = when (link.kind) {
                    RelationKind.ATTRACTION -> SemanticRelationKind.ATTRACTION
                    RelationKind.CAUSALITY -> SemanticRelationKind.CAUSALITY
                    else -> error("epistemic ingest emitted non-epistemic link ${link.kind}")
                },
                subjectCid = from.regionCid.hex,
                objectCid = to.regionCid.hex,
                provenanceCid = provenance.hex,
            )
        }
    }

    /**
     * The angular COORDINATE for a region-to-region link: [AngularCodec.encode] over
     * the regions' structural keys (schema shape + LZ76 + bits — the codeable surface
     * a region carries). Raw FNV over cids lived here and was the documented
     * anti-pattern: avalanche destroys locality, so `recallNear` over those keys was
     * exact-match-or-noise. FNV keeps its IDENTITY job; this is the COORDINATE.
     */
    private fun angular(from: EpistemicRegion, to: EpistemicRegion, kind: RelationKind): Long =
        borg.trikeshed.narsese.AngularCodec.encode(
            relation = SemanticRelationKind.entries[kind.ordinal.coerceIn(0, SemanticRelationKind.entries.size - 1)],
            taxonomyKey = null,
            subjectTerm = from.schema.structuralKey,
            objectTerm = to.schema.structuralKey,
        )

    private fun attraction(left: EpistemicRegion, right: EpistemicRegion): Double {
        if (left.regionCid == right.regionCid) return 1.0
        val schema = prefixSimilarity(left.schema.structuralKey, right.schema.structuralKey)
        val complexity = closeness(left.schema.normalizedComplexity, right.schema.normalizedComplexity)
        val entropy = closeness(left.metrics.shannonBitsPerByte, right.metrics.shannonBitsPerByte)
        val tokenDensity = closeness(
            left.metrics.uniqueTokens.toDouble() / left.metrics.tokens.coerceAtLeast(1),
            right.metrics.uniqueTokens.toDouble() / right.metrics.tokens.coerceAtLeast(1),
        )
        val content = contentJaccard(left.lineCids, right.lineCids)
        return (0.35 * schema + 0.20 * complexity + 0.15 * entropy + 0.10 * tokenDensity + 0.20 * content)
            .coerceIn(0.0, 1.0)
    }
}

fun textualMetrics(lines: Series<String>): TextualMetrics {
    val text = lines.view.joinToString("\n")
    val bytes = text.encodeToByteArray()
    val frequency = IntArray(256)
    for (byte in bytes) frequency[byte.toInt() and 0xFF]++
    var entropy = 0.0
    if (bytes.isNotEmpty()) for (count in frequency) if (count > 0) {
        val probability = count.toDouble() / bytes.size
        entropy -= probability * log2(probability)
    }
    val tokenSet = linkedSetOf<String>()
    var tokens = 0
    for (match in TOKEN.findAll(text.lowercase())) {
        tokens++
        tokenSet += match.value
    }
    var characterCount = 0
    for (line in lines.view) characterCount += line.length
    return TextualMetrics(
        bytes = bytes.size,
        characters = characterCount,
        lines = lines.size,
        tokens = tokens,
        uniqueTokens = tokenSet.size,
        shannonBitsPerByte = entropy,
        selfInformationBits = entropy * bytes.size,
        meanLineLength = if (lines.size == 0) 0.0 else characterCount.toDouble() / lines.size,
    )
}

fun kolmogorovSchemaSignature(
    lines: Series<String>,
    metrics: TextualMetrics = textualMetrics(lines),
): KolmogorovSchemaSignature {
    val structural = if (lines.size == 0) "_" else lines.shape(::lineKind).key.view.joinToString("")
    val text = lines.view.joinToString("\n")
    val phrases = lz76PhraseCount(text)
    val pointerBits = ceil(log2((text.length + 1).coerceAtLeast(2).toDouble())).toLong()
    val descriptionBits = phrases.toLong() * (pointerBits + 8L)
    val normalized = if (metrics.bytes == 0) 0.0 else
        (descriptionBits.toDouble() / (metrics.bytes.toDouble() * 8.0)).coerceIn(0.0, 1.0)
    val cid = ContentId.of("$structural|$phrases|$descriptionBits".encodeToByteArray())
    return KolmogorovSchemaSignature(structural, phrases, descriptionBits, normalized, cid)
}

private fun lz76PhraseCount(text: String): Int {
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

private fun lineKind(line: String): Char {
    val text = line.trim()
    return when {
        text.startsWith("```") || text.startsWith("~~~") -> 'F'
        text.startsWith("#") -> 'H'
        text.startsWith("- ") || text.startsWith("* ") || text.startsWith("+ ") -> 'L'
        ORDERED.containsMatchIn(text) -> 'N'
        text.startsWith("{") || text.startsWith("[") -> 'J'
        KEY_VALUE.containsMatchIn(text) -> 'K'
        text.startsWith(">") -> 'Q'
        CODE.containsMatchIn(text) -> 'C'
        else -> 'P'
    }
}

private fun prefixSimilarity(left: String, right: String): Double {
    if (left.isEmpty() && right.isEmpty()) return 1.0
    val limit = minOf(left.length, right.length)
    var common = 0
    while (common < limit && left[common] == right[common]) common++
    return common.toDouble() / maxOf(left.length, right.length).coerceAtLeast(1)
}

private fun closeness(left: Double, right: Double): Double =
    1.0 - (abs(left - right) / maxOf(abs(left), abs(right), 1.0)).coerceIn(0.0, 1.0)

private fun contentJaccard(left: Series<ContentId>, right: Series<ContentId>): Double {
    val leftSet = hashSetOf<String>()
    val rightSet = hashSetOf<String>()
    for (cid in left.view) leftSet += cid.hex
    for (cid in right.view) rightSet += cid.hex
    if (leftSet.isEmpty() && rightSet.isEmpty()) return 1.0
    var intersection = 0
    for (cid in leftSet) if (cid in rightSet) intersection++
    val union = leftSet.size + rightSet.size - intersection
    return intersection.toDouble() / union.coerceAtLeast(1)
}

private val TOKEN = Regex("[A-Za-z0-9_]+")
private val ORDERED = Regex("^\\d+[.)]\\s+")
private val KEY_VALUE = Regex("^[A-Za-z_][A-Za-z0-9_.-]{0,63}:\\s*")
private val CODE = Regex("[=(){};]|\\b(fun|class|val|var|def|import|return)\\b")
