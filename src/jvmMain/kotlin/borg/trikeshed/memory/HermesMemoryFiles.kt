package borg.trikeshed.memory

import borg.trikeshed.cursor.BudgetCoord
import borg.trikeshed.job.ContentId
import borg.trikeshed.narsese.AngularCodec
import borg.trikeshed.narsese.BeliefBagElement
import borg.trikeshed.narsese.BeliefIntake
import borg.trikeshed.narsese.EvidenceCoord
import borg.trikeshed.narsese.Nal
import borg.trikeshed.narsese.RelationKind
import borg.trikeshed.narsese.SemanticSignal
import java.io.File

/**
 * HermesMemoryFiles — the frozen-snapshot seam between the belief bag and
 * Hermes' `~/.hermes/memories/{MEMORY.md, USER.md}`.
 *
 * Session start:
 *  1. [ingestUserEdits] — diff disk vs the last rendered CID. New/changed §
 *     entries mint at USER-scale evidence (actor="user" authority rung);
 *     deleted entries take counter-evidence + attention-zero. `!`-prefix pins
 *     (durability=1 → render-immune).
 *  2. [renderTo] — deterministic render of the bag's top-k, written to disk
 *     and recorded as THE snapshot for the session. Mid-session belief changes
 *     hit the bag+WAL only — the file (and the prompt prefix built from it)
 *     stays frozen until next session.
 */
class HermesMemoryFiles(
    private val bag: BeliefBagElement,
    private val memoriesDir: File,
    private val evaluatorCid: ContentId,
) {
    private val stampFile = File(memoriesDir, ".render-cid")

    /** Diff disk content against the last render; user deltas become heavy evidence. */
    suspend fun ingestUserEdits(fileName: String = "MEMORY.md"): Int {
        val file = File(memoriesDir, fileName)
        if (!file.isFile) return 0
        val disk = file.readText()
        val lastCid = stampFile.takeIf { it.isFile }?.readText()?.trim()
        if (lastCid != null && ContentId.of(disk.encodeToByteArray()).hex == lastCid) return 0

        val diskEntries = BeliefRender.entriesOf(disk)
        val known = renderedEntries.associateBy { entryAngular(it) }
        var deltas = 0

        for (entry in diskEntries) {
            val angular = entryAngular(entry)
            if (known.containsKey(angular) && known[angular] == entry) continue
            val pinned = entry.startsWith("!")
            bag.intake.send(
                BeliefIntake.Mint(
                    SemanticSignal(
                        angular = angular,
                        evidence = EvidenceCoord(Nal.USER_UNIT, 0L),
                        relation = RelationKind.MATCH,
                        subjectCid = ContentId.of(entry.encodeToByteArray()).value,
                        provenanceCid = evaluatorCid.value,
                    ),
                    BudgetCoord(1.0f, if (pinned) 1.0f else 0.6f, 0.9f),
                    gloss = entry,
                ),
            )
            gloss(angular, entry)
            deltas++
        }
        for ((angular, entry) in known) {
            if (diskEntries.none { entryAngular(it) == angular }) {
                // user deleted it: counter-evidence + attention floor
                bag.intake.send(BeliefIntake.Reinforce(angular, EvidenceCoord(0L, Nal.USER_UNIT)))
                bag.intake.send(BeliefIntake.Attend(angular, BudgetCoord.zero()))
                glossTable.remove(angular)
                deltas++
            }
        }
        return deltas
    }

    /** Render the bag's top-k into the file; returns the frozen-snapshot identity. */
    fun renderTo(fileName: String = "MEMORY.md", cap: Int = BeliefRender.MEMORY_CAP, k: Int = 64): RenderedMemory {
        val rendered = BeliefRender.render(bag.recallTop(k), gloss = { s -> glossTable[s.angular] }, cap = cap)
        memoriesDir.mkdirs()
        File(memoriesDir, fileName).writeText(rendered.text)
        stampFile.writeText(rendered.cid.hex)
        renderedEntries = BeliefRender.entriesOf(rendered.text)
        return rendered
    }

    /**
     * The gloss table: angular → the human-readable entry it renders as.
     * Persisted as a TSV sidecar — captions must survive the process, or every
     * restart would strip the render back to user-authored entries only.
     */
    private val glossFile = File(memoriesDir, ".glosses.tsv")
    private val glossTable = HashMap<Long, String>().apply {
        if (glossFile.isFile) {
            for (line in glossFile.readLines()) {
                val tab = line.indexOf('\t')
                if (tab > 0) line.substring(0, tab).toLongOrNull()?.let { put(it, line.substring(tab + 1)) }
            }
        }
    }
    private var renderedEntries: List<String> = emptyList()

    fun glossOf(angular: Long): String? = glossTable[angular]

    fun gloss(angular: Long, entry: String) {
        glossTable[angular] = entry.replace('\t', ' ').replace('\n', ' ')
        memoriesDir.mkdirs()
        glossFile.writeText(glossTable.entries.sortedBy { it.key }.joinToString("\n") { "${it.key}\t${it.value}" })
    }

    companion object {
        /** Entry identity: the AngularCodec coordinate of the entry's own text. */
        fun entryAngular(entry: String): Long = AngularCodec.encode(
            relation = RelationKind.MATCH,
            taxonomyKey = "memories",
            subjectTerm = entry.removePrefix("!").take(96),
        )
    }
}
