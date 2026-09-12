package borg.trikeshed.torrent

import borg.trikeshed.lib.Join
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.j
import borg.trikeshed.lib.toSeries
import borg.trikeshed.lib.view

/** Binary bencode values. Dictionary order is the unsigned order of its byte-string keys. */
sealed class BencodeValue {
    internal var start = -1
    internal var end = -1
    class Bytes(bytes: ByteArray) : BencodeValue() {
        private val data = bytes.copyOf()
        val bytes: ByteArray get() = data.copyOf()
        fun text(): String = try { data.decodeToString(throwOnInvalidSequence = true) }
            catch (cause: Exception) { throw IllegalArgumentException("Invalid UTF-8 text field", cause) }
    }
    class Integer(val value: Long) : BencodeValue()
    class ListValue(val values: Series<BencodeValue>) : BencodeValue()
    class Dictionary(val entries: Series<Join<ByteArray, BencodeValue>>) : BencodeValue() {
        operator fun get(key: String): BencodeValue? {
            val bytes = key.encodeToByteArray()
            return entries.view.firstOrNull { it.a.contentEquals(bytes) }?.b
        }
    }
}

/** Checked lengths, canonical integers, unique sorted dictionary keys, and bounded recursion. */
object TorrentBencode {
    const val MAX_BYTES = 64 * 1024 * 1024
    const val MAX_DEPTH = 64
    const val MAX_NODES = 262_144

    fun decode(bytes: ByteArray): BencodeValue = decodePrefix(bytes).let {
        require(it.b == bytes.size) { "Trailing bencode data" }; it.a
    }
    fun decodePrefix(bytes: ByteArray): Join<BencodeValue, Int> {
        require(bytes.size <= MAX_BYTES) { "Bencode size limit" }
        val reader = Reader(bytes)
        return reader.read(0) j reader.position
    }
    fun dictionary(vararg entries: Pair<String, BencodeValue>): BencodeValue.Dictionary =
        BencodeValue.Dictionary(entries.map { it.first.encodeToByteArray() j it.second }.toSeries())

    fun encode(value: BencodeValue): ByteArray {
        val chunks = mutableListOf<ByteArray>()
        var size = 0
        var nodes = 0
        fun append(bytes: ByteArray) { require(bytes.size <= MAX_BYTES - size); size += bytes.size; chunks += bytes }
        fun string(bytes: ByteArray) { append("${bytes.size}:".encodeToByteArray()); append(bytes) }
        fun write(node: BencodeValue, depth: Int) {
            require(depth <= MAX_DEPTH && ++nodes <= MAX_NODES) { "Bencode complexity limit" }
            when (node) {
                is BencodeValue.Bytes -> string(node.bytes)
                is BencodeValue.Integer -> append("i${node.value}e".encodeToByteArray())
                is BencodeValue.ListValue -> {
                    append(byteArrayOf(108)); for (entry in node.values.view) write(entry, depth + 1); append(byteArrayOf(101))
                }
                is BencodeValue.Dictionary -> {
                    append(byteArrayOf(100))
                    val entries = node.entries.view.sortedWith { a, b -> compare(a.a, b.a) }
                    var previous: ByteArray? = null
                    for (entry in entries) {
                        previous?.let { require(compare(it, entry.a) < 0) { "Duplicate dictionary key" } }
                        previous = entry.a
                        string(entry.a); write(entry.b, depth + 1)
                    }
                    append(byteArrayOf(101))
                }
            }
        }
        write(value, 0)
        val output = ByteArray(size)
        var at = 0
        for (chunk in chunks) { chunk.copyInto(output, at); at += chunk.size }
        return output
    }
    internal fun compare(a: ByteArray, b: ByteArray): Int {
        for (i in 0 until minOf(a.size, b.size)) {
            val d = (a[i].toInt() and 255) - (b[i].toInt() and 255)
            if (d != 0) return d
        }
        return a.size.compareTo(b.size)
    }
    private class Reader(val bytes: ByteArray) {
        var position = 0
        var nodes = 0
        fun peek(): Int { require(position < bytes.size) { "Truncated bencode" }; return bytes[position].toInt() and 255 }
        fun read(depth: Int): BencodeValue {
            require(depth <= MAX_DEPTH && ++nodes <= MAX_NODES) { "Bencode complexity limit" }
            val start = position
            val node = when (val token = peek()) {
                105 -> {
                    position++
                    val begin = position
                    while (peek() != 101) { require(position - begin < 20) { "Bencode integer overflow" }; position++ }
                    val text = bytes.copyOfRange(begin, position++).decodeToString()
                    require(Regex("0|-?[1-9][0-9]*").matches(text)) { "Non-canonical bencode integer" }
                    BencodeValue.Integer(text.toLongOrNull() ?: throw IllegalArgumentException("Bencode integer outside signed 64-bit range"))
                }
                108 -> {
                    position++
                    val values = mutableListOf<BencodeValue>()
                    while (peek() != 101) values += read(depth + 1)
                    position++; BencodeValue.ListValue(values.toSeries())
                }
                100 -> {
                    position++
                    val values = mutableListOf<Join<ByteArray, BencodeValue>>()
                    var previous: ByteArray? = null
                    while (peek() != 101) {
                        val key = read(depth + 1) as? BencodeValue.Bytes ?: throw IllegalArgumentException("Dictionary key is not bytes")
                        val raw = key.bytes
                        previous?.let { require(compare(it, raw) < 0) { "Unsorted or duplicate dictionary key" } }
                        previous = raw
                        values += raw j read(depth + 1)
                    }
                    position++; BencodeValue.Dictionary(values.toSeries())
                }
                in 48..57 -> {
                    var length = 0
                    val first = token
                    var digits = 0
                    while (peek() != 58) {
                        val digit = peek() - 48
                        require(digit in 0..9 && (digits == 0 || first != 48)) { "Invalid byte-string length" }
                        require(length <= (MAX_BYTES - digit) / 10) { "Byte-string size limit" }
                        length = length * 10 + digit; digits++; position++
                    }
                    position++
                    require(length <= bytes.size - position) { "Truncated byte string" }
                    BencodeValue.Bytes(bytes.copyOfRange(position, position + length)).also { position += length }
                }
                else -> throw IllegalArgumentException("Invalid bencode token $token")
            }
            return node.also { it.start = start; it.end = position }
        }
    }
}
