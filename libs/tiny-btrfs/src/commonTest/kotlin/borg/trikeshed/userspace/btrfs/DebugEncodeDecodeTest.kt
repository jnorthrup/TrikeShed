package borg.trikeshed.userspace.btrfs

import borg.trikeshed.collections.s_
import kotlinx.coroutines.test.runTest
import kotlin.test.Test


class DebugEncodeDecodeTest {
    @Test
    fun debugEncodeDecodeLeaf() = runTest {
        val buf = UserspaceBtrfsBuffer(chunkSize = 4096)
        buf.open()

        val key = BtrfsKey(1uL, 1u, 0uL)
        val data = byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte())
        val item: BtrfsItem = BtrfsItem(key, data)
        val leaf = BtrfsLeaf(s_[item])

        val nodeId = buf.allocateNode()

        // Check what the raw bytes look like before writeLeaf
        val rawBuf = ByteArray(4096)
        println("Before encodeLeaf: magic at 0 = ${rawBuf[0]},${rawBuf[1]},${rawBuf[2]},${rawBuf[3]}")

        buf.writeLeaf(nodeId, leaf)
        val bytes = buf.readNode(nodeId)
        println("After readNode: bytes size = ${bytes?.size}")
        if (bytes != null) {
            println("Magic at 0 = ${bytes[0]},${bytes[1]},${bytes[2]},${bytes[3]}")
            val magic = (bytes[0].toUInt() and 0xFFu) or
                        ((bytes[1].toUInt() and 0xFFu) shl 8) or
                        ((bytes[2].toUInt() and 0xFFu) shl 16) or
                        ((bytes[3].toUInt() and 0xFFu) shl 24)
            println("Magic value = 0x${magic.toString(16)}")
        }

        buf.close()
    }
}
