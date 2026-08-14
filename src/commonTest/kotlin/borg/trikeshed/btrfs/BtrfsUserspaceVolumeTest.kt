package borg.trikeshed.btrfs

import borg.trikeshed.userspace.nio.file.spi.InMemoryFileOperations
import borg.trikeshed.lib.j
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BtrfsUserspaceVolumeTest {

    @Test
    fun testVirtualBtrfsContract() = runTest {
        val rootDir = "/test_btrfs"
        val memOps = InMemoryFileOperations()
        val volume = BtrfsUserspaceVolume(rootDir, memOps)

        // 1. createSubvolume
        assertTrue(volume.createSubvolume("alpha"), "Initial creation should succeed")
        assertFalse(volume.createSubvolume("alpha"), "Duplicate creation should be rejected")
        assertTrue(volume.hasSubvolume("alpha"), "Subvolume state should be observable")
        assertTrue("alpha" in volume.listSubvolumes(), "Subvolume should be in list")

        // 2. Write files
        volume.write("alpha/a.txt", "content_a".encodeToByteArray())
        volume.write("alpha/remove.txt", "content_remove".encodeToByteArray())
        assertTrue(volume.hasFile("alpha/a.txt"), "a.txt should exist")
        assertTrue(volume.hasFile("alpha/remove.txt"), "remove.txt should exist")
        
        // Delete a file
        assertTrue(volume.deleteFile("alpha/remove.txt"), "deleteFile should succeed for remove.txt")
        assertNull(volume.fetch("alpha/remove.txt"), "Deleted file should return null")
        assertNotNull(volume.fetch("alpha/a.txt"), "a.txt should remain")

        // 3. Snapshot
        assertTrue(volume.snapshot("alpha", "alpha-v1"), "Snapshot creation should succeed")
        
        // Modify source after snapshot
        volume.write("alpha/a.txt", "new_content".encodeToByteArray())
        volume.write("alpha/new.txt", "new_file".encodeToByteArray())
        assertTrue(volume.deleteFile("alpha/a.txt"), "deleteFile should succeed for a.txt in source")
        
        // Snapshot fetch results should be unaltered
        val snapshotA = volume.fetch("alpha-v1/a.txt")
        assertNotNull(snapshotA, "a.txt should exist in snapshot")
        assertEquals("content_a", snapshotA.decodeToString(), "Snapshot content should be unchanged")
        assertNull(volume.fetch("alpha-v1/new.txt"), "new.txt should not exist in snapshot")
        
        // Writes/deletes through snapshot path are rejected
        assertFalse(volume.write("alpha-v1/b.txt", "bad".encodeToByteArray()), "Snapshot write should be rejected")
        assertFalse(volume.deleteFile("alpha-v1/a.txt"), "Snapshot delete should be rejected")

        // 4. Send/fetch replication
        val stream = volume.send("alpha-v1")
        assertNotNull(stream, "Stream should be produced")
        
        val recvOps = InMemoryFileOperations()
        val recvVolume = BtrfsUserspaceVolume("/recv_btrfs", recvOps)
        
        assertTrue(recvVolume.receive("alpha-replica", stream), "Receive should succeed")
        assertTrue(recvVolume.hasSubvolume("alpha-replica"), "Replica subvolume should exist")
        
        // Malformed stream: Bit flip (Checksum failure)
        val badChecksumStream = stream.a j { i: Int -> if (i == 17) (stream.b(i).toInt() xor 1).toByte() else stream.b(i) }
        assertFalse(recvVolume.receive("bad-checksum", badChecksumStream), "Receive should reject corrupted stream")
        assertFalse(recvVolume.hasSubvolume("bad-checksum"), "Partial subvolume should not be created for corrupt stream")
        
        // Malformed stream: Truncated
        val truncatedStream = (stream.a - 5) j { i: Int -> stream.b(i) }
        assertFalse(recvVolume.receive("truncated", truncatedStream), "Receive should reject truncated stream")
        
        // Malformed stream: Trailing bytes
        val trailingStream = (stream.a + 5) j { i: Int -> if (i < stream.a) stream.b(i) else 0 }
        assertFalse(recvVolume.receive("trailing", trailingStream), "Receive should reject stream with trailing bytes")
        
        // Test paths with .. and absolute paths logic using manual byte manipulation over the existing stream is complex,
        // we already test `sanitize` indirectly but we can also test file operations directly
        assertFalse(volume.write("alpha/../b.txt", "bad".encodeToByteArray()), "Traversal should be rejected")
        assertFalse(volume.write("alpha//b.txt", "bad".encodeToByteArray()), "Empty segment should be rejected")
        assertFalse(volume.createSubvolume("alpha/beta"), "Slash in subvolume name should be rejected")
        
        val replicaA = recvVolume.fetch("alpha-replica/a.txt")
        assertNotNull(replicaA, "a.txt should exist in replica")
        assertEquals("content_a", replicaA.decodeToString(), "Replica content should match")
        assertNull(recvVolume.fetch("alpha-replica/remove.txt"), "Deleted file should be absent in replica")

        // 5. Delete subvolume
        assertTrue(volume.deleteSubvolume("alpha"), "Delete live subvolume should succeed")
        assertFalse(volume.hasSubvolume("alpha"), "Live subvolume should be unavailable")
        assertFalse("alpha" in volume.listSubvolumes(), "Deleted subvolume should not be in list")
        assertNull(volume.fetch("alpha/a.txt"), "Live subvolume files should be unavailable")
        assertTrue(volume.hasSubvolume("alpha-v1"), "Snapshot should not be destroyed by source deletion")
        assertTrue("alpha-v1" in volume.listSubvolumes(), "Snapshot should still be in list")
        assertNotNull(volume.fetch("alpha-v1/a.txt"), "Snapshot files should remain")
        
        assertTrue(volume.deleteSubvolume("alpha-v1"), "Delete snapshot should succeed")
        assertFalse(volume.hasSubvolume("alpha-v1"), "Snapshot should be unavailable")
        assertFalse("alpha-v1" in volume.listSubvolumes(), "Deleted snapshot should not be in list")
        assertNull(volume.fetch("alpha-v1/a.txt"), "Snapshot files should be unavailable")
    }
}
