package bbcursive.adapters

import borg.trikeshed.lib.*
import bbcursive.std
import java.nio.ByteBuffer
import java.util.function.UnaryOperator
import java.nio.charset.StandardCharsets

/**
 * Minimal adapter layer that exposes a Series-friendly API over the existing
 * bbcursive Java primitives. This is intentionally small and idiomatic: convert
 * Series -> primitive, delegate to std.*, and convert results back.
 */
object Adapters {
    @JvmStatic
    fun bb(chars: Series<Char>, vararg ops: UnaryOperator<ByteBuffer>): ByteBuffer {
        val str = String(chars.toArray())
        return std.bb(str, *ops)
    }

    @JvmStatic
    fun bb(bytes: Series<Byte>, vararg ops: UnaryOperator<ByteBuffer>): ByteBuffer {
        val arr = bytes.toArray()
        val buf = ByteBuffer.wrap(arr)
        return std.bb(buf, *ops)
    }

    @JvmStatic
    fun bb(chars: Series<Char>, vararg ops: (ByteBuffer) -> ByteBuffer): ByteBuffer {
        val unaryOps = ops.map { UnaryOperator<ByteBuffer> { b -> it(b) } }.toTypedArray()
        return bb(chars, *unaryOps)
    }

    @JvmStatic
    fun str(chars: Series<Char>, vararg ops: UnaryOperator<ByteBuffer>): String {
        val bb = bb(chars, *ops)
        return StandardCharsets.UTF_8.decode(bb).toString()
    }

    @JvmStatic
    fun str(chars: Series<Char>, vararg ops: (ByteBuffer) -> ByteBuffer): String {
        val unaryOps = ops.map { UnaryOperator<ByteBuffer> { b -> it(b) } }.toTypedArray()
        return str(chars, *unaryOps)
    }

    @JvmStatic
    fun cat(buffers: List<ByteBuffer>): ByteBuffer = std.cat(buffers)

    @JvmStatic
    fun alloc(size: Int): ByteBuffer = std.alloc(size)

    @JvmStatic
    fun setAllocator(a: bbcursive.Allocator) = std.setAllocator(a)

    @JvmStatic
    fun getAllocator(): bbcursive.Allocator? = std.getAllocator()
}
