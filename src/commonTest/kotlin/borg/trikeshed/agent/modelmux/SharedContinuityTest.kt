package borg.trikeshed.agent.modelmux

import borg.trikeshed.cas.BlockTooLarge
import borg.trikeshed.cas.ContextBlockExchange
import borg.trikeshed.ipns.Ed25519
import borg.trikeshed.ipns.IpnsCid
import borg.trikeshed.ipns.IpnsRecord
import borg.trikeshed.ipns.toIpnsCid
import borg.trikeshed.job.CasStore
import borg.trikeshed.job.ContentId
import borg.trikeshed.lib.*
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/** What the two children can both reach: published blocks, nothing else. */
private class Network : ContextBlockExchange {
    val stored = LinkedHashMap<IpnsCid, ByteArray>()

    override suspend fun publish(bytes: ByteArray): IpnsCid = IpnsCid.raw(bytes).also { stored[it] = bytes.copyOf() }
    override suspend fun pin(cid: IpnsCid) { check(cid in stored) { "cannot pin what the node does not hold" } }
    override suspend fun fetch(cid: IpnsCid, maxBytes: Int): ByteArray {
        val bytes = stored[cid] ?: error("no provider for $cid")
        if (bytes.size > maxBytes) throw BlockTooLarge(maxBytes)
        return bytes.copyOf()
    }
}

/**
 * The reader's own view of the network. It counts only what the READER transfers: the publisher's read-back verification
 * fetches the same blocks through the same network, and must not be mistaken for the reader's cost.
 */
private class ReaderView(private val network: Network) : ContextBlockExchange {
    var fetches = 0
    override suspend fun fetch(cid: IpnsCid, maxBytes: Int): ByteArray { fetches++; return network.fetch(cid, maxBytes) }
    override suspend fun publish(bytes: ByteArray): IpnsCid = error("a reader does not publish")
    override suspend fun pin(cid: IpnsCid) = error("a reader does not pin")
}

/**
 * Continuity between two children that never share a conversation, only evidence:
 * one immutable baseline, a change proposed by one of them, and an update the other adopts only when it asks.
 *
 * The head announcement is modelled by the signed record a peer would receive over IPNS; its verification is the real
 * [AuthorHeads] path (signature, expiry, sequence), not a stand-in.
 */
class SharedContinuityTest {
    private val scope = "team/a"
    private val now = Instant.parse("2026-10-24T12:00:00Z")
    private val alice = Ed25519.generate()

    private val planV1 = "1. Parse the manifest.\n2. Verify the sha256 before caching any block.\n3. Pin the whole closure.\n"
    private val planV2 = "1. Parse the manifest.\n2. Verify the sha256 and the size before caching any block.\n3. Pin the whole closure.\n"
    private val notes = "Blocks are raw sha2-256 CIDs.\nThe ceiling is one mebibyte per block.\n"
    private val failure = "Tried: verify each block while it streams in.\nFailed: truncated blocks passed the length check. Do not retry; verify after the full read.\n"

    private fun head(manifest: ContentId, sequence: ULong): ByteArray =
        IpnsRecord.create(alice, "/ipfs/" + manifest.toIpnsCid(0x55u), sequence, Instant.parse("2026-10-25T12:00:00Z"), 300_000_000_000uL, Ed25519).bytes

    @Test
    fun twoChildrenShareOneBaselineAndAdoptAChangeOnlyWhenTheyChoose() = runTest {
        val network = Network()
        val reader = ReaderView(network)

        // The publisher (child A): a baseline, announced as its head at sequence 1.
        val r0 = revision(scope, "plan.md" to planV1, "notes.md" to notes, author = "alice")
        val casA = r0.intoCas(CasStore.inMemory())
        val publisher = SharedContextPublication(casA, network, null,
            PublicationPolicy(PublicationAudience.PRIVATE_PEERS, approvedScopes = setOf(scope), approvedAncestry = setOf(r0.cid)))
        assertEquals(PublicationStatus.COMPLETE, publisher.publish(r0.cid).status)

        // The reader (child B): an empty store, the network, and a pin on whatever head it first saw.
        val heads = AuthorHeads(Ed25519) { now }
        assertEquals(HeadDecision.ACCEPTED_FIRST, heads.offer(alice.name, head(r0.cid, 1u)))
        val pinned = heads.head(alice.name)!!.manifest
        assertEquals(r0.cid, pinned)

        val childB = SharedContextElement.open("child-b", CasStore.inMemory(), reader, ContextReadPolicy(mode = SharedContextMode.PEER, allowedScopes = setOf(scope)))
        try {
            val firstQuestion = "verify the sha256 before caching"
            val first = childB.project(ContextRequest(pinned, firstQuestion, scope))
            val baselineView = first.projection.render()
            assertTrue(first.projection.excerpts.size > 0 && "Verify the sha256 before caching" in baselineView, baselineView)
            assertEquals(3, childB.counters().fetches.executed, "the baseline cost three block transfers: the manifest and two documents")
            assertEquals(3, reader.fetches)

            // A proposes a change: a successor revision with a changed plan, an unchanged notes file, and a failure it learned.
            val r1 = revision(scope, "plan.md" to planV2, "notes.md" to notes, "failure-001.md" to failure, parents = listOf(r0.cid), author = "alice")
            r1.intoCas(casA)
            assertEquals(PublicationStatus.COMPLETE, publisher.publish(r1.cid).status)
            assertEquals(HeadDecision.ACCEPTED_NEWER, heads.offer(alice.name, head(r1.cid, 2u)))
            assertEquals(r1.cid, heads.head(alice.name)!!.manifest, "the author's head moved")

            // B's world did not: its pinned snapshot answers exactly as before, and no block crossed the wire to prove it.
            val stable = childB.project(ContextRequest(pinned, firstQuestion, scope))
            assertEquals(baselineView, stable.projection.render(), "a moved head never changes what a pinned task sees")
            assertEquals(3, reader.fetches)

            // Hearing of the update is a question B asks, not something that happens to it.
            val update = assertNotNull(heads.updateFor(alice.name, pinned))
            assertEquals(r1.cid, update.manifest)
            assertEquals(3, reader.fetches, "knowing about an update transfers nothing")

            // B adopts it and asks something only A's failure note can answer.
            val secondQuestion = "why did verifying while streaming fail with truncated blocks"
            val adopted = childB.project(ContextRequest(update.manifest, secondQuestion, scope))
            val excerpts = (0 until adopted.projection.excerpts.size).map { adopted.projection.excerpts[it] }
            val learned = excerpts.firstOrNull { it.document == "failure-001.md" }
            assertNotNull(learned, "the failure A learned is retrieved by B, with no conversation shared: ${adopted.projection.render()}")
            assertTrue("truncated blocks passed the length check" in learned.text)
            assertEquals(0, learned.range.start, "the excerpt names its exact place in A's original document")
            assertTrue(failure.startsWith(learned.text.lineSequence().first()), "and its text is A's original line, not a paraphrase")

            // Only what B lacked crossed the wire: the new manifest, the changed plan and the failure note. notes.md was already local.
            assertEquals(6, reader.fetches)
            assertEquals(6, childB.counters().fetches.executed)

            // Both revisions stay readable and distinct; nothing was overwritten.
            val old = childB.acquire(r0.cid, scope)
            val new = childB.acquire(r1.cid, scope)
            fun text(snapshot: ContextSnapshot, name: String) = (0 until snapshot.documents.size).map { snapshot.documents[it] }.single { it.name == name }.text
            assertEquals(planV1, text(old, "plan.md"))
            assertEquals(planV2, text(new, "plan.md"))
            assertEquals(text(old, "notes.md"), text(new, "notes.md"))
            assertEquals(6, reader.fetches, "re-reading either revision is local")
            assertEquals(listOf(r0.cid), new.manifest.parents.toList(), "the successor names the baseline it came from")

            // B has now adopted r1; there is nothing further to hear about until the head moves again.
            assertNull(heads.updateFor(alice.name, update.manifest))
        } finally {
            childB.drain()
        }
    }

    @Test
    fun aRevisionTheAuthorNeverApprovedForExportNeverReachesTheOtherChild() = runTest {
        val network = Network()
        val r0 = revision(scope, "plan.md" to planV1, author = "alice")
        val draft = revision("team/draft", "wip.md" to "half-formed idea\n", author = "alice")
        val casA = r0.intoCas(CasStore.inMemory())
        draft.intoCas(casA)
        val publisher = SharedContextPublication(casA, network, null, PublicationPolicy(PublicationAudience.PRIVATE_PEERS, approvedScopes = setOf(scope)))
        assertEquals(PublicationStatus.COMPLETE, publisher.publish(r0.cid).status)
        val refused = kotlin.runCatching { publisher.publish(draft.cid) }.exceptionOrNull()
        assertTrue(refused is PublicationRefused && refused.reason == PublicationRefusal.SCOPE_NOT_APPROVED, refused.toString())

        val childB = SharedContextElement.open("child-b", CasStore.inMemory(), network, ContextReadPolicy(mode = SharedContextMode.PEER, allowedScopes = setOf(scope, "team/draft")))
        try {
            val failed = kotlin.runCatching { childB.project(ContextRequest(draft.cid, "half-formed idea", "team/draft")) }.exceptionOrNull()
            assertTrue(failed is ContextBlockException && failed.reason == ContextRejection.EXCHANGE_FAILED, failed.toString())
            assertTrue(draft.blocks.keys.none { network.stored.containsKey(it.rawCid()) }, "none of the draft's blocks were ever published")
        } finally {
            childB.drain()
        }
    }
}
