@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING", "RedundantVisibilityModifier", "unused")

package borg.trikeshed.userspace.nio

import borg.trikeshed.lib.MetaSeries

/**
 * Base buffer class implementing [MetaSeries] for indexed byte access.
 *
 * @param I the index type (e.g., [Int] for [ByteBuffer], [Long] for LongBuffer)
 * @param capacity the buffer capacity in the index type I
 */
public abstract class Buffer<I : Comparable<I>>(
    protected val capacity: I
) : MetaSeries<I, Byte> {
    // NIO buffer state (Int-based, per NIO spec)
    protected var position0: Int = 0
    protected var limit0: Int = 0
    protected var mark0: Int = -1

    init {
        limit0 = capacityInt
    }

    /** Capacity as Int for NIO buffer state. Implemented by subclasses. */
    protected abstract val capacityInt: Int

    // ── MetaSeries implementation ──────────────────────────────────

    /** The domain (capacity) as type I. */
    public override val a: I get() = capacity

    /** The index function for MetaSeries access. */
    public override val b: (I) -> Byte = this::metaGet

    /** MetaSeries absolute get by index I — implemented by subclasses. */
    protected abstract fun metaGet(index: I): Byte

    /** MetaSeries absolute put by index I — implemented by subclasses. */
    protected abstract fun metaPut(index: I, value: Byte)

    // ── NIO index functions (Int-based) ────────────────────────────

    /** NIO absolute get by int index — implemented by subclasses. */
    protected abstract fun nioGet(index: Int): Byte

    /** NIO absolute put by int index — implemented by subclasses. */
    protected abstract fun nioPut(index: Int, value: Byte)

    // ── NIO Buffer API ────────────────────────────────────────────

    public open fun capacity(): I = capacity

    public open fun position(): Int = position0

    public open fun position(p0: Int): Buffer<I> {
        require(p0 in 0..limit0) { "position must be between 0 and limit" }
        position0 = p0
        if (mark0 > position0) mark0 = -1
        return this
    }

    public open fun limit(): Int = limit0

    public open fun limit(p0: Int): Buffer<I> {
        require(p0 in 0..capacityInt) { "limit must be between 0 and capacity" }
        limit0 = p0
        if (position0 > limit0) position0 = limit0
        if (mark0 > limit0) mark0 = -1
        return this
    }

    public open fun mark(): Buffer<I> {
        mark0 = position0
        return this
    }

    public open fun reset(): Buffer<I> {
        require(mark0 >= 0) { "Mark has not been set" }
        position0 = mark0
        return this
    }

    public open fun clear(): Buffer<I> {
        position0 = 0
        limit0 = capacityInt
        mark0 = -1
        return this
    }

    public open fun flip(): Buffer<I> {
        limit0 = position0
        position0 = 0
        mark0 = -1
        return this
    }

    public open fun rewind(): Buffer<I> {
        position0 = 0
        mark0 = -1
        return this
    }

    public open fun remaining(): Int = limit0 - position0

    public open fun hasRemaining(): Boolean = remaining() > 0

    public open fun isReadOnly(): Boolean = false

    public open fun hasArray(): Boolean = false

    public open fun array(): Any = throw UnsupportedOperationException("Buffer has no accessible array")

    public open fun arrayOffset(): Int = throw UnsupportedOperationException("Buffer has no accessible array")

    public open fun isDirect(): Boolean = false

    public open fun slice(): Buffer<I> = throw UnsupportedOperationException("slice is not supported")

    public open fun slice(p0: Int, p1: Int): Buffer<I> = throw UnsupportedOperationException("slice with range is not supported")

    public open fun duplicate(): Buffer<I> = throw UnsupportedOperationException("duplicate is not supported")

    // ── Relative get/put (position-based, NIO API) ─────────────────

    /** Gets the byte at the current position and increments position. */
    public open fun get(): Byte {
        val value = nioGet(position0)
        position0++
        return value
    }

    /** Puts a byte at the current position and increments position. */
    public open fun put(value: Byte): Buffer<I> {
        nioPut(position0, value)
        position0++
        return this
    }

    // ── Absolute get/put (Int-based, NIO API) ──────────────────────

    /** Gets the byte at the given absolute int index. */
    public open fun get(index: Int): Byte = nioGet(index)

    /** Puts a byte at the given absolute int index. */
    public open fun put(index: Int, value: Byte): Buffer<I> {
        nioPut(index, value)
        return this
    }

    // ── Bulk get/put ──────────────────────────────────────────────

    public open fun get(dst: ByteArray, offset: Int, length: Int): Buffer<I> {
        require(offset >= 0 && length >= 0 && offset + length <= dst.size) { "destination out of bounds" }
        requireReadable(length)
        for (i in 0 until length) {
            dst[offset + i] = get()
        }
        return this
    }

    public open fun get(dst: ByteArray): Buffer<I> = get(dst, 0, dst.size)

    public open fun put(src: Buffer<*>): Buffer<I> {
        while (src.hasRemaining()) put(src.get())
        return this
    }

    public open fun put(src: ByteArray, offset: Int, length: Int): Buffer<I> {
        require(offset >= 0 && length >= 0 && offset + length <= src.size) { "source out of bounds" }
        requireWritable(length)
        for (i in 0 until length) {
            put(src[offset + i])
        }
        return this
    }

    public open fun put(src: ByteArray): Buffer<I> = put(src, 0, src.size)

    // ── Compact ───────────────────────────────────────────────────

    public open fun compact(): Buffer<I> {
        requireWritable()
        val rem = remaining()
        if (rem > 0) {
            for (i in 0 until rem) {
                nioPut(i, nioGet(position0 + i))
            }
        }
        position0 = rem
        limit0 = capacityInt
        mark0 = -1
        return this
    }

    // ── Helpers ───────────────────────────────────────────────────

    private fun requireReadable(count: Int) {
        require(count >= 0) { "count must be non-negative" }
        require(position0 + count <= limit0) { "Not enough data remaining" }
    }

    private fun requireWritable() {
        require(!isReadOnly()) { "Read-only buffer" }
    }

    private fun requireWritable(count: Int) {
        requireWritable()
        require(position0 + count <= limit0) { "Not enough space remaining" }
    }

    public override fun toString(): String = "Buffer(position=$position0, limit=$limit0, capacity=$capacity)"
}