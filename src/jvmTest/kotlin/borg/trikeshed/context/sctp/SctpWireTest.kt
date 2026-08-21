package borg.trikeshed.sctp

import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SctpWireTest {

    @AfterTest
    fun resetWire() {
        LoopbackSctpWire.reset()
        SctpWire.register(LoopbackSctpWire)
    }

    @Test
    fun defaultIsLoopbackAndClaimsNoOffload() {
        val wire = SctpWire.default
        assertEquals("loopback", wire.backing)
        assertFalse(wire.kernelOffload)
    }

    @Test
    fun loopbackRoundTripsAPacketToABoundPort() = runBlocking {
        val wire = SctpWire.default
        wire.bind(9001)
        val packet = SctpInitChunk(
            initiateTag = 7u, aRwnd = 65535u, outboundStreams = 2u, inboundStreams = 2u, initialTsn = 1u,
        ).encode()
        wire.send("127.0.0.1:9001", packet)
        val got = wire.receive(9001)
        assertEquals("127.0.0.1:9001", got?.path)
        assertContentEquals(packet, got?.bytes)
    }

    @Test
    fun loopbackDropsPacketsForUnboundPathsAndReturnsNullAfterClose() = runBlocking {
        val wire = SctpWire.default
        wire.send("10.0.0.2:9999", byteArrayOf(1, 2, 3))   // unbound: dropped, no throw
        wire.bind(9002)
        wire.close()
        assertNull(wire.receive(9002))
    }

    @Test
    fun registerOverridesDefaultAndElementPicksItUp() {
        runBlocking {
            SctpWire.register(JvmKernelSctpWire)
            assertTrue(SctpWire.default.kernelOffload)
            val element = SctpElement()
            assertEquals("kernel-sctp (stub)", element.wire.backing)
            element.open()
            assertFailsWith<UnsupportedOperationException> { element.bind(9003) }
        }
    }

    @Test
    fun elementBindRegistersThePortOnTheWire() = runBlocking {
        val element = SctpElement()
        element.open()
        element.bind(9004)
        element.wire.send("localhost:9004", byteArrayOf(42))
        assertEquals(42, element.wire.receive(9004)?.bytes?.single())
    }
}
