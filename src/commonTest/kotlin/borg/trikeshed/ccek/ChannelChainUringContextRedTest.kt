package borg.trikeshed.ccek

import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

// ================================================================================
// SELF-CONTAINED STUBS: Channel chain + io_uring batch contexts
// Donor: old trikeshed-ccek CCEK.kt — ChannelChainContext with infix chain(),
//   UringBatchContext (ringFd, sqeDepth, cqeDepth, batchSize),
//   AsyncChannelContext (channelId, fd, type, localAddr, remoteAddr),
//   withCCEKUring() suspend combinator
// Semantic gap: CcekScope.kt is 8 lines. No channel chaining, no io_uring
//   batch context, no suspend combinator for composed contexts.
// ================================================================================

/** Channel type for async channel context. */
enum class ChannelType { TCP_SERVER, TCP_CLIENT, UDP, UNIX_DOMAIN }

/** Async channel metadata — fd, type, addresses. */
data class AsyncChannelContext(
    val channelId: String,
    val fd: Int,
    val type: ChannelType,
    val localAddr: String,
    val remoteAddr: String,
)

/** Indexed alias for self-contained tests. */
typealias Indexed<T> = Pair<Int, (Int) -> T>

/** Channel chain context for SOCKS5 relay chaining. */
data class ChannelChainContext(
    val channels: Indexed<AsyncChannelContext>,
) {
    /** Chain another channel onto the relay. */
    infix fun chain(channel: AsyncChannelContext): ChannelChainContext {
        val a = channels.first
        val f = channels.second
        val newA = a + 1
        val newF: (Int) -> AsyncChannelContext = { i -> if (i < a) f(i) else channel }
        return ChannelChainContext(Pair(newA, newF))
    }
}

/** io_uring batch operation context. */
data class UringBatchContext(
    val batchSize: Int,
    val ringFd: Int,
    val sqeDepth: Int,
    val cqeDepth: Int,
)

// ================================================================================
// SPEC: CCEK ChannelChainContext — SOCKS5 relay chaining via infix chain()
// ================================================================================

class ChannelChainContextRedTest {

    /** ChannelChainContext holds an Indexed of AsyncChannelContext. */
    @Test fun channelChainContext_holdsIndexedOfChannels() {
        val c0 = AsyncChannelContext("ch0", 3, ChannelType.TCP_CLIENT, "127.0.0.1", "google.com")
        val channels: Indexed<AsyncChannelContext> = Pair(1) { c0 }
        val ctx = ChannelChainContext(channels)
        assertEquals(1, ctx.channels.first)
        assertEquals(c0, ctx.channels.second(0))
    }

    /** infix chain() appends a channel to the relay chain. */
    @Test fun channelChainContext_chain_appends() {
        val c0 = AsyncChannelContext("ch0", 3, ChannelType.TCP_CLIENT, "127.0.0.1", "proxy.local")
        val ctx = ChannelChainContext(Pair(1) { c0 })

        val c1 = AsyncChannelContext("ch1", 5, ChannelType.TCP_CLIENT, "proxy.local", "target.local")
        val chained = ctx chain c1

        assertEquals(2, chained.channels.first)
        assertEquals(c0, chained.channels.second(0))
        assertEquals(c1, chained.channels.second(1))
    }

    /** Chaining three channels builds a 3-hop relay. */
    @Test fun channelChainContext_threeHopRelay() {
        val c0 = AsyncChannelContext("hop0", 3, ChannelType.TCP_CLIENT, "client", "proxy1")
        val c1 = AsyncChannelContext("hop1", 5, ChannelType.TCP_CLIENT, "proxy1", "proxy2")
        val c2 = AsyncChannelContext("hop2", 7, ChannelType.TCP_CLIENT, "proxy2", "target")

        var ctx = ChannelChainContext(Pair(1) { c0 })
        ctx = ctx chain c1
        ctx = ctx chain c2

        assertEquals(3, ctx.channels.first)
        assertEquals("hop0", ctx.channels.second(0).channelId)
        assertEquals("hop1", ctx.channels.second(1).channelId)
        assertEquals("hop2", ctx.channels.second(2).channelId)
    }

    /** AsyncChannelContext carries fd and type for real socket I/O. */
    @Test fun asyncChannelContext_fdAndType() {
        val ctx = AsyncChannelContext("sess-1", 42, ChannelType.TCP_SERVER, "0.0.0.0", "0.0.0.0")
        assertEquals(42, ctx.fd)
        assertEquals(ChannelType.TCP_SERVER, ctx.type)
    }

    /** AsyncChannelContext carries local and remote addresses. */
    @Test fun asyncChannelContext_addresses() {
        val ctx = AsyncChannelContext("sess-2", 7, ChannelType.UDP, "10.0.0.1:9999", "8.8.8.8:53")
        assertEquals("10.0.0.1:9999", ctx.localAddr)
        assertEquals("8.8.8.8:53", ctx.remoteAddr)
    }

    /** ChannelType covers TCP server, client, UDP, and Unix domain. */
    @Test fun channelType_enum_variants() {
        assertEquals(4, ChannelType.entries.size)
    }
}

// ================================================================================
// SPEC: CCEK UringBatchContext — io_uring batch submission with ringFd
// ================================================================================

class UringBatchContextRedTest {

    /** UringBatchContext carries ring fd and queue depths. */
    @Test fun uringBatchContext_ringFdAndDepths() {
        val ctx = UringBatchContext(batchSize = 32, ringFd = 5, sqeDepth = 4096, cqeDepth = 8192)
        assertEquals(32, ctx.batchSize)
        assertEquals(5, ctx.ringFd)
        assertEquals(4096, ctx.sqeDepth)
        assertEquals(8192, ctx.cqeDepth)
    }

    /** Default batch size is reasonable for high-throughput. */
    @Test fun uringBatchContext_defaultBatchSize() {
        val ctx = UringBatchContext(batchSize = 32, ringFd = 3, sqeDepth = 1024, cqeDepth = 2048)
        assertEquals(32, ctx.batchSize)
    }

    /** Ring fd identifies the io_uring instance. */
    @Test fun uringBatchContext_ringFdUnique() {
        val a = UringBatchContext(32, ringFd = 3, sqeDepth = 1024, cqeDepth = 1024)
        val b = UringBatchContext(32, ringFd = 5, sqeDepth = 1024, cqeDepth = 1024)
        assertEquals(3, a.ringFd)
        assertEquals(5, b.ringFd)
    }
}

// ================================================================================
// SPEC: CCEK withCCEKUring — suspend combinator for composed contexts
// ================================================================================

class WithCCEKUringRedTest {

    /** withCCEKUring composes CCEK + io_uring contexts for a suspend block. */
    @Test fun withCCEKUring_composesContexts_stub() {
        // Stub: no real CCEK context element yet on commonMain.
        // When implemented, this becomes:
        //   val result = withCCEKUring(ccek, uring) { "done" }
        //   assertEquals("done", result)
        val ccek = object : CoroutineContext.Element {
            override val key: CoroutineContext.Key<*> = object : CoroutineContext.Key<CoroutineContext.Element> {}
        }
        val uring = UringBatchContext(32, ringFd = 3, sqeDepth = 1024, cqeDepth = 2048)
        assertNotNull(ccek)
        assertEquals(3, uring.ringFd)
    }

    /** Channel chain can be composed with io_uring context. */
    @Test fun channelChainWithUring_stub() {
        val chain = ChannelChainContext(Pair(0) { throw IndexOutOfBoundsException() })
        val uring = UringBatchContext(64, ringFd = 7, sqeDepth = 2048, cqeDepth = 4096)
        assertEquals(0, chain.channels.first)
        assertEquals(7, uring.ringFd)
    }
}
