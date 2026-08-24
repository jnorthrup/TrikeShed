package borg.trikeshed.htx.client.ipfs

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CidBlockStoreTest {
    @Test
    fun memoryBlockStoreRoundTripsByCid() {
        val store = MemoryBlockStore()
        val data = "hello blocks".encodeToByteArray()
        val cid = CID.sha256(data)
        kotlinx.coroutines.runBlocking {
            store.put(cid, data)
            assertEquals(data.toList(), store.get(cid)?.toList())
            assertTrue(store.has(cid))
            assertNull(store.get(CID.sha256("other".encodeToByteArray())))
        }
    }

    @Test
    fun cidIsSha256OfBytes() {
        val data = byteArrayOf(1, 2, 3)
        val cid = CID.sha256(data)
        // authoritative SHA-256 of bytes [1,2,3] — computed, not hardcoded, so the
        // test proves CID uses SHA-256 (stability of the digest) without a magic constant.
        val expected = java.security.MessageDigest.getInstance("SHA-256").digest(data)
            .joinToString("") { "%02x".format(it) }
        assertEquals(expected, cid.hex())
    }

    @Test
    fun btrfsBlockStorePersistsAndVerifies() {
        val root = java.nio.file.Files.createTempDirectory("cid-block-store-test").toFile()
        val store = BtrfsBlockStore(root)
        val data = "persist me through btrfs CoW".encodeToByteArray()
        val cid = CID.sha256(data)
        kotlinx.coroutines.runBlocking {
            store.put(cid, data)
            // new instance over the same root proves persistence (content-addressed path)
            val reopened = BtrfsBlockStore(root)
            assertEquals(data.toList(), reopened.get(cid)?.toList())
            assertTrue(reopened.has(cid))
            assertNull(reopened.get(CID.sha256("absent".encodeToByteArray())))
        }
    }
}
