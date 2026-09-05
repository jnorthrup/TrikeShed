package borg.trikeshed.pijul

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The gate closes the three swarm-case defects (see [GitPijulGateway]):
 * line→char coordinates, content-addressed identity, and posted divergence.
 */
class GitPijulGatewayTest {

    private val base = "package borg.trikeshed.common\n" +
        "actual fun readLines(path: String): List<String> =X.readAllLines(Paths.get(path)).map { it }\n" +
        "// tail\n"

    /** A one-commit bot branch's diff against [base], replacing line 2 with [replacement] (one or more lines). */
    private fun diff(vararg replacement: String): String = buildString {
        append("diff --git a/src/R.kt b/src/R.kt\n--- a/src/R.kt\n+++ b/src/R.kt\n")
        append("@@ -1,3 +1,${2 + replacement.size} @@\n")
        append(" package borg.trikeshed.common\n")
        append("-actual fun readLines(path: String): List<String> =X.readAllLines(Paths.get(path)).map { it }\n")
        for (r in replacement) append("+$r\n")
        append(" // tail\n")
    }

    private val fixed = "actual fun readLines(path: String): List<String> =X.readAllLines(Paths.get(path))"

    @Test
    fun aMultiLineBaseWithALineCoordinatePatchRendersInOrder() {
        val hunks = GitPijulGateway.hunksOf(diff(fixed))
        assertEquals(1, hunks.size)
        val h = hunks[0]
        assertEquals(2, h.deleteStart); assertEquals(1, h.deleteCount); assertEquals(3, h.insertAt)
        val out = GitPijulGateway.render(base, hunks)
        assertEquals("package borg.trikeshed.common\n$fixed\n// tail\n", out)
    }

    @Test
    fun insertBeforeFirstLineAndAppendAfterLast() {
        val top = "diff --git a/f b/f\n--- a/f\n+++ b/f\n@@ -1,1 +1,2 @@\n+HEAD\n A\n"
        assertEquals("HEAD\nA\nB\n", GitPijulGateway.render("A\nB\n", GitPijulGateway.hunksOf(top)))
        val tail = "diff --git a/f b/f\n--- a/f\n+++ b/f\n@@ -2,1 +2,2 @@\n B\n+TAIL\n"
        assertEquals("A\nB\nTAIL\n", GitPijulGateway.render("A\nB\n", GitPijulGateway.hunksOf(tail)))
        val fresh = "diff --git a/n b/n\nnew file mode 100644\n--- /dev/null\n+++ b/n\n@@ -0,0 +1,2 @@\n+one\n+two\n"
        assertEquals("one\ntwo\n", GitPijulGateway.render("", GitPijulGateway.hunksOf(fresh)))
    }

    @Test
    fun identicalEditsFromTwoBranchesAreOneHunkAndLandOnce() {
        val a = GitPijulGateway.Arm("bolt-a", GitPijulGateway.hunksOf(diff(fixed)))
        val b = GitPijulGateway.Arm("bolt-b", GitPijulGateway.hunksOf(diff(fixed)))
        assertEquals(a.hunks[0].id, b.hunks[0].id, "the id hashes the change, not the branch")
        val plan = GitPijulGateway.plan(listOf(a, b))
        assertEquals(1, plan.groups.size)
        assertTrue(plan.groups[0].converged)
        assertEquals(listOf("bolt-a", "bolt-b"), plan.groups[0].variants[0].arms)
        val out = GitPijulGateway.render(base, plan.survivors.getValue("src/R.kt"))
        assertEquals(1, Regex("readAllLines").findAll(out).count(), out)
        assertFalse(out.contains(".map { it }"))
    }

    @Test
    fun theSwarmIsPostedAsOneLocusWithThreeSpellingsAndAnAcceptLandsOne() {
        val arms = List(10) { i -> GitPijulGateway.Arm("bolt-$i", GitPijulGateway.hunksOf(diff(fixed))) } +
            GitPijulGateway.Arm("bolt-c1", GitPijulGateway.hunksOf(diff("// Bolt: drop identity map", fixed))) +
            GitPijulGateway.Arm("bolt-c2", GitPijulGateway.hunksOf(diff("// Optimization", fixed)))
        val posted = GitPijulGateway.plan(arms)
        assertEquals(1, posted.groups.size)
        val g = posted.groups[0]
        assertEquals(3, g.variants.size, "12 branches, 3 distinct spellings")
        assertEquals("src/R.kt:L2-3", g.locus)
        assertEquals(1, posted.unresolved.size, "nothing lands while the locus is posted")
        assertTrue(posted.survivors.isEmpty())

        val table = GitPijulGateway.parseResolutions("# pick the bare fix\n${g.locus} accept bolt-3\n")
        val resolved = GitPijulGateway.plan(arms, table)
        assertTrue(resolved.unresolved.isEmpty())
        val out = GitPijulGateway.render(base, resolved.survivors.getValue("src/R.kt"))
        assertEquals("package borg.trikeshed.common\n$fixed\n// tail\n", out)

        val rejected = GitPijulGateway.plan(arms, GitPijulGateway.parseResolutions("${g.locus} reject"))
        assertEquals(1, rejected.rejected.size)
        assertTrue(rejected.survivors.isEmpty())
    }

    @Test
    fun shuffledArmsRenderTheSame() {
        val b = "L1\nL2\nL3\nL4\nL5\nL6\n"
        fun ins(after: Int, text: String) = "diff --git a/f b/f\n--- a/f\n+++ b/f\n@@ -$after,1 +$after,2 @@\n L$after\n+$text\n"
        fun del(line: Int) = "diff --git a/f b/f\n--- a/f\n+++ b/f\n@@ -$line,1 +$line,0 @@\n-L$line\n"
        val arms = listOf(
            GitPijulGateway.Arm("a", GitPijulGateway.hunksOf(ins(1, "X"))),
            GitPijulGateway.Arm("b", GitPijulGateway.hunksOf(ins(3, "Y"))),
            GitPijulGateway.Arm("c", GitPijulGateway.hunksOf(del(5))),
            GitPijulGateway.Arm("d", GitPijulGateway.hunksOf(ins(6, "Z"))),
        )
        val expected = "L1\nX\nL2\nL3\nY\nL4\nL6\nZ\n"
        val orders = listOf(arms, arms.reversed(), listOf(arms[2], arms[0], arms[3], arms[1]), listOf(arms[3], arms[1], arms[2], arms[0]))
        for (o in orders) {
            val p = GitPijulGateway.plan(o)
            assertTrue(p.unresolved.isEmpty(), "disjoint loci never post")
            assertEquals(expected, GitPijulGateway.render(b, p.survivors.getValue("f")), "order ${o.map { it.label }}")
        }
    }

    @Test
    fun scratchAndGhPagesAreNotProductionPaths() {
        for (p in listOf(".jules/bolt.md", ".Jules/palette.md", "plan.md", "test_plan.md", "test_script.sh", "test_script.py",
            "src/x/JulesSessionCard.kt.rej", "docs/index.html", "docs/icons/forge-icon.svg", "docs/sw-kill.js", "docs/dispatch/x.md")) {
            assertFalse(GitPijulGateway.isProductionPath(p), p)
        }
        for (p in listOf("src/commonMain/kotlin/A.kt", "src/commonMain/resources/web/script.js", "src/jvmMain/java/B.java", "docs/guide-x.md")) {
            assertTrue(GitPijulGateway.isProductionPath(p), p)
        }
    }

    @Test
    fun aDiffWithTwoRunsInOneBlockIsTwoHunks() {
        val d = "diff --git a/f b/f\n--- a/f\n+++ b/f\n@@ -1,5 +1,5 @@\n-A\n+a\n B\n C\n-D\n+d\n E\n"
        val hs = GitPijulGateway.hunksOf(d)
        assertEquals(2, hs.size)
        assertEquals(1, hs[0].deleteStart); assertEquals(4, hs[1].deleteStart)
        assertEquals("a\nB\nC\nd\nE\n", GitPijulGateway.render("A\nB\nC\nD\nE\n", hs))
    }
}
