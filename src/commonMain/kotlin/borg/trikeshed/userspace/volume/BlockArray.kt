package borg.trikeshed.userspace.volume

class BlockArray(val volume: Volume, val startLba: Long, val endLba: Long) {
    suspend fun read(index: Long): ByteArray {
        val lba = startLba + index
        if (lba >= endLba) throw IndexOutOfBoundsException("LBA $lba out of bounds")
        return volume.read(lba, 1)
    }

    suspend fun write(index: Long, data: ByteArray) {
        val lba = startLba + index
        if (lba >= endLba) throw IndexOutOfBoundsException("LBA $lba out of bounds")
        if (data.size != volume.blockSize) throw IllegalArgumentException("Data size must match blockSize")
        volume.write(lba, data)
    }
}
