package borg.trikeshed.btrfs

object BtrfsDeviceTree {

    private fun readULongLE(buf: ByteArray, offset: Int): ULong {
        var result = 0uL
        for (i in 0..7) {
            result = result or ((buf[offset + i].toULong() and 0xFFuL) shl (i * 8))
        }
        return result
    }

    private fun readUIntLE(buf: ByteArray, offset: Int): UInt {
        var result = 0u
        for (i in 0..3) {
            result = result or ((buf[offset + i].toUInt() and 0xFFu) shl (i * 8))
        }
        return result
    }

    fun parse(bytes: ByteArray, offset: Int): List<Pair<BtrfsKey, BtrfsDevItem>> {
        val result = mutableListOf<Pair<BtrfsKey, BtrfsDevItem>>()
        
        // nritems is at offset 96 within the btrfs_header
        val nrItems = readUIntLE(bytes, offset + 96).toInt()

        // item pointers start at offset 101
        var itemPtrOffset = offset + 101
        
        for (i in 0 until nrItems) {
            val keyObjectid = readULongLE(bytes, itemPtrOffset)
            val keyType = bytes[itemPtrOffset + 8].toUByte()
            val keyOffset = readULongLE(bytes, itemPtrOffset + 9)
            val key = BtrfsKey(keyObjectid, keyType, keyOffset)
            
            require(key.type == BTRFS_KEY_TYPE_DEV_ITEM) {
                "expected key.type = ${BTRFS_KEY_TYPE_DEV_ITEM}, got ${key.type}"
            }
            
            val dataOffsetInNode = readUIntLE(bytes, itemPtrOffset + 17).toInt()
            val itemSize = readUIntLE(bytes, itemPtrOffset + 21).toInt()
            
            // fix: data offset is relative to the start of the node (offset)
            val payloadOffset = offset + 101 + dataOffsetInNode
            
            val devid = readULongLE(bytes, payloadOffset)
            // read only the first 8 bytes of uuid for simplicity here as required by the spec
            val uuid = readULongLE(bytes, payloadOffset + 8) 
            // uuid is actually 16 bytes, size is at offset 24
            val size = readULongLE(bytes, payloadOffset + 24)
            val bytesUsed = readULongLE(bytes, payloadOffset + 32)
            
            // Path is at offset 98 onwards, max size is itemSize - 98
            // read until NUL or end
            var pathEnd = payloadOffset + 98
            val maxPathEnd = payloadOffset + itemSize
            while (pathEnd < maxPathEnd && bytes[pathEnd] != 0.toByte()) {
                pathEnd++
            }
            
            val path = bytes.decodeToString(payloadOffset + 98, pathEnd)
            
            val devItem = BtrfsDevItem(devid, uuid, size, bytesUsed, path)
            result.add(Pair(key, devItem))
            
            itemPtrOffset += 25
        }
        
        return result
    }
}