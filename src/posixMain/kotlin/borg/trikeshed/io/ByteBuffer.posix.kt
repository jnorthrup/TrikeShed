package borg.trikeshed.io

import kotlinx.cinterop.*
import platform.posix.*

actual class ByteBuffer private constructor(
    private val ptr: CPointer<ByteVar>,
    actual val capacity: Int
) {
    actual var position: Int = 0
    actual var limit: Int = capacity
    private var isReadOnly: Boolean = false

    actual companion object {
        actual fun allocate(capacity: Int): ByteBuffer {
            val ptr = nativeHeap.allocArray<ByteVar>(capacity)
            return ByteBuffer(ptr, capacity)
        }

        actual fun wrap(array: ByteArray): ByteBuffer {
            val ptr = array.toCValues().ptr
            return ByteBuffer(ptr.reinterpret(), array.size)
        }
    }

    actual fun clear(): ByteBuffer {
        position = 0
        limit = capacity
        return this
    }

    actual fun flip(): ByteBuffer {
        limit = position
        position = 0
        return this
    }

    actual fun rewind(): ByteBuffer {
        position = 0
        return this
    }

    actual fun remaining(): Int = limit - position

    actual fun hasRemaining(): Boolean = position < limit

    actual fun get(): Byte {
        if (position >= limit) throw IndexOutOfBoundsException()
        return ptr[position++]
    }

    actual fun get(index: Int): Byte {
        if (index >= limit) throw IndexOutOfBoundsException()
        return ptr[index]
    }

    actual fun get(dst: ByteArray, offset: Int, length: Int): ByteBuffer {
        if (length > remaining()) throw BufferUnderflowException()
        if (offset < 0 || length < 0 || offset + length > dst.size) throw IndexOutOfBoundsException()
        
        memcpy(dst.refTo(offset), ptr + position, length.convert())
        position += length
        return this
    }

    actual fun put(b: Byte): ByteBuffer {
        if (isReadOnly) throw ReadOnlyBufferException()
        if (position >= limit) throw BufferOverflowException()
        ptr[position++] = b
        return this
    }

    actual fun put(index: Int, b: Byte): ByteBuffer {
        if (isReadOnly) throw ReadOnlyBufferException()
        if (index >= limit) throw IndexOutOfBoundsException()
        ptr[index] = b
        return this
    }

    actual fun put(src: ByteArray, offset: Int, length: Int): ByteBuffer {
        if (isReadOnly) throw ReadOnlyBufferException()
        if (length > remaining()) throw BufferOverflowException()
        if (offset < 0 || length < 0 || offset + length > src.size) throw IndexOutOfBoundsException()

        memcpy(ptr + position, src.refTo(offset), length.convert())
        position += length
        return this
    }

    actual fun array(): ByteArray {
        val result = ByteArray(limit)
        memcpy(result.refTo(0), ptr, limit.convert())
        return result
    }
}

class BufferOverflowException : RuntimeException()
class BufferUnderflowException : RuntimeException()
class ReadOnlyBufferException : RuntimeException()
