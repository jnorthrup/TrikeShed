package borg.trikeshed.num

import borg.trikeshed.isam.meta.PlatformCodec.Companion.currentPlatformCodec.readUInt
import borg.trikeshed.lib.CZero.nz
import borg.trikeshed.lib.CZero.z
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.emptySerie
import borg.trikeshed.lib.forEach
import borg.trikeshed.lib.get
import borg.trikeshed.lib.getOrNull
import borg.trikeshed.lib.isEmpty
import borg.trikeshed.lib.iterator
import borg.trikeshed.lib.j
import borg.trikeshed.lib.reversed
import borg.trikeshed.lib.size
import borg.trikeshed.lib.toSeries
import borg.trikeshed.lib.α
import borg.trikeshed.lib.`▶`
import kotlin.math.absoluteValue
import kotlin.math.max


/** made immutable by series. */
class BigInt private constructor(private val sign: Boolean?, private val magnitude: Series<UInt>) : Number(),
    Comparable<BigInt> {


    // Constructor from Long
    constructor(value: Long) : this(
        sign = if (value.z) null else value > 0,
        magnitude = if (value.z) emptySeries() else {
            value.absoluteValue.let { absValue ->
                val low = (absValue and 0xFFFF_FFFFL).toUInt() // Lower 32 bits
                val high = ((absValue ushr 32) and 0xFFFF_FFFFL).toUInt() // Upper 32 bits
                if (high.nz) arrayOf(high, low) else arrayOf(low)
            }.toSeries()
        }
    )

    private constructor(
        value: ULong,
        /**This parameter is not used within the constructor but
         *  ensures that the method signatures for ULong and Long
         *  constructors do not clash when compiled in Java.*/
        javaCannotCompileULongAndLongMethodsThatConflict: String = ""
    ) : this(
        sign = if (value.z) null else true,
        magnitude = if (value.z) emptySeries() else value.let { absValue ->
            val low = (absValue and 0xFFFF_FFFFUL).toUInt()
            val high = ((absValue shr 32) and 0xFFFF_FFFFUL).toUInt()
            if (high.nz) arrayOf(high, low) else arrayOf(low)
        }.toSeries()
    )

    constructor(value: String) : this(
        sign = if (value.isEmpty()) null else value[0] != '-',
        magnitude = if (value.isEmpty()) emptySeries() else {
            val magnitude = mutableListOf<UInt>()
            var i = if (value[0] == '-') 1 else 0
            while (i < value.length) {
                var chunk = 0u
                for (j in 0..8) {
                    if (i < value.length) {
                        chunk = chunk * 10u + (value[i] - '0').toUInt()
                        i++
                    }
                }
                magnitude.add(chunk)
            }
            magnitude.toSeries()
        }
    )


    fun toUByteArray(): UByteArray {

//        0b000001 as network endian byte[] looks like: [0, 0, 0, 1]
//        0b000001 as little endian byte[] looks like: [1, 0, 0, 0]
//        0b000001 as big endian byte[] looks like: [0, 0, 0, 1]


        if (sign == null || magnitude.isEmpty()) {
            return ubyteArrayOf(0u)
        }

        val result = mutableListOf<UByte>()
        result.add(if (sign) 1u else 255u) // Add the unsigned byte for the sign
        //write the int's in network endian order (big endian) (most significant byte first)

        magnitude.forEach {
            result.add((it shr 24).toUByte())
            result.add((it shr 16).toUByte())
            result.add((it shr 8).toUByte())
            result.add(it.toUByte())
        }
        return result.toUByteArray()
    }

    /**
    from network-endian Ubyte[]
    the length must be a multiple of 4+1 except for 0
    the first byte is the sign
    the rest are the magnitude in network-endian order (big endian) (most significant byte first)

    when the length is not a (multiple of 4)+1, treat the missing bytes as leading zeros in the first magnitude chunk
     */
    @OptIn(ExperimentalUnsignedTypes::class)
    constructor(value: UByteArray) : this(
        sign = value.takeUnless { it.isEmpty() }?.let { it[0].dec().z },
        magnitude = value.takeUnless(UByteArray::isEmpty)?.let { v1 ->
            var uByteArray = v1.drop(1).toUByteArray()
            if (uByteArray.size % 4 != 0) uByteArray =
                UByteArray(uByteArray.size + (4 - uByteArray.size % 4)) + uByteArray
            val res1 = UIntArray(uByteArray.size / 4)
            for (i in uByteArray.indices step 4)
                readUInt(
                    uByteArray.sliceArray(i until i + 4).asByteArray()
                ).let { res1[i / 4] = it }
            res1.toSeries()
        } ?: emptySeries()
    )

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
    operator fun not(): BigInt = BigInt(sign?.let(Boolean::not), magnitude α UInt::inv)

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

    @Deprecated(
        "Direct conversion to Char is deprecated. Use toInt().toChar() or Char constructor instead.\nIf you override toChar() function in your Number inheritor, it's recommended to gradually deprecate the overriding function and then remove it.\nSee https://youtrack.jetbrains.com/issue/KT-46465 for details about the migration",
        replaceWith = ReplaceWith("this.toInt().toChar()")
    )
    override fun toChar(): Char = toInt().toChar()
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

    operator fun plus(addend: BigInt): BigInt = when {
        sign == null -> addend
        addend.sign == null -> this
        sign == addend.sign -> BigInt(sign, processMagnitudes(addend, true))
        else -> BigInt(sign, processMagnitudes(addend, false))
    }

    operator fun minus(subtrahend: BigInt): BigInt {
        val negatedSubtrahend = BigInt(subtrahend.sign?.not(), subtrahend.magnitude)
        return plus(negatedSubtrahend)
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
            is Byte -> invoke(primitive.toLong())
            is Short -> invoke(primitive.toLong())
            is Int -> BigInt(primitive.toLong())
//            is Long -> BigInt(primitive)
            is UByte -> invoke(primitive.toULong())
            is UShort -> invoke(primitive.toULong())
            is UInt -> invoke(primitive.toULong())
//            is String -> invoke(primitive.toLong())
            else -> throw IllegalArgumentException("Unsupported type ${primitive::class}")
        }
    }
}


