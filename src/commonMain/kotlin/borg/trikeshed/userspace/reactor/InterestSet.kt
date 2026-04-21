package borg.trikeshed.userspace.reactor

import borg.trikeshed.context.BitMasked

/**
 * I/O interest bitmask — ported from literbike reactor/operation.rs.
 *
 * Each state uses its ordinal for bitshifted comparison and alignment with semantic methods.
 */
enum class Interest : BitMasked {
    READ,
    WRITE,
    ACCEPT,
    CONNECT,
    ERROR;

    companion object {
        fun fromOperation(op: IOOperation): Interest = when (op) {
            IOOperation.Read -> READ
            IOOperation.Write -> WRITE
            IOOperation.Accept -> ACCEPT
            IOOperation.Connect -> CONNECT
            IOOperation.Error -> ERROR
        }

        fun fromMask(mask: UInt): Set<Interest> =
            entries.filter { (mask and it.mask) != 0u }.toSet()

        fun toMask(interests: Iterable<Interest>): UInt =
            interests.fold(0u) { acc, interest -> acc or interest.mask }
    }
}

/**
 * Maps to a single interest flag.
 */
enum class IOOperation {
    Read, Write, Accept, Connect, Error
}
