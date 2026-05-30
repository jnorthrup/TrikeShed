package borg.trikeshed.cascade

import borg.trikeshed.cursor.*
import borg.trikeshed.lib.*

/* ── Reading Column Ordinals ──────────────────────────────────────────── *
 *
 * Column layout for a generic readings tensor.
 * ColumnMeta↻ suppliers carry per-cell type evidence — lazy, not shared.
 *
 * Ordinal   Column                          IOMemento
 * ───────   ───────────────────────────────  ──────────
 *   0       group_0                         IoInt
 *   1       group_1                         IoInt
 *   2       group_2                         IoInt
 *   3       group_3                         IoInt
 *   4       entity_id                       IoInt
 *   5       reading_id                      IoLong (watermark key)
 *   6       reading_date (epoch ms)         IoLong
 *   7       interval                        IoInt
 *   8       metric_0                        IoDouble
 *   9       metric_1                        IoDouble
 *  10       metric_2                        IoDouble
 *  11       metric_3                        IoDouble
 *  12       metric_4                        IoDouble
 *  13       metric_5                        IoDouble
 *  14       metric_6                        IoDouble
 */

object Readings {
    // ── Key columns (grouping axes) ───────────────────────────────────
    const val COL_GROUP_0      = 0
    const val COL_GROUP_1      = 1
    const val COL_GROUP_2      = 2
    const val COL_GROUP_3      = 3
    const val COL_ENTITY       = 4
    const val COL_READING_ID   = 5   // watermark key
    const val COL_DATE         = 6   // epoch ms

    // ── Metric columns (reduce targets) ───────────────────────────────
    const val COL_INTERVAL     = 7
    const val COL_M0           = 8
    const val COL_M1           = 9
    const val COL_M2           = 10
    const val COL_M3           = 11
    const val COL_M4           = 12
    const val COL_M5           = 13
    const val COL_M6           = 14

    const val WIDTH = 15

    /** All metric column ordinals — targets for stats reduce. */
    val METRIC_COLS = intArrayOf(
        COL_INTERVAL, COL_M0, COL_M1, COL_M2,
        COL_M3, COL_M4, COL_M5, COL_M6
    )

    // ── Column names ──────────────────────────────────────────────────
    val NAMES = arrayOf(
        "group_0", "group_1", "group_2", "group_3",
        "entity_id", "reading_id", "reading_date", "interval",
        "metric_0", "metric_1", "metric_2",
        "metric_3", "metric_4", "metric_5", "metric_6",
    )

    // ── Column types ──────────────────────────────────────────────────
    val TYPES = arrayOf<TypeMemento>(
        IOMemento.IoInt,    // group_0
        IOMemento.IoInt,    // group_1
        IOMemento.IoInt,    // group_2
        IOMemento.IoInt,    // group_3
        IOMemento.IoInt,    // entity_id
        IOMemento.IoLong,   // reading_id
        IOMemento.IoLong,   // reading_date
        IOMemento.IoInt,    // interval
        IOMemento.IoDouble, // metric_0
        IOMemento.IoDouble, // metric_1
        IOMemento.IoDouble, // metric_2
        IOMemento.IoDouble, // metric_3
        IOMemento.IoDouble, // metric_4
        IOMemento.IoDouble, // metric_5
        IOMemento.IoDouble, // metric_6
    )

    /** RowVec metadata supplier for column [c]. Lazy, per-cell. */
    fun columnMeta(c: Int): `ColumnMeta↻` = {
        ColumnMeta(NAMES[c], TYPES[c])
    }

    /** Build a RowVec from a raw value array. */
    fun rowVec(values: Array<Any?>): RowVec =
        WIDTH j { c -> values[c] j columnMeta(c) }

    /** Extract year/month/day/hour/min from epoch-ms date column. */
    fun dateAxes(dateMs: Long): IntArray {
        // Pure Kotlin epoch-ms decomposition — no java.util.Calendar in commonMain
        val totalSec = dateMs / 1000
        val day = totalSec / 86400
        val rem = totalSec % 86400
        val hour = (rem / 3600).toInt()
        val minute = ((rem % 3600) / 60).toInt()
        // Julian day → year/month/day (simplified UTC)
        val z = day + 719468
        val era = (if (z >= 0) z else z - 146096) / 146097
        val doe = z - era * 146097
        val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365
        val y = yoe + era * 400
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        val mp = (5 * doy + 2) / 153
        val d = doy - (153 * mp + 2) / 5 + 1
        val m = if (mp < 10) mp + 3 else mp - 9
        val yyyy = (y + if (m <= 2) 1 else 0).toInt()
        return intArrayOf(yyyy, m.toInt(), d.toInt(), hour, minute)
    }
}
