package borg.trikeshed.kademlia.id
import borg.trikeshed.lib.assert
import borg.trikeshed.num.BigInt
import borg.trikeshed.kademlia.bitops.BitOps
import borg.trikeshed.kademlia.bitops.BitOps.Companion.minOps
import borg.trikeshed.kademlia.id.impl.*
import borg.trikeshed.kademlia.net.NetMask
import kotlin.random.Random

interface NUID<Primitive : Comparable<Primitive>> {
    var id: Primitive?
    val netmask: NetMask<Primitive>
    val ops: BitOps<Primitive>

    fun random(distance: Int? = null, centroid: Primitive = id!!) = ops.run {
        var accum = centroid
        val uBits = netmask.bits
        (distance?.takeIf { it <= uBits } ?: nextInt(uBits)).let { dist ->
            linkedSetOf<Int>().apply {
                while (size < dist) add(nextInt(uBits))
            }
        }.sorted().forEach {
            accum = xor(accum, shl(one, it))
        }
        accum
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

    companion object {
        fun minNUID(size: Int): NUID<*> =
            when (size) {
                in Int.MIN_VALUE..7 -> object : ByteNUID(minOps(size).one as Byte) {
                    override val netmask: NetMask<Byte>
                        get() = object : NetMask<Byte> {
                            override val bits: Int
                                get() = size.toInt()
                        }
                }
                8 -> object : UByteNUID(minOps(size).one as UByte) {
                    override val netmask: NetMask<UByte>
                        get() = object : NetMask<UByte> {
                            override val bits: Int
                                get() = size.toInt()
                        }
                }
                in 9..15 -> object : ShortNUID(minOps(size).one as Short) {
                    override val netmask: NetMask<Short>
                        get() = object : NetMask<Short> {
                            override val bits: Int
                                get() = size.toInt()
                        }
                }
                16 -> object : UShortNUID(minOps(size).one as UShort) {
                    override val netmask: NetMask<UShort>
                        get() = object : NetMask<UShort> {
                            override val bits: Int
                                get() = size.toInt()
                        }
                }
                in 17..31 -> object : IntNUID(minOps(size).one as Int) {
                    override val netmask: NetMask<Int>
                        get() = object : NetMask<Int> {
                            override val bits: Int
                                get() = size.toInt()
                        }
                }
                32 -> object : UIntNUID(minOps(size).one as UInt) {
                    override val netmask: NetMask<UInt>
                        get() = object : NetMask<UInt> {
                            override val bits: Int
                                get() = size.toInt()
                        }
                }
                in 33..63 -> object : LongNUID(minOps(size).one as Long) {
                    override val netmask: NetMask<Long>
                        get() = object : NetMask<Long> {
                            override val bits: Int
                                get() = size.toInt()
                        }
                }
                64 -> object : ULongNUID(minOps(size).one as ULong) {
                    override val netmask: NetMask<ULong>
                        get() = object : NetMask<ULong> {
                            override val bits: Int
                                get() = size.toInt()
                        }
                }
                else -> object : BigIntegerNUID((minOps(size) as BitOps<BigInt>).one) {
                    override val netmask: NetMask<BigInt>
                        get() = object : NetMask<BigInt> {
                            override val bits: Int
                                get() = size.toInt()
                        }
                }
            }
    }
}