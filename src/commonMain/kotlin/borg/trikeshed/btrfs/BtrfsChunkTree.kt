package borg.trikeshed.btrfs

object BtrfsChunkTree {

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

    private fun readUShortLE(buf: ByteArray, offset: Int): UShort {
        val low = buf[offset].toUInt() and 0xFFu
        val high = buf[offset + 1].toUInt() and 0xFFu
        return (low or (high shl 8)).toUShort()
    }

    fun parse(bytes: ByteArray, offset: Int): List<Pair<BtrfsKey, BtrfsChunkItem>> {
        val result = mutableListOf<Pair<BtrfsKey, BtrfsChunkItem>>()
        
        // nritems is at offset 96 within the btrfs_header
        val nrItems = readUIntLE(bytes, offset + 96).toInt()

        // item pointers start at offset 101
        var itemPtrOffset = offset + 101
        
        for (i in 0 until nrItems) {
            val keyObjectid = readULongLE(bytes, itemPtrOffset)
            val keyType = bytes[itemPtrOffset + 8].toUByte()
            val keyOffset = readULongLE(bytes, itemPtrOffset + 9)
            val key = BtrfsKey(keyObjectid, keyType, keyOffset)
            
            require(key.type == BTRFS_KEY_TYPE_CHUNK_ITEM) {
                "expected key.type = ${BTRFS_KEY_TYPE_CHUNK_ITEM}, got ${key.type}"
            }
            
            // item pointers have 25 bytes total (17 byte key + 4 byte offset + 4 byte size)
            val dataOffsetInNode = readUIntLE(bytes, itemPtrOffset + 17).toInt()
            
            // fix: data offset is relative to the start of the node (offset) + 101 byte header 
            val payloadOffset = offset + 101 + dataOffsetInNode
            
            val stripeLength = readULongLE(bytes, payloadOffset)
            val type = bytes[payloadOffset + 24].toUByte() // bitmask for single, raid0, raid1...
            val numStripes = readUShortLE(bytes, payloadOffset + 44)
            val subStripes = readUShortLE(bytes, payloadOffset + 46)
            
            val stripes = mutableListOf<BtrfsStripe>()
            var stripeOffset = payloadOffset + 48 // btrfs_chunk is 48 bytes without stripes
            
            for (s in 0 until numStripes.toInt()) {
                val devid = readULongLE(bytes, stripeOffset)
                val stripeOff = readULongLE(bytes, stripeOffset + 8)
                stripes.add(BtrfsStripe(devid, stripeOff))
                // each btrfs_stripe is 32 bytes (devid 8, offset 8, dev_uuid 16)
                stripeOffset += 32
            }
            
            val chunkItem = BtrfsChunkItem(stripeLength, type, numStripes, subStripes, stripes)
            result.add(Pair(key, chunkItem))
            
            itemPtrOffset += 25 // advance to next item pointer
        }

        return result
    }
}