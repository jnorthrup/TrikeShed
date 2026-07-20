package borg.trikeshed.browser.storage

import borg.trikeshed.userspace.volume.Volume

class IndexedDbWasmVolume(
    private val config: BrowserVolumeConfig
) : Volume by IndexedDbVolume(IndexedDbWasmBlockDevice(config), config)

class IndexedDbWasmBlockDevice(
    private val config: BrowserVolumeConfig
) : BlockDevice {
    override val blockSize: Int = config.blockSize

    private val idb = IndexedDbWasm()
    private var db: Any? = null

    private suspend fun getDb(): Any {
        if (db == null) {
            db = idb.open()
        }
        return db!!
    }

    override suspend fun read(offset: Long, length: Int): ByteArray {
        val dbInstance = getDb()
        val result = ByteArray(length)

        var bytesRead = 0
        var currentOffset = offset

        while (bytesRead < length) {
            val chunkIdx = currentOffset / 1048576 // 256 blocks of 4096 = 1MiB
            val key = "${config.namespace}:chunk:$chunkIdx"

            val chunkData = idb.read(dbInstance, key) ?: ByteArray(1048576)

            val chunkOffset = (currentOffset % 1048576).toInt()
            val toCopy = minOf(length - bytesRead, chunkData.size - chunkOffset)

            chunkData.copyInto(result, bytesRead, chunkOffset, chunkOffset + toCopy)

            bytesRead += toCopy
            currentOffset += toCopy
        }

        return result
    }

    override suspend fun write(offset: Long, data: ByteArray) {
        val dbInstance = getDb()
        var bytesWritten = 0
        var currentOffset = offset

        while (bytesWritten < data.size) {
            val chunkIdx = currentOffset / 1048576
            val key = "${config.namespace}:chunk:$chunkIdx"

            // Read existing chunk
            val chunkData = idb.read(dbInstance, key) ?: ByteArray(1048576)

            val chunkOffset = (currentOffset % 1048576).toInt()
            val toCopy = minOf(data.size - bytesWritten, 1048576 - chunkOffset)

            data.copyInto(chunkData, chunkOffset, bytesWritten, bytesWritten + toCopy)

            // Write back
            idb.write(dbInstance, key, chunkData)

            bytesWritten += toCopy
            currentOffset += toCopy
        }
    }

    override suspend fun sync() {
        // sync happens per transaction
    }

    override suspend fun close() {
        db = null
    }
}
