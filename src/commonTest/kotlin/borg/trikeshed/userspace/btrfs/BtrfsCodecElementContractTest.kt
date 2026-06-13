package borg.trikeshed.userspace.btrfs

import borg.trikeshed.userspace.LiburingElement
import borg.trikeshed.userspace.nio.spi.NioSupervisor
import borg.trikeshed.userspace.context.AsyncContextKey
import borg.trikeshed.userspace.context.AsyncContextElement
import borg.trikeshed.userspace.context.ElementLifecycleState
import borg.trikeshed.userspace.FanoutDispatcherElement
import borg.trikeshed.userspace.FunctionalUringFacade
import borg.trikeshed.userspace.SelectionResult
import borg.trikeshed.userspace.UringOp.Companion.UringSubmission
import borg.trikeshed.userspace.UserspaceChannelBackend
import borg.trikeshed.userspace.nio.ByteBuffer
import borg.trikeshed.userspace.nio.platform.spi.PlatformCodec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.test.assertNotEquals

/**
 * TDD RED: BtrfsCodecElement contract — LE codec accessible via CCEK coroutine context,
 * with btrfs operations flowing through reactor/uring.
 *
 * Contract:
 * 1. BtrfsCodecElement installs in coroutine context via BtrfsCodecKey
 * 2. Codec provides little-endian primitives (on-disk btrfs format is LE)
 * 3. NioSupervisor can register BtrfsCodecElement as service
 * 4. btrfs read/write operations resolve codec from context and encode/decode LE
 * 5. FunctionalUringFacade delegates btrfs I/O through same reactor path
 */
class BtrfsCodecElementContractTest {

    // ── Key & Element Existence ──────────────────────────────────────────────

    @Test
    fun btrfsCodecKey_isSingleton_sameReference() {
        val k1: CoroutineContext.Key<*> = AsyncContextKey.BtrfsCodecKey
        val k2: CoroutineContext.Key<*> = AsyncContextKey.BtrfsCodecKey
        assertSame(k1, k2, "BtrfsCodecKey must be the same object reference (singleton)")
    }

    @Test
    fun btrfsCodecKey_isDistinctFromOtherKeys() {
        assertNotEquals<Any>(AsyncContextKey.BtrfsCodecKey, AsyncContextKey.NioUserspaceKey)
        assertNotEquals<Any>(AsyncContextKey.BtrfsCodecKey, AsyncContextKey.LiburingKey)
        assertNotEquals<Any>(AsyncContextKey.BtrfsCodecKey, AsyncContextKey.FanoutDispatcherKey)
    }

    // ── Codec Provided by Element ────────────────────────────────────────────

    @Test
    fun btrfsCodecElement_exposesLittleEndianCodec() {
        val elem = makeBtrfsCodecElement()
        val codec = elem.codec

        // LE write/read roundtrip
        val bytes = codec.writeLong(0x0102030405060708L)
        assertEquals(byteArrayOf(0x08, 0x07, 0x06, 0x05, 0x04, 0x03, 0x02, 0x01), bytes)

        val read = codec.readLong(bytes)
        assertEquals(0x0102030405060708L, read)
    }

    @Test
    fun btrfsCodecElement_codecIsNotPlatformCodec() {
        val elem = makeBtrfsCodecElement()
        // PlatformCodec.wireCodec is BIG_ENDIAN for network
        // BtrfsCodec must be LITTLE_ENDIAN for on-disk format
        val platformWire = PlatformCodec.wireCodec.writeLong(0x0102030405060708L)
        val btrfsLE = elem.codec.writeLong(0x0102030405060708L)
        assertNotEquals(platformWire, btrfsLE, "Btrfs LE codec must differ from network BE wire codec")
    }

    // ── Context Resolution ──────────────────────────────────────────────────

    @Test
    fun coroutineContext_resolvesBtrfsCodecElement() = runBlocking {
        val elem = makeBtrfsCodecElement()
        val scope = CoroutineScope(elem + SupervisorJob())
        scope.coroutineContext[AsyncContextKey.BtrfsCodecKey]?.let { resolved ->
            assertSame(elem, resolved, "Context must resolve BtrfsCodecElement by key")
        } ?: error("BtrfsCodecKey not found in coroutine context")
    }

    @Test
    fun nioSupervisor_canRegisterBtrfsCodecElement() = runBlocking {
        val supervisor = NioSupervisor()
        val btrfsElem = makeBtrfsCodecElement()
        
        supervisor.register(btrfsElem)
        supervisor.open()
        
        val resolved = supervisor.service<BtrfsCodecElement>()
        assertNotNull(resolved, "NioSupervisor.service<BtrfsCodecElement>() must return registered element")
        assertSame(btrfsElem, resolved)
        
        supervisor.close()
    }

    // ── Btrfs I/O Through Reactor/Uring ─────────────────────────────────────

    @Test
    fun btrfsRead_resolvesCodecFromContext_encodesLE() = runBlocking {
        val btrfsElem = makeBtrfsCodecElement()
        val scope = CoroutineScope(btrfsElem + SupervisorJob())
        
        val result = scope.coroutineContext[AsyncContextKey.BtrfsCodecKey]?.codec?.let { codec ->
            val buf = ByteBuffer.wrap(ByteArray(8))
            codec.writeLong(buf, 0, 0xDEADBEEFCAFEBABEL)
            codec.readLong(buf, 0)
        }
        
        assertEquals(0xDEADBEEFCAFEBABEL, result!!)
    }

    @Test
    fun functionalUringFacade_btrfsOperationsUseBtrfsCodec() {
        val backend = object : UserspaceChannelBackend {
            val ops = mutableListOf<Pair<String, ByteArray>>()
            override fun read(file: FileImpl, buffer: ByteBuffer, offset: Long): Int {
                ops += "read" to buffer.array().copyOf()
                return buffer.capacity()
            }
            override fun write(file: FileImpl, buffer: ByteBuffer, offset: Long): Int {
                ops += "write" to buffer.array().copyOf()
                return buffer.capacity()
            }
            override fun submitBatch(submissions: List<UringSubmission>): List<SelectionResult> = emptyList()
        }
        
        val facade = FunctionalUringFacade(entries = 8, backend = backend)
        val file = FileImpl(42)
        
        // Simulate btrfs read: user provides LE buffer, facade should preserve it
        val leBuffer = ByteBuffer.wrap(byteArrayOf(0x08, 0x07, 0x06, 0x05, 0x04, 0x03, 0x02, 0x01))
        facade.read(file, leBuffer, offset = 4096L, userData = 0xBEEF)
        
        assertEquals(1, facade.submit())
        assertEquals(1, backend.ops.size)
        assertEquals("read", backend.ops[0].first)
        // Buffer content should be preserved (LE bytes pass through unchanged)
        assertEquals(byteArrayOf(0x08, 0x07, 0x06, 0x05, 0x04, 0x03, 0x02, 0x01), backend.ops[0].second)
    }

    // ── Reactor Integration ─────────────────────────────────────────────────

    @Test
    fun btrfsCodecElement_installedAlongsideLiburingAndFanout() = runBlocking {
        val liburing = LiburingElement(SupervisorJob())
        val fanout = object : FanoutDispatcherElement(SupervisorJob()) {
            override val lifecycleState = ElementLifecycleState.CREATED
            override val fanoutSubscribers = emptyList<AsyncContextElement>()
            override suspend fun open() {}
            override suspend fun drain() {}
            override suspend fun close() {}
        }
        val btrfs = makeBtrfsCodecElement()
        
        val scope = CoroutineScope(liburing + fanout + btrfs + SupervisorJob())
        val ctx = scope.coroutineContext
        
        assertNotNull(ctx[AsyncContextKey.LiburingKey], "LiburingElement must be in context")
        assertNotNull(ctx[AsyncContextKey.FanoutDispatcherKey], "FanoutDispatcherElement must be in context")
        assertNotNull(ctx[AsyncContextKey.BtrfsCodecKey], "BtrfsCodecElement must be in context")
    }

    // ── Test Helpers ────────────────────────────────────────────────────────

    private fun makeBtrfsCodecElement(): BtrfsCodecElement =
        object : BtrfsCodecElement(SupervisorJob()) {
            override val lifecycleState = ElementLifecycleState.CREATED
            override val fanoutSubscribers = emptyList<AsyncContextElement>()
            override suspend fun open() {}
            override suspend fun drain() {}
            override suspend fun close() {}
        }

    private class FileImpl(override val id: Int) : borg.trikeshed.userspace.FileImpl(id)
}

// Missing type stubs expected to be implemented in GREEN phase:
// - AsyncContextKey.BtrfsCodecKey
// - BtrfsCodecElement
// - BtrfsCodec interface
// - FunctionalUringFacade.read/write with codec-aware path
// - UserspaceChannelBackend.submitBatch for btrfs batch ops