package borg.trikeshed.miniduck.tablespace

import borg.trikeshed.miniduck.BlockRowVec
import borg.trikeshed.miniduck.DocRowVec
import borg.trikeshed.cursor.RowVec
import borg.trikeshed.lib.*
import borg.trikeshed.lib.mutable.SeriesBuffer

// --- BlockStore SPI ---

interface BlockStore {
    suspend fun put(collection: CharSequence, block: BlockRowVec): CharSequence?
    suspend fun putWithId(collection: CharSequence, id: CharSequence, block: BlockRowVec)
    suspend fun remove(collection: CharSequence, blockId: CharSequence)
    suspend fun get(collection: CharSequence, blockId: CharSequence): BlockRowVec?
    suspend fun list(collection: CharSequence): List<CharSequence>
}

class InMemoryBlockStore : BlockStore {
    private val store: SeriesBuffer<Pair<CharSequence, SeriesBuffer<Pair<CharSequence, BlockRowVec>>>> = SeriesBuffer()
    private val idCounter: SeriesBuffer<Pair<CharSequence, Int>> = SeriesBuffer()

    private fun getOrCreateCollection(collection: CharSequence): SeriesBuffer<Pair<CharSequence, BlockRowVec>> {
        return store.view.find { it.first == collection }?.second ?: run {
            val buf: SeriesBuffer<Pair<CharSequence, BlockRowVec>> = SeriesBuffer()
            store.add(collection to buf)
            buf
        }
    }

    private fun getCollection(collection: CharSequence): SeriesBuffer<Pair<CharSequence, BlockRowVec>>? =
        store.view.find { it.first == collection }?.second

    private fun getIdCounter(collection: CharSequence): Int =
        idCounter.view.find { it.first == collection }?.second ?: 0

    private fun setIdCounter(collection: CharSequence, value: Int) {
        val existing = idCounter.view.find { it.first == collection }
        if (existing != null) {
            // replace in place isn't easy; remove and add
            idCounter.view.filter { it.first != collection }
            idCounter.add(collection to value)
        } else {
            idCounter.add(collection to value)
        }
    }

    override suspend fun put(collection: CharSequence, block: BlockRowVec): CharSequence? {
        val coll = getOrCreateCollection(collection)
        val nextId = getIdCounter(collection)
        setIdCounter(collection, nextId + 1)
        coll.add(nextId.toString() to block)
        return nextId.toString()
    }

    override suspend fun putWithId(collection: CharSequence, id: CharSequence, block: BlockRowVec) {
        getOrCreateCollection(collection).add(id to block)
    }

    override suspend fun remove(collection: CharSequence, blockId: CharSequence) {
        getCollection(collection)?.let { coll ->
            val filtered = coll.view.filter { it.first != blockId }
            coll.clear()
            filtered.forEach { coll.add(it) }
        }
    }

    override suspend fun get(collection: CharSequence, blockId: CharSequence): BlockRowVec? {
        return getCollection(collection)?.view?.find { it.first == blockId }?.second
    }

    override suspend fun list(collection: CharSequence): List<CharSequence> {
        return getCollection(collection)?.view?.map { it.first }?.toList() ?: emptyList()
    }
}

// --- Region ---

class Region(val name: CharSequence, val store: BlockStore)

// --- Schema discovery ---

data class ColumnSchema(val name: CharSequence)
data class TableSchema(val name: CharSequence, val columns: List<ColumnSchema>)

// --- Tablespace ---

class Tablespace(val name: CharSequence) {
    private val regions: SeriesBuffer<Region> = SeriesBuffer()

    suspend fun addRegion(region: Region) {
        regions.add(region)
    }

    suspend fun scan(collection: CharSequence): Series<RowVec> {
        val allRows: SeriesBuffer<RowVec> = SeriesBuffer()
        for (region in regions.view) {
            val blockIds = region.store.list(collection)
            for (blockId in blockIds) {
                val block = region.store.get(collection, blockId) ?: continue
                val child = block.child
                for (i in 0 until child.size) {
                    allRows.add(child[i])
                }
            }
        }
        return allRows.size j { allRows[it] }
    }

    suspend fun scanToJson(collection: CharSequence): CharSequence {
        val cursor = scan(collection)
        return cursor.toJson()
    }

    suspend fun discoverSchema(collection: CharSequence): TableSchema {
        val allKeys: SeriesBuffer<CharSequence> = SeriesBuffer()
        for (region in regions.view) {
            val blockIds = region.store.list(collection)
            for (blockId in blockIds) {
                val block = region.store.get(collection, blockId) ?: continue
                val child = block.child
                for (i in 0 until child.size) {
                    val row = child[i]
                    for (j in 0 until row.size) {
                        val meta = row[j]
                        allKeys.add(meta.b().a)
                    }
                }
            }
        }
        return TableSchema(collection, allKeys.view.map { ColumnSchema(it) })
    }
}

// --- Series constructor for test convenience ---

fun <T> Series(size: Int, f: (Int) -> T): Series<T> = size j f

// --- Extension to convert Series<RowVec> to JSON ---

fun Series<RowVec>?.toJson(): CharSequence {
    val rows = this ?: return ""
    if (rows.isEmpty()) return ""
    val json = StringBuilder()
    for (rowIndex in 0 until rows.size) {
        if (rowIndex > 0) json.append('\n')
        val row = rows[rowIndex]
        json.append('{')
        for (colIndex in 0 until row.size) {
            if (colIndex > 0) json.append(',')
            val cell = row[colIndex]
            val meta = cell.b()
            val colName = meta.a
            json.append("\"$colName\": ")
            val value = cell.a
            when (value) {
                null -> json.append("null")
                is CharSequence -> json.append("\"$value\"")
                else -> json.append(value)
            }
        }
        json.append('}')
        if (row is DocRowVec) {
            val childJson = row.child.toJson()
            if (childJson.isNotBlank()) {
                json.append('\n').append(childJson)
            }
        }
    }
    return json.toString()
}
