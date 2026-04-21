package borg.trikeshed.dht.id

import borg.trikeshed.lib.assert
import borg.trikeshed.num.BigInt
import borg.trikeshed.platform.bitops.BitOps
import borg.trikeshed.platform.bitops.BitOps.Companion.minOps
import gk.kademlia.id.impl.*
import borg.trikeshed.dht.net.NetMask
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
    val netmask: borg.trikeshed.dht.net.NetMask<Primitive>
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
