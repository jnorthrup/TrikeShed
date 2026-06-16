@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING", "RedundantVisibilityModifier", "unused")

package borg.trikeshed.userspace.nio

public open class ByteBuffer protected constructor(
    private val backing: ByteArray,
    private val base: Int,
    capacity: Int,
    position: Int = 0,
    limit: Int = capacity,
    mark: Int = -1,
    private val readOnly: Boolean = false,
    private var order0: ByteOrder = ByteOrder.BIG_ENDIAN,
) : Buffer<Int>(capacity), Comparable<ByteBuffer> {

    init {
        require(base >= 0) { "base must be non-negative" }
        require(capacity >= 0) { "capacity must be non-negative" }
        require(base + capacity <= backing.size) { "capacity exceeds backing array length" }
        require(position in 0..limit) { "position must be between 0 and limit" }
        require(limit in 0..capacity) { "limit must be between 0 and capacity" }
        position0 = position
        limit0 = limit
        mark0 = mark
    }

    /** Capacity as Int for NIO buffer state. */
    override val capacityInt: Int = capacity

    public constructor(capacity: Int) : this(ByteArray(capacity), 0, capacity)

    public constructor(array: ByteArray) : this(array, 0, array.size)

    public constructor(array: ByteArray, offset: Int, length: Int) : this(array, offset, length, 0, length, -1, false, ByteOrder.BIG_ENDIAN)

    private fun absoluteIndex(index: Int): Int {
        require(index in 0 until capacity) { "index out of bounds: $index" }
        return base + index
    }

    private fun requireIndex(index: Int, size: Int = 1) {
        require(index >= 0) { "index must be non-negative" }
        require(index + size <= capacity) { "index out of bounds" }
    }

    private fun requireReadable(count: Int) {
        require(count >= 0) { "count must be non-negative" }
        require(position0 + count <= limit0) { "Not enough data remaining" }
    }

    private fun requireWritable() {
        require(!readOnly) { "Read-only buffer" }
    }

    private fun requireWritable(count: Int) {
        requireWritable()
        require(position0 + count <= limit0) { "Not enough space remaining" }
    }

    // ── MetaSeries / NIO index functions ───────────────────────────

    /** MetaSeries get by Int index (same as NIO get for ByteBuffer). */
    override protected fun metaGet(index: Int): Byte = backing[absoluteIndex(index)]

    /** MetaSeries put by Int index (same as NIO put for ByteBuffer). */
    override protected fun metaPut(index: Int, value: Byte) {
        requireWritable()
        backing[absoluteIndex(index)] = value
    }

    /** NIO get by int index. */
    override protected fun nioGet(index: Int): Byte = backing[absoluteIndex(index)]

    /** NIO put by int index. */
    override protected fun nioPut(index: Int, value: Byte) {
        requireWritable()
        backing[absoluteIndex(index)] = value
    }

    // ── Array access ───────────────────────────────────────────────

    public override fun hasArray(): Boolean = true

    public override fun array(): ByteArray = backing

    public override fun arrayOffset(): Int = base

    // ── Slicing & duplication ──────────────────────────────────────

    public override fun slice(): ByteBuffer = slice(position0, limit0)

    public override fun slice(p0: Int, p1: Int): ByteBuffer {
        require(p0 in 0..capacity) { "slice begin out of bounds" }
        require(p1 in p0..capacity) { "slice end out of bounds" }
        val length = p1 - p0
        return ByteBuffer(backing, base + p0, length, 0, length, -1, readOnly, order0)
    }

    public override fun duplicate(): ByteBuffer = ByteBuffer(backing, base, capacity, position0, limit0, mark0, readOnly, order0)

    public fun asReadOnlyBuffer(): ByteBuffer = ByteBuffer(backing, base, capacity, position0, limit0, mark0, true, order0)

    // ── Relative get/put (inherit from Buffer) ────────────────────

    // ── Absolute get/put (inherit from Buffer, delegate to nioGet/nioPut) ────────────────

    // ── Bulk get/put (backing-optimized overrides) ────────────────

    public override fun get(dst: ByteArray, offset: Int, length: Int): ByteBuffer {
        require(offset >= 0 && length >= 0 && offset + length <= dst.size) { "destination out of bounds" }
        requireReadable(length)
        val start = absoluteIndex(position0)
        backing.copyInto(dst, destinationOffset = offset, startIndex = start, endIndex = start + length)
        position0 += length
        return this
    }

    public override fun get(dst: ByteArray): ByteBuffer = get(dst, 0, dst.size)

    public fun get(index: Int, dst: ByteArray, offset: Int, length: Int): ByteBuffer {
        require(offset >= 0 && length >= 0 && offset + length <= dst.size) { "destination out of bounds" }
        requireIndex(index, length)
        backing.copyInto(dst, destinationOffset = offset, startIndex = absoluteIndex(index), endIndex = absoluteIndex(index) + length)
        return this
    }

    public override fun put(src: Buffer<*>): ByteBuffer {
        while (src.hasRemaining()) put(src.get())
        return this
    }

    public fun put(src: ByteBuffer): ByteBuffer {
        val srcRem = src.remaining()
        requireWritable(srcRem)
        val thisStart = absoluteIndex(position0)
        val srcStart = src.absoluteIndex(src.position())
        src.backing.copyInto(backing, destinationOffset = thisStart, startIndex = srcStart, endIndex = srcStart + srcRem)
        position0 += srcRem
        src.position(src.position() + srcRem)
        return this
    }

    public fun put(index: Int, src: ByteBuffer, srcOffset: Int, length: Int): ByteBuffer {
        requireWritable()
        require(srcOffset >= 0 && length >= 0 && srcOffset + length <= src.capacity) { "source out of bounds" }
        requireIndex(index, length)
        src.backing.copyInto(backing, destinationOffset = absoluteIndex(index), startIndex = src.base + srcOffset, endIndex = src.base + srcOffset + length)
        return this
    }

    public override fun put(src: ByteArray, offset: Int, length: Int): ByteBuffer {
        require(offset >= 0 && length >= 0 && offset + length <= src.size) { "source out of bounds" }
        requireWritable(length)
        src.copyInto(backing, destinationOffset = absoluteIndex(position0), startIndex = offset, endIndex = offset + length)
        position0 += length
        return this
    }

    public override fun put(src: ByteArray): ByteBuffer = put(src, 0, src.size)

    public fun put(index: Int, src: ByteArray, offset: Int, length: Int): ByteBuffer {
        require(offset >= 0 && length >= 0 && offset + length <= src.size) { "source out of bounds" }
        requireWritable()
        requireIndex(index, length)
        src.copyInto(backing, destinationOffset = absoluteIndex(index), startIndex = offset, endIndex = offset + length)
        return this
    }

    public fun put(index: Int, src: ByteArray): ByteBuffer = put(index, src, 0, src.size)

    // ── Position/limit/mark overrides (covariant returns) ──────────

    public override fun position(p0: Int): ByteBuffer {
        super.position(p0)
        return this
    }

    public override fun limit(p0: Int): ByteBuffer {
        super.limit(p0)
        return this
    }

    public override fun mark(): ByteBuffer {
        super.mark()
        return this
    }

    public override fun reset(): ByteBuffer {
        super.reset()
        return this
    }

    public override fun clear(): ByteBuffer {
        super.clear()
        return this
    }

    public override fun flip(): ByteBuffer {
        super.flip()
        return this
    }

    public override fun rewind(): ByteBuffer {
        super.rewind()
        return this
    }

    public override fun compact(): ByteBuffer {
        super.compact()
        return this
    }

    // ── Read-only & direct ─────────────────────────────────────────

    public override fun isDirect(): Boolean = false

    // ── Byte order ─────────────────────────────────────────────────

    public fun order(): ByteOrder = order0

    public fun order(p0: ByteOrder): ByteBuffer {
        order0 = p0
        return this
    }

    // ── Alignment ──────────────────────────────────────────────────

    public fun alignmentOffset(p0: Int, p1: Int): Int {
        require(p0 > 0) { "alignment must be positive" }
        require(p1 >= 0 && p1 < p0) { "offset must be between 0 and alignment" }
        val absolutePosition = base + position0
        val distance = ((absolutePosition - p1) % p0 + p0) % p0
        return if (distance == 0) 0 else p0 - distance
    }

    public fun alignedSlice(p0: Int): ByteBuffer {
        val offset = alignmentOffset(p0, 0)
        val start = position0 + offset
        return if (start > limit0) slice(limit0, limit0) else slice(start, limit0)
    }

    // ── Typed get/put (short, char, int, long, float, double) ──────

    private fun readShort(index: Int): Short {
        requireIndex(index, 2)
        val first = nioGet(index).toInt() and 0xFF
        val second = nioGet(index + 1).toInt() and 0xFF
        return if (order0 == ByteOrder.BIG_ENDIAN) {
            ((first shl 8) or second).toShort()
        } else {
            ((second shl 8) or first).toShort()
        }
    }

    private fun writeShort(index: Int, value: Short) {
        requireWritable()
        requireIndex(index, 2)
        val bits = value.toInt() and 0xFFFF
        if (order0 == ByteOrder.BIG_ENDIAN) {
            backing[absoluteIndex(index)] = ((bits ushr 8) and 0xFF).toByte()
            backing[absoluteIndex(index + 1)] = (bits and 0xFF).toByte()
        } else {
            backing[absoluteIndex(index)] = (bits and 0xFF).toByte()
            backing[absoluteIndex(index + 1)] = ((bits ushr 8) and 0xFF).toByte()
        }
    }

    private fun readInt(index: Int): Int {
        requireIndex(index, 4)
        val b0 = nioGet(index).toInt() and 0xFF
        val b1 = nioGet(index + 1).toInt() and 0xFF
        val b2 = nioGet(index + 2).toInt() and 0xFF
        val b3 = nioGet(index + 3).toInt() and 0xFF
        return if (order0 == ByteOrder.BIG_ENDIAN) {
            (b0 shl 24) or (b1 shl 16) or (b2 shl 8) or b3
        } else {
            (b3 shl 24) or (b2 shl 16) or (b1 shl 8) or b0
        }
    }

    private fun writeInt(index: Int, value: Int) {
        requireWritable()
        requireIndex(index, 4)
        if (order0 == ByteOrder.BIG_ENDIAN) {
            backing[absoluteIndex(index)] = (value ushr 24).toByte()
            backing[absoluteIndex(index + 1)] = (value ushr 16).toByte()
            backing[absoluteIndex(index + 2)] = (value ushr 8).toByte()
            backing[absoluteIndex(index + 3)] = value.toByte()
        } else {
            backing[absoluteIndex(index)] = value.toByte()
            backing[absoluteIndex(index + 1)] = (value ushr 8).toByte()
            backing[absoluteIndex(index + 2)] = (value ushr 16).toByte()
            backing[absoluteIndex(index + 3)] = (value ushr 24).toByte()
        }
    }

    private fun readLong(index: Int): Long {
        requireIndex(index, 8)
        val parts = LongArray(8) { nioGet(index + it).toLong() and 0xFF }
        return if (order0 == ByteOrder.BIG_ENDIAN) {
            parts[0] shl 56 or (parts[1] shl 48) or (parts[2] shl 40) or (parts[3] shl 32) or
                (parts[4] shl 24) or (parts[5] shl 16) or (parts[6] shl 8) or parts[7]
        } else {
            parts[7] shl 56 or (parts[6] shl 48) or (parts[5] shl 40) or (parts[4] shl 32) or
                (parts[3] shl 24) or (parts[2] shl 16) or (parts[1] shl 8) or parts[0]
        }
    }

    private fun writeLong(index: Int, value: Long) {
        requireWritable()
        requireIndex(index, 8)
        if (order0 == ByteOrder.BIG_ENDIAN) {
            for (i in 0 until 8) {
                backing[absoluteIndex(index + i)] = (value ushr ((7 - i) * 8)).toByte()
            }
        } else {
            for (i in 0 until 8) {
                backing[absoluteIndex(index + i)] = (value ushr (i * 8)).toByte()
            }
        }
    }

    public fun getChar(): Char = getChar(position0).also { position0 += 2 }

    public fun putChar(p0: Char): ByteBuffer {
        putChar(position0, p0)
        position0 += 2
        return this
    }

    public fun getChar(p0: Int): Char = readShort(p0).toInt().toChar()

    public fun putChar(p0: Int, p1: Char): ByteBuffer {
        writeShort(p0, p1.code.toShort())
        return this
    }

    public fun getShort(): Short = getShort(position0).also { position0 += 2 }

    public fun putShort(p0: Short): ByteBuffer {
        putShort(position0, p0)
        position0 += 2
        return this
    }

    public fun getShort(p0: Int): Short = readShort(p0)

    public fun putShort(p0: Int, p1: Short): ByteBuffer {
        writeShort(p0, p1)
        return this
    }

    public fun getInt(): Int = getInt(position0).also { position0 += 4 }

    public fun putInt(p0: Int): ByteBuffer {
        putInt(position0, p0)
        position0 += 4
        return this
    }

    public fun getInt(p0: Int): Int = readInt(p0)

    public fun putInt(p0: Int, p1: Int): ByteBuffer {
        writeInt(p0, p1)
        return this
    }

    public fun getLong(): Long = getLong(position0).also { position0 += 8 }

    public fun putLong(p0: Long): ByteBuffer {
        putLong(position0, p0)
        position0 += 8
        return this
    }

    public fun getLong(p0: Int): Long = readLong(p0)

    public fun putLong(p0: Int, p1: Long): ByteBuffer {
        writeLong(p0, p1)
        return this
    }

    public fun getFloat(): Float = Float.fromBits(getInt(position0).also { position0 += 4 })

    public fun putFloat(p0: Float): ByteBuffer {
        putInt(position0, p0.toRawBits())
        position0 += 4
        return this
    }

    public fun getFloat(p0: Int): Float = Float.fromBits(getInt(p0))

    public fun putFloat(p0: Int, p1: Float): ByteBuffer {
        putInt(p0, p1.toRawBits())
        return this
    }

    public fun getDouble(): Double = Double.fromBits(getLong(position0).also { position0 += 8 })

    public fun putDouble(p0: Double): ByteBuffer {
        putLong(position0, p0.toRawBits())
        position0 += 8
        return this
    }

    public fun getDouble(p0: Int): Double = Double.fromBits(getLong(p0))

    public fun putDouble(p0: Int, p1: Double): ByteBuffer {
        putLong(p0, p1.toRawBits())
        return this
    }

    // ── Object methods ─────────────────────────────────────────────

    public override fun toString(): String = "ByteBuffer(position=${position0}, limit=${limit0}, capacity=${capacity}, order=${order0})"

    public override fun hashCode(): Int {
        var result = 1
        for (i in position0 until limit0) {
            result = 31 * result + (nioGet(i).toInt() and 0xFF)
        }
        return result
    }

    public override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ByteBuffer) return false
        if (remaining() != other.remaining()) return false
        for (i in 0 until remaining()) {
            if (nioGet(position0 + i) != other.nioGet(other.position() + i)) return false
        }
        return true
    }

    public override fun compareTo(other: ByteBuffer): Int {
        val length = minOf(remaining(), other.remaining())
        for (i in 0 until length) {
            val a = nioGet(position0 + i).toInt() and 0xFF
            val b = other.nioGet(other.position() + i).toInt() and 0xFF
            if (a != b) return a - b
        }
        return remaining() - other.remaining()
    }

    public fun mismatch(other: ByteBuffer): Int {
        val length = minOf(remaining(), other.remaining())
        for (i in 0 until length) {
            if (nioGet(position0 + i) != other.nioGet(other.position() + i)) return i
        }
        return if (remaining() != other.remaining()) length else -1
    }

    // ── Factory methods ────────────────────────────────────────────

    public companion object {
        public fun allocateDirect(p0: Int): ByteBuffer = allocate(p0)

        public fun allocate(p0: Int): ByteBuffer = ByteBuffer(p0)

        public fun wrap(p0: ByteArray, p1: Int, p2: Int): ByteBuffer = ByteBuffer(p0, p1, p2)

        public fun wrap(p0: ByteArray): ByteBuffer = ByteBuffer(p0)
    }
}