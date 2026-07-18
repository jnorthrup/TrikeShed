package borg.trikeshed.userspace.nio

interface Volume {
    val blockSize: Int
    val capacity: Long // in blocks

    suspend fun read(lba: Long, count: Int): ByteBuffer
    suspend fun write(lba: Long, data: ByteBuffer)
    suspend fun sync()
}

class BlockArray(private val volume: Volume) {
    suspend fun get(lba: Long): ByteBuffer {
        return volume.read(lba, 1)
    }

    suspend fun set(lba: Long, data: ByteBuffer) {
        require(data.remaining() == volume.blockSize) { "Data size must match block size" }
        volume.write(lba, data)
    }
}

class BootBlock(private val volume: Volume) {
    suspend fun read(): ByteBuffer {
        return volume.read(0, 1)
    }

    suspend fun write(data: ByteBuffer) {
        require(data.remaining() == volume.blockSize) { "Data size must match block size" }
        volume.write(0, data)
    }
}

class InMemoryVolume(override val blockSize: Int, override val capacity: Long) : Volume {
    private val memory = ByteArray((blockSize * capacity).toInt())

    override suspend fun read(lba: Long, count: Int): ByteBuffer {
        require(lba >= 0 && lba + count <= capacity) { "Read out of bounds" }
        val start = (lba * blockSize).toInt()
        val end = start + count * blockSize
        val bytes = memory.copyOfRange(start, end)
        return ByteBuffer.wrap(bytes)
    }

    override suspend fun write(lba: Long, data: ByteBuffer) {
        require(lba >= 0) { "Write out of bounds: lba must be >= 0" }
        val start = (lba * blockSize).toInt()
        val end = start + data.remaining()
        require(end <= memory.size) { "Write out of bounds" }

        val dataBytes = ByteArray(data.remaining())
        data.get(dataBytes)
        dataBytes.copyInto(memory, start)
    }

    override suspend fun sync() {
        // No-op for in-memory
    }
}
