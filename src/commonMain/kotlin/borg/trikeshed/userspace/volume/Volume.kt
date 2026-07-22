package borg.trikeshed.userspace.volume

enum class IoKind {
    READ, WRITE
}

class IoRequest(val lba: Long, val data: ByteArray, val kind: IoKind)

sealed class IoResult {
    class Ok(val bytes: ByteArray) : IoResult()
    class Failure(val cause: Throwable) : IoResult()
    object Cancelled : IoResult()
}

interface Volume {
    val blockSize: Int
    val capacity: Long

    suspend fun read(lba: Long, count: Int): ByteArray
    suspend fun write(lba: Long, data: ByteArray)
    suspend fun sync()

    suspend fun submitBatch(requests: List<IoRequest>): List<IoResult> = throw NotImplementedError()

    fun enqueue_burst(requests: List<IoRequest>) { throw NotImplementedError() }
    suspend fun dequeue_burst(): List<IoResult> { throw NotImplementedError() }
}
