package borg.trikeshed.dht.net

import borg.trikeshed.dht.id.BitOps

interface NetMask<P : Comparable<P>> {

    val bits: Int

    @Suppress("UNCHECKED_CAST")
    val ops: BitOps<P>
        get() = BitOps.minOps(bits) as BitOps<P>

    val satu: P get() = ops.one

    val mask: P
        get() = ops.run {
            var acc = xor(satu, satu)
            repeat(bits) { x ->
                acc = shl(satu, x)
                acc = xor(satu, acc)
            }
            acc
        }

    fun distance(alice: P, bob: P): Int = ops.run {
        val xor1 = xor(alice, bob)
        (0 until bits).fold(0) { acc, i ->
            if (one == and(one, shr(xor1, i)))
                acc.inc() else acc
        }
    }

    companion object {
        object CoolSz : NetMask<ULong> {
            override val bits: Int = 64
        }

        object WarmSz : NetMask<Byte> {
            override val bits: Int = 7
        }

        object HotSz : NetMask<Byte> {
            override val bits: Int = 2
        }
    }
}