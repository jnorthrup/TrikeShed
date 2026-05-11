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
                override fun get(key: String): String? = if (key == "cache:token") "abc123" else null
                override fun set(key: String, value: String, ttl: Long?): Boolean = true
                override fun del(key: String): Boolean = false
                override fun lpush(key: String, vararg elements: String): Int = 0
                override fun rpush(key: String, vararg elements: String): Int = 0
                override fun lpop(key: String): String? = null
                override fun rpop(key: String): String? = null
                override fun ll(key: String): List<String> = emptyList()
                override fun hset(key: String, field: String, value: String): Int = 0
                override fun hget(key: String, field: String): String? = null
                override fun hgetall(key: String): Map<String, String> = emptyMap()
                override fun hdel(key: String, vararg fields: String): Int = 0
                override fun keys(pattern: String): List<String> = emptyList()
                override fun expire(key: String, ttl: Long): Boolean = false
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
                override fun get(key: String): String? = null
                override fun set(key: String, value: String, ttl: Long?): Boolean {
                    assertEquals("cmc:tickers", key)
                    assertEquals("[1,2,3]", value)
                    return true
                }
                override fun del(key: String): Boolean = false
                override fun lpush(key: String, vararg elements: String): Int = 0
                override fun rpush(key: String, vararg elements: String): Int = 0
                override fun lpop(key: String): String? = null
                override fun rpop(key: String): String? = null
                override fun ll(key: String): List<String> = emptyList()
                override fun hset(key: String, field: String, value: String): Int = 0
                override fun hget(key: String, field: String): String? = null
                override fun hgetall(key: String): Map<String, String> = emptyMap()
                override fun hdel(key: String, vararg fields: String): Int = 0
                override fun keys(pattern: String): List<String> = emptyList()
                override fun expire(key: String, ttl: Long): Boolean = false
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
                override fun get(key: String): String? = null
                override fun set(key: String, value: String, ttl: Long?): Boolean = false
                override fun del(key: String): Boolean = false
                override fun lpush(key: String, vararg els: String): Int {
                    els.toList().reversed().forEach { elements.add(0, it) }
                    return elements.size
                }
                override fun rpush(key: String, vararg elements: String): Int = 0
                override fun lpop(key: String): String? = if (elements.isNotEmpty()) elements.removeAt(0) else null
                override fun rpop(key: String): String? = null
                override fun ll(key: String): List<String> = elements.toList()
                override fun hset(key: String, field: String, value: String): Int = 0
                override fun hget(key: String, field: String): String? = null
                override fun hgetall(key: String): Map<String, String> = emptyMap()
                override fun hdel(key: String, vararg fields: String): Int = 0
                override fun keys(pattern: String): List<String> = emptyList()
                override fun expire(key: String, ttl: Long): Boolean = false
                override fun query(query: ReddishQuery) = 0 j { _: Int -> ReddishKey(query.pattern, "") }
            },
        )

        store.lpush("queue:jobs", "job1", "job2")
        val popped = store.lpop("queue:jobs")

        assertEquals("job2", popped)
    }

    @Test
    fun hsetAndHgetAllWork() {
        val hash = mutableMapOf<String, String>()
        val store = ReddishStore(
            client = object : ReddishClient {
                override fun get(key: String): String? = null
                override fun set(key: String, value: String, ttl: Long?): Boolean = false
                override fun del(key: String): Boolean = false
                override fun lpush(key: String, vararg elements: String): Int = 0
                override fun rpush(key: String, vararg elements: String): Int = 0
                override fun lpop(key: String): String? = null
                override fun rpop(key: String): String? = null
                override fun ll(key: String): List<String> = emptyList()
                override fun hset(key: String, field: String, value: String): Int {
                    hash[field] = value
                    return 1
                }
                override fun hget(key: String, field: String): String? = null
                override fun hgetall(key: String): Map<String, String> = hash.toMap()
                override fun hdel(key: String, vararg fields: String): Int = 0
                override fun keys(pattern: String): List<String> = emptyList()
                override fun expire(key: String, ttl: Long): Boolean = false
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
                override fun get(key: String): String? = null
                override fun set(key: String, value: String, ttl: Long?): Boolean = false
                override fun del(key: String): Boolean = false
                override fun lpush(key: String, vararg elements: String): Int = 0
                override fun rpush(key: String, vararg elements: String): Int = 0
                override fun lpop(key: String): String? = null
                override fun rpop(key: String): String? = null
                override fun ll(key: String): List<String> = emptyList()
                override fun hset(key: String, field: String, value: String): Int = 0
                override fun hget(key: String, field: String): String? = null
                override fun hgetall(key: String): Map<String, String> = emptyMap()
                override fun hdel(key: String, vararg fields: String): Int = 0
                override fun keys(pattern: String): List<String> = emptyList()
                override fun expire(key: String, ttl: Long): Boolean = false
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
