package borg.trikeshed.reddish

import borg.trikeshed.lib.j
import borg.trikeshed.lib.size
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReddishStoreTest {
    @Test
    fun getDelegatesToClientAndReturnsValue() {
        val store = ReddishStore(
            client = object : ReddishClient {
                override fun get(key: CharSequence): CharSequence? = if (key == "cache:token") "abc123" else null
                override fun set(key: CharSequence, value: CharSequence, ttl: Long?): Boolean = true
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
                override fun keys(pattern: CharSequence): List<CharSequence> = emptyList()
                override fun expire(key: CharSequence, ttl: Long): Boolean = false
                override fun query(query: ReddishQuery) = 0 j { _: Int -> ReddishKey(query.pattern, "") }
            },
        )

        val value = store.get("cache:token")

        assertEquals("abc123", value)
    }

    @Test
    fun setDelegatesToClientAndReturnsAccepted() {
        val store = ReddishStore(
            client = object : ReddishClient {
                override fun get(key: CharSequence): CharSequence? = null
                override fun set(key: CharSequence, value: CharSequence, ttl: Long?): Boolean {
                    assertEquals("cmc:tickers", key)
                    assertEquals("[1,2,3]", value)
                    return true
                }
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
                override fun keys(pattern: CharSequence): List<CharSequence> = emptyList()
                override fun expire(key: CharSequence, ttl: Long): Boolean = false
                override fun query(query: ReddishQuery) = 0 j { _: Int -> ReddishKey(query.pattern, "") }
            },
        )

        val ok = store.set("cmc:tickers", "[1,2,3]", ttl = 300)

        assertTrue(ok)
    }

    @Test
    fun lpushAndLpopRoundTrip() {
        val elements = mutableListOf("a", "b", "c")
        val store = ReddishStore(
            client = object : ReddishClient {
                override fun get(key: CharSequence): CharSequence? = null
                override fun set(key: CharSequence, value: CharSequence, ttl: Long?): Boolean = false
                override fun del(key: CharSequence): Boolean = false
                override fun lpush(key: CharSequence, vararg els: CharSequence): Int {
                    els.toList().reversed().forEach { elements.add(0, it) }
                    return elements.size
                }
                override fun rpush(key: CharSequence, vararg elements: CharSequence): Int = 0
                override fun lpop(key: CharSequence): CharSequence? = if (elements.isNotEmpty()) elements.removeAt(0) else null
                override fun rpop(key: CharSequence): CharSequence? = null
                override fun ll(key: CharSequence): List<CharSequence> = elements.toList()
                override fun hset(key: CharSequence, field: CharSequence, value: CharSequence): Int = 0
                override fun hget(key: CharSequence, field: CharSequence): CharSequence? = null
                override fun hgetall(key: CharSequence): Map<CharSequence, CharSequence> = emptyMap()
                override fun hdel(key: CharSequence, vararg fields: CharSequence): Int = 0
                override fun keys(pattern: CharSequence): List<CharSequence> = emptyList()
                override fun expire(key: CharSequence, ttl: Long): Boolean = false
                override fun query(query: ReddishQuery) = 0 j { _: Int -> ReddishKey(query.pattern, "") }
            },
        )

        store.lpush("queue:jobs", "job1", "job2")
        val popped = store.lpop("queue:jobs")

        assertEquals("job2", popped)
    }

    @Test
    fun hsetAndHgetAllWork() {
        val hash = mutableMapOf<CharSequence, CharSequence>()
        val store = ReddishStore(
            client = object : ReddishClient {
                override fun get(key: CharSequence): CharSequence? = null
                override fun set(key: CharSequence, value: CharSequence, ttl: Long?): Boolean = false
                override fun del(key: CharSequence): Boolean = false
                override fun lpush(key: CharSequence, vararg elements: CharSequence): Int = 0
                override fun rpush(key: CharSequence, vararg elements: CharSequence): Int = 0
                override fun lpop(key: CharSequence): CharSequence? = null
                override fun rpop(key: CharSequence): CharSequence? = null
                override fun ll(key: CharSequence): List<CharSequence> = emptyList()
                override fun hset(key: CharSequence, field: CharSequence, value: CharSequence): Int {
                    hash[field] = value
                    return 1
                }
                override fun hget(key: CharSequence, field: CharSequence): CharSequence? = null
                override fun hgetall(key: CharSequence): Map<CharSequence, CharSequence> = hash.toMap()
                override fun hdel(key: CharSequence, vararg fields: CharSequence): Int = 0
                override fun keys(pattern: CharSequence): List<CharSequence> = emptyList()
                override fun expire(key: CharSequence, ttl: Long): Boolean = false
                override fun query(query: ReddishQuery) = 0 j { _: Int -> ReddishKey(query.pattern, "") }
            },
        )

        store.hset("user:1", "name", "alice")
        store.hset("user:1", "role", "admin")
        val all = store.hgetall("user:1")

        assertEquals("alice", all["name"])
        assertEquals("admin", all["role"])
        assertEquals(2, all.size)
    }

    @Test
    fun projectBuildsDocRowVec() {
        val store = ReddishStore(
            client = object : ReddishClient {
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
                override fun keys(pattern: CharSequence): List<CharSequence> = emptyList()
                override fun expire(key: CharSequence, ttl: Long): Boolean = false
                override fun query(query: ReddishQuery) = 0 j { _: Int -> ReddishKey(query.pattern, "") }
            },
        )

        val key = ReddishKey("cmc:cache", "[1,2,3]", ttl = 300, type = ReddishType.STRING)
        val row = store.project(key)

        assertEquals("cmc:cache", row["key"])
        assertEquals("STRING", row["type"])
        assertEquals("[1,2,3]", row["value"])
        assertEquals(7, row["length"])
        assertEquals(300, row["ttl"])
    }

    @Test
    fun keyCanProjectWithoutServiceWrapper() {
        val key = ReddishKey("rate:coinbase", "42", ttl = 60, type = ReddishType.STRING)
        val row = key.toRowVec()

        assertEquals("rate:coinbase", row["key"])
        assertEquals(42, row["length"])
        assertEquals(60, row["ttl"])
    }
}
