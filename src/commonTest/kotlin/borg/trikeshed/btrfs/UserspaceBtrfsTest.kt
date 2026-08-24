package borg.trikeshed.btrfs

import borg.trikeshed.userspace.nio.file.spi.InMemoryFileOperations
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertNotNull

class UserspaceBtrfsTest {
    @Test
    fun testSubvolumeCrudAndSendFetch() {
        val fileOps = InMemoryFileOperations()
        val btrfs = UserspaceBtrfs(rootDir = "/mem/test-btrfs", fileOps = fileOps)

        // 1. createSubvolume("alpha") succeeds, duplicate fails, and has/list are observable.
        assertTrue(btrfs.createSubvolume("alpha"))
        assertFalse(btrfs.createSubvolume("alpha")) // Duplicate fails
        assertTrue(btrfs.hasSubvolume("alpha"))
        assertEquals(listOf("alpha"), btrfs.listSubvolumes())
        assertTrue(btrfs.createDirectory("alpha", "workspace/pkg"))
        assertTrue(btrfs.isDirectory("alpha", "workspace"))
        assertEquals(listOf("pkg"), btrfs.listDirectory("alpha", "workspace"))

        // 2. write alpha/a.txt and alpha/remove.txt; deleteFile alpha/remove.txt removes it; fetch returns null/rejects it while alpha/a.txt survives.
        btrfs.writeFile("alpha", "a.txt", "content A".encodeToByteArray())
        btrfs.writeFile("alpha", "workspace/pkg/module.py", "VALUE = 1".encodeToByteArray())
        assertTrue(btrfs.isFile("alpha", "workspace/pkg/module.py"))
        btrfs.writeFile("alpha", "remove.txt", "content B".encodeToByteArray())
        btrfs.deleteFile("alpha", "remove.txt")
        assertNull(btrfs.fetchFile("alpha", "remove.txt"))
        val aContent = btrfs.fetchFile("alpha", "a.txt")
        assertNotNull(aContent)
        assertTrue(aContent.contentEquals("content A".encodeToByteArray()))

        // 3. snapshot alpha -> alpha-v1 creates byte-defensive immutable point-in-time snapshot.
        assertTrue(btrfs.snapshot("alpha", "alpha-v1"))

        // Subsequent source overwrites and deletes never alter snapshot fetch.
        btrfs.writeFile("alpha", "a.txt", "content A2".encodeToByteArray())
        btrfs.deleteFile("alpha", "a.txt")
        val snapAContent = btrfs.fetchFile("alpha-v1", "a.txt")
        assertNotNull(snapAContent)
        assertTrue(snapAContent.contentEquals("content A".encodeToByteArray()))

        // Any snapshot write/delete fails.
        assertFalse(btrfs.writeFile("alpha-v1", "new.txt", "content".encodeToByteArray()))
        assertFalse(btrfs.deleteFile("alpha-v1", "a.txt"))

        // 4. send alpha-v1 makes deterministic opaque bytes. receive in a distinct volume creates the snapshot subtree;
        val sendData = btrfs.send("alpha-v1")
        assertNotNull(sendData)
        assertTrue(btrfs.receive("alpha-v1-received", sendData))

        val recAContent = btrfs.fetchFile("alpha-v1-received", "a.txt")
        assertNotNull(recAContent)
        assertTrue(recAContent.contentEquals("content A".encodeToByteArray()))
        assertNull(btrfs.fetchFile("alpha-v1-received", "remove.txt"))

        // 5. malformed/truncated/corrupt send data fails closed: no partial destination subvolume/files.
        val corruptData = sendData.copyOfRange(0, sendData.size / 2)
        assertFalse(btrfs.receive("corrupt-received", corruptData))
        assertFalse(btrfs.hasSubvolume("corrupt-received"))

        // 6. deleteSubvolume alpha erases live files/unavailability but preserves alpha-v1; delete alpha-v1 makes it unavailable. Assert fetch/has/list after every deletion, not just Booleans.
        assertTrue(btrfs.deleteSubvolume("alpha"))
        assertFalse(btrfs.hasSubvolume("alpha"))
        assertTrue(btrfs.hasSubvolume("alpha-v1"))
        assertNull(btrfs.fetchFile("alpha", "a.txt"))

        assertTrue(btrfs.deleteSubvolume("alpha-v1"))
        assertFalse(btrfs.hasSubvolume("alpha-v1"))
        assertNull(btrfs.fetchFile("alpha-v1", "a.txt"))

        assertEquals(listOf("alpha-v1-received"), btrfs.listSubvolumes())

        // 7. Reject absolute paths, empty names, ., .., traversal. Every stored file is scoped to rootDir/subvolumes/<name>.
        assertFalse(btrfs.createSubvolume(""))
        assertFalse(btrfs.createSubvolume("."))
        assertFalse(btrfs.createSubvolume(".."))
        assertFalse(btrfs.createSubvolume("/abs/path"))
        assertFalse(btrfs.createSubvolume("a/b"))
        assertFalse(btrfs.writeFile("alpha-v1-received", "/abs/file", ByteArray(0)))
        assertFalse(btrfs.writeFile("alpha-v1-received", "../file", ByteArray(0)))
    }
}
