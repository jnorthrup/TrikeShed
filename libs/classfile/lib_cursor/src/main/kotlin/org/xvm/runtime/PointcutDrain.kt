package org.xvm.runtime

import borg.trikeshed.cursor.ColumnMeta
import borg.trikeshed.cursor.RowVec
import borg.trikeshed.isam.IsamDataFile
import borg.trikeshed.isam.IsamMetaFileReader
import borg.trikeshed.isam.RecordMeta
import borg.trikeshed.isam.meta.IOMemento
import borg.trikeshed.lib.Join
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.j
import borg.trikeshed.lib.joins
import borg.trikeshed.lib.size
import borg.trikeshed.lib.get
import borg.trikeshed.userspace.nio.file.spi.JvmFileOperations
import org.xvm.cursor.PointcutFacet
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/**
 * Orchestrates the pointcut drain pipeline: lifecycle gate -> rollup -> ISAM file artifacts.
 *
 * Writes binary wireproto files via TrikeShed IsamDataFile.
 * RowVec is the row. The meta file describes column layout; the groups line
 * controls column-grouped flat files vs row-dominant packed records.
 *
 * Lifecycle: RUNNING -> drain() -> DRAINING -> shutdown() -> SHUTDOWN
 */
class PointcutDrain {

    private val lifecycle: XvmLifecycle
    private val outputDir: Path
    private val table: TypedefCascadeTable
    private var drained: Boolean = false
    private var shutDown: Boolean = false

    constructor(lifecycle: XvmLifecycle, table: TypedefCascadeTable, outputDir: Path) {
        this.lifecycle = lifecycle
        this.table = table
        this.outputDir = outputDir
    }

    fun drain() {
        if (drained) return
        if (!lifecycle.isRunning()) {
            throw IllegalStateException("drain() requires RUNNING, got " + lifecycle.state())
        }
        writeArtifacts()
        lifecycle.drain()
        drained = true
    }

    fun shutdown() {
        if (!lifecycle.isDraining()) {
            throw IllegalStateException("shutdown() requires DRAINING, got " + lifecycle.state())
        }
        shutDown = true
        lifecycle.shutdown()
    }

    val isDrained: Boolean get() = drained
    val isShutDown: Boolean get() = shutDown

    // ── ISAM artifact writers ────────────────────────────────────────────

    private fun writeArtifacts() {
        try {
            Files.createDirectories(outputDir)
            val fileOps = JvmFileOperations()

            // 1. Cascade table rows -> ISAM
            val tableCursor = cascadeTableCursor()
            IsamDataFile.write(tableCursor, outputDir.resolve("table_dump.bin").toString(), emptyMap(), fileOps)

            // 2. Cascade rollup tiers — each tier gets its own ISAM file
            //    because each has different bucket counts
            val snap = CascadeRollup.cascadeRollup(table)
            val tierNames = listOf("leafscan", "kind_merge", "scope_rollup", "joint")
            for (i in snap.indices) {
                val tierCursor = singleTierCursor(snap[i])
                IsamDataFile.write(tierCursor, outputDir.resolve("cascade_${tierNames[i]}.bin").toString(), emptyMap(), fileOps)
            }

            // 3. Joint histogram -> ISAM (kind x scope = 36 rows)
            val joint = snap[3].jointHistogram
            if (joint != null) {
                val jointCursor = jointHistogramCursor(joint)
                IsamDataFile.write(jointCursor, outputDir.resolve("joint_histogram.bin").toString(), emptyMap(), fileOps)
            }
        } catch (e: IOException) {
            throw RuntimeException("Failed to write drain artifacts", e)
        }
    }

    // ── RowVec builders ──────────────────────────────────────────────────
    //
    // RowVec = Series2<Any?, () -> ColumnMeta>
    // Built via: values joins metaSuppliers
    //   values:     Int j { col -> theValue }
    //   suppliers:  Int j { col -> { -> columnMeta } }

    companion object {
        // RecordMeta with correct IOMemento types — networkSize must be non-null for ISAM
        //
        // Column groups for the cascade table:
        //   group "bytes" (id=0): kind, depth, scope, success — histogram lanes, always scanned together
        //   implicit  (id=1):      site_ord, pool_id        — join keys, random access
        //
        // Meta file groups line will read:  0-3:bytes
        // This produces:  table_dump.bytes.bin  (4 byte columns, flat array)
        //                table_dump.bin         (2 int columns, row-dominant)
        private val KIND_META    = RecordMeta("kind",    IOMemento.IoByte, groupId = 0, groupName = "bytes")
        private val DEPTH_META   = RecordMeta("depth",   IOMemento.IoByte, groupId = 0, groupName = "bytes")
        private val SCOPE_META   = RecordMeta("scope",   IOMemento.IoByte, groupId = 0, groupName = "bytes")
        private val SUCCESS_META = RecordMeta("success", IOMemento.IoByte, groupId = 0, groupName = "bytes")
        private val SITE_ORD_META = RecordMeta("site_ord", IOMemento.IoInt, groupId = 1, groupName = "1")
        private val POOL_ID_META  = RecordMeta("pool_id",  IOMemento.IoInt, groupId = 1, groupName = "1")

        private val TABLE_METAS = listOf(KIND_META, DEPTH_META, SCOPE_META, SUCCESS_META, SITE_ORD_META, POOL_ID_META)

        private val TIER_META         = RecordMeta("tier", IOMemento.IoInt)
        private val TOTAL_EVENTS_META = RecordMeta("total_events", IOMemento.IoLong)

        private val KIND_JOINT_META  = RecordMeta("kind",  IOMemento.IoInt)
        private val SCOPE_JOINT_META = RecordMeta("scope", IOMemento.IoInt)
        private val COUNT_JOINT_META = RecordMeta("count", IOMemento.IoLong)
    }

    private fun cascadeTableCursor(): Join<Int, (Int) -> RowVec> {
        val n = table.rowCount()
        val kind = table.kindColumn()
        val depth = table.depthColumn()
        val scope = table.scopeColumn()
        val success = table.successColumn()
        val siteOrd = table.siteOrdColumn()
        val poolId = table.poolIdColumn()

        return n j { row: Int ->
            val values: Series<Any?> = 6 j { col: Int ->
                when (col) {
                    0 -> kind[row]
                    1 -> depth[row]
                    2 -> scope[row]
                    3 -> success[row]
                    4 -> siteOrd[row]
                    5 -> poolId[row]
                    else -> throw IndexOutOfBoundsException(col)
                }
            }
            val metas: Series<() -> ColumnMeta> = 6 j { col: Int ->
                { -> TABLE_METAS[col] }
            }
            values joins metas
        }
    }

    /**
     * Single tier snapshot as a 1-row Cursor: tier, totalEvents, buckets..., joint...
     */
    private fun singleTierCursor(t: CascadeRollup.TierSnapshot): Join<Int, (Int) -> RowVec> {
        val bucketCount = t.buckets.size
        val jointCount = t.jointHistogram?.size ?: 0
        val totalCols = 2 + bucketCount + jointCount

        return 1 j { _: Int ->
            val values: Series<Any?> = totalCols j { col: Int ->
                when {
                    col == 0 -> t.tier
                    col == 1 -> t.totalEvents
                    col < 2 + bucketCount -> t.buckets[col - 2]
                    else -> t.jointHistogram!![col - 2 - bucketCount]
                }
            }
            val metas: Series<() -> ColumnMeta> = totalCols j { col: Int ->
                when {
                    col == 0 -> { -> TIER_META }
                    col == 1 -> { -> TOTAL_EVENTS_META }
                    else -> { -> RecordMeta("bucket_$col", IOMemento.IoLong) }
                }
            }
            values joins metas
        }
    }

    private fun jointHistogramCursor(joint: LongArray): Join<Int, (Int) -> RowVec> {
        val K = TypedefCascadeTable.KIND_COUNT
        val S = TypedefCascadeTable.SCOPE_COUNT

        return (K * S) j { idx: Int ->
            val k = idx / S
            val s = idx % S
            val values: Series<Any?> = 3 j { col: Int ->
                when (col) {
                    0 -> k
                    1 -> s
                    2 -> joint[idx]
                    else -> throw IndexOutOfBoundsException(col)
                }
            }
            val metas: Series<() -> ColumnMeta> = 3 j { col: Int ->
                when (col) {
                    0 -> { -> KIND_JOINT_META }
                    1 -> { -> SCOPE_JOINT_META }
                    2 -> { -> COUNT_JOINT_META }
                    else -> throw IndexOutOfBoundsException(col)
                }
            }
            values joins metas
        }
    }
}
