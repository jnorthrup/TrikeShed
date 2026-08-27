package borg.trikeshed.cas

import borg.trikeshed.job.CasStore
import borg.trikeshed.job.ContentId
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.get
import borg.trikeshed.lib.j
import borg.trikeshed.lib.size

/**
 * ScoringSession — a scoring run is a document (Step F).
 *
 * Session identity = canonical bytes over (corpus snapshot, method, scores):
 *   corpus  — the store's point-in-time: lastSeq + the sorted spine-set cids
 *   method  — the scorer's parameters as a cid (aperture, minGrade, thresholds)
 *   scores  — every (target cid, grade, density) in target order
 *
 * Identical inputs MUST reproduce the identical session cid — determinism is a
 * gate, not a hope. Mechanical scorers only on this path (linkMatch/linkDensity/
 * residualDensity; no model calls). Session-cid chains are the time-series
 * subset; diffing two sessions shows matching-strength drift as the corpus
 * grows or restages.
 */
object ScoringSession {

    /** One mechanical score for one target document: grade counts + density. */
    data class Score(
        val targetCid: ContentId,
        val linked: Int,
        val partial: Int,
        val contentOnly: Int,
        val density: Double,
    )

    /** One scoring run's full input identity + output set (pre-CAS). */
    data class Run(
        val corpusLastSeq: Long,
        val spineCids: List<ContentId>,
        val methodCid: ContentId,
        val scores: List<Score>,
    )

    /** Method parameters → cid: the scorer's own identity. */
    fun methodCid(aperture: String, minGrade: String, attractionThreshold: Double): ContentId =
        ContentId.of("scorer-v1|aperture=$aperture|minGrade=$minGrade|attraction=$attractionThreshold".encodeToByteArray())

    /**
     * Canonical session bytes — order-stable everywhere (sorted spine cids, scores
     * in the given target order). Deterministic: no clock, no map iteration order.
     */
    fun canonicalBytes(run: Run): ByteArray {
        val sb = StringBuilder()
        sb.append("scoring-session-v1\n")
        sb.append("corpus.seq=").append(run.corpusLastSeq).append('\n')
        val spines = run.spineCids.map { it.value }.sorted()
        sb.append("corpus.spines=").append(spines.size).append('\n')
        for (s in spines) sb.append(s).append('\n')
        sb.append("method=").append(run.methodCid.value).append('\n')
        sb.append("scores=").append(run.scores.size).append('\n')
        for (s in run.scores) {
            sb.append(s.targetCid.value).append('|')
                .append(s.linked).append('|').append(s.partial).append('|').append(s.contentOnly).append('|')
                .append(s.density.toString()).append('\n')
        }
        return sb.toString().encodeToByteArray()
    }

    /** The session document's cid: content-address over the canonical bytes. */
    fun sessionCid(cas: CasStore, run: Run): ContentId = cas.put(canonicalBytes(run))

    /**
     * Score one probe spine against an index snapshot: purely mechanical
     * [LineCasIndex.linkDensity] — counts per doc, normalized by the probe size.
     */
    fun scoreProbes(index: LineCasIndex, probes: List<Pair<ContentId, LineSpine>>): List<Score> {
        val out = ArrayList<Score>(probes.size)
        for ((docCid, spine) in probes) {
            val density = index.linkDensity(spine)
            var linked = 0; var partial = 0; var content = 0; var total = 0
            for (i in 0 until density.size) {
                val o = density[i].b
                linked += o.linked; partial += o.partial; content += o.contentOnly
                total += o.total
            }
            val norm = if (spine.size == 0) 0.0 else total.toDouble() / spine.size
            out += Score(docCid, linked, partial, content, norm)
        }
        return out
    }

    /** Two sessions' drift: only expected deltas — scores whose grade counts changed. */
    data class Drift(val added: List<ContentId>, val removed: List<ContentId>, val changed: List<ContentId>)

    fun diff(a: Run, b: Run): Drift {
        val byCid = { r: Run -> r.scores.associate { it.targetCid to it } }
        val am = byCid(a); val bm = byCid(b)
        val added = bm.keys.filter { it !in am }
        val removed = am.keys.filter { it !in bm }
        val changed = am.keys.filter { cid -> cid in bm && am[cid] != bm[cid] }
        return Drift(added, removed, changed)
    }
}
