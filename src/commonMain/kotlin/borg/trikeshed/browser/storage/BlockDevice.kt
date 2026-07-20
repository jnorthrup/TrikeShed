package borg.trikeshed.browser.storage

interface BlockDevice {
    val blockSize: Int
    suspend fun read(offset: Long, length: Int): ByteArray
    suspend fun write(offset: Long, data: ByteArray)
    suspend fun sync()
    suspend fun close()
}
