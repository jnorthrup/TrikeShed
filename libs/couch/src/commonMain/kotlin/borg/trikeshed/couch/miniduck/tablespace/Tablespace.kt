package borg.trikeshed.couch.miniduck.tablespace

import borg.trikeshed.couch.miniduck.BlockRowVec
import borg.trikeshed.couch.miniduck.DocRowVec
import borg.trikeshed.couch.miniduck.MiniCursor
import borg.trikeshed.couch.miniduck.MiniRowVec
import borg.trikeshed.lib.*
import borg.trikeshed.couch.miniduck.schema.ColumnSchema
import borg.trikeshed.couch.miniduck.schema.TableSchema
import borg.trikeshed.couch.miniduck.toJson

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

    private val _regions = mutableListOf<Region>()
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
        val rows = mutableListOf<MiniRowVec>()
        for (region in _regions) {
            for (blockId in region.store.list(collection)) {
                val block = region.store.get(collection, blockId) ?: continue
                if (block.state != BlockRowVec.State.SEALED) continue
                val childSeries = block.child as? Series<MiniRowVec> ?: continue
                for (i in 0 until childSeries.size) {
                    rows.add(childSeries[i])
                }
            }
        }
        return rows.size j { rows[it] }
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
            if (row is DocRowVec) {
                val keys = (0 until row.size).mapNotNull { idx ->
                    // DocRowVec exposes keys in the first N entries
                    row.keys.getOrNull(idx)
                }
                seen.addAll(keys)
            }
        }
        return TableSchema(
            name = collection,
            columns = seen.mapIndexed { idx, name -> ColumnSchema(idx, name) }
        )
    }
}
