package borg.trikeshed.couch.isam

import borg.trikeshed.collections.IntColumn

/**
 * Strings in id order, each once, as UTF-8 in one byte array: [ends] is where id `n` stops (it starts where `n - 1`
 * stops), [hashes] its hash, and [slots] an open-addressing table of ids + 1 (0 is empty) that finds a string again
 * without an object per entry. An id is the ordinal of the string's first [put] and never changes; the pool only
 * grows, so a string put stays. [size] is published after the string is in, so a reader holding an id below it reads
 * a whole string while a put is under way. Strings are held as UTF-8: a lone surrogate comes back as `?`.
 *
 * The hash is the string's own, spread. A string of ASCII alone is compared and stored char by char, with no byte
 * array made for it; any other string is encoded first.
 */
class PackedStringpool(capacity: Int = 16) : Stringpool {
    var bytes = ByteArray(capacity * MEAN)
    var used = 0
    val ends = IntColumn(capacity)
    val hashes = IntColumn(capacity)
    var slots = IntArray(tableFor(capacity))
    var size = 0

    /** The slot that holds [value]'s id, or the empty slot where it would go; [value] is ASCII with this [hash]. */
    fun slotOf(value: String, hash: Int): Int {
        val mask = slots.size - 1
        var at = hash and mask
        while (slots[at] != 0 && !(hashes[slots[at] - 1] == hash && same(slots[at] - 1, value))) at = (at + 1) and mask
        return at
    }

    /** The slot that holds [key]'s id, or the empty slot where it would go; [key] is a string's bytes with this [hash]. */
    fun slotOf(key: ByteArray, hash: Int): Int {
        val mask = slots.size - 1
        var at = hash and mask
        while (slots[at] != 0 && !(hashes[slots[at] - 1] == hash && same(slots[at] - 1, key))) at = (at + 1) and mask
        return at
    }

    /** Whether id [id] is the ASCII [value]. */
    fun same(id: Int, value: String): Boolean {
        val from = if (id == 0) 0 else ends[id - 1]
        if (ends[id] - from != value.length) return false
        for (i in value.indices) if (bytes[from + i].toInt() != value[i].code) return false
        return true
    }

    /** Whether id [id] is [key]. */
    fun same(id: Int, key: ByteArray): Boolean {
        val from = if (id == 0) 0 else ends[id - 1]
        if (ends[id] - from != key.size) return false
        for (i in key.indices) if (bytes[from + i] != key[i]) return false
        return true
    }

    /** Id of [value] when present, else -1. */
    fun find(value: String): Int {
        val hash = hash(value)
        return slots[if (ascii(value)) slotOf(value, hash) else slotOf(value.encodeToByteArray(), hash)] - 1
    }

    override fun put(value: String): Int {
        val hash = hash(value)
        if (!ascii(value)) return put(value.encodeToByteArray(), hash)
        val at = slotOf(value, hash)
        if (slots[at] != 0) return slots[at] - 1
        room(value.length)
        for (i in value.indices) bytes[used + i] = value[i].code.toByte()
        return filed(at, hash, value.length)
    }

    /** [key], the bytes of a string with this [hash]: the way in for a string that is not ASCII alone. */
    fun put(key: ByteArray, hash: Int): Int {
        val at = slotOf(key, hash)
        if (slots[at] != 0) return slots[at] - 1
        room(key.size)
        key.copyInto(bytes, used)
        return filed(at, hash, key.size)
    }

    override fun get(id: Int): String? =
        if (id < 0 || id >= size) null else bytes.decodeToString(if (id == 0) 0 else ends[id - 1], ends[id])

    /** Room for [n] more bytes. */
    fun room(n: Int) {
        if (used + n > bytes.size) bytes = bytes.copyOf(maxOf(bytes.size shl 1, used + n))
    }

    /** The [length] bytes just written at [used] are the next id, slotted at [at]. */
    fun filed(at: Int, hash: Int, length: Int): Int {
        val id = size
        used += length
        ends.add(used)
        hashes.add(hash)
        slots[at] = id + 1
        size = id + 1
        if (size shl 1 > slots.size) widen()
        return id
    }

    /** The table doubled, every id placed again by its stored hash. */
    fun widen() {
        val wider = IntArray(slots.size shl 1)
        val mask = wider.size - 1
        for (id in 0 until size) {
            var at = hashes[id] and mask
            while (wider[at] != 0) at = (at + 1) and mask
            wider[at] = id + 1
        }
        slots = wider
    }

    companion object {
        /** The bytes a string is taken to run to when a pool is sized by its count. */
        const val MEAN = 32

        /** The smallest power of two table that holds [capacity] ids at half full. */
        fun tableFor(capacity: Int): Int {
            var n = 32
            while (n < capacity shl 1) n = n shl 1
            return n
        }

        /** [value]'s own hash, its bits spread so the low ones that pick a slot depend on all of them. */
        fun hash(value: String): Int {
            var h = value.hashCode()
            h = h xor (h ushr 15)
            h *= -2048144789
            return h xor (h ushr 13)
        }

        /** Whether every char of [value] is one byte alone. */
        fun ascii(value: String): Boolean {
            for (c in value) if (c.code >= 0x80) return false
            return true
        }
    }
}
