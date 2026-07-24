package borg.trikeshed.sctp

class SctpBoundedStream(val capacity: Int = 10) {

    suspend fun enqueue(data: ByteArray) {
        // To be implemented
    }

    suspend fun dequeue(): ByteArray {
        // To be implemented
        return ByteArray(0)
    }
}
