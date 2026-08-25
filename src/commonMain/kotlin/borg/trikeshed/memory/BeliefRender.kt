package borg.trikeshed.memory

import borg.trikeshed.cursor.BudgetCoord
import borg.trikeshed.job.ContentId
import borg.trikeshed.lib.Join
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.get
import borg.trikeshed.lib.j
import borg.trikeshed.lib.size
import borg.trikeshed.narsese.Nal
import borg.trikeshed.narsese.RelationKind
import borg.trikeshed.narsese.SemanticSignal

/** Resolve a belief to human-readable entry text; null = unresolvable (skipped, MISSING_EVIDENCE upstream). */
typealias Gloss = (SemanticSignal) -> String?

/** rendered text ⋈ ContentId of that text — the frozen-snapshot identity. */
typealias RenderedMemory = Join<String, ContentId>

val RenderedMemory.text: String get() = a
val RenderedMemory.cid: ContentId get() = b

/**
 * BeliefRender — the MEMORY file as a bounded, deterministic RENDER of the
 * belief bag. Curation IS eviction from the render, never from evidence.
 *
 * Contract:
 *  - Hermes-exact caps (chars, not tokens — compatibility) and `\n§\n` entry
 *    delimiters, so the file drops into ~/.hermes/memories unmodified.
 *  - Deterministic: score = expectation × priority, ordered (score desc,
 *    angular asc), greedy fill ≤ cap. An unchanged bag re-renders
 *    byte-identical — the frozen snapshot survives restarts and keeps the
 *    prompt prefix cacheable across sessions.
 *  - `!`-prefixed glosses are pins (durability=1 upstream): they sort first
 *    and are immune to cap-pressure until nothing else remains.
 *  - CONTRADICTION-related beliefs are EXCLUDED from the render until
 *    resolved (tell-all #2): conflicting knowledge must not reach the prompt.
 */
object BeliefRender {

    const val MEMORY_CAP = 2200
    const val USER_CAP = 1375
    const val DELIM = "\n§\n"

    fun render(
        top: Series<Join<SemanticSignal, BudgetCoord>>,
        gloss: Gloss,
        cap: Int = MEMORY_CAP,
    ): RenderedMemory {
        data class Row(val text: String, val score: Float, val angular: Long, val pinned: Boolean)

        val rows = ArrayList<Row>(top.size)
        for (i in 0 until top.size) {
            val (signal, budget) = top[i]
            if (signal.relation == RelationKind.CONTRADICTION) continue
            val text = gloss(signal)?.trim()?.takeIf { it.isNotEmpty() } ?: continue
            val pinned = text.startsWith("!") || budget.df >= 0.999f
            rows.add(Row(text, Nal.truthOf(signal.evidence).expectation() * budget.pf, signal.angular, pinned))
        }
        rows.sortWith(
            compareByDescending<Row> { it.pinned }
                .thenByDescending { it.score }
                .thenBy { it.angular },
        )
        val sb = StringBuilder()
        for (row in rows) {
            val addition = if (sb.isEmpty()) row.text else DELIM + row.text
            if (sb.length + addition.length > cap) continue
            sb.append(addition)
        }
        val text = sb.toString()
        return text j ContentId.of(text.encodeToByteArray())
    }

    /** Split a memory file's disk content back into its § entries. */
    fun entriesOf(text: String): List<String> =
        text.split(DELIM).map { it.trim() }.filter { it.isNotEmpty() }
}
