package borg.trikeshed.sctp

import borg.trikeshed.lib.Series
import borg.trikeshed.lib.j

class TlvChunkParser {
    class Chunk(val type: Int, val data: ByteArray)

    fun parse(data: ByteArray): Series<Chunk> {
        val resultList = mutableListOf<Chunk>()
        var offset = 0
        while (offset < data.size) {
            if (offset + 4 > data.size) break
            val type = data[offset].toInt() and 0xFF
            val length = ((data[offset + 2].toInt() and 0xFF) shl 8) or (data[offset + 3].toInt() and 0xFF)
            if (offset + length > data.size) break

            val chunkData = data.copyOfRange(offset + 4, offset + length)

            if (type == 0x00) {
                resultList.add(Chunk(type, chunkData))
            } else {
                val action = type shr 6
                if (action == 0 || action == 1) {
                    break // Stop processing
                }
                // skip others implicitly
            }

            val padding = (4 - (length % 4)) % 4
            offset += length + padding
        }

        return resultList.size j { i -> resultList[i] }
    }
}
