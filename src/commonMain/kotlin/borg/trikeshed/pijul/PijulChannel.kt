package borg.trikeshed.pijul

import borg.trikeshed.crdt.PijulCrdt
import borg.trikeshed.job.ContentId
import borg.trikeshed.lib.Join
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.j
import borg.trikeshed.patch.Blake3Hash
import borg.trikeshed.util.oroboros.MergeReceipt

/**
 * A Pijul channel for the flywheel's drain integration.
 *
 * Instead of sequential git 3-way merges (drainThreeWay), completed Jules
 * sessions produce patches that are applied to this channel. Patches that
 * touch different regions of the same file commute — no conflict, no merge
 * resolution, no working-tree-clean gate between applications.
 *
 * Integration point: FlywheelDriver.drainFanout can call [applyPatch] for
 * each completed session in parallel instead of the sequential
 * git merge → conflict → commit cycle.
 *
 * The channel materializes to a working tree via [renderFiles], which is
 * the only time the filesystem is touched. Git sees the result as a single
 * clean commit — no merge markers, no intermediate states.
 */
class PijulChannel(
    private val patchStore: PatchStorage = PatchStorage(),
) {
    /** The live CRDT graph keyed by file path. */
    private val files = mutableMapOf<String, PijulCrdt>()

    /** Provenance: patch hash → session that produced it. */
    private val provenance = mutableMapOf<Blake3Hash, PatchProvenance>()

    data class PatchProvenance(
        val workId: String,
        val sessionId: String,
        val patchCid: ContentId,
        val title: String,
        val appliedAt: Long,
    )

    /**
     * Apply a Jules patch to the channel. Non-overlapping edits commute
     * with all previously applied patches — no conflict resolution needed.
     *
     * @return the list of files touched by this patch.
     */
    fun applyPatch(
        workId: String,
        sessionId: String,
        patchCid: ContentId,
        title: String,
        changes: List<FileChanges>,
    ): List<String> {
        val patchId = Blake3Hash.hash(
            (workId + sessionId + patchCid.value + System.nanoTime()).encodeToByteArray()
        )
        val touched = mutableListOf<String>()
        for (fc in changes) {
            val crdt = files.getOrPut(fc.path) { PijulCrdt() }
            val pijulChanges = fc.inserts.map { pair ->
                Change.Insert(pair.a, pair.b)
            } + fc.deletes.map { pair ->
                Change.Delete(pair.a, pair.b)
            }
            val patch = Patch(
                id = patchId,
                changes = pijulChanges,
                dependencies = emptyList(),
            )
            crdt.apply(patch)
            touched.add(fc.path)
        }
        provenance[patchId] = PatchProvenance(workId, sessionId, patchCid, title, System.currentTimeMillis())
        return touched.distinct()
    }

    /**
     * Render a file's current content from the CRDT graph.
     * This is pure — no filesystem touch.
     */
    fun renderFile(path: String): String? =
        files[path]?.render()

    /**
     * Render all files and write them to [targetDir].
     * This is the single filesystem touch point — call once after all
     * patches are applied, then git-add + git-commit the result.
     */
    fun materialize(targetDir: java.io.File): List<String> {
        val written = mutableListOf<String>()
        for ((path, crdt) in files) {
            val content = crdt.render()
            val target = java.io.File(targetDir, path)
            target.parentFile?.mkdirs()
            target.writeText(content)
            written.add(path)
        }
        return written.sorted()
    }

    /** All patches applied to this channel, in application order. */
    fun appliedPatches(): Series<Patch> {
        val all = patchStore.getAll()
        return all.size j { i -> all[i] }
    }

    /** Provenance for a patch hash, if known. */
    fun provenanceFor(patchId: Blake3Hash): PatchProvenance? = provenance[patchId]

    /** Files currently tracked by this channel. */
    fun trackedFiles(): Set<String> = files.keys.toSet()

    /**
     * Build a MergeReceipt for every patch applied, suitable for the
     * flywheel's WorkDrained provenance path.
     */
    fun receipts(): List<MergeReceipt> = provenance.values.map { p ->
        MergeReceipt(
            workId = p.workId,
            producer = "pijul-channel",
            producerRef = p.sessionId,
            patchCid = p.patchCid,
            revision = "pijul-${p.patchCid.value.take(12)}",
            versionTag = p.title.take(80),
            lexicalMemory = borg.trikeshed.util.oroboros.LexicalMemory(
                summary = "Applied via Pijul channel (commutative)",
                title = p.title,
                content = "",
            ),
            claimedAt = p.appliedAt,
            prUrl = null,
        )
    }

    /** Reset the channel — used when git is the source of truth and the
     *  channel needs to resync from a clean tree. */
    fun reset() {
        files.clear()
        provenance.clear()
    }
}

/**
 * File-level changes extracted from a Jules patch. Each file gets a set
 * of insert and delete operations expressed as line-based positions.
 *
 * The flywheel's [parsePatchFiles] already parses unified diffs; this
 * type is the intermediate representation between diff parsing and CRDT
 * application.
 */
data class FileChanges(
    val path: String,
    val inserts: List<Join<Int, String>> = emptyList(),
    val deletes: List<Join<Int, Int>> = emptyList(),
) {
    companion object {
        fun empty(path: String) = FileChanges(path)
    }
}
