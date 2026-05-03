package borg.trikeshed.miniduck.tablespace

import borg.trikeshed.miniduck.BlockRowVec
import borg.trikeshed.miniduck.DocRowVec
import borg.trikeshed.cursor.RowVec
import borg.trikeshed.lib.*

// --- BlockStore SPI ---

interface BlockStore {
    fun put(collection: String, block: BlockRowVec): String?
    fun get(collection: String, blockId: String): BlockRowVec?
    fun list(collection: String): List<String>
}

class InMemoryBlockStore : BlockStore {
    private val store: MutableMap<String, MutableMap<String, BlockRowVec>> = mutableMapOf()
    private val idCounter = mutableMapOf<String, Int>()

    override fun put(collection: String, block: BlockRowVec): String? {
        val coll = store.getOrPut(collection) { mutableMapOf() }
        val id = (idCounter.getOrPut(collection) { 0 }).toString()
        idCounter[collection] = id.toInt() + 1
        coll[id] = block
        return id
    }

    override fun get(collection: String, blockId: String): BlockRowVec? {
        return store[collection]?.get(blockId)
    }

    override fun list(collection: String): List<String> {
        return store[collection]?.keys?.toList() ?: emptyList()
    }
}

// --- Region ---

class Region(val name: String, val store: BlockStore)

// --- Schema discovery ---

data class ColumnSchema(val name: String)
data class TableSchema(val name: String, val columns: List<ColumnSchema>)

// --- Tablespace ---

class Tablespace(val name: String) {
    private val regions: MutableList<Region> = mutableListOf()

    fun addRegion(region: Region) {
        regions.add(region)
    }

    fun scan(collection: String): Series<RowVec> {
        val allRows = mutableListOf<RowVec>()
        for (region in regions) {
            val blockIds = region.store.list(collection)
            for (blockId in blockIds) {
                val block = region.store.get(collection, blockId) ?: continue
                val child = block.child
                if (child != null) {
                    for (i in 0 until child.size) {
                        allRows.add(child[i])
                    }
                }
            }
        }
        return allRows.size j { i -> allRows[i] }
    }

    fun scanToJson(collection: String): String {
        val cursor = scan(collection)
        return cursor.toJson()
    }

    fun discoverSchema(collection: String): TableSchema {
        val allKeys = mutableSetOf<String>()
        for (region in regions) {
            val blockIds = region.store.list(collection)
            for (blockId in blockIds) {
                val block = region.store.get(collection, blockId) ?: continue
                val child = block.child
                if (child != null) {
                    for (i in 0 until child.size) {
                        val row = child[i]
                        for (j in 0 until row.size) {
                            val meta = row[j]
                            allKeys.add(meta.b().a)
                        }
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

fun Series<RowVec>?.toJson(): String {
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
                is String -> json.append("\"$value\"")
                else -> json.append(value)
            }
        }
        json.append('}')
    }
    return json.toString()
}
