package borg.trikeshed.num

import borg.trikeshed.lib.*
import borg.trikeshed.lib.CZero.nz
import borg.trikeshed.lib.CZero.z
import kotlin.math.absoluteValue
import kotlin.math.max


/** made immutable by series. */
class BigInt(private val sign: Boolean?, private val magnitude: Series<UInt>) : Number(), Comparable<BigInt> {


    // Constructor from Long
    private constructor(value: Long) : this(
        sign = if (value.z) null else value > 0,
        magnitude = if (value.z) emptySeries() else {
            value.absoluteValue.let { absValue ->
                val low = (absValue and 0xFFFF_FFFF).toUInt() // Lower 32 bits
                val high = ((absValue ushr 32) and 0xFFFF_FFFF).toUInt() // Upper 32 bits
                if (high.nz) arrayOf(high, low) else arrayOf(low)
            }.toSeries()
        }
    )

    private constructor(value: ULong) : this(
        sign = if (value.z) null else true,
        magnitude = if (value.z) emptySeries() else value.let { absValue ->
            val low = (absValue and 0xFFFF_FFFFUL).toUInt()
            val high = ((absValue shr 32) and 0xFFFF_FFFFUL).toUInt()
            if (high.nz) arrayOf(high, low) else arrayOf(low)
        }.toSeries()
    )

    private constructor(value: String) : this(
        sign = when {
            value.startsWith("-") -> false
            value.startsWith("+") || value.isEmpty() || value.first().isDigit() -> true
            else -> throw NumberFormatException("Invalid BigInt string: $value")
        },
        magnitude = value.trimStart('+', '-').trimStart('0').let { cleanedValue ->
            if (cleanedValue.isEmpty()) {
                arrayOf<UInt>().toSeries()
            } else {
                val chunkSize =
                    9 // Using 9 digits per chunk to ensure UInt compatibility (max UInt value is 4,294,967,295)
                val chunks = cleanedValue.chunked(chunkSize).reversed()
                val base = 1_000_000_000.toUInt() // 10^9, matching the chunkSize

                chunks.fold(mutableListOf<UInt>()) { acc, chunk ->
                    val chunkValue = chunk.toUInt()
                    if (chunkValue.nz) {
                        val sizeDiff = chunks.size - 1 - acc.size
                        for (i in 0 until sizeDiff) {
                            var carry = chunkValue
                            for (j in acc.indices) {
                                carry = multiplyAdd(acc, acc[j], base, carry, j)
                            }
                            if (carry.nz) {
                                acc.add(carry)
                            }
                        }
                    }
                    acc
                }.toSeries()
            }
        }
    )

    fun toUByteArray(): UByteArray {
        if (sign == null || magnitude.isEmpty()) {
            return ubyteArrayOf(0u)
        }

        val result = mutableListOf<UByte>()
        result.add(if (sign) 1u else 255u) // Add the unsigned byte for the sign

        for (x in magnitude.size - 1 downTo 0) {
            val value = magnitude[x]
            var nonZeroByteFound = false
            for (shift in 0..24 step 8) {
                val byteValue = ((value shr shift) and 0xFFu).toUByte()
                if (nonZeroByteFound || byteValue.toUInt().nz || x == magnitude.size - 1) {
                    nonZeroByteFound = true
                    result.add(byteValue)
                }
            }
        }

        return result.toUByteArray()
    }


    //comparator
    override fun compareTo(other: BigInt): Int {
        sign?.let {
            other.sign?.let { if (sign != other.sign) return if (sign) 1 else -1 } ?: return if (sign) 1 else -1
        } ?: other.sign?.let { return if (other.sign) -1 else 1 } ?: return 0

        val magnitudeComparison = magnitude.size.compareTo(other.magnitude.size)
        if (magnitudeComparison.nz) return magnitudeComparison

        for (i in magnitude.size - 1 downTo 0) {
            val magnitudeComparison = magnitude[i].compareTo(other.magnitude[i])
            if (magnitudeComparison.nz) return magnitudeComparison
        }

        return 0
    }

    /** inverts the bits (but not xor?).  Assumes that the sign of the result should be opposite of the current instance's sign */
    fun not(): BigInt = BigInt(sign?.let(Boolean::not), magnitude α UInt::inv)

    fun or(bigInt: BigInt): BigInt = BigInt(
        sign, max(magnitude.size, bigInt.magnitude.size) j {
            (magnitude.getOrNull(it) ?: 0u) or (bigInt.magnitude.getOrNull(it) ?: 0u)
        }
    )

    fun and(bigInt: BigInt): BigInt = BigInt(
        sign, max(magnitude.size, bigInt.magnitude.size) j {
            (magnitude.getOrNull(it) ?: 0u) and (bigInt.magnitude.getOrNull(it) ?: 0u)
        }
    )


    fun xor(bigInt: BigInt): BigInt = BigInt(
        sign, max(magnitude.size, bigInt.magnitude.size) j {
            (magnitude.getOrNull(it) ?: 0u) xor (bigInt.magnitude.getOrNull(it) ?: 0u)
        }
    )


    override fun toString(): String {
        val signString = if (sign == null) "" else if (sign) "+" else "-"
        return signString + magnitude.reversed().`▶`.joinToString("") { it.toString().padStart(9, '0') }
    }

    override fun toByte(): Byte = toInt().toByte()

    override fun toChar(): Char = toLong().toInt().toChar()

    override fun toDouble(): Double = toLong().toDouble()
    override fun toFloat(): Float = toLong().toFloat()
    override fun toInt(): Int = toLong().toInt()
    override fun toLong(): Long = when {
        sign == null -> 0L
        magnitude.size > 2 -> throw ArithmeticException("Overflow")
        else -> {
            val value = magnitude[0].toLong() or (magnitude[1].toLong() shl 32)
            if (sign) value else -value
        }
    }

    override fun toShort(): Short = toInt().toShort()

    fun shl(i: Int): BigInt {
        if (i < 0) throw IllegalArgumentException("Shift amount must be positive")
        if (sign == null) return this

        val shift = i % 32
        val shiftAmount = i / 32

        val result = mutableListOf<UInt>()
        if (shiftAmount > 0) repeat(shiftAmount) { result.add(0u) }

        var carry = 0u
        for (x in magnitude) {
            val shifted = (x shl shift) or carry
            result.add(shifted)
            carry = x shr (32 - shift)
        }
        if (carry.nz) result.add(carry)

        return BigInt(sign, result.toUIntArray().toSeries())

    }

    fun shr(i: Int): BigInt {
        if (i < 0) throw IllegalArgumentException("Shift amount must be positive")
        if (sign == null) return this

        val shift = i % 32
        val shiftAmount = i / 32

        val result = mutableListOf<UInt>()
        if (shiftAmount > 0) repeat(shiftAmount) { result.add(0u) }

        var carry = 0u
        for (x in magnitude.reversed()) {
            val shifted = (x shr shift) or carry
            result.add(shifted)
            carry = x shl (32 - shift)
        }
        if (carry.nz) result.add(carry)

        return BigInt(sign, result.reversed().toUIntArray().toSeries())
    }

    fun add(addend: BigInt) = when {
        sign == null -> addend
        addend.sign == null -> this
        sign == addend.sign -> BigInt(sign, processMagnitudes(addend, true))
        else -> BigInt(sign, processMagnitudes(addend, false))
    }

    fun subtract(subtrahend: BigInt): BigInt {
        val negatedSubtrahend = BigInt(subtrahend.sign?.not(), subtrahend.magnitude)
        return add(negatedSubtrahend)
    }

    private fun processMagnitudes(addend: BigInt, addition: Boolean): Series<UInt> {
        val m1 = magnitude.reversed()
        val m2 = addend.magnitude.reversed()

        val result = mutableListOf<UInt>()
        var carry = 0uL
        val maxSize = max(m1.size, m2.size)

        for (i in 0 until maxSize) {
            val x = m1.getOrNull(i) ?: 0u
            val y = m2.getOrNull(i) ?: 0u
            val sum = if (addition) x + y + carry else x - y - carry
            result.add(sum.toUInt())
            carry = sum shr 32
        }

        if (carry != 0uL) result.add(carry.toUInt())
        return result.toUIntArray().toSeries()
    }


    companion object {
        /** Creates a [BigInt] from a [UByteArray] in big-endian byte order. The sign bit is encoded as the first byte 1,0,-1; buyer beware. */
        @ExperimentalUnsignedTypes
        fun fromUByteArray(uByteArray: UByteArray): BigInt {
            if (uByteArray.isEmpty()) {
                throw IllegalArgumentException("UByte array must not be empty")
            }

            val signUByte = uByteArray[0]
            if (signUByte.toUInt() == 0u) return BigInt(0)

            val sign = signUByte.toUInt() == 1u
            val magnitude = mutableListOf<UInt>()

            var value = 0u
            var shift = 0
            for (i in (uByteArray.size - 1) downTo 1) {
                val byteValue = uByteArray[i].toUInt()
                value = value or (byteValue shl shift)
                shift += 8

                if (shift == 32) {
                    magnitude.add(value)
                    value = 0u
                    shift = 0
                }
            }
            if (value.nz) {
                magnitude.add(value)
            }
            return BigInt(sign, magnitude.toUIntArray().toSeries())
        }

        const val BASE = 1L shl 32 //   Base value of 2^32
        val ZERO = BigInt(null, emptySeries())
        val ONE = BigInt(1)
        val TWO = BigInt(2UL)
        val TEN = BigInt(10U)

        fun multiplyAdd(accumulator: MutableList<UInt>, a: UInt, b: UInt, carryIn: UInt, index: Int): UInt {
            val product = a.toULong() * b.toULong()
            val high = (product shr 32).toUInt()
            val low = (product and 0xFFFF_FFFFUL).toUInt()
            val sum = low.toULong() + carryIn.toULong() + accumulator[index].toULong()
            accumulator[index] = sum.toUInt()
            return (sum shr 32).toUInt() + high
        }

        operator fun <Primitive : Comparable<Primitive>> invoke(primitive: Primitive): BigInt = when (primitive) {
            is Byte -> invoke(primitive.toULong())
            is Short -> invoke(primitive.toULong())
            is Int -> BigInt(primitive)
            is UByte -> invoke(primitive.toULong())
            is UShort -> invoke(primitive.toULong())
            is UInt -> invoke(primitive.toULong())
            else -> throw IllegalArgumentException("Unsupported type ${primitive::class}")
        }
    }
}


