package borg.trikeshed.userspace.volume

interface Volume {
    val blockSize: Int
    val capacity: Long

    suspend fun read(lba: Long, count: Int): ByteArray
    suspend fun write(lba: Long, data: ByteArray)
    suspend fun sync()
}
