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
) : Buffer(capacity), Comparable<ByteBuffer> {

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

    public override fun hasArray(): Boolean = true

    public override fun array(): ByteArray = backing

    public override fun arrayOffset(): Int = base

    public override fun slice(): ByteBuffer = slice(position0, limit0)

    public override fun slice(p0: Int, p1: Int): ByteBuffer {
        require(p0 in 0..capacity) { "slice begin out of bounds" }
        require(p1 in p0..capacity) { "slice end out of bounds" }
        val length = p1 - p0
        return ByteBuffer(backing, base + p0, length, 0, length, -1, readOnly, order0)
    }

    public override fun duplicate(): ByteBuffer = ByteBuffer(backing, base, capacity, position0, limit0, mark0, readOnly, order0)

    public fun asReadOnlyBuffer(): ByteBuffer = ByteBuffer(backing, base, capacity, position0, limit0, mark0, true, order0)

    public fun get(): Byte = get(position0++)

    public fun put(p0: Byte): ByteBuffer {
        requireWritable(1)
        backing[absoluteIndex(position0++)] = p0
        return this
    }

    public fun get(p0: Int): Byte = backing[absoluteIndex(p0)]

    public fun put(p0: Int, p1: Byte): ByteBuffer {
        requireWritable()
        backing[absoluteIndex(p0)] = p1
        return this
    }

    public fun get(p0: ByteArray, p1: Int, p2: Int): ByteBuffer {
        require(p1 >= 0 && p2 >= 0 && p1 + p2 <= p0.size) { "destination out of bounds" }
        requireReadable(p2)
        val start = absoluteIndex(position0)
        backing.copyInto(p0, destinationOffset = p1, startIndex = start, endIndex = start + p2)
        position0 += p2
        return this
    }

    public fun get(p0: ByteArray): ByteBuffer = get(p0, 0, p0.size)

    public fun get(p0: Int, p1: ByteArray, p2: Int, p3: Int): ByteBuffer {
        require(p2 >= 0 && p3 >= 0 && p2 + p3 <= p1.size) { "destination out of bounds" }
        requireIndex(p0, p3)
        backing.copyInto(p1, destinationOffset = p2, startIndex = absoluteIndex(p0), endIndex = absoluteIndex(p0) + p3)
        return this
    }

    public fun put(p0: ByteBuffer): ByteBuffer {
        while (p0.hasRemaining()) put(p0.get())
        return this
    }

    public fun put(p0: Int, p1: ByteBuffer, p2: Int, p3: Int): ByteBuffer {
        requireWritable()
        require(p2 >= 0 && p3 >= 0 && p2 + p3 <= p1.capacity) { "source out of bounds" }
        requireIndex(p0, p3)
        p1.backing.copyInto(backing, destinationOffset = absoluteIndex(p0), startIndex = p1.base + p2, endIndex = p1.base + p2 + p3)
        return this
    }

    public fun put(p0: ByteArray, p1: Int, p2: Int): ByteBuffer {
        require(p1 >= 0 && p2 >= 0 && p1 + p2 <= p0.size) { "source out of bounds" }
        requireWritable(p2)
        p0.copyInto(backing, destinationOffset = absoluteIndex(position0), startIndex = p1, endIndex = p1 + p2)
        position0 += p2
        return this
    }

    public fun put(p0: ByteArray): ByteBuffer = put(p0, 0, p0.size)

    public fun put(p0: Int, p1: ByteArray, p2: Int, p3: Int): ByteBuffer {
        require(p2 >= 0 && p3 >= 0 && p2 + p3 <= p1.size) { "source out of bounds" }
        requireWritable()
        requireIndex(p0, p3)
        p1.copyInto(backing, destinationOffset = absoluteIndex(p0), startIndex = p2, endIndex = p2 + p3)
        return this
    }

    public fun put(p0: Int, p1: ByteArray): ByteBuffer = put(p0, p1, 0, p1.size)

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

    public fun compact(): ByteBuffer {
        requireWritable()
        val rem = remaining()
        if (rem > 0) {
            backing.copyInto(backing, destinationOffset = base, startIndex = absoluteIndex(position0), endIndex = absoluteIndex(limit0))
        }
        position0 = rem
        limit0 = capacity
        mark0 = -1
        return this
    }

    public override fun isDirect(): Boolean = false

    public override fun toString(): String= "ByteBuffer(position=${position0}, limit=${limit0}, capacity=${capacity}, order=${order0})"

    public override fun hashCode(): Int {
        var result = 1
        for (i in position0 until limit0) {
            result = 31 * result + (backing[absoluteIndex(i)].toInt() and 0xFF)
        }
        return result
    }

    public override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ByteBuffer) return false
        if (remaining() != other.remaining()) return false
        for (i in 0 until remaining()) {
            if (get(position0 + i) != other.get(other.position() + i)) return false
        }
        return true
    }

    public override fun compareTo(other: ByteBuffer): Int {
        val length = minOf(remaining(), other.remaining())
        for (i in 0 until length) {
            val a = get(position0 + i).toInt() and 0xFF
            val b = other.get(other.position() + i).toInt() and 0xFF
            if (a != b) return a - b
        }
        return remaining() - other.remaining()
    }

    public fun mismatch(other: ByteBuffer): Int {
        val length = minOf(remaining(), other.remaining())
        for (i in 0 until length) {
            if (get(position0 + i) != other.get(other.position() + i)) return i
        }
        return if (remaining() != other.remaining()) length else -1
    }

    public fun order(): ByteOrder = order0

    public fun order(p0: ByteOrder): ByteBuffer {
        order0 = p0
        return this
    }

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

    private fun readShort(index: Int): Short {
        requireIndex(index, 2)
        val first = get(index).toInt() and 0xFF
        val second = get(index + 1).toInt() and 0xFF
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
        val b0 = get(index).toInt() and 0xFF
        val b1 = get(index + 1).toInt() and 0xFF
        val b2 = get(index + 2).toInt() and 0xFF
        val b3 = get(index + 3).toInt() and 0xFF
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
        val parts = LongArray(8) { get(index + it).toLong() and 0xFF }
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

    public companion object {
        public fun allocateDirect(p0: Int): ByteBuffer = allocate(p0)
        public fun allocate(p0: Int): ByteBuffer = ByteBuffer(p0)
        public fun wrap(p0: ByteArray, p1: Int, p2: Int): ByteBuffer = ByteBuffer(p0, p1, p2)
        public fun wrap(p0: ByteArray): ByteBuffer = ByteBuffer(p0)
    }
}
