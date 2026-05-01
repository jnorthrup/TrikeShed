package borg.trikeshed.miniduck.tablespace

import borg.trikeshed.cursor.RowVec
import borg.trikeshed.miniduck.BlockRowVec
import borg.trikeshed.miniduck.MiniCursor
import borg.trikeshed.lib.*
import borg.trikeshed.miniduck.schema.ColumnSchema
import borg.trikeshed.miniduck.schema.TableSchema
import borg.trikeshed.miniduck.toJson
import borg.trikeshed.miniduck.toRowVec

/**
 * Region: a named locality (datacenter, zone, rack) backed by a BlockStore.
 *
 * A region is the deployment unit — blocks live in exactly one region.
 * Tablespace scans fan out across regions and merge results.
 */
class Region(
    val name: String,
    val store: BlockStore,
)

/**
 * Tablespace: a named collection of regions.
 *
 * Collections (like CouchDB databases) are implicit — they exist
 * as soon as you put a block into them. Schema is discovered from
 * the documents themselves, not declared upfront.
 *
 * Scan merges blocks from all regions into a single MiniCursor
 * (Series<MiniRowVec>) — the standard couch cursor algebra.
 */
class Tablespace(val name: String) {

   val _regions = mutableListOf<Region>()
    val regions: List<Region> get() = _regions.toList()

    fun addRegion(region: Region) {
        _regions.add(region)
    }

    /**
     * Scan a collection across all regions.
     * Returns a MiniCursor over all rows from all sealed blocks
     * in all regions for the given [collection].
     *
     * Order: region-sequential, block-sequential, row-sequential.
     * For custom ordering, pipe the cursor through CursorOps (orderBy, etc.).
     */
    fun scan(collection: String): MiniCursor {
        val rows = mutableListOf<RowVec>()
        for (region in _regions) {
            for (blockId in region.store.list(collection)) {
                val block = region.store.get(collection, blockId) ?: continue
                if (block.state != BlockRowVec.State.SEALED) continue
                val childSeries = block.child ?: continue
                for (i in 0 until childSeries.size) {
                    rows.add(childSeries[i])
                }
            }
        }
        return rows.size j { index: Int -> rows[index].toRowVec() }
    }

    /**
     * Scan and project to NDJSON string.
     * Convenience: scan → toJson in one call.
     */
    fun scanToJson(collection: String): String = scan(collection).toJson()

    /**
     * Discover implicit schema from a collection.
     * Scans all rows across all regions and unions all DocRowVec keys.
     * Returns a TableSchema with columns ordered by first-seen.
     */
    fun discoverSchema(collection: String): TableSchema {
        val seen = linkedSetOf<String>()
        val cursor = scan(collection)
        for (i in 0 until cursor.size) {
            val row = cursor[i]
            for (idx in 0 until row.size) {
                seen.add(row.b(idx).b().a)
            }
        }
        return TableSchema(
            name = collection,
            columns = seen.mapIndexed { idx, name -> ColumnSchema(idx, name) }
        )
    }
}
