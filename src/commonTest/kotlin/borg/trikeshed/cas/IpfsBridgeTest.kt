package borg.trikeshed.cas

import borg.trikeshed.job.CasStore
import borg.trikeshed.job.ContentId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class IpfsBridgeTest {

    @Test
    fun testIpfsBlocks() {
        val cas = CasStore.inMemory()
        val bridge = IpfsBridge(cas)

        val data = "hello ipfs".encodeToByteArray()
        val cid = bridge.putBlock(data)

        val retrieved = bridge.getBlock(cid)
        assertNotNull(retrieved)
        assertEquals("hello ipfs", retrieved.decodeToString())

        // Verify it's actually in CAS
        val casRetrieved = cas.get(cid)
        assertNotNull(casRetrieved)
        assertEquals("hello ipfs", casRetrieved.decodeToString())
    }

    @Test
    fun testIpnsResolution() {
        val cas = CasStore.inMemory()
        val bridge = IpfsBridge(cas)

        // In this bridge, IPNS names map to CasManifest CIDs
        val manifestCid = ContentId.of("dummy-manifest-content".encodeToByteArray())

        bridge.publishIpns("my-node", manifestCid)

        val resolved = bridge.resolveIpns("my-node")
        assertEquals(manifestCid, resolved)

        assertNull(bridge.resolveIpns("unknown-node"))
    }
}
