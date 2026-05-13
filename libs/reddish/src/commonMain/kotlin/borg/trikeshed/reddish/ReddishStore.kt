package borg.trikeshed.reddish

import borg.trikeshed.cursor.RowVec
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.emptySeries
import borg.trikeshed.lib.size
import borg.trikeshed.miniduck.DocRowVec

private val reddishRowKeys = listOf("key", "type", "value", "length", "ttl")

enum class ReddishType {
    STRING, LIST, HASH, SET, SORTED_SET, BITMAP, GEO, STREAM, HYPERLOGLOG
}

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

data class ReddishSet(
    val key: CharSequence,
    val members: Set<CharSequence> = emptySet(),
    val ttl: Long? = null,
)

data class ReddishSortedSet(
    val key: CharSequence,
    val entries: Map<CharSequence, Double> = emptyMap(),
    val ttl: Long? = null,
)

data class ReddishBitmap(
    val key: CharSequence,
    val bytes: ByteArray = byteArrayOf(),
    val ttl: Long? = null,
) {
    fun bitAt(offset: Int): Boolean =
        if (offset < 0 || bytes.isEmpty()) false
        else (bytes[offset / 8].toInt() and (1 shl (offset % 8))) != 0

    fun setBit(offset: Int, value: Boolean): ReddishBitmap {
        val copy = bytes.copyOf(maxOf(bytes.size, offset / 8 + 1))
        val b = offset / 8
        val bit = offset % 8
        if (value) copy[b] = (copy[b].toInt() or (1 shl bit)).toByte()
        else copy[b] = (copy[b].toInt() and (0xFF xor (1 shl bit))).toByte()
        return copy(key = key, bytes = copy, ttl = ttl)
    }

    fun populateCount(): Int =
        (0 until bytes.size * 8).count { bitAt(it) }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ReddishBitmap) return false
        return key == other.key && bytes.contentEquals(other.bytes) && ttl == other.ttl
    }

    override fun hashCode(): Int {
        var result = key.hashCode()
        result = 31 * result + bytes.contentHashCode()
        result = 31 * result + (ttl?.hashCode() ?: 0)
        return result
    }
}

data class ReddishGeoPoint(
    val longitude: Double,
    val latitude: Double,
    val member: CharSequence,
)

data class ReddishGeo(
    val key: CharSequence,
    val points: List<ReddishGeoPoint> = emptyList(),
    val ttl: Long? = null,
)

data class ReddishStreamEntry(
    val id: CharSequence,
    val fields: Map<CharSequence, CharSequence> = emptyMap(),
)

data class ReddishStream(
    val key: CharSequence,
    val entries: List<ReddishStreamEntry> = emptyList(),
    val maxLen: Long? = null,
    val ttl: Long? = null,
)

data class ReddishHyperLogLog(
    val key: CharSequence,
    val bytes: ByteArray = ByteArray(16384),
    val cardinality: Long = 0L,
    val ttl: Long? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ReddishHyperLogLog) return false
        return key == other.key && cardinality == other.cardinality && ttl == other.ttl && bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int {
        var result = key.hashCode()
        result = 31 * result + bytes.contentHashCode()
        result = 31 * result + cardinality.hashCode()
        result = 31 * result + (ttl?.hashCode() ?: 0)
        return result
    }
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
    fun sadd(key: CharSequence, vararg members: CharSequence): Int
    fun srem(key: CharSequence, vararg members: CharSequence): Int
    fun smembers(key: CharSequence): Set<CharSequence>
    fun zadd(key: CharSequence, score: Double, member: CharSequence): Int
    fun zrem(key: CharSequence, vararg members: CharSequence): Int
    fun zrange(key: CharSequence, start: Int, end: Int): List<CharSequence>
    fun zscore(key: CharSequence, member: CharSequence): Double?
    fun zrangeByScore(key: CharSequence, min: Double, max: Double): List<CharSequence>
    fun geoadd(key: CharSequence, points: List<ReddishGeoPoint>): Int
    fun geodist(key: CharSequence, a: CharSequence, b: CharSequence): Double?
    fun georadius(key: CharSequence, longitude: Double, latitude: Double, radiusMeters: Double): List<CharSequence>
    fun xadd(key: CharSequence, entry: Map<CharSequence, CharSequence>): CharSequence?
    fun xrange(key: CharSequence, start: CharSequence = "-", end: CharSequence = "+"): List<ReddishStreamEntry>
    fun xlen(key: CharSequence): Long
    fun pfadd(key: CharSequence, vararg elements: CharSequence): Boolean
    fun pfcount(vararg keys: CharSequence): Long
    fun pfmerge(destKey: CharSequence, vararg sourceKeys: CharSequence): Boolean
    fun keys(pattern: CharSequence): List<CharSequence>
    fun expire(key: CharSequence, ttl: Long): Boolean
    fun type(key: CharSequence): ReddishType?
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

fun ReddishSet.toRowVec(): DocRowVec =
    DocRowVec(
        keys = listOf("key", "type", "memberCount", "ttl"),
        cells = listOf(key, ReddishType.SET.name, members.size, ttl ?: -1),
    )

fun ReddishStream.toRowVec(): DocRowVec =
    DocRowVec(
        keys = listOf("key", "type", "entryCount", "maxLen", "ttl"),
        cells = listOf(key, ReddishType.STREAM.name, entries.size, maxLen ?: -1, ttl ?: -1),
    )

fun ReddishGeo.toRowVec(): DocRowVec =
    DocRowVec(
        keys = listOf("key", "type", "pointCount", "ttl"),
        cells = listOf(key, ReddishType.GEO.name, points.size, ttl ?: -1),
    )

fun ReddishHyperLogLog.toRowVec(): DocRowVec =
    DocRowVec(
        keys = listOf("key", "type", "cardinality", "byteSize", "ttl"),
        cells = listOf(key, ReddishType.HYPERLOGLOG.name, cardinality, bytes.size, ttl ?: -1),
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

    fun sadd(key: CharSequence, vararg members: CharSequence): Int = client.sadd(key, *members)

    fun srem(key: CharSequence, vararg members: CharSequence): Int = client.srem(key, *members)

    fun smembers(key: CharSequence): Set<CharSequence> = client.smembers(key)

    fun zadd(key: CharSequence, score: Double, member: CharSequence): Int = client.zadd(key, score, member)

    fun zrem(key: CharSequence, vararg members: CharSequence): Int = client.zrem(key, *members)

    fun zrange(key: CharSequence, start: Int, end: Int): List<CharSequence> = client.zrange(key, start, end)

    fun zscore(key: CharSequence, member: CharSequence): Double? = client.zscore(key, member)

    fun zrangeByScore(key: CharSequence, min: Double, max: Double): List<CharSequence> =
        client.zrangeByScore(key, min, max)

    fun geoadd(key: CharSequence, points: List<ReddishGeoPoint>): Int = client.geoadd(key, points)

    fun geodist(key: CharSequence, a: CharSequence, b: CharSequence): Double? = client.geodist(key, a, b)

    fun georadius(key: CharSequence, longitude: Double, latitude: Double, radiusMeters: Double): List<CharSequence> =
        client.georadius(key, longitude, latitude, radiusMeters)

    fun xadd(key: CharSequence, entry: Map<CharSequence, CharSequence>): CharSequence? =
        client.xadd(key, entry)

    fun xrange(key: CharSequence, start: CharSequence = "-", end: CharSequence = "+"): List<ReddishStreamEntry> =
        client.xrange(key, start, end)

    fun xlen(key: CharSequence): Long = client.xlen(key)

    fun pfadd(key: CharSequence, vararg elements: CharSequence): Boolean = client.pfadd(key, *elements)

    fun pfcount(vararg keys: CharSequence): Long = client.pfcount(*keys)

    fun pfmerge(destKey: CharSequence, vararg sourceKeys: CharSequence): Boolean =
        client.pfmerge(destKey, *sourceKeys)

    fun keys(pattern: CharSequence): List<CharSequence> = client.keys(pattern)

    fun expire(key: CharSequence, ttl: Long): Boolean = client.expire(key, ttl)

    fun type(key: CharSequence): ReddishType? = client.type(key)

    fun query(query: ReddishQuery): Series<ReddishKey> = client.query(query)

    fun project(key: ReddishKey): DocRowVec = key.toRowVec()
}
