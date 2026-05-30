package borg.trikeshed.cascade

import borg.trikeshed.cursor.*
import borg.trikeshed.lib.*

/* ── Cascade Views — N-level groupBy projections ───────────────────── *
 *
 * Each view is a different eigenbasis of the readings tensor.
 * The reduce monoid (StatsReduce) is the invariant inner product.
 * Key prefixes determine which eigenvectors are preserved and which collapse.
 *
 * Level 1: byEntity   — [entity, Y, M, D, H, min]
 * Level 2: byGroup3   — [group_3, entity, Y, M, D, H, min]
 * Level 3: byGroup2   — [group_2, entity, Y, M, D, H, min]
 * Level 4: byGroup1   — [group_1, entity, Y, M, D, H, min]
 * Level 5: byGroup0   — [group_0, entity, Y, M, D, H, min]
 *
 * All views are lazy — Cursor groupBy defers materialization until access.
 * RowVec K carries ColumnMeta↻ per cell — type evidence flows through.
 */

object CascadeViews {

    /**
     * Augment a raw readings Cursor with date axis columns.
     * Returns an augmented Cursor with 5 extra columns: year, month, day, hour, minute.
     */
    fun Cursor.withDateAxes(): Cursor {
        val dateNames = arrayOf("year", "month", "day", "hour", "minute")
        val dateTypes = arrayOf<TypeMemento>(
            IOMemento.IoInt, IOMemento.IoInt, IOMemento.IoInt,
            IOMemento.IoInt, IOMemento.IoInt,
        )
        return size j { row ->
            val rv = this[row]
            val dateMs = rv[Readings.COL_DATE].a as? Long ?: 0L
            val axes = Readings.dateAxes(dateMs)
            (Readings.WIDTH + 5) j { c ->
                if (c < Readings.WIDTH) rv[c]
                else {
                    val dc = c - Readings.WIDTH
                    axes[dc] j { ColumnMeta(dateNames[dc], dateTypes[dc]) }
                }
            }
        }
    }

    // ── Date axis ordinals in the augmented Cursor ────────────────────
    private const val AX_YEAR   = Readings.WIDTH
    private const val AX_MONTH  = Readings.WIDTH + 1
    private const val AX_DAY    = Readings.WIDTH + 2
    private const val AX_HOUR   = Readings.WIDTH + 3
    private const val AX_MINUTE = Readings.WIDTH + 4

    /** Date axes shared by all views. */
    private val DATE_AXES = intArrayOf(AX_YEAR, AX_MONTH, AX_DAY, AX_HOUR, AX_MINUTE)

    // ── Level 1: byEntity ─────────────────────────────────────────────
    /** [entity_id, Y, M, D, H, min] */
    fun Cursor.byEntity(): Cursor {
        val aug = withDateAxes()
        return aug.statsGroupBy(intArrayOf(Readings.COL_ENTITY, *DATE_AXES))
    }

    // ── Level 2: byGroup3 ─────────────────────────────────────────────
    /** [group_3, entity_id, Y, M, D, H, min] */
    fun Cursor.byGroup3(): Cursor {
        val aug = withDateAxes()
        return aug.statsGroupBy(intArrayOf(Readings.COL_GROUP_3, Readings.COL_ENTITY, *DATE_AXES))
    }

    // ── Level 3: byGroup2 ─────────────────────────────────────────────
    /** [group_2, entity_id, Y, M, D, H, min] */
    fun Cursor.byGroup2(): Cursor {
        val aug = withDateAxes()
        return aug.statsGroupBy(intArrayOf(Readings.COL_GROUP_2, Readings.COL_ENTITY, *DATE_AXES))
    }

    // ── Level 4: byGroup1 ─────────────────────────────────────────────
    /** [group_1, entity_id, Y, M, D, H, min] */
    fun Cursor.byGroup1(): Cursor {
        val aug = withDateAxes()
        return aug.statsGroupBy(intArrayOf(Readings.COL_GROUP_1, Readings.COL_ENTITY, *DATE_AXES))
    }

    // ── Level 5: byGroup0 ─────────────────────────────────────────────
    /** [group_0, entity_id, Y, M, D, H, min] */
    fun Cursor.byGroup0(): Cursor {
        val aug = withDateAxes()
        return aug.statsGroupBy(intArrayOf(Readings.COL_GROUP_0, Readings.COL_ENTITY, *DATE_AXES))
    }

    // ── All 5 views as a Series<Cursor> ───────────────────────────────
    /**
     * Produce all 5 cascade views from a readings Cursor.
     * Order: byEntity, byGroup3, byGroup2, byGroup1, byGroup0.
     */
    fun Cursor.allViews(): Series<Cursor> = 5 j { level ->
        when (level) {
            0 -> byEntity()
            1 -> byGroup3()
            2 -> byGroup2()
            3 -> byGroup1()
            4 -> byGroup0()
            else -> error("Invalid cascade level: $level")
        }
    }

    /** View names in level order. */
    val VIEW_NAMES = arrayOf(
        "byEntity", "byGroup3", "byGroup2", "byGroup1", "byGroup0",
    )
}
