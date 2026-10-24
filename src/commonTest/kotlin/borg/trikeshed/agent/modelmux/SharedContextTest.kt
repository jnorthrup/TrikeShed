package borg.trikeshed.agent.modelmux

import borg.trikeshed.ipns.IpnsCid
import borg.trikeshed.job.CasStore
import borg.trikeshed.job.ContentId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Block-level shared context. The exchange is a fixture here; a real peer is exercised by the opt-in integration test. */
class SharedContextTest {
    private fun bytes(text: String) = bytesOf(text)
    private fun ContentId.raw(): IpnsCid = rawCid()

    @Test
    fun localReadNeverCallsExchange() = runTest {
        val cas = CasStore.inMemory()
        val held = cas.put(bytes("held locally"))
        val ctx = SharedContext(cas, ForbiddenExchange, ContextReadPolicy(mode = SharedContextMode.LOCAL))

        val hit = ctx.block(held)
        assertEquals(BlockSource.LOCAL, hit.source)
        assertContentEquals(bytes("held locally"), hit.bytes)

        // A LOCAL miss is an absence. It does not become a fetch, even though an exchange is wired.
        val missing = ContentId.of(bytes("never stored"))
        val absent = assertFailsWith<ContextBlockException> { ctx.block(missing) }
        assertEquals(ContextRejection.ABSENT, absent.reason)
        assertEquals(missing, absent.cid)
    }

    @Test
    fun localHitNeverCallsExchangeEvenInPeerMode() = runTest {
        val cas = CasStore.inMemory()
        val held = cas.put(bytes("already cached"))
        val ctx = SharedContext(cas, ForbiddenExchange, ContextReadPolicy(mode = SharedContextMode.PEER))
        assertEquals(BlockSource.LOCAL, ctx.block(held).source)
        assertEquals(ContextReadCounters(localReads = 1, peerFetches = 0, peerBytes = 0), ctx.counters())
    }

    @Test
    fun peerMissVerifiesThenCaches() = runTest {
        val payload = bytes("from a peer")
        val cid = ContentId.of(payload)
        val peer = StubExchange(mapOf(cid.raw() to payload))
        val cas = CasStore.inMemory()
        val ctx = SharedContext(cas, peer, ContextReadPolicy(mode = SharedContextMode.PEER, maxBlockBytes = 64))

        assertFalse(cas.holds(cid))
        val first = ctx.block(cid)
        assertEquals(BlockSource.PEER, first.source)
        assertContentEquals(payload, first.bytes)
        assertTrue(cas.holds(cid), "a verified peer block is admitted to the local store")

        val second = ctx.block(cid)
        assertEquals(BlockSource.LOCAL, second.source)
        assertEquals(1, peer.fetched.size, "the second read is served locally")
        assertEquals(cid.raw(), peer.fetched.single().first, "asked for the raw-codec identity of the same digest")
        assertEquals(64, peer.fetched.single().second, "the transport is told the ceiling")
        assertEquals(ContextReadCounters(localReads = 1, peerFetches = 1, peerBytes = payload.size.toLong()), ctx.counters())
    }

    @Test
    fun digestMismatchIsRejectedAndNeverCached() = runTest {
        val wanted = ContentId.of(bytes("what the manifest names"))
        val served = bytes("what the peer sent instead")
        val cas = CasStore.inMemory()
        val ctx = SharedContext(cas, StubExchange(mapOf(wanted.raw() to served)), ContextReadPolicy(mode = SharedContextMode.PEER))

        val rejected = assertFailsWith<ContextBlockException> { ctx.block(wanted) }
        assertEquals(ContextRejection.DIGEST_MISMATCH, rejected.reason)
        assertFalse(cas.holds(wanted))
        assertFalse(cas.holds(ContentId.of(served)), "unrequested bytes do not enter the cache under their own identity either")
        assertEquals(ContextReadCounters(0, 0, 0), ctx.counters())
    }

    @Test
    fun oversizeIsRejectedBeforeCachingWhetherPeerOrLocal() = runTest {
        val tooBig = bytes("123456789")                       // 9 bytes against a ceiling of 8
        val bigCid = ContentId.of(tooBig)

        val peerCas = CasStore.inMemory()
        val viaPeer = SharedContext(
            peerCas, StubExchange(mapOf(bigCid.raw() to tooBig)),
            ContextReadPolicy(mode = SharedContextMode.PEER, maxBlockBytes = 8),
        )
        assertEquals(ContextRejection.OVERSIZE, assertFailsWith<ContextBlockException> { viaPeer.block(bigCid) }.reason)
        assertFalse(peerCas.holds(bigCid))

        val localCas = CasStore.inMemory()
        localCas.put(tooBig)
        val viaLocal = SharedContext(localCas, null, ContextReadPolicy(maxBlockBytes = 8))
        assertEquals(ContextRejection.OVERSIZE, assertFailsWith<ContextBlockException> { viaLocal.block(bigCid) }.reason)
    }

    @Test
    fun exchangeFailureIsTypedAndCancellationPropagates() = runTest {
        val cid = ContentId.of(bytes("unreachable"))
        val down = IllegalStateException("peer down")
        val failing = SharedContext(CasStore.inMemory(), StubExchange(failWith = down), ContextReadPolicy(mode = SharedContextMode.PEER))
        val typed = assertFailsWith<ContextBlockException> { failing.block(cid) }
        assertEquals(ContextRejection.EXCHANGE_FAILED, typed.reason)
        assertSame(down, typed.cause)

        val cancel = CancellationException("owner cancelled")
        val cancelling = SharedContext(CasStore.inMemory(), StubExchange(failWith = cancel), ContextReadPolicy(mode = SharedContextMode.PEER))
        val propagated = assertFailsWith<CancellationException> { cancelling.block(cid) }
        assertSame(cancel, propagated, "cancellation is not converted into a rejection")
    }

    @Test
    fun aCorruptLocalCopyIsRejectedNotServed() = runTest {
        val cas = CasStore.inMemory()
        val cid = cas.put(bytes("will be corrupted"))
        cas.corrupt(cid)
        val ctx = SharedContext(cas, ForbiddenExchange, ContextReadPolicy(mode = SharedContextMode.PEER))
        val rejected = assertFailsWith<ContextBlockException> { ctx.block(cid) }
        assertEquals(ContextRejection.DIGEST_MISMATCH, rejected.reason)
        assertNotNull(rejected.cause)
    }

    @Test
    fun admittedBytesAreReadableAndTheCeilingAppliesToWrites() = runTest {
        val cas = CasStore.inMemory()
        val ctx = SharedContext(cas, null, ContextReadPolicy(maxBlockBytes = 8))
        val cid = ctx.admit(bytes("12345678"))
        assertEquals(ContentId.of(bytes("12345678")), cid)
        assertContentEquals(bytes("12345678"), ctx.block(cid).bytes)

        val tooBig = bytes("123456789")
        val rejected = assertFailsWith<ContextBlockException> { ctx.admit(tooBig) }
        assertEquals(ContextRejection.OVERSIZE, rejected.reason)
        assertFalse(cas.holds(ContentId.of(tooBig)), "a block that could not be read back is never stored")
    }

    @Test
    fun peerModeWithoutAnExchangeIsAConstructionError() {
        assertFailsWith<IllegalArgumentException> {
            SharedContext(CasStore.inMemory(), null, ContextReadPolicy(mode = SharedContextMode.PEER))
        }
    }
}
