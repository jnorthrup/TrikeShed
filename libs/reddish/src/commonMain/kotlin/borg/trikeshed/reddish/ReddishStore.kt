package borg.trikeshed.reddish

import borg.trikeshed.cursor.RowVec
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.emptySeries
import borg.trikeshed.lib.size
import borg.trikeshed.miniduck.DocRowVec

private val reddishRowKeys = listOf("key", "type", "value", "length", "ttl")

data class ReddishKey(
    val key: CharSequence,
    val value: CharSequence,
    val ttl: Long? = null,
    val type: ReddishType = ReddishType.STRING,
)

data class ReddishList(
    val key: CharSequence,
    val elements: List<CharSequence> = emptyList(),
    val ttl: Long? = null,
)

data class ReddishHash(
    val key: CharSequence,
    val fields: Map<CharSequence, CharSequence> = emptyMap(),
    val ttl: Long? = null,
)

enum class ReddishType {
    STRING, LIST, HASH
}

data class ReddishQuery(
    val pattern: CharSequence,
    val type: ReddishType? = null,
    val limit: Int = 25,
)

interface ReddishClient {
    fun get(key: CharSequence): CharSequence?
    fun set(key: CharSequence, value: CharSequence, ttl: Long? = null): Boolean
    fun del(key: CharSequence): Boolean
    fun lpush(key: CharSequence, vararg elements: CharSequence): Int
    fun rpush(key: CharSequence, vararg elements: CharSequence): Int
    fun lpop(key: CharSequence): CharSequence?
    fun rpop(key: CharSequence): CharSequence?
    fun ll(key: CharSequence): List<CharSequence>
    fun hset(key: CharSequence, field: CharSequence, value: CharSequence): Int
    fun hget(key: CharSequence, field: CharSequence): CharSequence?
    fun hgetall(key: CharSequence): Map<CharSequence, CharSequence>
    fun hdel(key: CharSequence, vararg fields: CharSequence): Int
    fun keys(pattern: CharSequence): List<CharSequence>
    fun expire(key: CharSequence, ttl: Long): Boolean
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
    fun get(key: CharSequence): CharSequence? = client.get(key)

    fun set(key: CharSequence, value: CharSequence, ttl: Long? = null): Boolean = client.set(key, value, ttl)

    fun del(key: CharSequence): Boolean = client.del(key)

    fun lpush(key: CharSequence, vararg elements: CharSequence): Int = client.lpush(key, *elements)

    fun rpush(key: CharSequence, vararg elements: CharSequence): Int = client.rpush(key, *elements)

    fun lpop(key: CharSequence): CharSequence? = client.lpop(key)

    fun rpop(key: CharSequence): CharSequence? = client.rpop(key)

    fun ll(key: CharSequence): List<CharSequence> = client.ll(key)

    fun hset(key: CharSequence, field: CharSequence, value: CharSequence): Int = client.hset(key, field, value)

    fun hget(key: CharSequence, field: CharSequence): CharSequence? = client.hget(key, field)

    fun hgetall(key: CharSequence): Map<CharSequence, CharSequence> = client.hgetall(key)

    fun hdel(key: CharSequence, vararg fields: CharSequence): Int = client.hdel(key, *fields)

    fun keys(pattern: CharSequence): List<CharSequence> = client.keys(pattern)

    fun expire(key: CharSequence, ttl: Long): Boolean = client.expire(key, ttl)

    fun query(query: ReddishQuery): Series<ReddishKey> = client.query(query)

    fun project(key: ReddishKey): DocRowVec = key.toRowVec()
}
