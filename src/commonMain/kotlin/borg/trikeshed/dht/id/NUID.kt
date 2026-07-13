package borg.trikeshed.dht.id

import borg.trikeshed.lib.assert
import borg.trikeshed.num.BigInt
import borg.trikeshed.platform.bitops.BitOps
import borg.trikeshed.platform.bitops.BitOps.Companion.minOps
import borg.trikeshed.dht.id.impl.*
import borg.trikeshed.dht.net.NetMask
import kotlin.random.Random

interface NUID<Primitive : Comparable<Primitive>> {
    var id: Primitive?
    val netmask: borg.trikeshed.dht.net.NetMask<Primitive>
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
            assert(netmask.bits > i)
            plus(acc, shl(one, i))
        }
    }

    fun toBytes(): ByteArray {
        val number = ops.toNumber(id ?: ops.xor(ops.one, ops.one))
        if (number is BigInt) {
            val bytes = number.toByteArray()
            if (bytes.size < 9) {
                val padded = ByteArray(9)
                bytes.copyInto(padded, 9 - bytes.size)
                return padded
            }
            return bytes
        } else {
            val longVal = number.toLong()
            return ByteArray(8) { i -> (longVal shr (i * 8)).toByte() }
        }
    }

    fun fromBytes(bytes: ByteArray) {
        if (ops.one is BigInt) {
            id = BigInt(bytes) as Primitive
            return
        }
        var longVal = 0L
        for (i in 0 until minOf(bytes.size, 8)) {
            longVal = longVal or ((bytes[i].toLong() and 0xFF) shl (i * 8))
        }
        val primitive = when (ops.one) {
            is Byte -> longVal.toByte() as Primitive
            is UByte -> longVal.toUByte() as Primitive
            is Short -> longVal.toShort() as Primitive
            is UShort -> longVal.toUShort() as Primitive
            is Int -> longVal.toInt() as Primitive
            is UInt -> longVal.toUInt() as Primitive
            is Long -> longVal as Primitive
            is ULong -> longVal.toULong() as Primitive
            else -> BigInt(longVal.toString()) as Primitive
        }
        id = primitive
    }

    companion object {
        fun fromBytes(bytes: ByteArray): NUID<Byte> {
            val nuid = minNUID(8) as NUID<Byte>
            nuid.fromBytes(bytes)
            return nuid
        }

        fun minNUID(size: Int): NUID<*> =
            when (size) {
                in Int.MIN_VALUE..7 -> object : borg.trikeshed.dht.id.impl.ByteNUID(minOps(size).one as Byte) {
                    override val netmask: borg.trikeshed.dht.net.NetMask<Byte>
                        get() = object : borg.trikeshed.dht.net.NetMask<Byte> {
                            override val bits: Int
                                get() = size
                        }
                }

                8 -> object : borg.trikeshed.dht.id.impl.UByteNUID(minOps(size).one as UByte) {
                    override val netmask: borg.trikeshed.dht.net.NetMask<UByte>
                        get() = object : borg.trikeshed.dht.net.NetMask<UByte> {
                            override val bits: Int
                                get() = size
                        }
                }

                in 9..15 -> object : borg.trikeshed.dht.id.impl.ShortNUID(minOps(size).one as Short) {
                    override val netmask: borg.trikeshed.dht.net.NetMask<Short>
                        get() = object : borg.trikeshed.dht.net.NetMask<Short> {
                            override val bits: Int
                                get() = size
                        }
                }

                16 -> object : borg.trikeshed.dht.id.impl.UShortNUID(minOps(size).one as UShort) {
                    override val netmask: borg.trikeshed.dht.net.NetMask<UShort>
                        get() = object : borg.trikeshed.dht.net.NetMask<UShort> {
                            override val bits: Int
                                get() = size
                        }
                }

                in 17..31 -> object : borg.trikeshed.dht.id.impl.IntNUID(minOps(size).one as Int) {
                    override val netmask: borg.trikeshed.dht.net.NetMask<Int>
                        get() = object : borg.trikeshed.dht.net.NetMask<Int> {
                            override val bits: Int
                                get() = size
                        }
                }

                32 -> object : borg.trikeshed.dht.id.impl.UIntNUID(minOps(size).one as UInt) {
                    override val netmask: borg.trikeshed.dht.net.NetMask<UInt>
                        get() = object : borg.trikeshed.dht.net.NetMask<UInt> {
                            override val bits: Int
                                get() = size
                        }
                }

                in 33..63 -> object : borg.trikeshed.dht.id.impl.LongNUID(minOps(size).one as Long) {
                    override val netmask: borg.trikeshed.dht.net.NetMask<Long>
                        get() = object : borg.trikeshed.dht.net.NetMask<Long> {
                            override val bits: Int
                                get() = size
                        }
                }

                64 -> object : borg.trikeshed.dht.id.impl.ULongNUID(minOps(size).one as ULong) {
                    override val netmask: borg.trikeshed.dht.net.NetMask<ULong>
                        get() = object : borg.trikeshed.dht.net.NetMask<ULong> {
                            override val bits: Int
                                get() = size
                        }
                }

                else -> object : borg.trikeshed.dht.id.impl.BigIntegerNUID(minOps(size).one as BigInt) {
                    override val netmask: borg.trikeshed.dht.net.NetMask<BigInt>
                        get() = object : borg.trikeshed.dht.net.NetMask<BigInt> {
                            override val bits: Int
                                get() = size
                        }
                }
            }
    }
}
