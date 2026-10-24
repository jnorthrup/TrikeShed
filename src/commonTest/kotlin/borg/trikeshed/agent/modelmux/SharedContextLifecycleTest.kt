package borg.trikeshed.agent.modelmux

import borg.trikeshed.cas.ContextBlockExchange
import borg.trikeshed.ipns.IpnsCid
import borg.trikeshed.job.CasStore
import borg.trikeshed.job.ContentId
import borg.trikeshed.lib.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** A peer that holds every fetch at a gate until the test opens it, and measures how much was asked of it at once. */
private class GatedPeer(private val blocks: Map<IpnsCid, ByteArray>, private val holding: Set<IpnsCid>? = null) : ContextBlockExchange {
    val calls = mutableListOf<IpnsCid>()
    val entered = Channel<IpnsCid>(Channel.UNLIMITED)
    var active = 0
    var maxActive = 0
    var failNext: Throwable? = null
    private var opened = false
    private val gates = HashMap<IpnsCid, CompletableDeferred<Unit>>()

    private fun gate(cid: IpnsCid) = gates.getOrPut(cid) { CompletableDeferred() }

    /** Releases every gate, now and for later fetches. */
    fun open() {
        opened = true
        gates.values.forEach { it.complete(Unit) }
    }

    fun release(cid: IpnsCid) = gate(cid).complete(Unit)

    override suspend fun fetch(cid: IpnsCid, maxBytes: Int): ByteArray {
        calls += cid
        active++
        maxActive = maxOf(maxActive, active)
        try {
            entered.send(cid)
            if (!opened && (holding == null || cid in holding)) gate(cid).await()
            failNext?.let { failNext = null; throw it }
            return blocks[cid] ?: throw IllegalStateException("peer has no $cid")
        } finally {
            active--
        }
    }

    override suspend fun publish(bytes: ByteArray): IpnsCid = error("not a read")
    override suspend fun pin(cid: IpnsCid) = error("not a read")
}

private val PEER = ContextReadPolicy(mode = SharedContextMode.PEER, allowedScopes = setOf("team/a"))

class SharedContextLifecycleTest {
    private fun peerOf(rev: Revision, holding: Set<IpnsCid>? = null) = GatedPeer(rev.blocks.mapKeys { it.key.rawCid() }, holding)

    private suspend fun openElement(
        peer: ContextBlockExchange?, cas: CasStore = CasStore.inMemory(), parallelism: Int = 4, capacity: Int = 16, policy: ContextReadPolicy = PEER,
        cacheLimit: Int = 64,
    ) = SharedContextElement.open("lifecycle", cas, peer, policy, parallelism, capacity, cacheLimit)

    private fun TestScope.settle() = runCurrent()

    @Test
    fun identicalMissesShareOneFetchButNotTaskState() = runTest {
        val rev = revision("team/a", "notes.md" to "alpha manifest\n")
        val doc = ContentId.of(bytesOf("alpha manifest\n"))
        val peer = peerOf(rev)
        // The manifest is already local, so both tasks go straight to the same missing document block.
        val element = openElement(peer, cas = CasStore.inMemory().also { check(it.put(rev.manifest.encode()) == rev.cid) })
        try {
            val first = async { element.acquire(rev.cid, "team/a") }
            val second = async { element.acquire(rev.cid, "team/a") }
            peer.entered.receive()
            settle()
            assertEquals(listOf(doc.rawCid()), peer.calls, "two tasks asked, one transfer began")
            peer.open()
            val a = first.await()
            val b = second.await()

            assertEquals(listOf("notes.md"), (0 until a.documents.size).map { a.documents[it].name })
            assertEquals(a.manifestCid, b.manifestCid)
            assertNotSame(a, b, "each task holds its own snapshot; only the bytes were shared")
            val fetches = element.counters().fetches
            assertEquals(2, fetches.requests)
            assertEquals(1, fetches.executed, "one physical transfer")
            assertEquals(1, fetches.joined, "the other request did no work of its own")
            assertEquals(1, peer.calls.size)
        } finally {
            element.drain()
        }
    }

    @Test
    fun aCancelledLeaderDoesNotCancelTheFetchOthersWaitOn() = runTest {
        val rev = revision("team/a", "notes.md" to "alpha\n")
        val doc = ContentId.of(bytesOf("alpha\n"))
        val peer = peerOf(rev)
        val element = openElement(peer)
        try {
            val leader = async { element.context.block(doc) }
            peer.entered.receive()
            val waiter = async { element.context.block(doc) }
            settle()
            leader.cancel()
            settle()
            peer.open()
            assertEquals(doc, waiter.await().cid, "the waiter still got the block")
            assertTrue(leader.isCancelled)
            assertEquals(1, element.counters().fetches.executed, "the one transfer was never repeated")
        } finally {
            element.drain()
        }
    }

    @Test
    fun aFailedFetchIsSharedWithWaitersButNeverRemembered() = runTest {
        val rev = revision("team/a", "notes.md" to "alpha\n")
        val doc = ContentId.of(bytesOf("alpha\n"))
        val peer = peerOf(rev)
        val element = openElement(peer)
        try {
            peer.open()
            peer.failNext = IllegalStateException("peer down")
            val failed = assertFailsWith<ContextBlockException> { element.context.block(doc) }
            assertEquals(ContextRejection.EXCHANGE_FAILED, failed.reason)

            assertEquals(doc, element.context.block(doc).cid, "the next request starts afresh and succeeds")
            assertEquals(2, peer.calls.size)
            element.context.block(doc)
            assertEquals(2, peer.calls.size, "a success is remembered by the store, a failure never was")
        } finally {
            element.drain()
        }
    }

    @Test
    fun aLeaderCancelledBeforeAdmissionHandsTheKeyToAWaiter() = runTest {
        // One worker, one slot: X runs, Y queues, so Z's caller is held back at admission itself.
        val rev = revision("team/a", "x.md" to "x\n", "y.md" to "y\n", "z.md" to "z\n")
        val x = ContentId.of(bytesOf("x\n"))
        val y = ContentId.of(bytesOf("y\n"))
        val z = ContentId.of(bytesOf("z\n"))
        val peer = peerOf(rev, holding = setOf(x.rawCid()))
        val element = openElement(peer, parallelism = 1, capacity = 1)
        try {
            val running = async { element.context.block(x) }
            peer.entered.receive()
            val queued = async { element.context.block(y) }
            settle()
            val leader = async { element.context.block(z) }
            settle()
            val waiter = async { element.context.block(z) }
            settle()
            assertFalse(peer.calls.contains(z.rawCid()), "Z has not been admitted yet")

            leader.cancel()
            settle()
            peer.release(x.rawCid())

            assertEquals(x, running.await().cid)
            assertEquals(y, queued.await().cid)
            assertEquals(z, waiter.await().cid, "the waiter took the key over instead of waiting on work that never started")
            assertEquals(3, element.counters().fetches.executed)
        } finally {
            peer.open()
            element.drain()
        }
    }

    @Test
    fun differentQuestionsShareBytesNotProjections() = runTest {
        val rev = revision("team/a", "a.md" to "alpha token one\n", "b.md" to "beta token two\n")
        val peer = peerOf(rev)
        val element = openElement(peer)
        try {
            peer.open()
            val q1 = ContextRequest(rev.cid, "alpha", "team/a")
            val q2 = ContextRequest(rev.cid, "beta", "team/a")
            val one = element.project(q1)
            val two = element.project(q2)

            assertEquals(3, peer.calls.size, "manifest and two documents, each transferred once for both questions")
            assertTrue(one.view.render().contains("alpha token one") && !one.view.render().contains("beta token two"))
            assertTrue(two.view.render().contains("beta token two") && !two.view.render().contains("alpha token one"))
            assertEquals(2, element.counters().projections.executed, "two questions, two projections")

            assertSame(one, element.project(q1), "the identical request is answered from what was already computed")
            val counters = element.counters()
            assertEquals(1, counters.projectionCacheHits)
            assertEquals(2, counters.projections.executed)
            assertEquals(3, peer.calls.size)
        } finally {
            element.drain()
        }
    }

    @Test
    fun aNewRevisionNeverReusesAnOldProjection() = runTest {
        val r1 = revision("team/a", "a.md" to "alpha token\n", "b.md" to "beta\n")
        val r2 = revision("team/a", "a.md" to "alpha token\n", "b.md" to "beta changed token\n", parents = listOf(r1.cid))
        val peer = GatedPeer((r1.blocks + r2.blocks).mapKeys { it.key.rawCid() })
        val element = openElement(peer)
        try {
            peer.open()
            val old = element.project(ContextRequest(r1.cid, "token", "team/a"))
            val new = element.project(ContextRequest(r2.cid, "token", "team/a"))

            assertTrue(!old.view.render().contains("changed"), old.view.render())
            assertTrue(new.view.render().contains("beta changed token"), new.view.render())
            assertEquals(0, element.counters().projectionCacheHits, "same question, different revision: nothing was reused")
            assertEquals(5, peer.calls.size, "r1: manifest, a, b; r2: manifest and the one changed document, since a is already local")
        } finally {
            element.drain()
        }
    }

    @Test
    fun coalescedReviewRecordsOneJudgeCallForTwoIdenticalRequests() = runTest {
        val rev = revision("team/a", "notes.md" to "alpha token\n")
        val peer = peerOf(rev)
        val element = openElement(peer)
        val judged = Channel<Unit>(Channel.UNLIMITED)
        val release = CompletableDeferred<Unit>()
        var judgeCalls = 0
        val judge = ReviewPolicy("judge-1", object : BreadcrumbOversight {
            override suspend fun review(question: String, projection: Projection): OversightReceipt {
                judgeCalls++
                judged.send(Unit)
                release.await()
                val n = projection.excerpts.size
                return OversightReceipt(OversightStatus.REVIEWED, "fake", List<Verdict?>(n) { Verdict.RELEVANT }, List<Double?>(n) { 0.9 }, null)
            }
        })
        try {
            peer.open()
            val request = ContextRequest(rev.cid, "token", "team/a", review = judge)
            val first = async { element.project(request) }
            judged.receive()
            val second = async { element.project(request) }
            settle()
            release.complete(Unit)

            assertSame(first.await(), second.await(), "both received the one audited answer")
            assertEquals(1, judgeCalls, "the judge was asked once")
            val counters = element.counters().projections
            assertEquals(1, counters.executed)
            assertEquals(1, counters.joined)
        } finally {
            release.complete(Unit)
            element.drain()
        }
    }

    @Test
    fun anUnavailableReviewIsNeverRemembered() = runTest {
        val rev = revision("team/a", "notes.md" to "alpha token\n")
        val peer = peerOf(rev)
        val element = openElement(peer)
        var calls = 0
        var up = false
        val judge = ReviewPolicy("flaky", object : BreadcrumbOversight {
            override suspend fun review(question: String, projection: Projection): OversightReceipt {
                calls++
                val n = projection.excerpts.size
                return if (!up) OversightReceipt(OversightStatus.UNAVAILABLE, null, List<Verdict?>(n) { null }, List<Double?>(n) { null }, "down")
                else OversightReceipt(OversightStatus.REVIEWED, "fake", List<Verdict?>(n) { Verdict.RELEVANT }, List<Double?>(n) { 0.9 }, null)
            }
        })
        try {
            peer.open()
            val request = ContextRequest(rev.cid, "token", "team/a", review = judge)
            assertEquals(OversightStatus.UNAVAILABLE, element.project(request).receipt.status)
            up = true
            assertEquals(OversightStatus.REVIEWED, element.project(request).receipt.status, "the judge came back, so the answer was recomputed")
            assertEquals(2, calls)
            element.project(request)
            assertEquals(2, calls, "a reviewed answer is remembered")
        } finally {
            element.drain()
        }
    }

    @Test
    fun drainJoinsAdmittedFetchesAndStopsAdmission() = runTest {
        val rev = revision("team/a", "notes.md" to "alpha token\n")
        val doc = ContentId.of(bytesOf("alpha token\n"))
        val peer = peerOf(rev)
        val element = openElement(peer)
        try {
            val admitted = async { element.context.block(doc) }
            peer.entered.receive()

            val draining = async { element.drain() }
            settle()
            assertFalse(draining.isCompleted, "drain waits for the fetch it admitted")
            assertFailsWith<IllegalStateException> { element.acquire(rev.cid, "team/a") }
            assertFailsWith<IllegalStateException> { element.project(ContextRequest(rev.cid, "token", "team/a")) }

            peer.open()
            assertEquals(doc, admitted.await().cid, "the admitted caller got its block")
            draining.await()
        } finally {
            peer.open()
            element.drain()   // idempotent: safe whether or not the test already drained
        }
    }

    @Test
    fun backpressureBoundsConcurrentTransfers() = runTest {
        val texts = (1..5).map { "line $it\n" }
        val rev = revision("team/a", *texts.mapIndexed { i, t -> "d$i.md" to t }.toTypedArray())
        val peer = peerOf(rev)
        val element = openElement(peer, parallelism = 2, capacity = 2)
        try {
            val reads = texts.map { t -> async { element.context.block(ContentId.of(bytesOf(t))) } }
            settle()
            assertTrue(peer.maxActive <= 2, "at most 2 transfers at once, saw ${peer.maxActive}")
            peer.open()
            reads.forEach { it.await() }
            assertEquals(5, element.counters().fetches.executed)
            assertTrue(peer.maxActive <= 2, "saw ${peer.maxActive}")
        } finally {
            peer.open()
            element.drain()
        }
    }

    @Test
    fun theProjectionCacheIsBoundedAndLeastRecentlyUsed() = runTest {
        val rev = revision("team/a", "a.md" to "alpha token\n", "b.md" to "beta token\n", "c.md" to "gamma token\n")
        val peer = peerOf(rev)
        val element = openElement(peer, cacheLimit = 2)
        try {
            peer.open()
            val q1 = ContextRequest(rev.cid, "alpha", "team/a")
            val q2 = ContextRequest(rev.cid, "beta", "team/a")
            val q3 = ContextRequest(rev.cid, "gamma", "team/a")
            val first = element.project(q1)
            element.project(q2)
            assertSame(first, element.project(q1), "touching q1 makes q2 the least recently used")
            element.project(q3)                                     // evicts q2, not q1
            assertEquals(3, element.counters().projections.executed)

            assertSame(first, element.project(q1), "q1 survived the eviction")
            assertEquals(3, element.counters().projections.executed)
            element.project(q2)
            assertEquals(4, element.counters().projections.executed, "q2 was evicted, so it was recomputed")
        } finally {
            element.drain()
        }
    }

    @Test
    fun differentReviewConfigurationsNeverShareAnAnswer() = runTest {
        val rev = revision("team/a", "notes.md" to "alpha token\n")
        val peer = peerOf(rev)
        val element = openElement(peer)
        fun judge(key: String, verdict: Verdict) = ReviewPolicy(key, object : BreadcrumbOversight {
            override suspend fun review(question: String, projection: Projection): OversightReceipt {
                val n = projection.excerpts.size
                return OversightReceipt(OversightStatus.REVIEWED, key, List<Verdict?>(n) { verdict }, List<Double?>(n) { 0.5 }, null)
            }
        })
        try {
            peer.open()
            val strict = element.project(ContextRequest(rev.cid, "token", "team/a", review = judge("strict", Verdict.NOT_RELEVANT)))
            val lenient = element.project(ContextRequest(rev.cid, "token", "team/a", review = judge("lenient", Verdict.RELEVANT)))
            val unreviewed = element.project(ContextRequest(rev.cid, "token", "team/a"))

            assertEquals("strict", strict.receipt.model)
            assertEquals("lenient", lenient.receipt.model)
            assertEquals(OversightStatus.NOT_REQUESTED, unreviewed.receipt.status)
            assertEquals(3, element.counters().projections.executed)
            assertEquals(0, element.counters().projectionCacheHits)
        } finally {
            element.drain()
        }
    }

    @Test
    fun drainLetsAnAdmittedProjectionFinishItsLaterFetches() = runTest {
        val rev = revision("team/a", "notes.md" to "alpha token\n")
        val peer = peerOf(rev, holding = setOf(rev.cid.rawCid()))   // only the manifest is held at the gate
        val element = openElement(peer)
        try {
            val projecting = async { element.project(ContextRequest(rev.cid, "token", "team/a")) }
            peer.entered.receive()                                  // the manifest fetch is in flight
            val draining = async { element.drain() }
            settle()
            assertFalse(draining.isCompleted, "drain waits for the projection it admitted")

            peer.release(rev.cid.rawCid())                          // the projection now needs its document, after drain began
            assertTrue(projecting.await().view.render().contains("alpha token"), "the admitted projection completed through its second fetch")
            draining.await()
        } finally {
            peer.open()
            element.drain()
        }
    }

    @Test
    fun workCancelledByItsOwnerSurfacesAsAbandonedNeverAsTheCallersOwnCancellation() = runTest {
        val rev = revision("team/a", "notes.md" to "alpha\n")
        val doc = ContentId.of(bytesOf("alpha\n"))
        val peer = peerOf(rev)
        val element = openElement(peer)
        try {
            peer.open()
            peer.failNext = CancellationException("owner stopped")
            val failed = assertFailsWith<ContextBlockException> { element.context.block(doc) }
            assertEquals(ContextRejection.EXCHANGE_FAILED, failed.reason)
            var cause: Throwable? = failed
            while (cause != null && cause !is SharedWorkAbandoned) cause = cause.cause
            assertTrue(cause is SharedWorkAbandoned, "the caller was told the shared work was abandoned, not that it was itself cancelled: $failed")

            assertEquals(doc, element.context.block(doc).cid, "the next request starts afresh")
        } finally {
            element.drain()
        }
    }

    @Test
    fun afterDrainAcquireIsRefused() = runTest {
        val rev = revision("team/a", "notes.md" to "alpha\n")
        val peer = peerOf(rev)
        val element = openElement(peer)
        peer.open()
        element.drain()
        assertFailsWith<IllegalStateException> { element.acquire(rev.cid, "team/a") }
        assertEquals(0, peer.calls.size, "a refused request never reached the peer")
    }

    @Test
    fun afterDrainProjectIsRefused() = runTest {
        val rev = revision("team/a", "notes.md" to "alpha\n")
        val peer = peerOf(rev)
        val element = openElement(peer)
        peer.open()
        element.drain()
        assertFailsWith<IllegalStateException> { element.project(ContextRequest(rev.cid, "alpha", "team/a")) }
        assertEquals(0, peer.calls.size, "a refused request never reached the peer")
    }

    @Test
    fun afterDrainEvenARequestThatNeedsNoPeerIsRefused() = runTest {
        // Everything is already local, so a closed fetch seat cannot explain a refusal: only the element's own admission can.
        val rev = revision("team/a", "notes.md" to "alpha\n")
        val peer = peerOf(rev)
        val element = openElement(peer, cas = rev.intoCas(CasStore.inMemory()))
        assertEquals("notes.md", element.acquire(rev.cid, "team/a").documents[0].name, "served before the drain")
        element.drain()
        val acquire = assertFailsWith<IllegalStateException> { element.acquire(rev.cid, "team/a") }
        assertTrue("draining" in acquire.message.orEmpty(), acquire.message)
        val project = assertFailsWith<IllegalStateException> { element.project(ContextRequest(rev.cid, "alpha", "team/a")) }
        assertTrue("draining" in project.message.orEmpty(), project.message)
        assertEquals(0, peer.calls.size)
    }

    @Test
    fun afterDrainEvenARememberedProjectionIsNotServed() = runTest {
        val rev = revision("team/a", "notes.md" to "alpha\n")
        val element = openElement(peerOf(rev), cas = rev.intoCas(CasStore.inMemory()))
        val request = ContextRequest(rev.cid, "alpha", "team/a")
        val first = element.project(request)
        assertSame(first, element.project(request), "remembered while open")
        element.drain()
        assertFailsWith<IllegalStateException> { element.project(request) }
    }

    @Test
    fun theElementIsFoundThroughItsContextKey() = runTest {
        val element = openElement(null, policy = ContextReadPolicy(allowedScopes = setOf("team/a")))
        try {
            withContext(element) { assertSame(element, currentCoroutineContext()[SharedContextElement]) }
        } finally {
            element.drain()
        }
    }
}
