package borg.trikeshed.agent.modelmux

import borg.trikeshed.job.CasStore
import borg.trikeshed.lib.*
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ContextReadPolicyTest {
    @Test
    fun exactEntriesServeExactlyThemselvesAndTheDefaultServesNothing() {
        assertFalse(ContextReadPolicy().serves("team/a"))
        val policy = ContextReadPolicy(allowedScopes = setOf("team/a"))
        assertTrue(policy.serves("team/a"))
        assertFalse(policy.serves("team/ab")); assertFalse(policy.serves("team")); assertFalse(policy.serves("team/a/b"))
    }

    @Test
    fun aPrefixEntryServesEveryScopeBelowItAndNeverItselfOrASibling() {
        val policy = ContextReadPolicy(allowedScopes = setOf("reel/*"))
        assertTrue(policy.serves("reel/ace-1"))
        assertTrue(policy.serves("reel/ace-1/lane"))
        assertFalse(policy.serves("reel/"), "nothing after the prefix")
        assertFalse(policy.serves("reel"))
        assertFalse(policy.serves("reelx/1"), "a sibling that merely starts with the same letters")
        assertFalse(policy.serves("other/ace-1"))
        assertFalse(ContextReadPolicy(allowedScopes = setOf("*")).serves("anything"), "a bare star is not a universal grant")
        assertFalse(ContextReadPolicy(allowedScopes = setOf("/*")).serves("x"), "the prefix is the text before the star, so only scopes below it")
    }

    @Test
    fun aManifestCannotWidenWhatThePolicyServes() = runTest {
        val rev = revision("reel/run-1", "a.md" to "x\n")
        val context = SharedContext(rev.intoCas(CasStore.inMemory()), null, ContextReadPolicy(allowedScopes = setOf("reel/*")))
        assertEquals("a.md", context.acquire(rev.cid, "reel/run-1").documents[0].name)
        assertEquals(ContextRejection.SCOPE_MISMATCH, assertFailsWith<ContextBlockException> { context.acquire(rev.cid, "reel/run-2") }.reason,
            "a served scope, but this revision belongs to another run")
        assertEquals(ContextRejection.FORBIDDEN_SCOPE, assertFailsWith<ContextBlockException> { context.acquire(rev.cid, "team/x") }.reason)
    }
}
