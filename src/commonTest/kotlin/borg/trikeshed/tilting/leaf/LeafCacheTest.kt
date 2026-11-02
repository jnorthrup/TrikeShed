package borg.trikeshed.tilting.leaf

import borg.trikeshed.tilting.lz4.Lz4Kotlin
import borg.trikeshed.tilting.lzo.LzoKotlin
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LeafCacheTest {
    private fun codecs(): List<LeafCodec> = listOfNotNull(
        RawLeafCodec, Lz4LeafCodec(Lz4Kotlin()), LzoLeafCodec(LzoKotlin()),
        LeafSpecialization.lz4()?.let { Lz4LeafCodec(it) }, LeafSpecialization.lzo()?.let { LzoLeafCodec(it) },
    )

    private fun leaves(n: Int): List<ByteArray> {
        val rnd = Random(20261015)
        val words = "post user like feed body created_at author 2025-12-31T23:59:19.409Z ".encodeToByteArray()
        return List(n) { k -> ByteArray(4096) { i -> if ((i + k) % 7 < 5) words[(i * 3 + k) % words.size] else rnd.nextInt().toByte() } }
    }

    @Test
    fun longIntMapMatchesHashMap() {
        val rnd = Random(7)
        val map = LongIntMap(4)
        val reference = HashMap<Long, Int>()
        repeat(200_000) {
            val key = rnd.nextLong(-300, 300) * 1_000_003L // collisions in a small table, negative keys included
            when (rnd.nextInt(3)) {
                0 -> { val v = rnd.nextInt(1_000_000); map.put(key, v); reference[key] = v }
                1 -> assertEquals(reference.remove(key) != null, map.remove(key))
                else -> assertEquals(reference[key] ?: -1, map.get(key))
            }
            assertEquals(reference.size, map.size)
        }
        for ((k, v) in reference) assertEquals(v, map.get(k))
    }

    @Test
    fun hitsDecodeTheLeafThatWasPut() {
        val source = leaves(64)
        val dst = ByteArray(4096)
        for (codec in codecs()) {
            val cache = LeafCache(codec, Long.MAX_VALUE)
            for ((k, leaf) in source.withIndex()) {
                assertFalse(cache.get(k.toLong(), dst), codec.name)
                cache.put(k.toLong(), leaf)
            }
            for ((k, leaf) in source.withIndex()) {
                assertTrue(cache.get(k.toLong(), dst), codec.name)
                assertContentEquals(leaf, dst, "${codec.name} leaf $k")
            }
            assertEquals(64, cache.entries)
            assertEquals(64L, cache.hits)
            assertEquals(64L, cache.misses)
        }
    }

    @Test
    fun heldBytesStayWithinCapacity() {
        val source = leaves(200)
        val dst = ByteArray(4096)
        for (codec in codecs()) {
            val cache = LeafCache(codec, 64L * 1024)
            val rnd = Random(3)
            repeat(20_000) {
                val k = (source.size * rnd.nextDouble().let { it * it }).toInt()
                if (!cache.get(k.toLong(), dst)) cache.put(k.toLong(), source[k]) else assertContentEquals(source[k], dst)
                assertTrue(cache.heldBytes <= cache.capacityBytes, codec.name)
            }
            assertTrue(cache.evictions > 0, codec.name)
            assertEquals(20_000L, cache.hits + cache.misses)
        }
    }

    @Test
    fun aReferencedLeafGetsASecondChance() {
        val source = leaves(5)
        val dst = ByteArray(4096)
        val cache = LeafCache(RawLeafCodec, 4L * 4096) // four raw leaves
        for (k in 0 until 4) cache.put(k.toLong(), source[k])
        assertTrue(cache.get(0, dst))
        cache.put(4, source[4])
        assertTrue(cache.contains(0), "leaf 0 was referenced")
        assertFalse(cache.contains(1), "leaf 1 is the first unreferenced leaf the hand meets")
        assertEquals(4, cache.entries)
        assertEquals(1L, cache.evictions)
    }

    @Test
    fun aLeafLargerThanTheCacheIsNotHeld() {
        val cache = LeafCache(RawLeafCodec, 100)
        cache.put(1, ByteArray(4096))
        assertEquals(0, cache.entries)
        assertEquals(0L, cache.heldBytes)
    }
}
