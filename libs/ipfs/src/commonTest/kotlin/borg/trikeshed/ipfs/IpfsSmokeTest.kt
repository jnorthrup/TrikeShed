package borg.trikeshed.ipfs

import kotlin.test.*

class IpfsSmokeTest {
    @Test
    fun smoke() {
        // Verify IPFS production code compiles and basic types are accessible
        val cid = CID(ByteArray(32) { it.toByte() })
        assertTrue(cid.bytes.size == 32, "CID should hold 32 bytes")
        
        // Verify BlockStore interface exists
        val blockStore = object : BlockStore {
            override suspend fun put(cid: CID, data: ByteArray) {}
            override suspend fun get(cid: CID): ByteArray? = null
        }
        assertNotNull(blockStore, "BlockStore can be instantiated")
        
        // Verify NameResolver interface exists
        val nameResolver = object : NameResolver {
            override suspend fun resolve(name: String): CID? = null
        }
        assertNotNull(nameResolver, "NameResolver can be instantiated")
    }
}
