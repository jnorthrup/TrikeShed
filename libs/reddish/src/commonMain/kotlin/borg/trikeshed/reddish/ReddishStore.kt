package borg.trikeshed.reddish

import borg.trikeshed.cursor.RowVec
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.emptySeries
import borg.trikeshed.lib.size
import borg.trikeshed.miniduck.DocRowVec

private val reddishRowKeys = listOf("key", "type", "value", "length", "ttl")

data class ReddishKey(
    val key: String,
    val value: String,
    val ttl: Long? = null,
    val type: ReddishType = ReddishType.STRING,
)

data class ReddishList(
    val key: String,
    val elements: List<String> = emptyList(),
    val ttl: Long? = null,
)

data class ReddishHash(
    val key: String,
    val fields: Map<String, String> = emptyMap(),
    val ttl: Long? = null,
)

enum class ReddishType {
    STRING, LIST, HASH
}

data class ReddishQuery(
    val pattern: String,
    val type: ReddishType? = null,
    val limit: Int = 25,
)

interface ReddishClient {
    fun get(key: String): String?
    fun set(key: String, value: String, ttl: Long? = null): Boolean
    fun del(key: String): Boolean
    fun lpush(key: String, vararg elements: String): Int
    fun rpush(key: String, vararg elements: String): Int
    fun lpop(key: String): String?
    fun rpop(key: String): String?
    fun ll(key: String): List<String>
    fun hset(key: String, field: String, value: String): Int
    fun hget(key: String, field: String): String?
    fun hgetall(key: String): Map<String, String>
    fun hdel(key: String, vararg fields: String): Int
    fun keys(pattern: String): List<String>
    fun expire(key: String, ttl: Long): Boolean
    fun query(query: ReddishQuery): Series<ReddishKey>
}

fun ReddishKey.toRowVec(): DocRowVec =
    DocRowVec(
        keys = reddishRowKeys,
        cells = listOf(
            key,
            type.name,
            value,
            value.length,
            ttl ?: -1,
        ),
    )

class ReddishStore(
    private val client: ReddishClient,
) {
    fun get(key: String): String? = client.get(key)

    fun set(key: String, value: String, ttl: Long? = null): Boolean = client.set(key, value, ttl)

    fun del(key: String): Boolean = client.del(key)

    fun lpush(key: String, vararg elements: String): Int = client.lpush(key, *elements)

    fun rpush(key: String, vararg elements: String): Int = client.rpush(key, *elements)

    fun lpop(key: String): String? = client.lpop(key)

    fun rpop(key: String): String? = client.rpop(key)

    fun ll(key: String): List<String> = client.ll(key)

    fun hset(key: String, field: String, value: String): Int = client.hset(key, field, value)

    fun hget(key: String, field: String): String? = client.hget(key, field)

    fun hgetall(key: String): Map<String, String> = client.hgetall(key)

    fun hdel(key: String, vararg fields: String): Int = client.hdel(key, *fields)

    fun keys(pattern: String): List<String> = client.keys(pattern)

    fun expire(key: String, ttl: Long): Boolean = client.expire(key, ttl)

    fun query(query: ReddishQuery): Series<ReddishKey> = client.query(query)

    fun project(key: ReddishKey): DocRowVec = key.toRowVec()
}
