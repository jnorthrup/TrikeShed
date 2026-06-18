@file:OptIn(ExperimentalStdlibApi::class, ExperimentalUnsignedTypes::class)

package gk.kademlia.net

import gk.kademlia.bitops.BitOps

interface NetMask<P : Comparable<P>> {

    /**
     * Kademlia specifies a bit "count"
     */
    val bits: Int

    /**math functions*/
    @Suppress("UNCHECKED_CAST")
    val ops: BitOps<P>
        get() = BitOps.minOps(bits) as BitOps<P>

    /**one function
     * satu=indonesian 1
     */
    val satu: P get() = ops.one

    /**
     * IP networks have NetMasks.  netmasks with kademlia don't change the routing factors.
     * by default the mask is all open
     */
    val mask: P
        get() = ops.run {
            var acc = xor(satu, satu)
            repeat(bits) { x ->
                acc = shl(satu, x)
                acc = xor(satu, acc)
            }
            acc
        }

    /**
     * reports the xor bits count betweeb alice and bob
     */
    fun distance(alice: P, bob: P): Int = ops.run {
        val xor1 = xor(alice, bob)
        (0 until bits).fold(0) { acc, i ->
            if (one == and(one, shr(xor1, i)))
                acc.inc() else acc
        }.toInt()
    }

    companion object {
        /**
         * for federating data, you want an unbounded DHT full of volunteers.
         */
        object CoolSz : NetMask<ULong> {
            override val bits: Int = 64
        }

        /**
         * this node count probably out lives most single grenade detonations.
         */
        object WarmSz : NetMask<Byte> {
            override val bits: Int = 7
        }


        /**
         * for when n=3 is handy, spawn a new namespace with the first/best 3 nodes to volunteer.
         */
        object HotSz : NetMask<Byte> {
            override val bits: Int = 2
        }
    }
}