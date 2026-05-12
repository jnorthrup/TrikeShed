package borg.trikeshed.miniduck.tablespace

import borg.trikeshed.miniduck.BlockRowVec
import borg.trikeshed.miniduck.DocRowVec
import borg.trikeshed.cursor.RowVec
import borg.trikeshed.lib.*

// --- BlockStore SPI ---

interface BlockStore {
    fun put(collection: CharSequence, block: BlockRowVec): CharSequence?
    fun putWithId(collection: CharSequence, id: CharSequence, block: BlockRowVec)
    fun remove(collection: CharSequence, blockId: CharSequence)
    fun get(collection: CharSequence, blockId: CharSequence): BlockRowVec?
    fun list(collection: CharSequence): List<CharSequence>
}

class InMemoryBlockStore : BlockStore {
    private val store: MutableMap<CharSequence, MutableMap<CharSequence, BlockRowVec>> = mutableMapOf()
    private val idCounter = mutableMapOf<CharSequence, Int>()

    override fun put(collection: CharSequence, block: BlockRowVec): CharSequence? {
        val coll = store.getOrPut(collection) { mutableMapOf() }
        val id = (idCounter.getOrPut(collection) { 0 }).toString()
        idCounter[collection] = id.toInt() + 1
        coll[id] = block
        return id
    }

    override fun putWithId(collection: CharSequence, id: CharSequence, block: BlockRowVec) {
        store.getOrPut(collection) { mutableMapOf() }[id] = block
    }

    override fun remove(collection: CharSequence, blockId: CharSequence) {
        store[collection]?.remove(blockId)
    }

    override fun get(collection: CharSequence, blockId: CharSequence): BlockRowVec? {
        return store[collection]?.get(blockId)
    }

    override fun list(collection: CharSequence): List<CharSequence> {
        return store[collection]?.keys?.toList() ?: emptyList()
    }
}

// --- Region ---

class Region(val name: CharSequence, val store: BlockStore)

// --- Schema discovery ---

data class ColumnSchema(val name: CharSequence)
data class TableSchema(val name: CharSequence, val columns: List<ColumnSchema>)

// --- Tablespace ---

class Tablespace(val name: CharSequence) {
    private val regions: MutableList<Region> = mutableListOf()

    fun addRegion(region: Region) {
        regions.add(region)
    }

    fun scan(collection: CharSequence): Series<RowVec> {
        val allRows = mutableListOf<RowVec>()
        for (region in regions) {
            val blockIds = region.store.list(collection)
            for (blockId in blockIds) {
                val block = region.store.get(collection, blockId) ?: continue
                val child = block.child
                for (i in 0 until child.size) {
                    allRows.add(child[i])
                }
            }
        }
        return allRows.size j { i -> allRows[i] }
    }

    fun scanToJson(collection: CharSequence): CharSequence {
        val cursor = scan(collection)
        return cursor.toJson()
    }

    fun discoverSchema(collection: CharSequence): TableSchema {
        val allKeys = mutableSetOf<CharSequence>()
        for (region in regions) {
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
        return TableSchema(collection, allKeys.map { ColumnSchema(it) })
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
