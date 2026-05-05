package borg.trikeshed.userspace.btrfs

import borg.trikeshed.lib.*
import borg.trikeshed.tinybtrfs.NodeId

const val BTRFS_NODE_SIZE = 4096

fun crc32c(data: ByteArray, offset: Int = 0, length: Int = data.size - offset): UInt {
    if (length <= 0) return 0u
    val table = crc32cTable
    var crc = 0xFFFFFFFFu
    val end = offset + length
    for (i in offset until end) {
        val idx = ((crc xor (data[i].toUInt() and 0xFFu)) and 0xFFu).toInt()
        crc = (crc.toULong() shr 8).toUInt() xor table[idx]
    }
    return crc xor 0xFFFFFFFFu
}

private val crc32cTable: UIntArray by lazy {
    UIntArray(256) { i ->
        var c = i.toUInt()
        repeat(8) { c = if ((c and 1u) != 0u) (0x82F63B78u xor (c shr 1)) else (c shr 1) }
        c
    }
}
typealias  BtrfsKeyAtom = Join<ULong,Join<UInt,ULong>>
typealias BtrfsItem = Join<BtrfsKeyAtom, ByteArray>
typealias BtrfsChildPointer = Join<BtrfsKeyAtom, NodeId>
typealias BtrfsLeaf = Series<BtrfsItem>
typealias BtrfsInternal = Series<BtrfsChildPointer>

/** btrfs key projections — direct on the Join algebra */
inline val BtrfsKeyAtom.objectId: ULong get() = a
inline val BtrfsKeyAtom.type: UInt get() = b.a
inline val BtrfsKeyAtom.offset: ULong get() = b.b

/** serialize to raw bytes — fixed 24-byte btrfs key */
val BtrfsKeyAtom.bytes: ByteArray
    get() {
        val out = ByteArray(24) // 8+4+8 = 24 bytes
        var i = 0
        var oid = objectId
        repeat(8) { out[i++] = (oid and 0xFFu).toByte(); oid = oid shr 8 }
        var t = type
        repeat(4) { out[i++] = (t and 0xFFu).toByte(); t = t shr 8 }
        var off = offset
        repeat(8) { out[i++] = (off and 0xFFu).toByte(); off = off shr 8 }
        return out
    }

/** parse btrfs key from raw bytes */
fun btrfsKeyFromBytes(arr: ByteArray): BtrfsKeyAtom {
    var oid = 0UL
    for (i in 0..7) {
        oid = oid or ((arr[i].toULong() and 0xFFUL) shl (i * 8))
    }
    var t = 0u
    for (i in 8..11) {
        t = t or ((arr[i].toUInt() and 0xFFu) shl ((i - 8) * 8))
    }
    var off = 0UL
    for (i in 12..19) {
        off = off or ((arr[i].toULong() and 0xFFUL) shl ((i - 12) * 8))
    }
    return oid j (t j off)
}

fun encodeLeaf(leaf: BtrfsLeaf, buf: ByteArray, generation: ULong = 0UL) {
    val magic = 0x464F5254
    buf[0] = (magic and 0xFF).toByte()
    buf[1] = ((magic shr 8) and 0xFF).toByte()
    buf[2] = ((magic shr 16) and 0xFF).toByte()
    buf[3] = ((magic shr 24) and 0xFF).toByte()

    for (i in 0 until 8) {
        buf[8 + i] = ((generation shr (i * 8)) and 0xFFUL).toByte()
    }

    var offset = 16
    buf[offset++] = (leaf.size and 0xFF).toByte()
    buf[offset++] = ((leaf.size shr 8) and 0xFF).toByte()

    for (item: BtrfsItem in leaf) {
        val keyBytes = item.a.bytes
        val data = item.b
        buf[offset++] = (keyBytes.size and 0xFF).toByte()
        buf[offset++] = ((keyBytes.size shr 8) and 0xFF).toByte()
        for (i in keyBytes.indices) {
            buf[offset++] = keyBytes[i]
        }
        buf[offset++] = (data.size and 0xFF).toByte()
        buf[offset++] = ((data.size shr 8) and 0xFF).toByte()
        for (i in data.indices) {
            buf[offset++] = data[i]
        }
    }
}

fun decodeLeaf(buf: ByteArray): BtrfsLeaf {
    var offset = 16
    val size = (buf[offset].toInt() and 0xFF) or ((buf[offset + 1].toInt() and 0xFF) shl 8)
    offset += 2

    val items = arrayOfNulls<Join<BtrfsKeyAtom, ByteArray>>(size)
    for (i in 0 until size) {
        val keySize = (buf[offset].toInt() and 0xFF) or ((buf[offset + 1].toInt() and 0xFF) shl 8)
        offset += 2
        val keyArray = ByteArray(keySize)
        for (j in 0 until keySize) {
            keyArray[j] = buf[offset++]
        }

        val dataSize = (buf[offset].toInt() and 0xFF) or ((buf[offset + 1].toInt() and 0xFF) shl 8)
        offset += 2
        val dataArray = ByteArray(dataSize)
        for (j in 0 until dataSize) {
            dataArray[j] = buf[offset++]
        }

        items[i] = btrfsKeyFromBytes(keyArray) j dataArray
    }
    return size j { index: Int -> items[index] ?: error("Missing decoded leaf item at $index") }
}

fun encodeInternal(internal: BtrfsInternal, buf: ByteArray, generation: ULong = 0UL) {
    val magic = 0x4E465242
    // Simple byte copying instead of combine
    for (child in internal) {
        val keyBytes = child.a.bytes
        val nodeIdBytes = child.b.encodeToByteArray()
        // ... just copy bytes directly
    }
}

fun decodeInternal(buf: ByteArray): BtrfsInternal {
    var offset = 16
    val size = (buf[offset].toInt() and 0xFF) or ((buf[offset + 1].toInt() and 0xFF) shl 8)
    offset += 2

    val children = arrayOfNulls<Join<BtrfsKeyAtom, NodeId>>(size)
    for (i in 0 until size) {
        val keySize = (buf[offset].toInt() and 0xFF) or ((buf[offset + 1].toInt() and 0xFF) shl 8)
        offset += 2
        val keyArray = ByteArray(keySize)
        for (j in 0 until keySize) {
            keyArray[j] = buf[offset++]
        }

        val idSize = (buf[offset].toInt() and 0xFF) or ((buf[offset + 1].toInt() and 0xFF) shl 8)
        offset += 2
        val idArray = ByteArray(idSize)
        for (j in 0 until idSize) {
            idArray[j] = buf[offset++]
        }

        children[i] = btrfsKeyFromBytes(keyArray) j NodeId.fromBytes(idArray)
    }
    return size j { index: Int -> children[index] ?: error("Missing decoded internal child at $index") }
}
