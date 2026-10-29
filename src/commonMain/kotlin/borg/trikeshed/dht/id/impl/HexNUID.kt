package borg.trikeshed.dht.id.impl

import borg.trikeshed.dht.id.NUID
import borg.trikeshed.dht.net.NetMask
import borg.trikeshed.platform.bitops.BitOps

/** Fixed-width unsigned identity; full SHA identities need not pass through a narrower machine integer. */
class HexNUID(value: String, override val netmask: HexNetMask = HexNetMask(value.length * 4)) : NUID<String> {
    init { netmask.requireValue(value) }
    override var id: String? = value
        set(value) { value?.let(netmask::requireValue); field = value }
    override val ops: BitOps<String> get() = netmask.ops
}

/** Hex strings compare in unsigned numeric order when their width is fixed. */
class HexNetMask(override val bits: Int) : NetMask<String> {
    init { require(bits in 4..4096 && bits % 4 == 0) { "Hex identity width" } }
    private val width = bits / 4
    private val digits = "0123456789abcdef"
    fun requireValue(value: String) { require(value.length == width && value.all { it in digits }) { "Hex identity value" } }

    override fun distance(alice: String, bob: String): Int {
        requireValue(alice); requireValue(bob)
        return alice.indices.sumOf { (alice[it].digitToInt(16) xor bob[it].digitToInt(16)).countOneBits() }
    }

    override fun compareDistance(target: String, left: String, right: String): Int {
        requireValue(target); requireValue(left); requireValue(right)
        for (i in target.indices) {
            val a = left[i].digitToInt(16) xor target[i].digitToInt(16)
            val b = right[i].digitToInt(16) xor target[i].digitToInt(16)
            if (a != b) return a.compareTo(b)
        }
        return 0
    }

    override val ops: BitOps<String> = object : BitOps<String> {
        override val one = "0".repeat(width - 1) + "1"
        private fun binary(a: String, b: String, op: (Int, Int) -> Int): String {
            requireValue(a); requireValue(b)
            return a.indices.map { digits[op(a[it].digitToInt(16), b[it].digitToInt(16))] }.joinToString("")
        }
        override val xor: (String, String) -> String = { a, b -> binary(a, b) { x, y -> x xor y } }
        override val and: (String, String) -> String = { a, b -> binary(a, b) { x, y -> x and y } }
        private fun shift(value: String, amount: Int, left: Boolean): String {
            requireValue(value); require(amount >= 0) { "Negative identity shift" }
            if (amount >= bits) return "0".repeat(width)
            val whole = amount / 4
            val partial = amount % 4
            fun digit(index: Int) = if (index in value.indices) value[index].digitToInt(16) else 0
            return value.indices.map { i ->
                val from = if (left) i + whole else i - whole
                val shifted = if (partial == 0) digit(from)
                    else if (left) (digit(from) shl partial) or (digit(from + 1) ushr (4 - partial))
                    else (digit(from) ushr partial) or (digit(from - 1) shl (4 - partial))
                digits[shifted and 15]
            }.joinToString("")
        }
        override val shl: (String, Int) -> String = { value, amount -> shift(value, amount, true) }
        override val shr: (String, Int) -> String = { value, amount -> shift(value, amount, false) }
        private fun sum(a: String, b: String, subtract: Boolean): String {
            requireValue(a); requireValue(b)
            val result = CharArray(width)
            var carry = 0
            for (i in width - 1 downTo 0) {
                val digit = a[i].digitToInt(16) + (if (subtract) -b[i].digitToInt(16) else b[i].digitToInt(16)) + carry
                result[i] = digits[digit and 15]
                carry = digit shr 4
            }
            return result.concatToString()
        }
        override val plus: (String, String) -> String = { a, b -> sum(a, b, false) }
        override val minus: (String, String) -> String = { a, b -> sum(a, b, true) }
    }
}
