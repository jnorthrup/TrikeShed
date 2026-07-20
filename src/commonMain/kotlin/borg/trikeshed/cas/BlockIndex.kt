package borg.trikeshed.cas

import borg.trikeshed.job.ContentId

data class LbaEntry(val lba: Long, val sizeBytes: Int, val refCount: Int = 1)

class BlockIndex {
    private val entries = mutableMapOf<ContentId, LbaEntry>()

    fun put(cid: ContentId, entry: LbaEntry) {
        entries[cid] = entry
    }

    fun get(cid: ContentId): LbaEntry? = entries[cid]

    fun remove(cid: ContentId) {
        entries.remove(cid)
    }

    fun getEntries(): Map<ContentId, LbaEntry> = entries.toMap()

    fun encode(): ByteArray {
        val entryCount = entries.size
        val size = 12 + entryCount * 80
        val buffer = ByteArray(size)

        // Header
        buffer.writeIntAt(0, 0xCA5B1001.toInt())
        buffer.writeIntAt(4, 1)
        buffer.writeIntAt(8, entryCount)

        var offset = 12
        for ((cid, entry) in entries) {
            // cid hex: 64 bytes
            val hex = cid.value.removePrefix("sha256:")
            for (i in 0 until 64) {
                buffer[offset + i] = if (i < hex.length) hex[i].code.toByte() else 0
            }
            offset += 64

            // lba: 8 bytes
            buffer.writeLongAt(offset, entry.lba)
            offset += 8

            // sizeBytes: 4 bytes
            buffer.writeIntAt(offset, entry.sizeBytes)
            offset += 4

            // refCount: 4 bytes
            buffer.writeIntAt(offset, entry.refCount)
            offset += 4
        }

        return buffer
    }

    companion object {
        fun decode(bytes: ByteArray): BlockIndex {
            val index = BlockIndex()
            if (bytes.size < 12) return index

            val magic = bytes.readIntAt(0)
            if (magic != 0xCA5B1001.toInt()) return index

            val version = bytes.readIntAt(4)
            if (version != 1) return index

            val entryCount = bytes.readIntAt(8)
            var offset = 12

            for (i in 0 until entryCount) {
                if (offset + 80 > bytes.size) break

                val hexBytes = ByteArray(64)
                bytes.copyInto(hexBytes, 0, offset, offset + 64)
                val hexString = hexBytes.decodeToString().trimEnd('\u0000')
                val cid = ContentId("sha256:${hexString}")
                offset += 64

                val lba = bytes.readLongAt(offset)
                offset += 8

                val sizeBytes = bytes.readIntAt(offset)
                offset += 4

                val refCount = bytes.readIntAt(offset)
                offset += 4

                index.put(cid, LbaEntry(lba, sizeBytes, refCount))
            }

            return index
        }
    }
}

// Helper functions for byte array operations (Little Endian/Big Endian)
// Assuming Big Endian for simplicity, can adjust if needed
internal fun ByteArray.writeIntAt(index: Int, value: Int) {
    this[index] = (value shr 24).toByte()
    this[index + 1] = (value shr 16).toByte()
    this[index + 2] = (value shr 8).toByte()
    this[index + 3] = value.toByte()
}

internal fun ByteArray.readIntAt(index: Int): Int {
    return (this[index].toInt() and 0xFF shl 24) or
           (this[index + 1].toInt() and 0xFF shl 16) or
           (this[index + 2].toInt() and 0xFF shl 8) or
           (this[index + 3].toInt() and 0xFF)
}

internal fun ByteArray.writeLongAt(index: Int, value: Long) {
    this[index] = (value ushr 56).toByte()
    this[index + 1] = (value ushr 48).toByte()
    this[index + 2] = (value ushr 40).toByte()
    this[index + 3] = (value ushr 32).toByte()
    this[index + 4] = (value ushr 24).toByte()
    this[index + 5] = (value ushr 16).toByte()
    this[index + 6] = (value ushr 8).toByte()
    this[index + 7] = value.toByte()
}

internal fun ByteArray.readLongAt(index: Int): Long {
    return (this[index].toLong() and 0xFF shl 56) or
           (this[index + 1].toLong() and 0xFF shl 48) or
           (this[index + 2].toLong() and 0xFF shl 40) or
           (this[index + 3].toLong() and 0xFF shl 32) or
           (this[index + 4].toLong() and 0xFF shl 24) or
           (this[index + 5].toLong() and 0xFF shl 16) or
           (this[index + 6].toLong() and 0xFF shl 8) or
           (this[index + 7].toLong() and 0xFF)
}
