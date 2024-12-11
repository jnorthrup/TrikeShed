package borg.trikeshed.io

import org.khronos.webgl.Int8Array
import org.khronos.webgl.get
import org.khronos.webgl.set

actual class ByteBuffer private constructor(
    private val buffer: Int8Array,
    actual val capacity: Int
) {
    actual var position: Int = 0
    actual var limit: Int = capacity
    private var isReadOnly: Boolean = false

    actual companion object {
        actual fun allocate(capacity: Int): ByteBuffer {
            return ByteBuffer(Int8Array(capacity), capacity)
        }

        actual fun wrap(array: ByteArray): ByteBuffer {
            val int8Array = Int8Array(array.size)
            array.forEachIndexed { index, byte -> int8Array[index] = byte }
            return ByteBuffer(int8Array, array.size)
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
        return buffer[position++].toByte()
    }

    actual fun get(index: Int): Byte {
        if (index >= limit) throw IndexOutOfBoundsException()
        return buffer[index].toByte()
    }

    actual fun get(dst: ByteArray, offset: Int, length: Int): ByteBuffer {
        if (length > remaining()) throw BufferUnderflowException()
        if (offset < 0 || length < 0 || offset + length > dst.size) throw IndexOutOfBoundsException()
        
        for (i in 0 until length) {
            dst[offset + i] = buffer[position + i].toByte()
        }
        position += length
        return this
    }

    actual fun put(b: Byte): ByteBuffer {
        if (isReadOnly) throw ReadOnlyBufferException()
        if (position >= limit) throw BufferOverflowException()
        buffer[position++] = b.toInt()
        return this
    }

    actual fun put(index: Int, b: Byte): ByteBuffer {
        if (isReadOnly) throw ReadOnlyBufferException()
        if (index >= limit) throw IndexOutOfBoundsException()
        buffer[index] = b.toInt()
        return this
    }

    actual fun put(src: ByteArray, offset: Int, length: Int): ByteBuffer {
        if (isReadOnly) throw ReadOnlyBufferException()
        if (length > remaining()) throw BufferOverflowException()
        if (offset < 0 || length < 0 || offset + length > src.size) throw IndexOutOfBoundsException()

        for (i in 0 until length) {
            buffer[position + i] = src[offset + i].toInt()
        }
        position += length
        return this
    }

    actual fun array(): ByteArray {
        return ByteArray(limit) { buffer[it].toByte() }
    }
}

class BufferOverflowException : RuntimeException()
class BufferUnderflowException : RuntimeException()
class ReadOnlyBufferException : RuntimeException()
