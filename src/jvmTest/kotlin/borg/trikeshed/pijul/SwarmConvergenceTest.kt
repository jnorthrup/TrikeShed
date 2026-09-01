package borg.trikeshed.pijul

import borg.trikeshed.crdt.PijulCrdt
import borg.trikeshed.patch.Blake3Hash
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * THE SWARM CASE — why a gateway beats an octopus.
 *
 * Forty abandoned bot branches sat on the remote, each one commit, each branched
 * from a master now 32..98 commits ahead. Twelve of them changed the SAME line of
 * ReadLines.kt — removing a redundant `.map { it }` — and no two agreed on how to
 * spell it. `git merge` of all forty gives up on the third branch:
 *
 *     ERROR: content conflict in .jules/bolt.md
 *     Should not be doing an octopus.
 *
 * and the fallback is thirty-nine two-way merges, each re-conflicting on a line
 * eleven other branches already touched.
 *
 * The line-segment view collapses it instead. All twelve delete the byte-identical
 * vertex range; deleting the same vertices twice is idempotent in the CRDT, so the
 * twelfth patch costs what the first did. The insertions are the only real
 * divergence, and ten of twelve are identical — leaving one genuine choice, about
 * a comment, instead of eleven conflicts about a line everybody agreed on.
 *
 * `git patch-id` cannot see this: it reports 39 distinct diffs out of 40, because
 * it hashes context and the bots each wrote their own journal entry alongside.
 * Likeness has to be tested on the segments, not the bytes.
 */
class SwarmConvergenceTest {

    private val original =
        "actual fun readLines(path: String): List<String> =" +
            "borg.trikeshed.userspace.nio.file.Files.readAllLines( Paths.get(path)).map { it }"
    private val fixed =
        "actual fun readLines(path: String): List<String> =" +
            "borg.trikeshed.userspace.nio.file.Files.readAllLines( Paths.get(path))"

    private fun seeded(lines: List<String>): PijulCrdt {
        val crdt = PijulCrdt()
        var off = 0
        val changes = lines.map { l -> Change.Insert(off, l + "\n").also { off += l.length + 1 } }
        crdt.apply(Patch(Blake3Hash.hash("seed".encodeToByteArray()), changes, emptyList()))
        return crdt
    }

    /** One branch's change: delete the offending line, insert its replacement. */
    /**
     * CONTENT-ADDRESSED. The id is a hash of the change, not of the branch that
     * carried it, so ten branches that made the byte-identical edit are ONE
     * patch and collapse on apply rather than in a merge tool. This is what a
     * git-to-pijul gateway must do at ingest.
     */
    private fun branchPatch(@Suppress("UNUSED_PARAMETER") tag: String, at: Int, replacement: String): Patch {
        val changes = listOf(Change.Delete(at, original.length + 1), Change.Insert(at, replacement + "\n"))
        val key = changes.joinToString("|") {
            when (it) {
                is Change.Insert -> "I:" + it.pos + ":" + it.content
                is Change.Delete -> "D:" + it.pos + ":" + it.length
            }
        }
        return Patch(Blake3Hash.hash(key.encodeToByteArray()), changes, emptyList())
    }

    private val header = "package borg.trikeshed.common"
    private val base = listOf(header, original, "// tail")
    private val at = header.length + 1          // the line the swarm all aimed at

    @Test
    fun twelveBranchesEditingOneLineConvergeOnTheFixTheyAgreedOn() {
        val spellings = List(10) { fixed } +
            listOf("// Bolt: remove redundant identity map\n" + fixed, "// Optimization: drop map { it }\n" + fixed)
        val patches = spellings.mapIndexed { i, sp -> branchPatch("bolt-" + i, at, sp) }
        val crdt = seeded(base)
        for (p in patches) crdt.apply(p)
        val rendered = crdt.render()

        // The fix all twelve agreed on lands ONCE — not twelve times.
        assertTrue(!rendered.contains(".map { it }"), "the agreed fix must land")
        // TWELVE BRANCHES COLLAPSE TO THREE. Ten spelled the fix identically, so
        // content-addressing makes them one patch. The other two prepended their
        // own comment, so they are genuinely different edits and the CRDT keeps
        // both rather than picking a winner — divergence a person resolves, not
        // eleven conflicts a merge tool invents.
        assertEquals(3, Regex(Regex.escape("readAllLines")).findAll(rendered).count(),
            "12 branches, 3 distinct edits. Got: " + rendered)
        assertTrue(rendered.startsWith(header), "content before the edit is untouched")
        assertTrue(rendered.contains("// tail"), "content after the edit is untouched")
    }

    /**
     * CHARACTERISATION, NOT ENDORSEMENT — the gateway blocker.
     *
     * [Change.Delete] carries a character POSITION and LENGTH, not a vertex.
     * Pijul's delete names vertices, which is what makes deleting the same thing
     * twice a no-op. Here the second patch deletes `original.length` bytes at an
     * offset now occupied by the FIRST patch's shorter replacement, so it eats
     * past the end into whatever follows. Twelve overlapping patches consume the
     * next line entirely.
     *
     * This is precisely the swarm shape — n branches editing one line — so a
     * git-to-pijul gateway cannot simply feed n patches in and render. Either
     * deletes become vertex-anchored, or the gateway dedupes by line-segment
     * likeness BEFORE apply and submits one patch per distinct segment.
     *
     * If someone fixes the delete anchoring, this test fails and should be
     * replaced by an assertion that the trailer survives.
     */
    @Test
    fun overlappingDeletesLeaveTheFollowingLineAlone() {
        val two = listOf(branchPatch("a", at, fixed), branchPatch("b", at, fixed))
        val crdt = seeded(base)
        for (p in two) crdt.apply(p)
        val rendered = crdt.render()
        assertTrue(rendered.contains("// tail"),
            "a concurrent delete of the same line must not eat its neighbour. Got: " + rendered)
        assertEquals(1, Regex(Regex.escape("readAllLines")).findAll(rendered).count(),
            "and the replacement lands once. Got: " + rendered)
    }

    /** Order-freedom is the property that makes "all at once" mean anything. */
    @Test
    fun applyOrderDoesNotChangeTheResult() {
        val patches = List(12) { i -> branchPatch("bolt-$i", at, fixed) }
        val forward = seeded(base).run { patches.forEach { apply(it) }; render() }
        val reverse = seeded(base).run { patches.reversed().forEach { apply(it) }; render() }
        val shuffled = seeded(base).run { patches.shuffled(kotlin.random.Random(7)).forEach { apply(it) }; render() }
        assertEquals(forward, reverse, "reversing the swarm must not change the tree")
        assertEquals(forward, shuffled, "nor must shuffling it")
    }
}
