package borg.trikeshed.reddish

import borg.trikeshed.lib.j
import borg.trikeshed.lib.size
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertNotNull

class ReddishStoreTest {
    private fun fullStubClient(): ReddishClient = object : ReddishClient {
        override fun get(key: CharSequence): CharSequence? = null
        override fun set(key: CharSequence, value: CharSequence, ttl: Long?): Boolean = false
        override fun del(key: CharSequence): Boolean = false
        override fun lpush(key: CharSequence, vararg elements: CharSequence): Int = 0
        override fun rpush(key: CharSequence, vararg elements: CharSequence): Int = 0
        override fun lpop(key: CharSequence): CharSequence? = null
        override fun rpop(key: CharSequence): CharSequence? = null
        override fun ll(key: CharSequence): List<CharSequence> = emptyList()
        override fun hset(key: CharSequence, field: CharSequence, value: CharSequence): Int = 0
        override fun hget(key: CharSequence, field: CharSequence): CharSequence? = null
        override fun hgetall(key: CharSequence): Map<CharSequence, CharSequence> = emptyMap()
        override fun hdel(key: CharSequence, vararg fields: CharSequence): Int = 0
        override fun sadd(key: CharSequence, vararg members: CharSequence): Int = 0
        override fun srem(key: CharSequence, vararg members: CharSequence): Int = 0
        override fun smembers(key: CharSequence): Set<CharSequence> = emptySet()
        override fun zadd(key: CharSequence, score: Double, member: CharSequence): Int = 0
        override fun zrem(key: CharSequence, vararg members: CharSequence): Int = 0
        override fun zrange(key: CharSequence, start: Int, end: Int): List<CharSequence> = emptyList()
        override fun zscore(key: CharSequence, member: CharSequence): Double? = null
        override fun zrangeByScore(key: CharSequence, min: Double, max: Double): List<CharSequence> = emptyList()
        override fun geoadd(key: CharSequence, points: List<ReddishGeoPoint>): Int = 0
        override fun geodist(key: CharSequence, a: CharSequence, b: CharSequence): Double? = null
        override fun georadius(key: CharSequence, lng: Double, lat: Double, r: Double): List<CharSequence> = emptyList()
        override fun xadd(key: CharSequence, entry: Map<CharSequence, CharSequence>): CharSequence? = null
        override fun xrange(key: CharSequence, s: CharSequence, e: CharSequence): List<ReddishStreamEntry> = emptyList()
        override fun xlen(key: CharSequence): Long = 0L
        override fun pfadd(key: CharSequence, vararg elements: CharSequence): Boolean = false
        override fun pfcount(vararg keys: CharSequence): Long = 0L
        override fun pfmerge(destKey: CharSequence, vararg sourceKeys: CharSequence): Boolean = false
        override fun keys(pattern: CharSequence): List<CharSequence> = emptyList()
        override fun expire(key: CharSequence, ttl: Long): Boolean = false
        override fun type(key: CharSequence): ReddishType? = null
        override fun query(query: ReddishQuery) = 0 j { _: Int -> ReddishKey(query.pattern, "") }
    }

    @Test fun getDelegatesToClient() {
        val store = ReddishStore(object : ReddishClient {
            override fun get(key: CharSequence) = if (key == "cache:token") "abc123" else null
            override fun query(query: ReddishQuery) = 0 j { _: Int -> ReddishKey(query.pattern, "") }
            override fun set(key: CharSequence, value: CharSequence, ttl: Long?): Boolean = true
            override fun del(key: CharSequence): Boolean = false
            override fun lpush(key: CharSequence, vararg e: CharSequence): Int = 0
            override fun rpush(key: CharSequence, vararg e: CharSequence): Int = 0
            override fun lpop(key: CharSequence): CharSequence? = null
            override fun rpop(key: CharSequence): CharSequence? = null
            override fun ll(key: CharSequence): List<CharSequence> = emptyList()
            override fun hset(key: CharSequence, f: CharSequence, v: CharSequence): Int = 0
            override fun hget(key: CharSequence, f: CharSequence): CharSequence? = null
            override fun hgetall(key: CharSequence): Map<CharSequence, CharSequence> = emptyMap()
            override fun hdel(key: CharSequence, vararg f: CharSequence): Int = 0
            override fun sadd(key: CharSequence, vararg m: CharSequence): Int = 0
            override fun srem(key: CharSequence, vararg m: CharSequence): Int = 0
            override fun smembers(key: CharSequence): Set<CharSequence> = emptySet()
            override fun zadd(key: CharSequence, s: Double, m: CharSequence): Int = 0
            override fun zrem(key: CharSequence, vararg m: CharSequence): Int = 0
            override fun zrange(key: CharSequence, s: Int, e: Int): List<CharSequence> = emptyList()
            override fun zscore(key: CharSequence, m: CharSequence): Double? = null
            override fun zrangeByScore(key: CharSequence, mn: Double, mx: Double): List<CharSequence> = emptyList()
            override fun geoadd(key: CharSequence, p: List<ReddishGeoPoint>): Int = 0
            override fun geodist(key: CharSequence, a: CharSequence, b: CharSequence): Double? = null
            override fun georadius(key: CharSequence, l1: Double, l2: Double, r: Double): List<CharSequence> = emptyList()
            override fun xadd(key: CharSequence, e: Map<CharSequence, CharSequence>): CharSequence? = null
            override fun xrange(key: CharSequence, s: CharSequence, e: CharSequence): List<ReddishStreamEntry> = emptyList()
            override fun xlen(key: CharSequence): Long = 0L
            override fun pfadd(key: CharSequence, vararg el: CharSequence): Boolean = false
            override fun pfcount(vararg k: CharSequence): Long = 0L
            override fun pfmerge(d: CharSequence, vararg sk: CharSequence): Boolean = false
            override fun keys(p: CharSequence): List<CharSequence> = emptyList()
            override fun expire(k: CharSequence, t: Long): Boolean = false
            override fun type(k: CharSequence): ReddishType? = null
        })
        assertEquals("abc123", store.get("cache:token"))
    }

    @Test fun setDelegatesToClient() {
        val store = ReddishStore(object : ReddishClient {
            override fun set(key: CharSequence, value: CharSequence, ttl: Long?): Boolean {
                assertEquals("cmc:tickers", key)
                assertEquals("[1,2,3]", value)
                return true
            }
            override fun query(query: ReddishQuery) = 0 j { _: Int -> ReddishKey(query.pattern, "") }
            override fun get(key: CharSequence): CharSequence? = null
            override fun del(key: CharSequence): Boolean = false
            override fun lpush(key: CharSequence, vararg e: CharSequence): Int = 0
            override fun rpush(key: CharSequence, vararg e: CharSequence): Int = 0
            override fun lpop(key: CharSequence): CharSequence? = null
            override fun rpop(key: CharSequence): CharSequence? = null
            override fun ll(key: CharSequence): List<CharSequence> = emptyList()
            override fun hset(key: CharSequence, f: CharSequence, v: CharSequence): Int = 0
            override fun hget(key: CharSequence, f: CharSequence): CharSequence? = null
            override fun hgetall(key: CharSequence): Map<CharSequence, CharSequence> = emptyMap()
            override fun hdel(key: CharSequence, vararg f: CharSequence): Int = 0
            override fun sadd(key: CharSequence, vararg m: CharSequence): Int = 0
            override fun srem(key: CharSequence, vararg m: CharSequence): Int = 0
            override fun smembers(key: CharSequence): Set<CharSequence> = emptySet()
            override fun zadd(key: CharSequence, s: Double, m: CharSequence): Int = 0
            override fun zrem(key: CharSequence, vararg m: CharSequence): Int = 0
            override fun zrange(key: CharSequence, s: Int, e: Int): List<CharSequence> = emptyList()
            override fun zscore(key: CharSequence, m: CharSequence): Double? = null
            override fun zrangeByScore(key: CharSequence, mn: Double, mx: Double): List<CharSequence> = emptyList()
            override fun geoadd(key: CharSequence, p: List<ReddishGeoPoint>): Int = 0
            override fun geodist(key: CharSequence, a: CharSequence, b: CharSequence): Double? = null
            override fun georadius(key: CharSequence, l1: Double, l2: Double, r: Double): List<CharSequence> = emptyList()
            override fun xadd(key: CharSequence, e: Map<CharSequence, CharSequence>): CharSequence? = null
            override fun xrange(key: CharSequence, s: CharSequence, e: CharSequence): List<ReddishStreamEntry> = emptyList()
            override fun xlen(key: CharSequence): Long = 0L
            override fun pfadd(key: CharSequence, vararg el: CharSequence): Boolean = false
            override fun pfcount(vararg k: CharSequence): Long = 0L
            override fun pfmerge(d: CharSequence, vararg sk: CharSequence): Boolean = false
            override fun keys(p: CharSequence): List<CharSequence> = emptyList()
            override fun expire(k: CharSequence, t: Long): Boolean = false
            override fun type(k: CharSequence): ReddishType? = null
        })
        assertTrue(store.set("cmc:tickers", "[1,2,3]", 300))
    }

    @Test fun typeEnumHasAllNine() {
        assertEquals(9, ReddishType.values().size)
        setOf(ReddishType.STRING, ReddishType.LIST, ReddishType.HASH, ReddishType.SET,
            ReddishType.SORTED_SET, ReddishType.BITMAP, ReddishType.GEO, ReddishType.STREAM,
            ReddishType.HYPERLOGLOG).forEach { assertNotNull(it) }
    }

    @Test fun keyProjectsToRowVec() {
        val key = ReddishKey("rate:coinbase", "42", ttl = 60, type = ReddishType.STRING)
        val row = key.toRowVec()
        assertEquals("rate:coinbase", row["key"])
        assertEquals("STRING", row["type"])
        assertEquals("42", row["value"])
        assertEquals(2, row["length"])
        assertEquals(60L, row["ttl"])
    }

    @Test fun setProjectsToRowVec() {
        val s = ReddishSet("tags", setOf("red", "blue"), ttl = 120)
        val row = s.toRowVec()
        assertEquals("SET", row["type"])
        assertEquals(2, row["memberCount"])
    }

    @Test fun streamProjectsToRowVec() {
        val obj = ReddishStream("events", listOf(ReddishStreamEntry("1-0", mapOf("a" to "1"))), maxLen = 1000)
        val row = obj.toRowVec()
        assertEquals("STREAM", row["type"])
        assertEquals(1, row["entryCount"])
        assertEquals(1000L, row["maxLen"])
    }

    @Test fun geoProjectsToRowVec() {
        val geo = ReddishGeo("places", listOf(ReddishGeoPoint(-74.0, 40.7, "NYC")))
        val row = geo.toRowVec()
        assertEquals("GEO", row["type"])
        assertEquals(1, row["pointCount"])
    }

    @Test fun hllProjectsToRowVec() {
        val hll = ReddishHyperLogLog("hll", cardinality = 42)
        val row = hll.toRowVec()
        assertEquals("HYPERLOGLOG", row["type"])
        assertEquals(42L, row["cardinality"])
    }

    @Test fun bitmapSetBitAndPopcount() {
        var bm = ReddishBitmap("bits")
        bm = bm.setBit(0, true).setBit(7, true)
        assertTrue(bm.bitAt(0))
        assertTrue(bm.bitAt(7))
        assertTrue(!bm.bitAt(4))
        assertEquals(2, bm.populateCount())
    }

    @Test fun bitmapEqualsAndHashCode() {
        val a = ReddishBitmap("k", byteArrayOf(1), 100)
        val b = ReddishBitmap("k", byteArrayOf(1), 100)
        val c = ReddishBitmap("k", byteArrayOf(2), 100)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertTrue(a != c)
    }

    @Test fun fullStoreDelegatesAllTypes() {
        val store = ReddishStore(object : ReddishClient {
            private val sets = mutableMapOf<CharSequence, MutableSet<CharSequence>>()
            private val zsets = mutableMapOf<CharSequence, MutableMap<CharSequence, Double>>()
            private val geos = mutableMapOf<CharSequence, MutableList<ReddishGeoPoint>>()
            private val streams = mutableMapOf<CharSequence, MutableList<ReddishStreamEntry>>()
            private val hllMap = mutableMapOf<CharSequence, Long>()
            private var idCounter = 0L
            override fun get(key: CharSequence): CharSequence? = null
            override fun set(key: CharSequence, value: CharSequence, ttl: Long?): Boolean = true
            override fun del(key: CharSequence): Boolean = false
            override fun lpush(key: CharSequence, vararg e: CharSequence) = e.size
            override fun rpush(key: CharSequence, vararg e: CharSequence) = e.size
            override fun lpop(key: CharSequence): CharSequence? = null
            override fun rpop(key: CharSequence): CharSequence? = null
            override fun ll(key: CharSequence): List<CharSequence> = emptyList()
            override fun hset(key: CharSequence, f: CharSequence, v: CharSequence): Int = 1
            override fun hget(key: CharSequence, f: CharSequence): CharSequence? = "v"
            override fun hgetall(key: CharSequence) = mapOf<CharSequence, CharSequence>()
            override fun hdel(key: CharSequence, vararg f: CharSequence): Int = 0
            override fun sadd(key: CharSequence, vararg m: CharSequence): Int {
                sets.getOrPut(key) { mutableSetOf() }.addAll(m)
                return m.size
            }
            override fun srem(key: CharSequence, vararg m: CharSequence): Int =
                sets[key]?.apply { removeAll(m.toSet()) }?.let { m.size } ?: 0
            override fun smembers(key: CharSequence): Set<CharSequence> = sets[key] ?: emptySet()
            override fun zadd(key: CharSequence, score: Double, member: CharSequence): Int {
                zsets.getOrPut(key) { mutableMapOf() }[member] = score
                return 1
            }
            override fun zrem(key: CharSequence, vararg m: CharSequence): Int {
                val map = zsets[key] ?: return 0
                m.forEach { map.remove(it) }
                return m.size
            }
            override fun zrange(key: CharSequence, start: Int, end: Int): List<CharSequence> = emptyList()
            override fun zscore(key: CharSequence, member: CharSequence): Double? = zsets[key]?.get(member)
            override fun zrangeByScore(key: CharSequence, min: Double, max: Double): List<CharSequence> =
                zsets[key]?.filterValues { it in min..max }?.keys?.toList() ?: emptyList()
            override fun geoadd(key: CharSequence, points: List<ReddishGeoPoint>): Int {
                geos.getOrPut(key) { mutableListOf() }.addAll(points)
                return points.size
            }
            override fun geodist(key: CharSequence, a: CharSequence, b: CharSequence): Double? = null
            override fun georadius(key: CharSequence, lng: Double, lat: Double, r: Double): List<CharSequence> = emptyList()
            override fun xadd(key: CharSequence, entry: Map<CharSequence, CharSequence>): CharSequence? {
                idCounter++
                val id = "${idCounter}-0"
                streams.getOrPut(key) { mutableListOf() }.add(ReddishStreamEntry(id, entry))
                return id
            }
            override fun xrange(key: CharSequence, s: CharSequence, e: CharSequence): List<ReddishStreamEntry> =
                streams[key] ?: emptyList()
            override fun xlen(key: CharSequence): Long = (streams[key]?.size ?: 0).toLong()
            override fun pfadd(key: CharSequence, vararg elements: CharSequence): Boolean {
                val old = hllMap[key] ?: 0
                hllMap[key] = old + elements.size.toLong()
                return true
            }
            override fun pfcount(vararg keys: CharSequence): Long = keys.sumOf { hllMap[it] ?: 0 }
            override fun pfmerge(destKey: CharSequence, vararg sourceKeys: CharSequence): Boolean {
                hllMap[destKey] = sourceKeys.sumOf { hllMap[it] ?: 0 }
                return true
            }
            override fun keys(p: CharSequence): List<CharSequence> = emptyList()
            override fun expire(key: CharSequence, ttl: Long): Boolean = true
            override fun type(key: CharSequence): ReddishType? = null
            override fun query(query: ReddishQuery) = 0 j { _: Int -> ReddishKey(query.pattern, "") }
        })

        assertEquals(2, store.sadd("tags", "red", "blue"))
        assertEquals(setOf("red", "blue"), store.smembers("tags"))
        assertEquals(1, store.srem("tags", "red"))
        assertEquals(setOf("blue"), store.smembers("tags"))

        assertEquals(1, store.zadd("lb", 100.0, "alice"))
        assertEquals(100.0, store.zscore("lb", "alice"))

        assertEquals(1, store.geoadd("places", listOf(ReddishGeoPoint(-74.0, 40.7, "NYC"))))

        val streamId = store.xadd("events", mapOf("a" to "1"))
        assertNotNull(streamId)
        assertEquals(1L, store.xlen("events"))
        assertEquals(1, store.xrange("events").size)

        assertTrue(store.pfadd("hll", "a", "b", "c"))
        assertEquals(3, store.pfcount("hll"))
    }
}
