package borg.trikeshed.reactor

import borg.trikeshed.io.ByteBuffer
import kotlinx.coroutines.sync.Mutex

expect class BufferPool(bufferSize: Int = 16384) {
    suspend fun acquire(): ByteBuffer
    suspend fun release(buffer: ByteBuffer)
}
