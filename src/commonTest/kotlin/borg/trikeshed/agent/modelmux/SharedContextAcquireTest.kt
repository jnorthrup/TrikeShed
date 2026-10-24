package borg.trikeshed.agent.modelmux

import borg.trikeshed.job.CasStore
import borg.trikeshed.job.ContentId
import borg.trikeshed.lib.*
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Closure-checked acquisition of a pinned revision. The exchange is a fixture; a real peer is the opt-in integration test. */
class SharedContextAcquireTest {
    private fun local(vararg scopes: String) = ContextReadPolicy(allowedScopes = scopes.toSet())
    private fun peer(vararg scopes: String) = ContextReadPolicy(mode = SharedContextMode.PEER, allowedScopes = scopes.toSet())

    private fun ContextSnapshot.texts(): List<Pair<String, String>> =
        (0 until documents.size).map { documents[it].name to documents[it].text }

    private suspend fun reason(block: suspend () -> Any?): ContextBlockException = assertFailsWith { block() }

    @Test
    fun acquireReturnsEveryPinnedDocumentFromTheLocalStore() = runTest {
        val rev = revision("proj", "src/A.kt" to "class A\n", "notes.md" to "# notes\n")
        val ctx = SharedContext(rev.intoCas(CasStore.inMemory()), ForbiddenExchange, local("proj"))

        val snap = ctx.acquire(rev.cid, "proj")

        assertEquals(rev.cid, snap.manifestCid)
        assertEquals(listOf("notes.md" to "# notes\n", "src/A.kt" to "class A\n"), snap.texts())
        assertContentEquals(bytesOf("class A\n"), snap.documents[1].bytes, "bytes are the exact originals")
        assertEquals(ContextReadCounters(localReads = 3, peerFetches = 0, peerBytes = 0), ctx.counters())
    }

    @Test
    fun peerAcquisitionFetchesTheClosureOnceThenServesLocally() = runTest {
        val rev = revision("proj", "a.txt" to "alpha\n", "b.txt" to "beta\n")
        val exchange = rev.asPeer()
        val ctx = SharedContext(CasStore.inMemory(), exchange, peer("proj"))

        val first = ctx.acquire(rev.cid, "proj")
        assertEquals(3, exchange.fetched.size, "manifest plus two documents")
        assertEquals(3, ctx.counters().peerFetches)

        val second = ctx.acquire(rev.cid, "proj")
        assertEquals(3, exchange.fetched.size, "a cached closure needs no peer")
        assertEquals(first.texts(), second.texts())
        assertEquals(3, ctx.counters().localReads)
    }

    @Test
    fun missingClosureNeverBecomesVisible() = runTest {
        val rev = revision("proj", "one" to "1\n", "two" to "2\n", "three" to "3\n")
        val missing = ContentId.of(bytesOf("2\n"))
        val cas = rev.intoCas(CasStore.inMemory(), except = setOf(missing))
        val ctx = SharedContext(cas, null, local("proj"))

        val failure = reason { ctx.acquire(rev.cid, "proj") }
        assertEquals(ContextRejection.ABSENT, failure.reason)
        assertEquals(missing, failure.cid, "the error names the block that was missing")

        // Nothing partial was left behind to trip over: supplying the block makes the same call succeed.
        cas.put(bytesOf("2\n"))
        assertEquals(3, ctx.acquire(rev.cid, "proj").documents.size)
    }

    @Test
    fun scopeIsCheckedBeforeAnythingIsRead() = runTest {
        val rev = revision("proj", "a" to "x\n")
        val ctx = SharedContext(CasStore.inMemory(), ForbiddenExchange, peer("proj"))

        // Not served here: refused without a single local read or fetch.
        assertEquals(ContextRejection.FORBIDDEN_SCOPE, reason { ctx.acquire(rev.cid, "other") }.reason)
        assertEquals(ContextReadCounters(0, 0, 0), ctx.counters())

        // Served here, but the manifest is pinned for a different scope: its own claim is data, not authority.
        val foreign = revision("elsewhere", "a" to "x\n")
        val cas = foreign.intoCas(CasStore.inMemory())
        val guarded = SharedContext(cas, null, local("proj"))
        assertEquals(ContextRejection.SCOPE_MISMATCH, reason { guarded.acquire(foreign.cid, "proj") }.reason)
        assertEquals(1, guarded.counters().localReads, "only the manifest was read, no document")
    }

    @Test
    fun theDefaultPolicyServesNothing() = runTest {
        val rev = revision("proj", "a" to "x\n")
        val ctx = SharedContext(rev.intoCas(CasStore.inMemory()), null, ContextReadPolicy())
        assertEquals(ContextRejection.FORBIDDEN_SCOPE, reason { ctx.acquire(rev.cid, "proj") }.reason)
    }

    @Test
    fun aBlockThatIsNotAManifestIsRejected() = runTest {
        val cas = CasStore.inMemory()
        val junk = cas.put(bytesOf("this is a source file, not a manifest\n"))
        val ctx = SharedContext(cas, null, local("proj"))
        assertEquals(ContextRejection.MANIFEST_INVALID, reason { ctx.acquire(junk, "proj") }.reason)
    }

    @Test
    fun spineMustMatchForEveryEntryEvenWhenContentIsShared() = runTest {
        val text = "shared bytes\nsecond line\n"
        val truth = ManifestDocument.of("first", bytesOf(text))
        val lie = ManifestDocument("second", truth.content, ContentId.of(bytesOf("a spine that is not this one")))

        fun serve(vararg docs: ManifestDocument): Pair<ContentId, SharedContext> {
            val manifest = BreadcrumbContextManifest.of("proj", "p", "me", emptyList(), docs.toList())
            val cas = CasStore.inMemory()
            cas.put(manifest.encode())
            cas.put(bytesOf(text))
            return manifest.cid to SharedContext(cas, null, local("proj"))
        }

        val (okCid, ok) = serve(truth)
        assertEquals(1, ok.acquire(okCid, "proj").documents.size)

        // One entry lying about its spine is caught, and so is the SECOND name for bytes already verified once.
        val (lieCid, onlyLie) = serve(lie)
        assertEquals(ContextRejection.SPINE_MISMATCH, reason { onlyLie.acquire(lieCid, "proj") }.reason)
        val (bothCid, both) = serve(truth, lie)
        assertEquals(ContextRejection.SPINE_MISMATCH, reason { both.acquire(bothCid, "proj") }.reason)
    }

    @Test
    fun duplicateContentIsFetchedAndCountedOnce() = runTest {
        val rev = revision("proj", "copy-1.txt" to "same bytes\n", "copy-2.txt" to "same bytes\n", "other.txt" to "different\n")
        val exchange = rev.asPeer()
        val ctx = SharedContext(CasStore.inMemory(), exchange, peer("proj"))

        val snap = ctx.acquire(rev.cid, "proj")

        assertEquals(3, snap.documents.size, "every name appears in the snapshot")
        assertEquals(3, exchange.fetched.size, "but identical bytes crossed the wire once: manifest + 2 distinct blocks")
        assertEquals(rev.blocks.size, exchange.fetched.size)
    }

    @Test
    fun budgetsAreEnforcedBeforeTheOverrunningFetch() = runTest {
        val rev = revision("proj", "a" to "aaaa\n", "b" to "bbbb\n", "c" to "cccc\n")

        // Block budget: manifest + first document fit; the second document is refused BEFORE it is fetched.
        val tight = rev.asPeer()
        val byBlocks = SharedContext(CasStore.inMemory(), tight, peer("proj").copy(maxBlocks = 2))
        assertEquals(ContextRejection.BUDGET_EXCEEDED, reason { byBlocks.acquire(rev.cid, "proj") }.reason)
        assertEquals(2, tight.fetched.size)

        // Byte budget: the total is checked as blocks arrive.
        val manifestSize = rev.manifest.encode().size
        val byBytes = SharedContext(rev.intoCas(CasStore.inMemory()), null, local("proj").copy(maxTotalBytes = manifestSize + 7L))
        assertEquals(ContextRejection.BUDGET_EXCEEDED, reason { byBytes.acquire(rev.cid, "proj") }.reason)

        // And a roomy budget is not tripped by the same revision.
        assertEquals(3, SharedContext(rev.intoCas(CasStore.inMemory()), null, local("proj")).acquire(rev.cid, "proj").documents.size)
    }

    @Test
    fun aSuccessorRevisionLeavesThePinnedRevisionStable() = runTest {
        val base = revision("proj", "a.kt" to "class A\n", "b.kt" to "class B\n")
        // Child A finds something and records it as an explicit successor; nothing is overwritten.
        val next = revision(
            "proj", "a.kt" to "class A\n", "b.kt" to "class B2\n", "findings.md" to "B was rewritten\n",
            parents = listOf(base.cid), author = "child-a",
        )
        val cas = base.intoCas(CasStore.inMemory())
        next.intoCas(cas)
        val ctx = SharedContext(cas, null, local("proj"))

        // Child B, still pinned to the base, sees exactly what it always saw.
        assertEquals(listOf("a.kt" to "class A\n", "b.kt" to "class B\n"), ctx.acquire(base.cid, "proj").texts())

        // Moving to the successor is explicit, carries attribution and lineage, and shares unchanged blocks.
        val moved = ctx.acquire(next.cid, "proj")
        assertEquals(listOf("a.kt" to "class A\n", "b.kt" to "class B2\n", "findings.md" to "B was rewritten\n"), moved.texts())
        assertEquals("child-a", moved.manifest.author)
        assertEquals(listOf(base.cid), (0 until moved.manifest.parents.size).map { moved.manifest.parents[it] })
        assertEquals(base.manifest.documents[0].content, moved.manifest.documents[0].content, "unchanged evidence is the same block")

        // And the base is still reachable afterwards.
        assertTrue(ctx.acquire(base.cid, "proj").documents.size == 2)
    }

    @Test
    fun aPeerCannotSmuggleNonTextIntoASnapshot() = runTest {
        // Built by hand: the builder of this code refuses such a document, but a hostile peer's manifest need not.
        val bad = byteArrayOf('a'.code.toByte(), 0xFF.toByte(), '\n'.code.toByte())
        val doc = ManifestDocument("blob.txt", ContentId.of(bad), ManifestDocument.spineOf(bad))
        val manifest = BreadcrumbContextManifest.of("team/a", "policy-1", "mallory", emptyList(), listOf(doc))
        val cas = CasStore.inMemory()
        cas.put(manifest.encode()); cas.put(bad)
        val context = SharedContext(cas, null, ContextReadPolicy(allowedScopes = setOf("team/a")))
        assertEquals(ContextRejection.NOT_TEXT, assertFailsWith<ContextBlockException> { context.acquire(manifest.cid, "team/a") }.reason)
    }
}
