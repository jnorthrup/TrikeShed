package borg.trikeshed.pijul

import borg.trikeshed.crdt.PijulCrdt
import borg.trikeshed.patch.Blake3Hash

/**
 * The git → Pijul gateway (docs/oroboros-git-absorption.md, "The git gate"):
 * a branch's unified diff becomes content-addressed hunks in BASE line
 * coordinates, N branches' hunks are grouped by the locus they claim, and the
 * survivors are applied to one [PijulCrdt] per path and rendered.
 *
 * Three defects the swarm case recorded (commit 6a9ec431d, SwarmConvergenceTest,
 * PijulCrdtPropertyTest) are closed HERE, at the gate, not in the CRDT:
 *
 *  1. Coordinates — [PijulDiffParser] speaks base LINE numbers and
 *     [PijulCrdt] attaches by cumulative CHARACTER span. [render] converts
 *     against the seeded base's line offsets.
 *  2. Identity — a [Hunk]'s id is a hash of the change itself (path, deleted
 *     span, inserted text), never of the branch that carried it, so the
 *     seventeen branches making the byte-identical `ReadLines.kt` edit are one
 *     hunk and apply once (SwarmConvergenceTest.branchPatch's rule).
 *  3. Same-locus divergence — hunks whose base spans overlap are one [Group];
 *     a group with one variant CONVERGED and applies; a group with several is
 *     POSTED and applies only under a [Resolution] a person supplied
 *     (FunnelResidualMerge.ResolutionRoutine's shape). The CRDT never has to
 *     pick a winner, and never renders two spellings of one line.
 *
 * Order — the three commutativity laws in PijulCrdtPropertyTest are still
 * @Ignore: an insert shifts the coordinate space above it. The gateway applies
 * survivors per path in DESCENDING base order, so every hunk resolves against
 * an untouched prefix; [render] is then a function of the SET of survivors
 * (GitPijulGatewayTest.shuffledArmsRenderTheSame).
 */
object GitPijulGateway {

    /** One contiguous run of `-`/`+` lines of a unified diff, in base line coordinates (1-based). */
    data class Hunk(
        val path: String,
        /** First base line deleted (1-based); 0 when nothing is deleted. */
        val deleteStart: Int,
        val deleteCount: Int,
        /** Base line BEFORE which [inserted] goes (1-based; base line count + 1 appends). */
        val insertAt: Int,
        /** Inserted lines without their newline. */
        val inserted: List<String>,
    ) {
        /** The change, spelled once — what the id hashes. */
        val key: String
            get() = path + "|D:" + deleteStart + ":" + deleteCount + "|I:" + insertAt + ":" + inserted.joinToString("\n")

        val id: Blake3Hash by lazy { Blake3Hash.hash(key.encodeToByteArray()) }

        val hex: String
            get() = id.bytes.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }.take(12)

        /** Closed base-line span this hunk claims: the deleted lines plus the anchor after them, or the insert anchor alone. */
        val lo: Int get() = if (deleteCount > 0) deleteStart else insertAt
        val hi: Int get() = if (deleteCount > 0) deleteStart + deleteCount else insertAt

        fun overlaps(o: Hunk): Boolean = path == o.path && lo <= o.hi && o.lo <= hi
    }

    /** One branch (or any diff source): a label and the production hunks it carries. */
    data class Arm(val label: String, val hunks: List<Hunk>)

    /** One distinct spelling at a locus and the arms that carry it. */
    data class Variant(val hunk: Hunk, val arms: List<String>)

    /** Hunks on one path whose spans chain-overlap. One variant = CONVERGED; more = POSTED. */
    data class Group(val path: String, val lo: Int, val hi: Int, val variants: List<Variant>) {
        val locus: String get() = "$path:L$lo" + (if (hi != lo) "-$hi" else "")
        val converged: Boolean get() = variants.size == 1
    }

    sealed interface Resolution {
        /** Keep the variants this arm carries in the group; drop the rest. */
        data class Accept(val arm: String) : Resolution
        /** Drop every variant in the group. */
        data object Reject : Resolution
    }

    data class Plan(
        val groups: List<Group>,
        /** Hunks that will be applied, per path. */
        val survivors: Map<String, List<Hunk>>,
        /** Groups still posted — nothing applies from them, and a run must not land while this is non-empty. */
        val unresolved: List<Group>,
        /** Groups a [Resolution.Reject] emptied. */
        val rejected: List<Group>,
    )

    // ── ingest ─────────────────────────────────────────────────────────────

    /**
     * Production paths only. Bot scratch (the drain contract: "scratch/slop
     * inside jules patches must NEVER be committed") and the gh-pages
     * regeneration under docs/ are refused at the door.
     */
    fun isProductionPath(path: String): Boolean {
        val name = path.substringAfterLast('/')
        if (path.startsWith(".jules/") || path.startsWith(".Jules/")) return false
        if (name == "plan.md" || name == "test_plan.md" || name.startsWith("test_script.") || name.endsWith(".rej")) return false
        if (path.startsWith("src/")) return true
        if (path.startsWith("docs/") && path.endsWith(".md") && !path.substring(5).contains('/')) return true
        return false
    }

    /**
     * Parse a unified diff into hunks. Every maximal run of `-`/`+` lines
     * inside an `@@` block is one hunk; context lines end a run. Paths come
     * from `+++ b/`, or `--- a/` for a deleted file.
     */
    fun hunksOf(diffText: String): List<Hunk> {
        val out = ArrayList<Hunk>()
        var path: String? = null
        var oldLine = 1
        var runDeleteStart = 0
        var runDeleteCount = 0
        val runInserts = ArrayList<String>()
        var inRun = false

        fun flush() {
            val p = path
            if (inRun && p != null && (runDeleteCount > 0 || runInserts.isNotEmpty())) {
                val insertAt = if (runDeleteCount > 0) runDeleteStart + runDeleteCount else oldLine
                out.add(Hunk(p, if (runDeleteCount > 0) runDeleteStart else 0, runDeleteCount, insertAt, runInserts.toList()))
            }
            inRun = false; runDeleteStart = 0; runDeleteCount = 0; runInserts.clear()
        }

        for (line in diffText.lineSequence()) {
            when {
                line.startsWith("diff --git") -> { flush(); path = null }
                line.startsWith("--- ") -> {
                    flush()
                    val p = line.removePrefix("--- ").substringBefore('\t').trim()
                    if (p != "/dev/null") path = p.removePrefix("a/")
                }
                line.startsWith("+++ ") -> {
                    flush()
                    val p = line.removePrefix("+++ ").substringBefore('\t').trim()
                    if (p != "/dev/null") path = p.removePrefix("b/")
                }
                line.startsWith("@@") -> {
                    flush()
                    val m = Regex("""@@\s+-(\d+)(?:,\d+)?\s+\+(\d+)(?:,\d+)?\s+@@""").find(line)
                    oldLine = m?.groupValues?.get(1)?.toInt() ?: 1
                }
                line.startsWith("\\") -> Unit // "\ No newline at end of file"
                line.startsWith("-") -> {
                    if (!inRun) { inRun = true; runDeleteStart = oldLine }
                    else if (runDeleteCount == 0) runDeleteStart = oldLine
                    runDeleteCount++
                    oldLine++
                }
                line.startsWith("+") -> {
                    if (!inRun) inRun = true
                    runInserts.add(line.substring(1))
                }
                else -> { flush(); oldLine++ } // context (" …") or blank
            }
        }
        flush()
        return out
    }

    // ── grouping and resolution ────────────────────────────────────────────

    /** Group every arm's hunks by locus. Identical hunks (same id) are one variant carried by several arms. */
    fun groups(arms: List<Arm>): List<Group> {
        data class Carried(val hunk: Hunk, val arm: String)
        val byPath = LinkedHashMap<String, ArrayList<Carried>>()
        for (a in arms) for (h in a.hunks) byPath.getOrPut(h.path) { ArrayList() }.add(Carried(h, a.label))
        val out = ArrayList<Group>()
        for ((path, carried) in byPath) {
            val sorted = carried.sortedWith(compareBy({ it.hunk.lo }, { it.hunk.hi }))
            var i = 0
            while (i < sorted.size) {
                var lo = sorted[i].hunk.lo
                var hi = sorted[i].hunk.hi
                val members = ArrayList<Carried>()
                members.add(sorted[i])
                var j = i + 1
                while (j < sorted.size && sorted[j].hunk.lo <= hi) {
                    hi = maxOf(hi, sorted[j].hunk.hi)
                    members.add(sorted[j]); j++
                }
                val variants = LinkedHashMap<Blake3Hash, Pair<Hunk, ArrayList<String>>>()
                for (m in members) variants.getOrPut(m.hunk.id) { m.hunk to ArrayList() }.second.add(m.arm)
                out.add(Group(path, lo, hi, variants.values.map { (h, arms) -> Variant(h, arms.distinct()) }))
                i = j
            }
        }
        return out
    }

    /** Apply [resolutions] (keyed by [Group.locus]) to the groups and collect the survivors. */
    fun plan(arms: List<Arm>, resolutions: Map<String, Resolution> = emptyMap()): Plan {
        val gs = groups(arms)
        val survivors = LinkedHashMap<String, ArrayList<Hunk>>()
        val unresolved = ArrayList<Group>()
        val rejected = ArrayList<Group>()
        for (g in gs) {
            val keep: List<Hunk> = when {
                g.converged -> listOf(g.variants[0].hunk)
                else -> when (val r = resolutions[g.locus]) {
                    null -> { unresolved.add(g); emptyList() }
                    is Resolution.Reject -> { rejected.add(g); emptyList() }
                    is Resolution.Accept -> {
                        val mine = g.variants.filter { r.arm in it.arms }.map { it.hunk }
                        if (mine.isEmpty()) { unresolved.add(g); emptyList() } else mine
                    }
                }
            }
            if (keep.isNotEmpty()) survivors.getOrPut(g.path) { ArrayList() }.addAll(keep)
        }
        return Plan(gs, survivors, unresolved, rejected)
    }

    // ── render through the CRDT ────────────────────────────────────────────

    /**
     * Seed a [PijulCrdt] with [base] (one vertex per line at its character
     * offset), apply [hunks] bottom-up as content-addressed patches, render.
     * A hunk applied twice is a no-op; the result depends on the set, not the order.
     */
    fun render(base: String, hunks: List<Hunk>): String {
        val lines = splitLines(base)
        val offsets = IntArray(lines.size + 2)
        var cum = 0
        for (i in lines.indices) { offsets[i + 1] = cum; cum += lines[i].length }
        offsets[lines.size + 1] = cum
        val total = cum
        fun offsetOf(line1: Int): Int = if (line1 <= 0) 0 else if (line1 > lines.size + 1) total else offsets[line1]

        val crdt = PijulCrdt()
        if (lines.isNotEmpty()) {
            var off = 0
            val seed = lines.map { l -> Change.Insert(off, l).also { off += l.length } }
            crdt.apply(Patch(Blake3Hash.hash(("seed:" + base.length).encodeToByteArray()), seed, emptyList()))
        }
        val distinct = LinkedHashMap<Blake3Hash, Hunk>()
        for (h in hunks) distinct.putIfAbsent(h.id, h)
        val ordered = distinct.values.sortedWith(compareByDescending<Hunk> { it.lo }.thenByDescending { it.hi })
        for (h in ordered) {
            val changes = ArrayList<Change>(2)
            if (h.deleteCount > 0) {
                val start = offsetOf(h.deleteStart)
                val end = offsetOf(h.deleteStart + h.deleteCount)
                if (end > start) changes.add(Change.Delete(start, end - start))
            }
            if (h.inserted.isNotEmpty()) {
                // attach AFTER the vertex that ends at the anchor: pos = anchor - 1 (root when the anchor is the start)
                val pos = offsetOf(h.insertAt) - 1
                changes.add(Change.Insert(pos, h.inserted.joinToString("\n") + "\n"))
            }
            if (changes.isNotEmpty()) crdt.apply(Patch(h.id, changes, emptyList()))
        }
        return crdt.render()
    }

    /** Lines WITH their newline; a final line without one is kept as is. */
    internal fun splitLines(text: String): List<String> {
        if (text.isEmpty()) return emptyList()
        val out = ArrayList<String>()
        var start = 0
        while (start < text.length) {
            val nl = text.indexOf('\n', start)
            if (nl < 0) { out.add(text.substring(start)); break }
            out.add(text.substring(start, nl + 1))
            start = nl + 1
        }
        return out
    }

    // ── the resolution table ───────────────────────────────────────────────

    /**
     * One line per posted locus: `<locus> accept <arm>` or `<locus> reject`.
     * `#` starts a comment. The locus is [Group.locus] exactly as the report printed it.
     */
    fun parseResolutions(text: String): Map<String, Resolution> {
        val out = LinkedHashMap<String, Resolution>()
        for (raw in text.lineSequence()) {
            val line = raw.substringBefore('#').trim()
            if (line.isEmpty()) continue
            val parts = line.split(Regex("\\s+"), limit = 3)
            require(parts.size >= 2) { "resolution line needs '<locus> accept <arm>' or '<locus> reject': $raw" }
            out[parts[0]] = when (parts[1]) {
                "accept" -> Resolution.Accept(parts.getOrNull(2)?.trim() ?: error("accept needs an arm: $raw"))
                "reject" -> Resolution.Reject
                else -> error("unknown resolution '${parts[1]}' in: $raw")
            }
        }
        return out
    }
}
