package borg.trikeshed.dht.id

import kotlin.random.Random

/**
 * Network Unique ID
 *
 * network IDs within larger networks within larger networks
 */

interface NUID<Primitive : Comparable<Primitive>> {
    var id: Primitive?
    val netmask: NetMask<Primitive>
    val ops: BitOps<Primitive>

    fun random(distance: Int? = null, centroid: Primitive = id!!) = ops.run {
        Random.run {
            var accum = centroid
            val uBits = netmask.bits
            (distance?.takeIf { it <= uBits } ?: nextInt(uBits)).let { distance ->
                linkedSetOf<Int>().apply {
                    while (size < distance) add(nextInt(uBits))
                }
            }.sorted().forEach {
                accum = xor(accum, shl(one, it))
            }
            accum
        }
    }

    val capacity: Primitive get() = with(ops) { xor(netmask.mask, minus(shl(one, netmask.bits), one)) }
    fun assign(it: Primitive) {
        if (id != null)
            id.run { throw RuntimeException("GUID assigned twice for $id") }
        id = it
    }

    fun fromBitClock(vararg clock: Int): Primitive = ops.run {
        clock.fold(xor(one, one)) { acc, i ->
            require(netmask.bits > i)
            plus(acc, shl(one, i))
        }
    }

    companion object {
        fun minNUID(size: Int): NUID<*> =
            when (size) {
                in Int.MIN_VALUE..7 -> object : ByteNUID(minOps(size).one as Byte) {
                    override val netmask: NetMask<Byte>
                        get() = object : NetMask<Byte> {
                            override val bits: Int get() = size
                        }
                }

                8 -> object : UByteNUID(minOps(size).one as UByte) {
                    override val netmask: NetMask<UByte>
                        get() = object : NetMask<UByte> {
                            override val bits: Int get() = size
                        }
                }

                in 9..15 -> object : ShortNUID(minOps(size).one as Short) {
                    override val netmask: NetMask<Short>
                        get() = object : NetMask<Short> {
                            override val bits: Int get() = size
                        }
                }

                16 -> object : UShortNUID(minOps(size).one as UShort) {
                    override val netmask: NetMask<UShort>
                        get() = object : NetMask<UShort> {
                            override val bits: Int get() = size
                        }
                }

                in 17..31 -> object : IntNUID(minOps(size).one as Int) {
                    override val netmask: NetMask<Int>
                        get() = object : NetMask<Int> {
                            override val bits: Int get() = size
                        }
                }

                32 -> object : UIntNUID(minOps(size).one as UInt) {
                    override val netmask: NetMask<UInt>
                        get() = object : NetMask<UInt> {
                            override val bits: Int get() = size
                        }
                }

                in 33..63 -> object : LongNUID(minOps(size).one as Long) {
                    override val netmask: NetMask<Long>
                        get() = object : NetMask<Long> {
                            override val bits: Int get() = size
                        }
                }

                64 -> object : ULongNUID(minOps(size).one as ULong) {
                    override val netmask: NetMask<ULong>
                        get() = object : NetMask<ULong> {
                            override val bits: Int get() = size
                        }
                }

                else -> object : BigIntegerNUID(minOps(size).one as BigInt) {
                    override val netmask: NetMask<BigInt>
                        get() = object : NetMask<BigInt> {
                            override val bits: Int get() = size
                        }
                }
            }
    }
}

@OptIn(ExperimentalUnsignedTypes::class)
interface BitOps<T> {
    val one: T
    val zero: T
    fun plus(a: T, b: T): T
    fun minus(a: T, b: T): T
    fun xor(a: T, b: T): T
    fun and(a: T, b: T): T
    fun or(a: T, b: T): T
    fun shl(a: T, bits: Int): T
    fun shr(a: T, bits: Int): T
    fun run<T>(block: BitOps<T>.() -> T): T = block(this)
}

object BitOps {
    fun minOps(size: Int): BitOps<*> = when (size) {
        in Int.MIN_VALUE..7  -> ByteBitOps
        8                    -> UByteBitOps
        in 9..15             -> ShortBitOps
        16                   -> UShortBitOps
        in 17..31            -> IntBitOps
        32                   -> UIntBitOps
        in 33..63            -> LongBitOps
        64                   -> ULongBitOps
        else                 -> BigIntegerBitOps
    }
}

object ByteBitOps : BitOps<Byte> {
    override val one: Byte = 1
    override val zero: Byte = 0
    override fun plus(a: Byte, b: Byte) = (a + b).toByte()
    override fun minus(a: Byte, b: Byte) = (a - b).toByte()
    override fun xor(a: Byte, b: Byte) = (a xor b).toByte()
    override fun and(a: Byte, b: Byte) = (a and b).toByte()
    override fun or(a: Byte, b: Byte) = (a or b).toByte()
    override fun shl(a: Byte, bits: Int) = (a shl bits).toByte()
    override fun shr(a: Byte, bits: Int) = (a.shr(bits)).toByte()
}

@OptIn(ExperimentalUnsignedTypes::class)
object UByteBitOps : BitOps<UByte> {
    override val one: UByte = 1.toUByte()
    override val zero: UByte = 0.toUByte()
    override fun plus(a: UByte, b: UByte) = a + b
    override fun minus(a: UByte, b: UByte) = a - b
    override fun xor(a: UByte, b: UByte) = a xor b
    override fun and(a: UByte, b: UByte) = a and b
    override fun or(a: UByte, b: UByte) = a or b
    override fun shl(a: UByte, bits: Int) = a shl bits
    override fun shr(a: UByte, bits: Int) = a ushr bits
}

object ShortBitOps : BitOps<Short> {
    override val one: Short = 1
    override val zero: Short = 0
    override fun plus(a: Short, b: Short) = (a + b).toShort()
    override fun minus(a: Short, b: Short) = (a - b).toShort()
    override fun xor(a: Short, b: Short) = (a xor b).toShort()
    override fun and(a: Short, b: Short) = (a and b).toShort()
    override fun or(a: Short, b: Short) = (a or b).toShort()
    override fun shl(a: Short, bits: Int) = (a shl bits).toShort()
    override fun shr(a: Short, bits: Int) = (a.shr(bits)).toShort()
}

@OptIn(ExperimentalUnsignedTypes::class)
object UShortBitOps : BitOps<UShort> {
    override val one: UShort = 1.toUShort()
    override val zero: UShort = 0.toUShort()
    override fun plus(a: UShort, b: UShort) = a + b
    override fun minus(a: UShort, b: UShort) = a - b
    override fun xor(a: UShort, b: UShort) = a xor b
    override fun and(a: UShort, b: UShort) = a and b
    override fun or(a: UShort, b: UShort) = a or b
    override fun shl(a: UShort, bits: Int) = a shl bits
    override fun shr(a: UShort, bits: Int) = a ushr bits
}

object IntBitOps : BitOps<Int> {
    override val one: Int = 1
    override val zero: Int = 0
    override fun plus(a: Int, b: Int) = a + b
    override fun minus(a: Int, b: Int) = a - b
    override fun xor(a: Int, b: Int) = a xor b
    override fun and(a: Int, b: Int) = a and b
    override fun or(a: Int, b: Int) = a or b
    override fun shl(a: Int, bits: Int) = a shl bits
    override fun shr(a: Int, bits: Int) = a.shr(bits)
}

@OptIn(ExperimentalUnsignedTypes::class)
object UIntBitOps : BitOps<UInt> {
    override val one: UInt = 1u
    override val zero: UInt = 0u
    override fun plus(a: UInt, b: UInt) = a + b
    override fun minus(a: UInt, b: UInt) = a - b
    override fun xor(a: UInt, b: UInt) = a xor b
    override fun and(a: UInt, b: UInt) = a and b
    override fun or(a: UInt, b: UInt) = a or b
    override fun shl(a: UInt, bits: Int) = a shl bits
    override fun shr(a: UInt, bits: Int) = a ushr bits
}

object LongBitOps : BitOps<Long> {
    override val one: Long = 1
    override val zero: Long = 0
    override fun plus(a: Long, b: Long) = a + b
    override fun minus(a: Long, b: Long) = a - b
    override fun xor(a: Long, b: Long) = a xor b
    override fun and(a: Long, b: Long) = a and b
    override fun or(a: Long, b: Long) = a or b
    override fun shl(a: Long, bits: Int) = a shl bits
    override fun shr(a: Long, bits: Int) = a.shr(bits)
}

@OptIn(ExperimentalUnsignedTypes::class)
object ULongBitOps : BitOps<ULong> {
    override val one: ULong = 1uL
    override val zero: ULong = 0uL
    override fun plus(a: ULong, b: ULong) = a + b
    override fun minus(a: ULong, b: ULong) = a - b
    override fun xor(a: ULong, b: ULong) = a xor b
    override fun and(a: ULong, b: ULong) = a and b
    override fun or(a: ULong, b: ULong) = a or b
    override fun shl(a: ULong, bits: Int) = a shl bits
    override fun shr(a: ULong, bits: Int) = a ushr bits
}

import java.math.BigInteger as JBigInteger

object BigIntegerBitOps : BitOps<BigInt> {
    override val one: BigInt = BigInt.ONE
    override val zero: BigInt = BigInt.ZERO
    override fun plus(a: BigInt, b: BigInt) = a + b
    override fun minus(a: BigInt, b: BigInt) = a - b
    override fun xor(a: BigInt, b: BigInt) = a xor b
    override fun and(a: BigInt, b: BigInt) = a and b
    override fun or(a: BigInt, b: BigInt) = a or b
    override fun shl(a: BigInt, bits: Int) = a.shiftLeft(bits)
    override fun shr(a: BigInt, bits: Int) = a.shiftRight(bits)
}

typealias BigInt = JBigInteger

// ─── Concrete NUID implementations ───

@OptIn(ExperimentalUnsignedTypes::class)
abstract class ByteNUID(override val ops: BitOps<Byte>) : NUID<Byte> {
    override var id: Byte? = null
}

@OptIn(ExperimentalUnsignedTypes::class)
abstract class UByteNUID(override val ops: BitOps<UByte>) : NUID<UByte> {
    override var id: UByte? = null
}

abstract class ShortNUID(override val ops: BitOps<Short>) : NUID<Short> {
    override var id: Short? = null
}

@OptIn(ExperimentalUnsignedTypes::class)
abstract class UShortNUID(override val ops: BitOps<UShort>) : NUID<UShort> {
    override var id: UShort? = null
}

abstract class IntNUID(override val ops: BitOps<Int>) : NUID<Int> {
    override var id: Int? = null
}

@OptIn(ExperimentalUnsignedTypes::class)
abstract class UIntNUID(override val ops: BitOps<UInt>) : NUID<UInt> {
    override var id: UInt? = null
}

abstract class LongNUID(override val ops: BitOps<Long>) : NUID<Long> {
    override var id: Long? = null
}

@OptIn(ExperimentalUnsignedTypes::class)
abstract class ULongNUID(override val ops: BitOps<ULong>) : NUID<ULong> {
    override var id: ULong? = null
}

abstract class BigIntegerNUID(override val ops: BitOps<BigInt>) : NUID<BigInt> {
    override var id: BigInt? = null
}