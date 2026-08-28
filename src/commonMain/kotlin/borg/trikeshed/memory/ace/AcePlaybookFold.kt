package borg.trikeshed.memory.ace

import borg.trikeshed.job.ContentId
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.get
import borg.trikeshed.lib.j
import borg.trikeshed.lib.size

/**
 * The ACE fold (arXiv:2510.04618): the LLM (Reflector) PROPOSES itemized delta
 * bullets; this deterministic merge integrates them. No LLM ever rewrites the
 * base — that is the context-collapse prevention, by construction.
 *
 * Merge rules:
 *  - a delta whose [BulletId] is already present is ignored (bullets are
 *    append-only; feedback mutates counters in the sidecar, never content),
 *  - a delta whose content cid already exists under another id is dropped
 *    (dedup — grow-and-refine's redundancy prune),
 *  - surviving deltas append in id order with helpful=0 and the next ordinal.
 * Same playbook + same deltas → byte-identical result, always.
 */
object AcePlaybookFold {

    fun fold(playbook: Playbook, deltas: Series<DeltaBullet>): Playbook {
        val out = ArrayList<PlaybookBullet>(playbook.size + deltas.size)
        val ids = HashSet<Int>()
        val cids = HashSet<String>()
        var maxOrdinal = -1
        for (i in 0 until playbook.size) {
            val b = playbook[i]
            out.add(b)
            ids.add(b.a.raw)
            cids.add(b.b.a.value)
            if (b.b.b.b > maxOrdinal) maxOrdinal = b.b.b.b
        }
        val fresh = ArrayList<DeltaBullet>()
        for (i in 0 until deltas.size) {
            val d = deltas[i]
            if (d.a.raw in ids || d.b.value in cids) continue
            ids.add(d.a.raw)
            cids.add(d.b.value)
            fresh.add(d)
        }
        fresh.sortBy { it.a.raw }
        for (d in fresh) {
            maxOrdinal++
            out.add(d.a j (d.b j (HelpfulCount(0) j maxOrdinal)))
        }
        return out.size j { i: Int -> out[i] }
    }

    /**
     * Canonical PLAYBOOK_BASE bytes: born-ordinal order, one resolved bullet per
     * line. Counters are deliberately absent — they live in the sidecar (belief
     * bag) so marking a bullet helpful never colds the chain.
     */
    fun playbookBytes(playbook: Playbook, resolve: (ContentId) -> ByteArray?): ByteArray {
        val ordered = ArrayList<PlaybookBullet>(playbook.size)
        for (i in 0 until playbook.size) ordered.add(playbook[i])
        ordered.sortBy { it.b.b.b }
        return buildString {
            append("ace-playbook-v1\n")
            for (b in ordered) {
                append("bullet ").append(b.a.raw).append(' ')
                val body = resolve(b.b.a)
                append(body?.decodeToString() ?: b.b.a.value)
                append('\n')
            }
        }.encodeToByteArray()
    }
}
