package gk.kademlia.id

import borg.trikeshed.lib.assert
import borg.trikeshed.num.BigInt
import gk.kademlia.bitops.BitOps
import gk.kademlia.bitops.BitOps.Companion.minOps
import gk.kademlia.id.impl.*
import gk.kademlia.net.NetMask
import kotlin.random.Random

//import java.math.BigInteger
//import java.math.BigInteger
//import java.util.concurrent.ThreadLocalRandom
//import kotlin.random as KotlinRandom

/**
 * Network Unique ID
 *
 * network IDs within larger networks within larger networks
 *
 */

interface NUID<Primitive : Comparable<Primitive>> {
    var id: Primitive?
    val netmask: NetMask<Primitive>
    val ops: BitOps<Primitive>

    fun random(distance: Int? = null, centroid: Primitive = id!!) = ops.run {
        Random/*
        ThreadLocalRandom.current().asKotlinRandom()*/.run {
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

    /**
     * whatever the definition of a Riac Bitclock means, this one means
     *
     * from _a[3,4,5]
     * to   1<<3+1<<4+1<<5
     */
    fun fromBitClock(vararg clock: Int): Primitive = ops.run {
        clock.fold(xor(one, one)) { acc, i ->
            assert(netmask.bits > i)
            plus(acc, shl(one, i))
        }
    }

    companion object {
        /**
         * minimum bitops types for the intended bitcount of NUID
         */
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

                else -> object : BigIntegerNUID(minOps(size).one as BigInt) {
                    override val netmask: NetMask<BigInt>
                        get() = object : NetMask<BigInt> {
                            override val bits: Int
                                get() = size.toInt()
                        }
                }
            }
    }
}