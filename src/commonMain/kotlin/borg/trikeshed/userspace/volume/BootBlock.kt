package borg.trikeshed.userspace.volume

class BootBlock(val volume: Volume) {
    suspend fun read(): ByteArray {
        return volume.read(0, 1)
    }

    suspend fun write(data: ByteArray) {
        if (data.size != volume.blockSize) throw IllegalArgumentException("Data size must match blockSize")
        volume.write(0, data)
    }
}
