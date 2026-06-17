package org.xvm.cursor

import borg.trikeshed.lib.α
import borg.trikeshed.lib.toList
import borg.trikeshed.lib.Join
import borg.trikeshed.lib.Series
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.util.LinkedHashMap
import borg.trikeshed.cursor.ColumnMeta
import borg.trikeshed.cursor.Cursor
import borg.trikeshed.cursor.IOMemento
import borg.trikeshed.cursor.RowVec
import borg.trikeshed.cursor.TypeMemento
import borg.trikeshed.cursor.`ColumnMeta↻`

interface StringHashCodec {
    val name: String
    fun hash(value: String): Long
}

object Utf8ByteHashCodec : StringHashCodec {
    override val name: String = "utf8-bytehash-31"

    override fun hash(value: String): Long {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        var acc = 982_451_653L
        for (byte in bytes) {
            acc = acc * 31L + (byte.toLong() and 0xFFL)
        }
        return acc
    }
}

class StringHashFacetCursor(
    private val strings: Series<String>,
    val codec: StringHashCodec = Utf8ByteHashCodec,
) {
    private data class HashFacet(
        val poolId: Int,
        val codecHash: Long,
        val value: String,
        val positions: IntArray,
    )

    private val occurrenceSchema: ColumnMeta by lazy {
        metaChain(
            "sourceIndex" to IOMemento.IoInt,
            "poolId" to IOMemento.IoInt,
            "codecHash" to IOMemento.IoLong,
            "value" to IOMemento.IoString,
        )
    }

    private val blackboardSchema: ColumnMeta by lazy {
        metaChain(
            "codecHash" to IOMemento.IoLong,
            "poolId" to IOMemento.IoInt,
            "value" to IOMemento.IoString,
            "count" to IOMemento.IoInt,
            "wireproto" to IOMemento.IoBytes,
            "rows" to IOMemento.IoArray,
            tail = occurrenceSchema,
        )
    }

    private val sourceValueMeta: ColumnMeta by lazy {
        ColumnMeta("value", IOMemento.IoString, blackboardSchema)
    }

    private val facets: List<HashFacet> by lazy {
        collectFacets()
    }

    private val facetByPoolIdTableLazy = lazy {
        facets.associateBy { it.poolId }
    }

    private val facetsByCodecHashTableLazy = lazy {
        facets.groupBy { it.codecHash }
    }

    private val sourceCursor: Cursor by lazy {
        rowSeries(strings.a) { index ->
            rowOf(cell(strings.b(index), sourceValueMeta))
        }
    }

    private val blackboardCursor: Cursor by lazy {
        rowSeries(facets.size) { index ->
            facetRow(facets[index])
        }
    }

    private val hashIdSeries: Series<Int> by lazy {
        series(strings.a) { index -> StringPool.intern(strings.b(index)) }
    }

    private val codecHashSeries: Series<Long> by lazy {
        series(strings.a) { index -> codec.hash(strings.b(index)) }
    }

    fun cursor(): Cursor = sourceCursor

    fun facets(): Cursor = blackboardCursor

    fun hashBlackboard(): Cursor = blackboardCursor

    fun childCursor(): Cursor = blackboardCursor

    fun alternateHashTablesLoaded(): Boolean =
        facetByPoolIdTableLazy.isInitialized() && facetsByCodecHashTableLazy.isInitialized()

    fun hashIds(): Series<Int> = hashIdSeries

    fun codecHashes(): Series<Long> = codecHashSeries

    fun at(index: Int): RowVec = cursor().b(index)

    fun facetByPoolId(poolId: Int): RowVec? = facetByPoolIdTableLazy.value[poolId]?.let(::facetRow)

    fun facetsByCodecHash(codecHash: Long): Cursor {
        val matches = facetsByCodecHashTableLazy.value[codecHash].orEmpty()
        return rowSeries(matches.size) { index ->
            facetRow(matches[index])
        }
    }

    private fun collectFacets(): List<HashFacet> {
        val positionsByPool = LinkedHashMap<Int, MutableList<Int>>()
        val valueByPool = LinkedHashMap<Int, String>()
        val codecHashByPool = LinkedHashMap<Int, Long>()
        for (index in 0 until strings.a) {
            val value = strings.b(index)
            val poolId = StringPool.intern(value)
            positionsByPool.getOrPut(poolId) { ArrayList() }.add(index)
            valueByPool.putIfAbsent(poolId, value)
            codecHashByPool.putIfAbsent(poolId, codec.hash(value))
        }
        return (positionsByPool.entries α { entry ->
            HashFacet(
                poolId = entry.key,
                codecHash = codecHashByPool.getValue(entry.key),
                value = valueByPool.getValue(entry.key),
                positions = entry.value.toIntArray(),
            )
        }).view.toList()
    }

    private fun facetRow(facet: HashFacet): RowVec = rowOf(
        cell(facet.codecHash, ColumnMeta("codecHash", IOMemento.IoLong)),
        cell(facet.poolId, ColumnMeta("poolId", IOMemento.IoInt)),
        cell(facet.value, ColumnMeta("value", IOMemento.IoString)),
        cell(facet.positions.size, ColumnMeta("count", IOMemento.IoInt)),
        cell(MemSegment(encodeFacetWireproto(facet)), ColumnMeta("wireproto", IOMemento.IoBytes)),
        cell(occurrenceCursor(facet), ColumnMeta("rows", IOMemento.IoArray, occurrenceSchema)),
    )

    private fun occurrenceCursor(facet: HashFacet): Cursor = rowSeries(facet.positions.size) { ordinal ->
        val sourceIndex = facet.positions[ordinal]
        rowOf(
            cell(sourceIndex, ColumnMeta("sourceIndex", IOMemento.IoInt)),
            cell(facet.poolId, ColumnMeta("poolId", IOMemento.IoInt)),
            cell(facet.codecHash, ColumnMeta("codecHash", IOMemento.IoLong)),
            cell(strings.b(sourceIndex), ColumnMeta("value", IOMemento.IoString)),
        )
    }

    private fun encodeFacetWireproto(facet: HashFacet): ByteArray {
        val valueBytes = facet.value.toByteArray(StandardCharsets.UTF_8)
        val record = ByteBuffer.allocate(8 + 4 + 4 + valueBytes.size + 4).order(ByteOrder.BIG_ENDIAN)
        record.putLong(facet.codecHash)
        record.putInt(facet.poolId)
        record.putInt(valueBytes.size)
        record.put(valueBytes)
        record.putInt(facet.positions.size)
        return record.array()
    }
}

private data class LocalJoin<A, B>(
    override val a: A,
    override val b: B,
) : Join<A, B>

private fun <T> series(size: Int, get: (Int) -> T): Series<T> = LocalJoin(size, get)

private fun rowSeries(size: Int, get: (Int) -> RowVec): Cursor = LocalJoin(size, get)

private fun rowOf(vararg cells: Join<Any?, `ColumnMeta↻`>): RowVec = LocalJoin(cells.size) { index -> cells[index] }

private fun cell(value: Any?, meta: ColumnMeta): Join<Any?, `ColumnMeta↻`> = LocalJoin(value) { meta }

private fun metaChain(
    vararg columns: Pair<String, TypeMemento>,
    tail: ColumnMeta? = null,
): ColumnMeta {
    var child = tail
    for (index in columns.indices.reversed()) {
        val column = columns[index]
        child = ColumnMeta(column.first, column.second, child)
    }
    return child ?: error("metaChain requires at least one column")
}
