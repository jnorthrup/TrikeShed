package borg.trikeshed.memory

import borg.trikeshed.cas.LineCas
import borg.trikeshed.cas.LineCasIndex
import borg.trikeshed.cas.LineSpine
import borg.trikeshed.cas.MatchGrade
import borg.trikeshed.cas.OverlapCounts
import borg.trikeshed.couch.CouchStore
import borg.trikeshed.couch.Document
import borg.trikeshed.couch.Field
import borg.trikeshed.job.CasStore
import borg.trikeshed.job.ContentId
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.get
import borg.trikeshed.lib.j
import borg.trikeshed.lib.size

/**
 * MemoryStore — the five-ring composition for the paper's memory store M.
 *
 * The paper (arXiv:2607.26637v1) defines one store class operated by three
 * roles. This facade composes the existing TrikeShed rings so that a memory
 * file is addressable at every granularity the roles need:
 *
 *   Ring 0 (physical):   whole-file blob in [cas] (CasStore, ContentId = sha256)
 *   Ring 1 (per-line):   LineCas spine — each line content-addressed with
 *                        neighbor stamps; [lineIndex] is the inverted index
 *   Ring 2 (logical):    Couch document metadata (description, kind, sequence)
 *   Ring 3 (causal):     the mutation stream feeds ReteNetwork (subscribed
 *                        externally — this store emits, does not own the Rete)
 *   Ring 4 (pointcut):   PointcutMutableSeries wraps the Couch ingress
 *                        externally — this store's [subscribeMutations] is
 *                        the observation point
 *
 * Layout convention: each memory document's Couch id IS the memory file path
 * (e.g. "/memories/people/alice.md"). The document fields carry:
 *   - "description": the one-line frontmatter description (d_f)
 *   - "contentId": whole-file SHA-256
 *   - "spineCid": LineCas taxonomy fingerprint (Ring 1 identity)
 *   - "agentId": which management agent wrote this memory
 *   - "kind": "declarative" | "skill" | "note" (paper's metadata.type)
 *   - "sequence": monotonic write sequence (leak-free protocol guard)
 */
class MemoryStore(
    val cas: CasStore,
    val couch: CouchStore,
) {
    /** Ring 1 inverted index — per-line content-addressed with neighbor stamps. */
    val lineIndex: LineCasIndex = LineCasIndex()

    /**
     * Put a memory file: decomposes into all rings.
     *
     * Ring 0: CAS-put whole bytes (idempotent).
     * Ring 1: LineCas.spineInto — each trimmed line CAS-put individually,
     *         spineCid computed as the taxonomy fingerprint. The spine is
     *         ingested into [lineIndex] so cross-file link matching works.
     * Ring 2: Couch document with metadata including spineCid.
     *
     * Returns the whole-file ContentId (Ring 0 identity).
     */
    fun put(file: MemoryFile, agentId: String = "system", kind: String = "declarative"): ContentId {
        // Ring 0 — whole-file blob.
        val cid = ContentId.of(file.content)
        cas.put(file.content)

        // Ring 1 — per-line agglomerate: each line CAS-put, spine built with
        // neighbor stamps, ingested into the inverted index.
        val text = file.content.decodeToString()
        val spine = LineCas.spineInto(cas, text)
        val spineCid = lineIndex.ingestSpine(spine)

        // Ring 2 — metadata document.
        val doc = Document(
            id = file.path,
            fields = listOf(
                Field("description", file.description),
                Field("contentId", cid.value),
                Field("spineCid", spineCid.value),
                Field("agentId", agentId),
                Field("kind", kind),
                Field("sequence", nextSequence().toString()),
            )
        )
        couch.put(doc)
        return cid
    }

    /**
     * Get a memory file by path (reconstructs from Ring 0 blob).
     * Returns null if not found or tombstoned.
     */
    fun get(path: String): MemoryFile? {
        val doc = couch.get(path) ?: return null
        if (doc.fields.any { it.name == "deleted" && it.value == "true" }) return null

        val description = doc.fields.find { it.name == "description" }?.value as? String ?: ""
        val cidStr = doc.fields.find { it.name == "contentId" }?.value as? String ?: return null
        val cid = ContentId(cidStr)
        val bytes = cas.get(cid) ?: return null
        return memoryFile(path, description, bytes)
    }

    /** Get the Ring 1 spineCid for a memory file (null if not found). */
    fun spineCidOf(path: String): ContentId? {
        val doc = couch.get(path) ?: return null
        val str = doc.fields.find { it.name == "spineCid" }?.value as? String ?: return null
        return ContentId(str)
    }

    /** Get the Ring 1 spine for a memory file (null if not found). */
    fun spineOf(path: String): LineSpine? {
        val scid = spineCidOf(path) ?: return null
        return lineIndex.spineOf(scid)
    }

    /**
     * Cross-file structural overlap between two memory files, graded by
     * MatchGrade. This is the management agent's "is this already stored
     * elsewhere?" check — LINKED matches (same content + same neighborhood)
     * indicate structural reuse, not boilerplate coincidence.
     */
    fun overlap(pathA: String, pathB: String): OverlapCounts {
        val sa = spineOf(pathA) ?: return OverlapCounts.ZERO
        val sb = spineOf(pathB) ?: return OverlapCounts.ZERO
        return LineCas.overlapCounts(sa, sb).also { /* OverlapCounts returned */ }
    }

    /**
     * Search the per-line index for content matching [queryText], graded by
     * neighbor stamps. Returns hits across all memory files. This is the
     * search agent's line-granularity traversal — it finds the exact lines
     * that match, not just the files.
     */
    fun linkSearch(queryText: String, minGrade: MatchGrade = MatchGrade.LINKED): Series<borg.trikeshed.cas.LinkHit> {
        val querySpine = LineCas.spine(queryText)
        if (querySpine.size == 0) return 0 j { _: Int -> error("empty") }
        // Merge hits across all query lines.
        var merged: List<borg.trikeshed.cas.LinkHit> = emptyList()
        for (i in 0 until querySpine.size) {
            val hits = lineIndex.linkMatch(querySpine[i], minGrade)
            for (h in 0 until hits.size) {
                merged = merged + hits[h]
            }
        }
        return merged.size j { i: Int -> merged[i] }
    }

    /**
     * Delete a memory file (tombstone in Couch; CAS blobs and spines retained
     * for durability — the paper's "early memories survive" + ConfixWal trail).
     */
    fun delete(path: String): Boolean = couch.delete(path)

    /** Check if a memory file exists (non-tombstoned). */
    fun contains(path: String): Boolean = get(path) != null

    /**
     * List all memory file paths (non-tombstoned). Returns a Series<String>.
     */
    fun listPaths(): Series<String> {
        val ids = couch.ids()
        var valid: MutableList<String> = mutableListOf()
        for (i in 0 until ids.size) {
            val id = ids[i]
            val doc = couch.get(id)
            if (doc != null && doc.fields.none { it.name == "deleted" && it.value == "true" }) {
                valid.add(id)
            }
        }
        return valid.size j { i: Int -> valid[i] }
    }

    /**
     * The Couch mutation stream — Ring 3 (Rete) and Ring 4 (pointcut) subscribe
     * here. Management-agent writes flow out; search-agent reads and ISAM
     * reindexing consume.
     */
    fun subscribeMutations(observer: (CouchStore.MutationEvent) -> Unit): () -> Unit =
        couch.subscribeMutations(observer)

    /** Monotonic sequence for write ordering (leak-free protocol guard). */
    private val seqCounter = java.util.concurrent.atomic.AtomicLong(0)
    private fun nextSequence(): Long = seqCounter.incrementAndGet()
}
