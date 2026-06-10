package borg.trikeshed.ipfs

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Integration test for DhtService with LoopbackDhtTransport.
 * Verifies the basic DHT operations work.
 */
class DhtServiceIntegrationTest {

    @Test
    fun `DhtService announce find via loopback transport`() = runBlocking {
        val transport = LoopbackDhtTransport()
        val svcA = DhtService(transport)
        val svcB = DhtService(transport)

        val cid = CID(byteArrayOf(1, 2, 3, 4))
        svcA.announceProvider(cid, "node-A:4001")

        // Wait for async transport announce to complete
        delay(50)

        val providers = svcB.findProviders(cid)
        assertTrue(providers.contains("node-A:4001"))
    }

    @Test
    fun `LoopbackDhtTransport close does not throw`() = runBlocking {
        val transport = LoopbackDhtTransport()
        transport.close()
    }

    @Test
    fun `IpfsElement putBlock get round trip`() = runBlocking {
        val transport = LoopbackDhtTransport()
        val dht = DhtService(transport)
        val diskStore = DiskBlockStore(java.io.File("/tmp/trikeshed-ipfs-test"))
        val ipfsElement = IpfsElement(diskStore, dht)

        val testData = "hello ipfs".toByteArray()
        val cid = CID(testData)

        ipfsElement.putBlock(cid, testData)
        val retrieved = ipfsElement.get(cid)

        assertNotNull(retrieved)
        assertEquals(testData.contentToString(), retrieved?.contentToString())
    }
}