package borg.trikeshed.userspace.reactor

/**
 * I/O interest bitmask — ported from literbike reactor/operation.rs.
 *
 * Mirrors epoll/poll interest flags as a typesafe inline value class.
 */
class InterestSet(val bits: Int) {

    companion object {
        val NONE: InterestSet    = InterestSet(0)
        val READ: InterestSet    = InterestSet(1 shl 0)
        val WRITE: InterestSet   = InterestSet(1 shl 1)
        val ACCEPT: InterestSet  = InterestSet(1 shl 2)
        val CONNECT: InterestSet = InterestSet(1 shl 3)
        val ERROR: InterestSet   = InterestSet(1 shl 4)

        fun fromOperation(op: IOOperation): InterestSet = when (op) {
            IOOperation.Read    -> READ
            IOOperation.Write   -> WRITE
            IOOperation.Accept  -> ACCEPT
            IOOperation.Connect -> CONNECT
            IOOperation.Error   -> ERROR
        }
    }

    fun isEmpty(): Boolean = bits == 0

    fun contains(other: InterestSet): Boolean = (bits and other.bits) == other.bits

    fun intersects(other: InterestSet): Boolean = (bits and other.bits) != 0

    operator fun plus(other: InterestSet): InterestSet = InterestSet(bits or other.bits)

    operator fun minus(other: InterestSet): InterestSet = InterestSet(bits and other.bits.inv())

    infix fun and(other: InterestSet): InterestSet = InterestSet(bits and other.bits)

    infix fun or(other: InterestSet): InterestSet = InterestSet(bits or other.bits)

    override fun toString(): String = buildString {
        if (isEmpty()) {
            append("InterestSet(NONE)")
            return@buildString
        }
        append("InterestSet(")
        val parts = mutableListOf<String>()
        if (contains(READ))    parts += "READ"
        if (contains(WRITE))   parts += "WRITE"
        if (contains(ACCEPT))  parts += "ACCEPT"
        if (contains(CONNECT)) parts += "CONNECT"
        if (contains(ERROR))   parts += "ERROR"
        append(parts.joinToString("|"))
        append(")")
    }
}

/**
 * Maps to a single interest flag.
 */
enum class IOOperation {
    Read, Write, Accept, Connect, Error
}
