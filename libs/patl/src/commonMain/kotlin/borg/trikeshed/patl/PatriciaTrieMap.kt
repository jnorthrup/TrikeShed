@file:OptIn(ExperimentalUnsignedTypes::class)
package borg.trikeshed.patl

import borg.trikeshed.lib.Series
import borg.trikeshed.lib.packInts
import borg.trikeshed.lib.TwInt

class PatriciaTrieMap<K, V>(
    val bitComp: BitComp<K>,
    private val keys: List<K> = emptyList(),
    private val values: List<V> = emptyList(),
    private val rootIndex: Int = IntNodeStore.NULL,
    val store: IntNodeStore = IntNodeStore()
) {

    fun insert(key: K, value: V): PatriciaTrieMap<K, V> {
        val newLeafIdx = keys.size
        val newLeafNode = -newLeafIdx - 1

        if (rootIndex == IntNodeStore.NULL) {
            val newKeys = keys + key
            val newValues = values + value
            return PatriciaTrieMap(bitComp, newKeys, newValues, newLeafNode, store)
        }

        var c = rootIndex
        var p = IntNodeStore.NULL
        var pId = 0

        while (c >= 0) {
            p = c
            val skip = store.getSkip(c).toUInt()
            val bit = getBit(bitComp, key, skip)
            pId = bit
            c = if (bit == 0) store.getLeftChild(c) else store.getRightChild(c)
        }

        val existingLeafIdx = -c - 1
        val existingKey = keys[existingLeafIdx]

        val mismatchUInt = bitComp.mismatch(existingKey, key)
        if (mismatchUInt == BitComp.ALL_MATCH) {
            val existingIdx = keys.indexOf(key)
            if (existingIdx >= 0) {
                val newValues = values.toMutableList()
                newValues[existingIdx] = value
                return PatriciaTrieMap(bitComp, keys, newValues, rootIndex, store)
            }
        }
        val mismatch = mismatchUInt.toInt()

        val newKeys = keys + key
        val newValues = values + value

        c = rootIndex
        p = IntNodeStore.NULL
        pId = 0

        while (c >= 0 && store.getSkip(c) < mismatch) {
            p = c
            val bit = getBit(bitComp, key, store.getSkip(c).toUInt())
            pId = bit
            c = if (bit == 0) store.getLeftChild(c) else store.getRightChild(c)
        }

        val newBit = getBit(bitComp, key, mismatchUInt)
        val left = if (newBit == 0) newLeafNode else c
        val right = if (newBit == 0) c else newLeafNode

        val newNode = store.append(p, pId, left, right, mismatch)

        fun copyPath(curr: Int): Int {
            if (curr == c) return newNode
            if (curr < 0 || curr == IntNodeStore.NULL) return curr
            val skip = store.getSkip(curr)
            val bit = getBit(bitComp, existingKey, skip.toUInt())
            val l = store.getLeftChild(curr)
            val r = store.getRightChild(curr)
            return store.append(
                IntNodeStore.NULL, 0,
                if (bit == 0) copyPath(l) else l,
                if (bit == 1) copyPath(r) else r,
                skip
            )
        }

        val newRoot = copyPath(rootIndex)

        return PatriciaTrieMap(bitComp, newKeys, newValues, newRoot, store)
    }

    fun lookup(key: K): V? {
        if (rootIndex == IntNodeStore.NULL) return null

        var c = rootIndex
        while (c >= 0) {
            val skip = store.getSkip(c)
            val bit = getBit(bitComp, key, skip.toUInt())
            c = if (bit == 0) store.getLeftChild(c) else store.getRightChild(c)
        }

        val leafIdx = -c - 1
        if (leafIdx >= keys.size || leafIdx < 0) return null

        val existingKey = keys[leafIdx]

        var match = true
        var mismatchUInt = 0u
        while (mismatchUInt < 10000u) {
            val b1 = getBit(bitComp, existingKey, mismatchUInt)
            val b2 = getBit(bitComp, key, mismatchUInt)
            if (b1 != b2) {
                match = false
                break
            }
            val len1 = bitComp.extract(existingKey).a * 8
            val len2 = bitComp.extract(key).a * 8
            if (mismatchUInt.toInt() >= len1 && mismatchUInt.toInt() >= len2) break
            mismatchUInt++
        }

        if (match) return values[leafIdx]

        return null
    }

    fun delete(key: K): PatriciaTrieMap<K, V> {
        val existingIdx = keys.indexOf(key)
        if (existingIdx < 0) return this

        val newKeys = keys.toMutableList()
        val newValues = values.toMutableList()

        if (keys.size == 1) {
            return PatriciaTrieMap(bitComp, newKeys, newValues, IntNodeStore.NULL, store)
        }

        var c = rootIndex
        var p = IntNodeStore.NULL
        var pp = IntNodeStore.NULL
        var pId = 0
        var ppId = 0

        while (c >= 0) {
            pp = p
            ppId = pId
            p = c
            val skip = store.getSkip(c)
            val bit = getBit(bitComp, key, skip.toUInt())
            pId = bit
            c = if (bit == 0) store.getLeftChild(c) else store.getRightChild(c)
        }

        val existingKey = keys[-c - 1]

        var match = true
        var mismatchUInt = 0u
        while (mismatchUInt < 10000u) {
            val b1 = getBit(bitComp, existingKey, mismatchUInt)
            val b2 = getBit(bitComp, key, mismatchUInt)
            if (b1 != b2) {
                match = false
                break
            }
            val len1 = bitComp.extract(existingKey).a * 8
            val len2 = bitComp.extract(key).a * 8
            if (mismatchUInt.toInt() >= len1 && mismatchUInt.toInt() >= len2) break
            mismatchUInt++
        }
        if (!match) return this

        val sibling = if (pId == 0) store.getRightChild(p) else store.getLeftChild(p)

        fun copyPath(curr: Int): Int {
            if (curr == p) return sibling
            if (curr < 0 || curr == IntNodeStore.NULL) return curr
            val skip = store.getSkip(curr)
            val bit = getBit(bitComp, key, skip.toUInt())
            val l = store.getLeftChild(curr)
            val r = store.getRightChild(curr)
            return store.append(
                IntNodeStore.NULL, 0,
                if (bit == 0) copyPath(l) else l,
                if (bit == 1) copyPath(r) else r,
                skip
            )
        }

        val newRoot = copyPath(rootIndex)

        return PatriciaTrieMap(bitComp, newKeys, newValues, newRoot, store)
    }

    val size: Int get() = {
        var s = 0
        for (i in keys.indices) {
            if (lookup(keys[i]) != null) s++
        }
        s
    }()

    private fun getBit(bitComp: BitComp<K>, key: K, bitIndex: UInt): Int {
        val bytes = bitComp.extract(key)
        val byteIdx = (bitIndex / 8u).toInt()
        val bitWithinByte = (bitIndex % 8u).toInt()

        if (byteIdx == bytes.a) {
            if (bitWithinByte == 0) return 1
            return 0
        }
        if (byteIdx > bytes.a) return 0

        val b = bytes.b(byteIdx).toInt()
        return (b shr bitWithinByte) and 1
    }
}
