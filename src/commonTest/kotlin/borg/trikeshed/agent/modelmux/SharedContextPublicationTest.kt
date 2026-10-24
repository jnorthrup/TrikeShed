package borg.trikeshed.agent.modelmux

import borg.trikeshed.cas.BlockTooLarge
import borg.trikeshed.cas.ContextBlockExchange
import borg.trikeshed.ipns.IpnsCid
import borg.trikeshed.job.CasStore
import borg.trikeshed.job.ContentId
import borg.trikeshed.lib.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** An in-memory stand-in for a peer network that records every call in order and can be told to misbehave in one way. */
private class MemoryPeer(val log: MutableList<String> = mutableListOf()) : ContextBlockExchange {
    val stored = LinkedHashMap<IpnsCid, ByteArray>()
    val pinned = LinkedHashSet<IpnsCid>()
    var failPinFor: IpnsCid? = null
    var wrongIdentityFor: IpnsCid? = null
    var unreachable: IpnsCid? = null
    var tamperedReadBackFor: IpnsCid? = null
    var cancelOnPublishFor: IpnsCid? = null

    override suspend fun publish(bytes: ByteArray): IpnsCid {
        val cid = IpnsCid.raw(bytes)
        log += "publish $cid"
        if (cid == cancelOnPublishFor) throw CancellationException("operator stopped")
        if (cid == wrongIdentityFor) return IpnsCid.raw(byteArrayOf(1, 2, 3))
        stored[cid] = bytes.copyOf()
        return cid
    }

    override suspend fun pin(cid: IpnsCid) {
        log += "pin $cid"
        if (cid == failPinFor) error("pin refused")
        check(cid in stored) { "cannot pin a block the node does not hold" }
        pinned += cid
    }

    override suspend fun fetch(cid: IpnsCid, maxBytes: Int): ByteArray {
        log += "fetch $cid"
        if (cid == unreachable) error("no provider for $cid")
        val bytes = stored[cid] ?: error("absent")
        if (bytes.size > maxBytes) throw BlockTooLarge(maxBytes)
        if (cid == tamperedReadBackFor) return bytes.copyOf().also { if (it.isNotEmpty()) it[0] = (it[0].toInt() xor 1).toByte() }
        return bytes.copyOf()
    }
}

private class CountingHead(private val log: MutableList<String>? = null) : HeadAnnouncer {
    var calls = 0
    override suspend fun announce(root: ContentId): HeadReceipt {
        calls++
        log?.add("announce")
        return HeadReceipt("k51fixture", 1uL, "/ipfs/fixture", true)
    }
}

class SharedContextPublicationTest {
    private val scope = "team/a"

    private fun stored(vararg revisions: Revision): CasStore {
        val cas = CasStore.inMemory()
        for (r in revisions) for (block in r.blocks.values) cas.put(block)
        return cas
    }

    private fun policy(
        audience: PublicationAudience = PublicationAudience.PRIVATE_PEERS,
        scopes: Set<String> = setOf(scope),
        ancestry: Set<ContentId> = emptySet(),
        publicApproved: Boolean = false,
        maxBlocks: Int = 8_192, maxBlockBytes: Int = 1_048_576, maxTotalBytes: Long = 67_108_864, depth: Int = 8,
    ) = PublicationPolicy(audience, scopes, ancestry, publicApproved, maxBlocks, maxBlockBytes, maxTotalBytes, depth)

    private suspend fun refusal(publication: SharedContextPublication, root: ContentId) = assertFailsWith<PublicationRefused> { publication.publish(root) }

    @Test
    fun localModeHasNoDiscoveryOrPublication() = runTest {
        val rev = revision(scope, "a.md" to "alpha\n")
        val peer = MemoryPeer(); val head = CountingHead()
        assertEquals(PublicationRefusal.LOCAL_MODE, refusal(SharedContextPublication(stored(rev), peer, head, PublicationPolicy()), rev.cid).reason, "LOCAL is the default")
        assertEquals(PublicationRefusal.LOCAL_MODE, refusal(SharedContextPublication(stored(rev), peer, head, PublicationPolicy(approvedScopes = setOf(scope))), rev.cid).reason, "approving a scope cannot override LOCAL")
        assertEquals(PublicationRefusal.LOCAL_MODE, refusal(SharedContextPublication(stored(rev), null, head, policy()), rev.cid).reason, "no exchange, no publication")
        assertTrue(peer.log.isEmpty()); assertEquals(0, head.calls)
    }

    @Test
    fun publicExportNeedsExplicitApprovalAndAScopeTheOperatorNamed() = runTest {
        val rev = revision(scope, "a.md" to "alpha\n")
        val peer = MemoryPeer()
        assertEquals(PublicationRefusal.PUBLIC_NOT_APPROVED, refusal(SharedContextPublication(stored(rev), peer, null, policy(PublicationAudience.PUBLIC)), rev.cid).reason)
        assertEquals(PublicationRefusal.SCOPE_NOT_APPROVED, refusal(SharedContextPublication(stored(rev), peer, null, policy(scopes = setOf("team/other"))), rev.cid).reason)
        assertEquals(PublicationRefusal.SCOPE_NOT_APPROVED, refusal(SharedContextPublication(stored(rev), peer, null, policy(PublicationAudience.PUBLIC, scopes = emptySet(), publicApproved = true)), rev.cid).reason, "approval of PUBLIC is not approval of a scope")
        assertTrue(peer.log.isEmpty(), "every refusal came before the first block was sent")

        assertEquals(PublicationStatus.COMPLETE, SharedContextPublication(stored(rev), peer, null, policy(PublicationAudience.PUBLIC, publicApproved = true)).publish(rev.cid).status)
        assertEquals(PublicationStatus.COMPLETE, SharedContextPublication(stored(rev), MemoryPeer(), null, policy()).publish(rev.cid).status)
    }

    @Test
    fun privateLineageCannotLeak() = runTest {
        val v1 = revision(scope, "a.md" to "one\n")
        val v2 = revision(scope, "a.md" to "two\n", parents = listOf(v1.cid))
        val cas = stored(v1, v2)
        val peer = MemoryPeer()

        val refused = refusal(SharedContextPublication(cas, peer, null, policy()), v2.cid)
        assertEquals(PublicationRefusal.PRIVATE_LINEAGE, refused.reason)
        assertTrue(v1.cid.value in refused.message.orEmpty())
        assertTrue(peer.log.isEmpty(), "not even the child's own blocks were sent: a reference to a private parent would dangle")

        val receipt = SharedContextPublication(cas, peer, null, policy(ancestry = setOf(v1.cid))).publish(v2.cid)
        assertEquals(PublicationStatus.COMPLETE, receipt.status)
        assertEquals(v1.blocks.keys + v2.blocks.keys, receipt.blocks.map { it.cid }.toSet(), "an approved parent travels with its whole closure")

        assertEquals(PublicationRefusal.CLOSURE_INCOMPLETE, refusal(SharedContextPublication(stored(v2), MemoryPeer(), null, policy(ancestry = setOf(v1.cid))), v2.cid).reason)

        val secret = revision("team/private", "x.md" to "secret\n")
        val child = revision(scope, "b.md" to "public\n", parents = listOf(secret.cid))
        val leak = MemoryPeer()
        assertEquals(PublicationRefusal.SCOPE_NOT_APPROVED, refusal(SharedContextPublication(stored(secret, child), leak, null, policy(ancestry = setOf(secret.cid))), child.cid).reason, "an approved parent is still held to the scope approval")
        assertTrue(leak.log.isEmpty())
    }

    @Test
    fun everyRequiredRawBlockMustBePinnedAndServedBackBeforeTheHeadMoves() = runTest {
        val rev = revision(scope, "a.md" to "alpha\n", "b.md" to "beta\n")
        val beta = ContentId.of(bytesOf("beta\n"))
        for (fault in listOf("pin", "identity", "unreachable")) {
            val peer = MemoryPeer(); val head = CountingHead()
            when (fault) {
                "pin" -> peer.failPinFor = beta.rawCid()
                "identity" -> peer.wrongIdentityFor = beta.rawCid()
                else -> peer.unreachable = beta.rawCid()
            }
            val receipt = SharedContextPublication(stored(rev), peer, head, policy()).publish(rev.cid)
            assertEquals(PublicationStatus.INCOMPLETE, receipt.status, fault)
            assertEquals(0, head.calls, "$fault: a valid signed name over a block nobody can fetch is a broken pointer, so it is never made")
            assertNull(receipt.head); assertNull(receipt.headFailure)
            val bad = receipt.blocks.single { !it.verified }
            assertEquals(beta, bad.cid)
            when (fault) {
                "pin" -> assertTrue(bad.published && !bad.pinned)
                "identity" -> assertTrue(!bad.published)
                else -> assertTrue(bad.published && bad.pinned, "accepted and retained, yet not available")
            }
            assertNotNull(bad.failure)
            assertEquals(receipt.blocks.size - 1, receipt.blocks.count { it.verified }, "$fault: the other blocks still have their own receipts")
        }
    }

    @Test
    fun aCompletePublicationAnnouncesTheHeadOnceAndOnlyAfterEveryBlock() = runTest {
        val rev = revision(scope, "a.md" to "alpha\n", "b.md" to "beta\n")
        val peer = MemoryPeer(); val head = CountingHead(peer.log)
        val receipt = SharedContextPublication(stored(rev), peer, head, policy()).publish(rev.cid)
        assertEquals(PublicationStatus.COMPLETE, receipt.status)
        assertEquals(1, head.calls); assertNotNull(receipt.head)
        assertEquals(3, receipt.blocks.size)
        assertTrue(receipt.blocks.all { it.published && it.pinned && it.verified && it.failure == null })
        assertEquals("announce", peer.log.last())
        assertEquals(3 * 3 + 1, peer.log.size, "publish, pin and read-back for each of the three blocks, then exactly one announcement")
        assertEquals(setOf(IpnsCid.raw(bytesOf("alpha\n")), IpnsCid.raw(bytesOf("beta\n"))), peer.pinned - rev.cid.rawCid())

        val failing = SharedContextPublication(stored(rev), MemoryPeer(), HeadAnnouncer { error("dht unreachable") }, policy()).publish(rev.cid)
        assertEquals(PublicationStatus.COMPLETE, failing.status, "the blocks are live even though the name could not be announced")
        assertNull(failing.head); assertTrue("dht unreachable" in failing.headFailure.orEmpty())
    }

    @Test
    fun bytesThatComeBackDifferentAreNotAvailabilityEvenIfTheNodeAcceptedAndPinnedThem() = runTest {
        val rev = revision(scope, "a.md" to "alpha\n", "b.md" to "beta\n")
        val beta = ContentId.of(bytesOf("beta\n"))
        val peer = MemoryPeer().also { it.tamperedReadBackFor = beta.rawCid() }
        val head = CountingHead()
        val receipt = SharedContextPublication(stored(rev), peer, head, policy()).publish(rev.cid)
        assertEquals(PublicationStatus.INCOMPLETE, receipt.status)
        assertEquals(0, head.calls, "a name over bytes that do not match is a broken pointer")
        val bad = receipt.blocks.single { !it.verified }
        assertEquals(beta, bad.cid)
        assertTrue(bad.published && bad.pinned, "the node did accept and retain it")
        assertTrue("do not match" in bad.failure.orEmpty(), bad.failure)
    }

    @Test
    fun cancellationIsNeverReportedAsAFailedBlock() = runTest {
        val rev = revision(scope, "a.md" to "alpha\n")
        val peer = MemoryPeer().also { it.cancelOnPublishFor = ContentId.of(bytesOf("alpha\n")).rawCid() }
        val head = CountingHead()
        assertFailsWith<CancellationException> { SharedContextPublication(stored(rev), peer, head, policy()).publish(rev.cid) }
        assertEquals(0, head.calls, "a cancelled publication announces nothing")
    }

    @Test
    fun theClosureIsPlannedFromLocalVerifiedBytesBeforeAnythingIsSent() = runTest {
        val rev = revision(scope, "a.md" to "alpha\n", "b.md" to "beta\n")
        val cas = CasStore.inMemory()
        cas.put(rev.manifest.encode()); cas.put(bytesOf("alpha\n"))                        // beta is missing
        val peer = MemoryPeer()
        assertEquals(PublicationRefusal.CLOSURE_INCOMPLETE, refusal(SharedContextPublication(cas, peer, null, policy()), rev.cid).reason)
        assertEquals(PublicationRefusal.CLOSURE_INCOMPLETE, refusal(SharedContextPublication(cas, peer, null, policy()), ContentId("sha256:" + "0".repeat(64))).reason)
        val junk = cas.put(bytesOf("this is not a manifest\n"))
        assertEquals(PublicationRefusal.MANIFEST_INVALID, refusal(SharedContextPublication(cas, peer, null, policy()), junk).reason)
        assertTrue(peer.log.isEmpty(), "all-or-nothing at planning time: a partial closure is never sent")
    }

    @Test
    fun budgetsAreEnforcedBeforeAnyEffect() = runTest {
        val rev = revision(scope, "a.md" to "alpha\n", "b.md" to "beta\n")
        val peer = MemoryPeer()
        assertEquals(PublicationRefusal.BUDGET_EXCEEDED, refusal(SharedContextPublication(stored(rev), peer, null, policy(maxBlocks = 2)), rev.cid).reason)
        assertEquals(PublicationRefusal.BUDGET_EXCEEDED, refusal(SharedContextPublication(stored(rev), peer, null, policy(maxBlockBytes = 3)), rev.cid).reason)
        assertEquals(PublicationRefusal.BUDGET_EXCEEDED, refusal(SharedContextPublication(stored(rev), peer, null, policy(maxTotalBytes = 10)), rev.cid).reason)

        val v1 = revision(scope, "a.md" to "1\n"); val v2 = revision(scope, "a.md" to "2\n", parents = listOf(v1.cid)); val v3 = revision(scope, "a.md" to "3\n", parents = listOf(v2.cid))
        val chain = policy(ancestry = setOf(v1.cid, v2.cid), depth = 1)
        assertEquals(PublicationRefusal.BUDGET_EXCEEDED, refusal(SharedContextPublication(stored(v1, v2, v3), peer, null, chain), v3.cid).reason)
        assertTrue(peer.log.isEmpty())
    }

    @Test
    fun identicalBlocksAreSentOnce() = runTest {
        val rev = revision(scope, "a.md" to "same\n", "b.md" to "same\n")
        val peer = MemoryPeer()
        val receipt = SharedContextPublication(stored(rev), peer, null, policy()).publish(rev.cid)
        assertEquals(2, receipt.blocks.size, "the manifest and one copy of the shared document")
        assertEquals(2, peer.log.count { it.startsWith("publish") })
    }

    @Test
    fun whatWasPublishedIsWhatAPeerReaderAcquiresAndVerifies() = runTest {
        val rev = revision(scope, "a.md" to "alpha manifest\n", "b.md" to "beta\n")
        val peer = MemoryPeer()
        assertEquals(PublicationStatus.COMPLETE, SharedContextPublication(stored(rev), peer, null, policy()).publish(rev.cid).status)
        val reader = SharedContext(CasStore.inMemory(), peer, ContextReadPolicy(mode = SharedContextMode.PEER, allowedScopes = setOf(scope)))
        val snapshot = reader.acquire(rev.cid, scope)
        assertEquals(listOf("a.md", "b.md"), (0 until snapshot.documents.size).map { snapshot.documents[it].name })
        assertEquals("alpha manifest\n", snapshot.documents[0].text)
    }
}
