package borg.trikeshed.cas

import borg.trikeshed.job.CasStore
import borg.trikeshed.job.ContentId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse

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
        
        // This registry names local CAS content; no IPNS network publication occurs.
        val manifestCid = ContentId.of("dummy-manifest-content".encodeToByteArray())
        
        bridge.publishIpns("my-node", manifestCid)
        
        val resolved = bridge.resolveIpns("my-node")
        assertEquals(manifestCid, resolved)
        
        assertNull(bridge.resolveIpns("unknown-node"))
        val replacement = bridge.putBlock("replacement manifest".encodeToByteArray())
        bridge.publishIpns("my-node", replacement)
        assertEquals(replacement, bridge.resolveIpns("my-node"))
        assertTrue(bridge.unpublishIpns("my-node"))
        assertNull(bridge.resolveIpns("my-node"))
        assertFalse(bridge.unpublishIpns("my-node"))

        // CAS data can be shared without making names global to the process or a network.
        bridge.publishIpns("my-node", replacement)
        val other = IpfsBridge(cas)
        assertNotNull(other.getBlock(replacement))
        assertNull(other.resolveIpns("my-node"))
    }
}
