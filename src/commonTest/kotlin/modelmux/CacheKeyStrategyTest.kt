package modelmux

import borg.trikeshed.lib.Series
import borg.trikeshed.lib.j
import modelmux.acp.AcpMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The cache-identity strategies, and — more importantly — the boundaries where
 * whitespace relaxation must STOP.
 *
 * A relaxed cache is a deliberate trade: it says two requests differing only in
 * formatting may share a reply. The tests that matter here are therefore the
 * NEGATIVE ones — the cases where relaxation must refuse to merge, because a
 * false merge does not degrade a hit rate, it returns the answer to a different
 * question.
 */
class CacheKeyStrategyTest {

    private fun msgs(vararg pairs: Pair<String, String>): Series<AcpMessage> =
        pairs.size j { i -> pairs[i].first j pairs[i].second }

    // ── exact: the default, and the M3 guarantee ────────────────────────

    @Test
    fun `exact identity is byte-sensitive`() {
        val a = ExactContentId.identity("""{"a":"x y"}""")
        val b = ExactContentId.identity("""{"a":"x  y"}""")
        assertNotEquals(a, b, "exact identity must not merge differing bytes")
    }

    @Test
    fun `exact identity is stable and sha256-shaped`() {
        val a = ExactContentId.identity("""{"a":1}""")
        assertEquals(a, ExactContentId.identity("""{"a":1}"""))
        assertTrue(a.startsWith("sha256:"), "identity must be a full ContentId, not a truncation")
    }

    // ── relaxed: what it MAY merge ──────────────────────────────────────

    @Test
    fun `relaxed merges runs of spaces and trailing whitespace`() {
        val s = WhitespaceRelaxed()
        assertEquals(
            s.identity("summarize   the    document"),
            s.identity("summarize the document"),
        )
        assertEquals(
            s.identity("line one   \nline two"),
            s.identity("line one\nline two"),
        )
    }

    @Test
    fun `relaxed merges line ending differences`() {
        val s = WhitespaceRelaxed()
        assertEquals(s.identity("a\r\nb"), s.identity("a\nb"))
    }

    @Test
    fun `relaxed collapses blank line runs to one`() {
        val s = WhitespaceRelaxed()
        assertEquals(s.identity("a\n\n\n\nb"), s.identity("a\n\nb"))
    }

    // ── relaxed: what it must NOT merge — the boundaries ────────────────

    @Test
    fun `fenced code keeps whitespace significant`() {
        val s = WhitespaceRelaxed()
        val indented = "run this:\n```\ndef f():\n    return 1\n```"
        val flattened = "run this:\n```\ndef f():\nreturn 1\n```"
        assertNotEquals(
            s.identity(indented), s.identity(flattened),
            "collapsing indentation inside a fence changes the program, not the spacing",
        )
    }

    @Test
    fun `inline code keeps whitespace significant`() {
        val s = WhitespaceRelaxed()
        assertNotEquals(
            s.identity("compare `a  b` please"),
            s.identity("compare `a b` please"),
        )
    }

    @Test
    fun `indent sensitive policy preserves leading whitespace outside fences`() {
        val s = WhitespaceRelaxed(WhitespacePolicy.INDENT_SENSITIVE)
        val yamlish = "root:\n  child: 1"
        val flattened = "root:\nchild: 1"
        assertNotEquals(
            s.identity(yamlish), s.identity(flattened),
            "unfenced YAML/Python indentation is structural under INDENT_SENSITIVE",
        )
        // …while the default prose policy is explicitly allowed to merge them.
        val prose = WhitespaceRelaxed(WhitespacePolicy.PROSE)
        assertEquals(prose.identity(yamlish), prose.identity(flattened))
    }

    @Test
    fun `relaxation never merges genuinely different text`() {
        val s = WhitespaceRelaxed()
        assertNotEquals(s.identity("delete the row"), s.identity("delete the rows"))
    }

    @Test
    fun `line endings only policy relaxes nothing else`() {
        val s = WhitespaceRelaxed(WhitespacePolicy.LINE_ENDINGS_ONLY)
        assertEquals(s.identity("a\r\nb"), s.identity("a\nb"))
        assertNotEquals(s.identity("a  b"), s.identity("a b"))
    }

    @Test
    fun `policy is part of the identity`() {
        // Two policies that normalize this input identically must still not
        // share entries: a cache written under one policy and read under
        // another is indistinguishable from a correctness bug.
        val a = WhitespaceRelaxed(WhitespacePolicy.PROSE).identity("plain text")
        val b = WhitespaceRelaxed(WhitespacePolicy.INDENT_SENSITIVE).identity("plain text")
        assertNotEquals(a, b, "identity must be namespaced by the policy that produced it")
    }

    // ── cascade ─────────────────────────────────────────────────────────

    @Test
    fun `cascade always consults exact first`() {
        val c = CacheCascade(listOf(WhitespaceRelaxed()))
        assertEquals("exact", c.strategies.first().name)
        assertEquals(2, c.strategies.size)
    }

    @Test
    fun `cascade puts exact first even when listed last`() {
        val c = CacheCascade(listOf(WhitespaceRelaxed(), ExactContentId))
        assertEquals("exact", c.strategies.first().name)
        assertEquals(2, c.strategies.size, "exact must not be duplicated")
    }

    @Test
    fun `exact only cascade is a single identity`() {
        val ids = CacheCascade.EXACT_ONLY.identities("""{"a":1}""")
        assertEquals(1, ids.size, "the default must behave exactly as before strategies existed")
        assertEquals(ExactContentId.identity("""{"a":1}"""), ids[0].second)
    }

    @Test
    fun `primary identity is always the exact one`() {
        val json = """{"a":"x   y"}"""
        assertEquals(
            ExactContentId.identity(json),
            CacheCascade.RELAXED.primary(json),
            "receipts must be attributed to the bytes actually sent",
        )
    }

    // ── prefix ladder ───────────────────────────────────────────────────

    @Test
    fun `prefix ladder has one rung per turn and grows monotonically`() {
        val ladder = prefixLadder(msgs("system" to "be brief", "user" to "hi"))
        assertEquals(2, ladder.size)
        assertNotEquals(ladder[0], ladder[1])
    }

    @Test
    fun `conversations sharing a prefix share its rungs`() {
        val a = prefixLadder(msgs("system" to "be brief", "user" to "hi"))
        val b = prefixLadder(msgs("system" to "be brief", "user" to "bye"))
        assertEquals(a[0], b[0], "a shared system prompt must be one CAS blob, not two")
        assertNotEquals(a[1], b[1])
        assertEquals(0, sharedPrefixDepth(a, b))
    }

    @Test
    fun `divergence at the first turn is depth minus one`() {
        val a = prefixLadder(msgs("system" to "alpha"))
        val b = prefixLadder(msgs("system" to "beta"))
        assertEquals(-1, sharedPrefixDepth(a, b))
    }

    @Test
    fun `ladder is not forgeable by concatenation`() {
        // ("ab","c") and ("a","bc") must not collapse into one identity — the
        // delimiter is what stops a crafted message from impersonating a prefix.
        val a = prefixLadder(msgs("user" to "ab", "user" to "c"))
        val b = prefixLadder(msgs("user" to "a", "user" to "bc"))
        assertNotEquals(a[1], b[1])
    }

    @Test
    fun `ladder under a policy recognises a re-wrapped shared prefix`() {
        val a = prefixLadder(msgs("system" to "be   brief"), WhitespacePolicy.PROSE)
        val b = prefixLadder(msgs("system" to "be brief"), WhitespacePolicy.PROSE)
        assertEquals(a[0], b[0])
        // …and without a policy it stays byte-exact.
        val strictA = prefixLadder(msgs("system" to "be   brief"))
        val strictB = prefixLadder(msgs("system" to "be brief"))
        assertNotEquals(strictA[0], strictB[0])
    }

    // ── normalizer edge cases ───────────────────────────────────────────

    @Test
    fun `unterminated inline code does not swallow the document`() {
        val n = WhitespaceNormalizer.normalize("a `b  c\nd   e", WhitespacePolicy.PROSE)
        // The backtick span closes at the newline, so the second line relaxes.
        assertTrue(n.endsWith("d e"), "was: $n")
    }

    @Test
    fun `fence info string and body survive intact`() {
        val src = "```kotlin\nval x =  1\n```"
        assertEquals(src, WhitespaceNormalizer.normalize(src, WhitespacePolicy.PROSE))
    }

    @Test
    fun `normalizer is idempotent`() {
        val once = WhitespaceNormalizer.normalize("a   b\n\n\n\nc  \n", WhitespacePolicy.PROSE)
        assertEquals(once, WhitespaceNormalizer.normalize(once, WhitespacePolicy.PROSE))
    }

    @Test
    fun `empty and whitespace only inputs are safe`() {
        assertEquals("", WhitespaceNormalizer.normalize("", WhitespacePolicy.PROSE))
        assertEquals("", WhitespaceNormalizer.normalize("   \n\n  ", WhitespacePolicy.PROSE))
    }
}
