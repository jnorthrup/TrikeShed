/**
 * Port of /Users/jim/work/literbike/src/reactor/operation.rs
 *
 * I/O operation and interest flag semantics for the reactor.
 */
package borg.literbike.reactor

import kotlin.jvm.JvmInline

/**
 * Mirrors Rust enum: `pub enum IOOperation`
 */
enum class IOOperation {
    Read,
    Write,
    Accept,
    Connect,
    Error,
}

/**
 * Mirrors Rust struct: `pub struct InterestSet(u8)`
 *
 * Bitwise flag set with constants for READ, WRITE, ACCEPT, CONNECT, ERROR.
 * Implements BitOr, BitOrAssign, BitAnd equivalents from Rust.
 */
@JvmInline
value class InterestSet(private val bits: UByte) {

    companion object {
        val NONE = InterestSet(0u.toUByte())
        val READ = InterestSet((1u shl 0).toUByte())
        val WRITE = InterestSet((1u shl 1).toUByte())
        val ACCEPT = InterestSet((1u shl 2).toUByte())
        val CONNECT = InterestSet((1u shl 3).toUByte())
        val ERROR = InterestSet((1u shl 4).toUByte())

        fun fromOperation(op: IOOperation): InterestSet = when (op) {
            IOOperation.Read -> READ
            IOOperation.Write -> WRITE
            IOOperation.Accept -> ACCEPT
            IOOperation.Connect -> CONNECT
            IOOperation.Error -> ERROR
        }
    }

    fun isEmpty(): Boolean = bits == 0u.toUByte()

    fun contains(other: InterestSet): Boolean = (bits and other.bits) == other.bits

    fun intersects(other: InterestSet): Boolean = (bits and other.bits) != 0u.toUByte()

    fun or(other: InterestSet): InterestSet = InterestSet(bits or other.bits)

    infix fun and(other: InterestSet): InterestSet = InterestSet(bits and other.bits)

    override fun toString(): String {
        if (isEmpty()) return "InterestSet(NONE)"
        val parts = buildList {
            if (contains(READ)) add("READ")
            if (contains(WRITE)) add("WRITE")
            if (contains(ACCEPT)) add("ACCEPT")
            if (contains(CONNECT)) add("CONNECT")
            if (contains(ERROR)) add("ERROR")
        }
        return "InterestSet(${parts.joinToString("|")})"
    }
}
