package borg.trikeshed.tilting.leaf

/**
 * A leaf cache in the shape of TrikeShed IsamLeaves' held image, widened from one decoded leaf to as many leaves as
 * fit in [capacityBytes] of [codec]'s encoding: raw holds leaves as they are, lz4/lzo hold them compressed and
 * decode on every hit. Keys are leaf numbers. Eviction is CLOCK (second chance): a hit sets the slot's reference bit,
 * the hand clears set bits and evicts the first clear one. One owner thread; no locks, no boxing.
 */
class LeafCache(val codec: LeafCodec, val capacityBytes: Long) {
    /** Encoded bytes held. */
    var heldBytes: Long = 0; private set
    var entries: Int = 0; private set
    var hits: Long = 0; private set
    var misses: Long = 0; private set
    var evictions: Long = 0; private set

    private val slotOf = LongIntMap()
    private var leafOf = LongArray(64)
    private var body = arrayOfNulls<ByteArray>(64)
    private var referenced = BooleanArray(64)
    private var free = IntArray(64) { 63 - it }
    private var freeCount = 64
    private var hand = 0

    /** Decodes leaf [leaf] into [dst] and returns true, or counts a miss and returns false. */
    fun get(leaf: Long, dst: ByteArray): Boolean {
        val s = slotOf.get(leaf)
        if (s < 0) {
            misses++
            return false
        }
        hits++
        referenced[s] = true
        codec.decodeInto(body[s]!!, dst)
        return true
    }

    /** Holds [content] (a decoded leaf) under [leaf], evicting until it fits; a leaf larger than the cache is not held. */
    fun put(leaf: Long, content: ByteArray) {
        val encoded = codec.encode(content, 0, content.size)
        if (encoded.size > capacityBytes) return
        val existing = slotOf.get(leaf)
        if (existing >= 0) release(existing)
        while (heldBytes + encoded.size > capacityBytes) evictOne()
        val s = takeSlot()
        leafOf[s] = leaf
        body[s] = encoded
        referenced[s] = false
        slotOf.put(leaf, s)
        heldBytes += encoded.size
        entries++
    }

    fun contains(leaf: Long): Boolean = slotOf.get(leaf) >= 0

    private fun evictOne() {
        while (true) {
            if (hand >= body.size) hand = 0
            val s = hand++
            if (body[s] == null) continue
            if (referenced[s]) {
                referenced[s] = false
                continue
            }
            release(s)
            evictions++
            return
        }
    }

    private fun release(s: Int) {
        slotOf.remove(leafOf[s])
        heldBytes -= body[s]!!.size
        body[s] = null
        referenced[s] = false
        entries--
        free[freeCount++] = s
    }

    private fun takeSlot(): Int {
        if (freeCount == 0) {
            val old = body.size
            val n = old * 2
            leafOf = leafOf.copyOf(n)
            body = body.copyOf(n)
            referenced = referenced.copyOf(n)
            free = IntArray(n)
            for (i in 0 until n - old) free[i] = n - 1 - i
            freeCount = n - old
        }
        return free[--freeCount]
    }
}

/**
 * Long -> non-negative Int, open addressing with linear probing and backward-shift deletion (no tombstones).
 * [get] returns -1 for an absent key. Long.MIN_VALUE cannot be a key.
 */
class LongIntMap(initialCapacity: Int = 128) {
    private var keys = LongArray(tableSize(initialCapacity)) { EMPTY }
    private var values = IntArray(keys.size)
    var size: Int = 0; private set

    fun get(key: Long): Int {
        val mask = keys.size - 1
        var i = mix(key) and mask
        while (true) {
            val k = keys[i]
            if (k == key) return values[i]
            if (k == EMPTY) return -1
            i = (i + 1) and mask
        }
    }

    fun put(key: Long, value: Int) {
        require(key != EMPTY) { "Long.MIN_VALUE is the empty marker" }
        if ((size + 1) * 4 > keys.size * 3) grow()
        val mask = keys.size - 1
        var i = mix(key) and mask
        while (true) {
            val k = keys[i]
            if (k == key) {
                values[i] = value
                return
            }
            if (k == EMPTY) {
                keys[i] = key
                values[i] = value
                size++
                return
            }
            i = (i + 1) and mask
        }
    }

    /** Removes [key]; later entries of its probe run shift back into the gap. */
    fun remove(key: Long): Boolean {
        val mask = keys.size - 1
        var i = mix(key) and mask
        while (true) {
            val k = keys[i]
            if (k == EMPTY) return false
            if (k == key) break
            i = (i + 1) and mask
        }
        var gap = i
        var j = (i + 1) and mask
        while (true) {
            val k = keys[j]
            if (k == EMPTY) break
            val home = mix(k) and mask
            // k stays where it is when its home lies cyclically in (gap, j]
            val stays = if (gap <= j) home in (gap + 1)..j else home > gap || home <= j
            if (!stays) {
                keys[gap] = k
                values[gap] = values[j]
                gap = j
            }
            j = (j + 1) and mask
        }
        keys[gap] = EMPTY
        size--
        return true
    }

    private fun grow() {
        val oldKeys = keys
        val oldValues = values
        keys = LongArray(oldKeys.size * 2) { EMPTY }
        values = IntArray(keys.size)
        size = 0
        for (i in oldKeys.indices) if (oldKeys[i] != EMPTY) put(oldKeys[i], oldValues[i])
    }

    private companion object {
        const val EMPTY: Long = Long.MIN_VALUE

        fun tableSize(capacity: Int): Int {
            var n = 16
            while (n * 3 < capacity * 4) n *= 2
            return n
        }

        /** murmur3 fmix64, folded to an Int. */
        fun mix(key: Long): Int {
            var h = key
            h = h xor (h ushr 33)
            h *= -0xae502812aa7333L
            h = h xor (h ushr 33)
            h *= -0x3b314601e57a13adL
            h = h xor (h ushr 33)
            return h.toInt()
        }
    }
}
