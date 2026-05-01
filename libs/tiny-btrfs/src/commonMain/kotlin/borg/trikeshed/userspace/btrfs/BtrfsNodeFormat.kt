package borg.trikeshed.userspace.btrfs

import borg.trikeshed.lib.*

const val BTRFS_NODE_SIZE = 4096

typealias BtrfsItem = Join<BtrfsByteKey, ByteArray>
typealias BtrfsChildPointer = Join<BtrfsByteKey, String>

class BtrfsLeaf(val items: List<BtrfsItem>)
class BtrfsInternal(val children: List<BtrfsChildPointer>)

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
    buf[offset++] = (leaf.items.size and 0xFF).toByte()
    buf[offset++] = ((leaf.items.size shr 8) and 0xFF).toByte()

    for (item in leaf.items) {
        val keyBytes = item.a.bytes
        val data = item.b
        buf[offset++] = (keyBytes.size and 0xFF).toByte()
        buf[offset++] = ((keyBytes.size shr 8) and 0xFF).toByte()
        for (i in 0 until keyBytes.size) {
            buf[offset++] = keyBytes[i]
        }
        buf[offset++] = (data.size and 0xFF).toByte()
        buf[offset++] = ((data.size shr 8) and 0xFF).toByte()
        for (i in 0 until data.size) {
            buf[offset++] = data[i]
        }
    }
}

fun decodeLeaf(buf: ByteArray): BtrfsLeaf {
    var offset = 16
    val size = (buf[offset].toInt() and 0xFF) or ((buf[offset + 1].toInt() and 0xFF) shl 8)
    offset += 2

    val items = mutableListOf<BtrfsItem>()
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

        items.add(BtrfsByteKey(keySize j { idx -> keyArray[idx] }) j dataArray)
    }
    return BtrfsLeaf(items)
}

fun encodeInternal(internal: BtrfsInternal, buf: ByteArray, generation: ULong = 0UL) {
    val magic = 0x4E465242
    buf[0] = (magic and 0xFF).toByte()
    buf[1] = ((magic shr 8) and 0xFF).toByte()
    buf[2] = ((magic shr 16) and 0xFF).toByte()
    buf[3] = ((magic shr 24) and 0xFF).toByte()

    for (i in 0 until 8) {
        buf[8 + i] = ((generation shr (i * 8)) and 0xFFUL).toByte()
    }

    var offset = 16
    buf[offset++] = (internal.children.size and 0xFF).toByte()
    buf[offset++] = ((internal.children.size shr 8) and 0xFF).toByte()

    for (child in internal.children) {
        val keyBytes = child.a.bytes
        val nodeId = child.b.encodeToByteArray()

        buf[offset++] = (keyBytes.size and 0xFF).toByte()
        buf[offset++] = ((keyBytes.size shr 8) and 0xFF).toByte()
        for (i in 0 until keyBytes.size) {
            buf[offset++] = keyBytes[i]
        }

        buf[offset++] = (nodeId.size and 0xFF).toByte()
        buf[offset++] = ((nodeId.size shr 8) and 0xFF).toByte()
        for (i in 0 until nodeId.size) {
            buf[offset++] = nodeId[i]
        }
    }
}

fun decodeInternal(buf: ByteArray): BtrfsInternal {
    var offset = 16
    val size = (buf[offset].toInt() and 0xFF) or ((buf[offset + 1].toInt() and 0xFF) shl 8)
    offset += 2

    val children = mutableListOf<BtrfsChildPointer>()
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

        children.add(BtrfsByteKey(keySize j { idx -> keyArray[idx] }) j idArray.decodeToString())
    }
    return BtrfsInternal(children)
}