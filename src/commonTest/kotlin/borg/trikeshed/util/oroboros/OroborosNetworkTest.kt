package borg.trikeshed.util.oroboros

import borg.trikeshed.job.ContentId
import borg.trikeshed.job.Sha256Pure
import borg.trikeshed.dht.routing.RoutingTable
import borg.trikeshed.dht.id.NUID
import borg.trikeshed.dht.include.Address
import borg.trikeshed.dht.net.NetMask
import borg.trikeshed.lib.Join
import borg.trikeshed.context.StreamTransport
import borg.trikeshed.context.StreamHandle
import borg.trikeshed.htx.HtxElement
import borg.trikeshed.htx.HtxRequest
import borg.trikeshed.htx.HtxExchangeResult
import borg.trikeshed.htx.HtxExchangeState
import borg.trikeshed.htx.HtxExchangeLifecycle
import borg.trikeshed.htx.HtxFlowStage
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DummyTransport : StreamTransport {
    val sendCh = Channel<ByteArray>(Channel.BUFFERED)
    val recvCh = Channel<ByteArray>(Channel.BUFFERED)

    override suspend fun openStream(): StreamHandle {
        return StreamHandle(1, sendCh, recvCh)
    }

    override val activeStreams: Int = 1
    override val key: kotlin.coroutines.CoroutineContext.Key<*> get() = object : kotlin.coroutines.CoroutineContext.Key<StreamTransport> {}
}

class DummyHtxElement(val resultBytes: ByteArray, val fail: Boolean = false) : HtxElement(routeService = borg.trikeshed.htx.HtxRouteService()) {
    override suspend fun exchange(request: HtxRequest): HtxExchangeResult {
        val state = if (fail) {
            HtxExchangeState(lifecycle = HtxExchangeLifecycle.FAILED, stage = HtxFlowStage.FAILURE)
        } else {
            HtxExchangeState(lifecycle = HtxExchangeLifecycle.RESPONDED, stage = HtxFlowStage.RESPONSE)
        }
        return borg.trikeshed.htx.HtxExchangeResult(state)
    }
}

class DummyRoutingTable : RoutingTable<Int, NetMask<Int>>(
    agentNUID = NUID(1, object: NetMask<Int>{
        override val bits = 32
        override fun distance(a: Int, b: Int) = 1
    }),
    optimal = false
) {
    override fun getClosest(target: Int, k: Int): List<Join<NUID<Int>, Address>> {
        return listOf(Join(NUID(target, agentNUID.netmask), Address("dummy-address")))
    }
}

class OroborosNetworkTest {

    private val HEX_CHARS = "0123456789abcdef".toCharArray()

    @Test
    fun testDhtProviderLookup() {
        val routingTable = DummyRoutingTable()
        val gateway = DhtContentGateway(routingTable) { 42 }

        val contentId = ContentId("sha256-abcdefabcdefabcdefabcdefabcdefabcdefabcdefabcdefabcdefabcdefabcd")
        val closest = gateway.lookup(contentId)

        assertEquals(1, closest.size)
        assertEquals(42, closest[0].a.id)
        assertEquals("dummy-address", closest[0].b.value)
    }

    @Test
    fun testIpfsDigestMismatchRejection() = runTest {
        val validContent = "hello world".encodeToByteArray()
        val hash = Sha256Pure.hash(validContent)

        val hexBuilder = StringBuilder(hash.size * 2)
        for (b in hash) {
            val v = b.toInt() and 0xFF
            hexBuilder.append(HEX_CHARS[v ushr 4])
            hexBuilder.append(HEX_CHARS[v and 0x0F])
        }
        val hexHash = hexBuilder.toString()
        val contentId = ContentId("sha256-$hexHash")

        val htxElement = DummyHtxElement(validContent)
        val gateway = object : IpfsContentGateway(htxElement) {
            override fun extractPayload(response: HtxExchangeResult): ByteArray = "wrong content".encodeToByteArray()
        }

        val ex = assertFailsWith<IllegalStateException> {
            gateway.fetch(contentId)
        }
        assertTrue(ex.message!!.contains("Digest mismatch rejection"))
    }

    @Test
    fun testIpfsHtxPathSuccess() = runTest {
        val validContent = "valid ipfs payload".encodeToByteArray()
        val hash = Sha256Pure.hash(validContent)

        val hexBuilder = StringBuilder(hash.size * 2)
        for (b in hash) {
            val v = b.toInt() and 0xFF
            hexBuilder.append(HEX_CHARS[v ushr 4])
            hexBuilder.append(HEX_CHARS[v and 0x0F])
        }
        val hexHash = hexBuilder.toString()
        val contentId = ContentId("sha256-$hexHash")

        val htxElement = DummyHtxElement(validContent)
        val gateway = object : IpfsContentGateway(htxElement) {
            override fun extractPayload(response: HtxExchangeResult): ByteArray = validContent
        }

        val fetched = gateway.fetch(contentId)
        assertTrue(fetched.contentEquals(validContent))
    }

    @Test
    fun testStreamTransportFraming() = runTest {
        val transport = DummyTransport()
        val gateway = StreamContentGateway(transport)

        val contentId = ContentId("sha256-abcdefabcdefabcdefabcdefabcdefabcdefabcdefabcdefabcdefabcdefabcd")

        val responseBytes = ByteArray(5)
        responseBytes[0] = 0x02 // CONTENT
        responseBytes[1] = 't'.code.toByte()
        responseBytes[2] = 'e'.code.toByte()
        responseBytes[3] = 's'.code.toByte()
        responseBytes[4] = 't'.code.toByte()
        transport.recvCh.send(responseBytes)
        transport.recvCh.close()

        val bytes = gateway.fetch(contentId)
        assertEquals("test", bytes.decodeToString())
    }

    @Test
    fun testContentGatewayFanoutFailures() = runTest {
        val gw1 = object : ContentGateway {
            override suspend fun fetch(contentId: ContentId): ByteArray {
                throw Exception("GW1 Failed")
            }
        }
        val gw2 = object : ContentGateway {
            override suspend fun fetch(contentId: ContentId): ByteArray {
                throw Exception("GW2 Failed")
            }
        }
        val fanout = ContentGatewayFanout(mapOf("gw1" to gw1, "gw2" to gw2))

        val failure = assertFailsWith<FanoutFailure> {
            fanout.fetch(ContentId("sha256-abcdefabcdefabcdefabcdefabcdefabcdefabcdefabcdefabcdefabcdefabcd"))
        }
        assertTrue(failure.failures.containsKey("gw1"))
        assertTrue(failure.failures.containsKey("gw2"))
    }
}
