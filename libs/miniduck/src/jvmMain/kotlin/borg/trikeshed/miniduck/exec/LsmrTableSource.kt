package borg.trikeshed.miniduck.exec

import borg.trikeshed.userspace.database.LsmrDatabase
import borg.trikeshed.lib.*
import borg.trikeshed.miniduck.schema.TableSchema

/**
 * TableSource backed by the LsmrDatabase. Simple row serialization for primitives and strings.
 * Keys used:
 *  - miniduck:table:{table}:count -> number of rows (as UTF-8 decimal)
 *  - miniduck:table:{table}:row:{idx} -> serialized row bytes
 */
import borg.trikeshed.miniduck.runBlockingCommon
import borg.trikeshed.miniduck.MiniDuckBlockCodec
import borg.trikeshed.miniduck.BlockRowVec
import borg.trikeshed.miniduck.DocRowVec
import borg.trikeshed.miniduck.MiniRowVec
import borg.trikeshed.lib.*

class LsmrTableSource(private val db: LsmrDatabase, private val blockSizeThreshold: Int = 128) : TableSource {

    private val mutableBlocks = mutableMapOf<String, BlockRowVec>()
    private fun blockCountKey(table: String) = "miniduck:table:$table:blockcount"
    private fun blockKey(table: String, idx: Int) = "miniduck:table:$table:block:$idx"

    private fun countKey(table: String) = "miniduck:table:$table:count"
    private fun rowKey(table: String, idx: Int) = "miniduck:table:$table:row:$idx"

    private fun serializeRow(row: List<Any?>): ByteArray {
        val parts = row.map { v ->
            when (v) {
                null -> "N"
                is Int -> "I${v}"
                is Long -> "L${v}"
                is Double -> "D${v}"
                is Float -> "F${v}"
                is String -> "S${v.length}:${v}"
                else -> "S${v.toString().length}:${v.toString()}"
            }
        }
        val s = parts.joinToString("\u001F")
        return s.toByteArray(Charsets.UTF_8)
    }

    private fun deserializeRow(bytes: ByteArray): List<Any?> {
        val s = String(bytes, Charsets.UTF_8)
        if (s.isEmpty()) return emptyList()
        return s.split("\u001F").map { token ->
            when {
                token == "N" -> null
                token.startsWith("I") -> token.substring(1).toIntOrNull()
                token.startsWith("L") -> token.substring(1).toLongOrNull()
                token.startsWith("D") -> token.substring(1).toDoubleOrNull()
                token.startsWith("F") -> token.substring(1).toFloatOrNull()
                token.startsWith("S") -> {
                    val rest = token.substring(1)
                    val idx = rest.indexOf(':')
                    if (idx >= 0) {
                        val lenStr = rest.substring(0, idx)
                        val data = rest.substring(idx + 1)
                        val len = lenStr.toIntOrNull()
                        if (len != null && len <= data.length) {
                            data.substring(0, len)
                        } else {
                            // fallback to whatever is present if length parse failed or data shorter than declared
                            data
                        }
                    } else rest
                }
                else -> token
            }
        }
    }

    private fun parseBlockCount(raw: ByteArray?): Int = raw?.let { String(it, Charsets.UTF_8).toIntOrNull() } ?: 0

    /**
     * Persist any in-memory mutable block for [tableName] as a sealed block in the DB.
     * This is useful for tests and deterministic flush points.
     */
    suspend fun flushMutableSuspend(tableName: String) {
        val blk = mutableBlocks[tableName] ?: return
        if (blk.rowCount <= 0) return
        val sealed = blk.seal()
        val rawCount = db.get(blockCountKey(tableName))
        val blockIndex = parseBlockCount(rawCount)
        db.put(blockKey(tableName, blockIndex), MiniDuckBlockCodec.encode(sealed).toByteArray(Charsets.UTF_8))
        db.put(blockCountKey(tableName), (blockIndex + 1).toString().toByteArray(Charsets.UTF_8))
        mutableBlocks[tableName] = BlockRowVec.mutable()
    }

    fun flushMutable(tableName: String) = runBlockingCommon { flushMutableSuspend(tableName) }

    // Suspend-aware open that reads from the LSMR db without blocking.
    override suspend fun openSuspend(execCtx: ExecutionContext, tableName: String): Cursor {
        // Prefer block-based storage (chunked sealed BlockRowVecs). Fallback to legacy per-row storage if no blocks found.
        val rows = mutableListOf<MiniRowVec>()

        val blockCountRaw = db.get(blockCountKey(tableName))
        if (blockCountRaw != null) {
            val blockCount = String(blockCountRaw, Charsets.UTF_8).toIntOrNull() ?: 0
            for (i in 0 until blockCount) {
                val raw = db.get(blockKey(tableName, i)) ?: continue
                val block = MiniDuckBlockCodec.decode(String(raw, Charsets.UTF_8))
                // map child rows to schema-aware keys when possible
                val schemaNames = execCtx.schemaManager.getTableSuspend(tableName)?.columns?.map { it.name }
                for (j in 0 until block.child.size) {
                    val childRow = block.child[j]
                    val mapped = if (childRow is DocRowVec && schemaNames != null && schemaNames.isNotEmpty()) {
                        DocRowVec(schemaNames.take(childRow.keys.size), childRow.cells)
                    } else childRow
                    rows.add(mapped)
                }
            }
        } else {
            // Legacy per-row layout
            val count = db.get(countKey(tableName))?.let { String(it, Charsets.UTF_8).toIntOrNull() } ?: 0
            for (i in 0 until count) {
                db.get(rowKey(tableName, i))?.let { bytes ->
                    val list = deserializeRow(bytes)
                    val keys = execCtx.schemaManager.getTableSuspend(tableName)?.columns?.map { it.name }
                        ?: List(list.size) { idx -> "c$idx" }
                    rows.add(DocRowVec(keys.take(list.size), list))
                }
            }
        }

        // include in-memory mutable block if present
        mutableBlocks[tableName]?.let { blk ->
            val schemaNames = execCtx.schemaManager.getTableSuspend(tableName)?.columns?.map { it.name }
            for (j in 0 until blk.child.size) {
                val childRow = blk.child[j]
                val mapped = if (childRow is DocRowVec && schemaNames != null && schemaNames.isNotEmpty()) {
                    DocRowVec(schemaNames.take(childRow.keys.size), childRow.cells)
                } else childRow
                rows.add(mapped)
            }
        }

        val schema = execCtx.schemaManager.getTableSuspend(tableName) ?: TableSchema(tableName, emptyList())
        val nameToIndex: Map<String, Int> = schema.columns.mapIndexed { i, c -> c.name to i }.toMap()
        var idx = -1
        return object : Cursor {
            override fun next(): Boolean {
                if (idx + 1 < rows.size) { idx++; return true }
                return false
            }

            override val row: RowAccessor
                get() = object : RowAccessor {
                    override fun get(index: Int): Any? = rows.getOrNull(idx)?.let { try { it.get(index) } catch (e: Throwable) { null } }
                    override fun get(name: String): Any? {
                        val colIndex = nameToIndex[name] ?: -1
                        return if (colIndex >= 0) get(colIndex) else null
                    }
                }

            override fun close() {}
        }
    }

    // Backwards-compatible synchronous wrapper for open
    override fun open(execCtx: ExecutionContext, tableName: String): Cursor = runBlockingCommon { openSuspend(execCtx, tableName) }

    fun seedRows(tableName: String, rows: List<List<Any?>>) {
        runBlockingCommon {
            // Attempt to read an existing schema from the LSMR keyspace so seeded rows
            // can use real column names (e.g., id,name) instead of generic c0,c1.
            val schemaRaw = db.get("miniduck:schema:$tableName")
            val keysForRows: List<String> = if (schemaRaw != null) {
                val s = String(schemaRaw, Charsets.UTF_8)
                val parts = s.split('|', limit = 2)
                val colsPart = if (parts.size > 1) parts[1] else parts[0]
                if (colsPart.isEmpty()) emptyList() else colsPart.split(',')
            } else {
                emptyList()
            }

            // Write all provided rows as a single sealed block (chunked storage)
            val block = BlockRowVec.mutable()
            rows.forEach { r ->
                val keys = if (keysForRows.isNotEmpty()) keysForRows.take(r.size) else (0 until r.size).map { idx -> "c$idx" }
                block.append(DocRowVec(keys, r))
            }
            val sealed = block.seal()
            db.put(blockKey(tableName, 0), MiniDuckBlockCodec.encode(sealed).toByteArray(Charsets.UTF_8))
            db.put(blockCountKey(tableName), "1".toByteArray(Charsets.UTF_8))
        }
    }

    override suspend fun insertSuspend(execCtx: ExecutionContext, tableName: String, row: List<Any?>) {
        // Ensure schema has enough columns (use generic names if not present)
        val cols = (0 until row.size).map { idx -> "c$idx" }
        val schema = execCtx.schemaManager.ensureColumnsSuspend(tableName, cols)
        val keys = schema.columns.take(row.size).map { it.name }
        val doc = DocRowVec(keys, row)

        val blk = mutableBlocks.getOrPut(tableName) { BlockRowVec.mutable() }
        blk.append(doc)

        if (blk.rowCount >= blockSizeThreshold) {
            val sealed = blk.seal()
            val rawCount = db.get(blockCountKey(tableName))
            val blockIndex = rawCount?.let { String(it, Charsets.UTF_8).toIntOrNull() } ?: 0
            db.put(blockKey(tableName, blockIndex), MiniDuckBlockCodec.encode(sealed).toByteArray(Charsets.UTF_8))
            db.put(blockCountKey(tableName), (blockIndex + 1).toString().toByteArray(Charsets.UTF_8))
            mutableBlocks[tableName] = BlockRowVec.mutable()
        }
    }

    // Backwards-compatible synchronous wrapper for insert
    override fun insert(execCtx: ExecutionContext, tableName: String, row: List<Any?>) = runBlockingCommon { insertSuspend(execCtx, tableName, row) }
}
