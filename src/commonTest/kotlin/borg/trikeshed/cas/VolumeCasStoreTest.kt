package borg.trikeshed.cas

import borg.trikeshed.job.ContentId
import borg.trikeshed.userspace.volume.Volume
import kotlinx.coroutines.runBlocking
import kotlin.random.Random
import kotlin.test.*

class FakeVolume(override val blockSize: Int = 4096) : Volume {
    override val capacity: Long = 100 * blockSize.toLong()
    private val blocks = mutableMapOf<Long, ByteArray>()

    override suspend fun read(lba: Long, count: Int): ByteArray {
        val res = ByteArray(count * blockSize)
        for (i in 0 until count) {
            val b = blocks[lba + i]
            if (b != null) {
                b.copyInto(res, i * blockSize)
            }
        }
        return res
    }

    override suspend fun write(lba: Long, data: ByteArray) {
        val blocksToWrite = (data.size + blockSize - 1) / blockSize
        for (i in 0 until blocksToWrite) {
            val end = minOf(data.size, (i + 1) * blockSize)
            val chunk = ByteArray(blockSize)
            data.copyInto(chunk, 0, i * blockSize, end)
            blocks[lba + i] = chunk
        }
    }

    override suspend fun sync() {}
}

class FakeCasReplicationHook : CasReplicationHook {
    var count = 0
    override suspend fun onPut(cid: ContentId, payload: ByteArray) {
        count++
    }
}

class VolumeCasStoreTest {

    @Test
    fun putThenGetRoundTrip() = runBlocking {
        val volume = FakeVolume()
        val store = VolumeCasStore(volume)
        val data = Random.nextBytes(1024)
        val cid = store.put(data)
        val retrieved = store.get(cid)
        assertNotNull(retrieved)
        assertTrue(data.contentEquals(retrieved))
    }

    @Test
    fun cidIsDeterministic() = runBlocking {
        val volume = FakeVolume()
        val store = VolumeCasStore(volume)
        val data = Random.nextBytes(1024)
        val cid1 = store.put(data)
        val cid2 = store.put(data)
        assertEquals(cid1, cid2)
    }

    @Test
    fun getMissingReturnsNull() = runBlocking {
        val volume = FakeVolume()
        val store = VolumeCasStore(volume)
        val cid = ContentId.of(ByteArray(10))
        assertNull(store.get(cid))
    }

    @Test
    fun manifestCidIsDeterministicAcrossRuns() = runBlocking {
        val volume = FakeVolume()
        val store = VolumeCasStore(volume)
        val cid1 = ContentId.of(byteArrayOf(1))
        val cid2 = ContentId.of(byteArrayOf(2))
        val cid3 = ContentId.of(byteArrayOf(3))

        val manifest1 = store.manifest(listOf(cid1, cid2, cid3))
        val manifest2 = store.manifest(listOf(cid3, cid1, cid2))

        assertEquals(manifest1.contentId(), manifest2.contentId())
    }

    @Test
    fun manifestCidChangesWhenCidsChange() = runBlocking {
        val volume = FakeVolume()
        val store = VolumeCasStore(volume)
        val cid1 = ContentId.of(byteArrayOf(1))
        val cid2 = ContentId.of(byteArrayOf(2))
        val cid3 = ContentId.of(byteArrayOf(3))
        val cid4 = ContentId.of(byteArrayOf(4))

        val manifest1 = store.manifest(listOf(cid1, cid2, cid3))
        val manifest2 = store.manifest(listOf(cid1, cid2, cid3, cid4))

        assertNotEquals(manifest1.contentId(), manifest2.contentId())
    }

    @Test
    fun replicationHookCalledOnPut() = runBlocking {
        val volume = FakeVolume()
        val hook = FakeCasReplicationHook()
        val store = VolumeCasStore(volume, hook)

        store.put(byteArrayOf(1))
        store.put(byteArrayOf(2))
        store.put(byteArrayOf(3))

        assertEquals(3, hook.count)
    }

    @Test
    fun deleteDecrementsRefcount() = runBlocking {
        val volume = FakeVolume()
        val store = VolumeCasStore(volume)
        val data = Random.nextBytes(10)
        val cid1 = store.put(data)
        val cid2 = store.put(data) // refcount=2
        assertEquals(cid1, cid2)

        assertNotNull(store.get(cid1))
        assertTrue(store.delete(cid1)) // deletes 1 ref, refcount=1
        assertNotNull(store.get(cid1)) // still there
        assertTrue(store.delete(cid1)) // deletes 1 ref, refcount=0
        assertNull(store.get(cid1))    // gone
    }

    @Test
    fun syncPersistsIndex() = runBlocking {
        val volume = FakeVolume()
        val store1 = VolumeCasStore(volume)
        val data = Random.nextBytes(100)
        val cid = store1.put(data)
        store1.put(byteArrayOf(1))
        store1.put(byteArrayOf(2))

        store1.sync()

        val store2 = VolumeCasStore(volume)
        val retrieved = store2.get(cid)
        assertNotNull(retrieved)
        assertTrue(data.contentEquals(retrieved))
    }

    @Test
    fun blockIndexEncodeRoundTrip() {
        val index = BlockIndex()
        val cid1 = ContentId.of(byteArrayOf(1))
        val cid2 = ContentId.of(byteArrayOf(2))

        index.put(cid1, LbaEntry(10, 100, 1))
        index.put(cid2, LbaEntry(20, 200, 2))

        val encoded = index.encode()
        val decoded = BlockIndex.decode(encoded)

        assertEquals(10, decoded.get(cid1)?.lba)
        assertEquals(100, decoded.get(cid1)?.sizeBytes)
        assertEquals(1, decoded.get(cid1)?.refCount)

        assertEquals(20, decoded.get(cid2)?.lba)
        assertEquals(200, decoded.get(cid2)?.sizeBytes)
        assertEquals(2, decoded.get(cid2)?.refCount)
    }

    @Test
    fun rejectsBlockSizeMismatch() {
        val volume = FakeVolume(4096)
        assertFailsWith<IllegalArgumentException> {
            VolumeCasStore(volume, blockSize = 8192)
        }
    }
}
