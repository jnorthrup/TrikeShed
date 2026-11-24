package narchy.spacegraph.layout

// Port of upstream src/layout/placement.ts at 125b369.
// The placement seam: a layout strategy decides which boxes exist; a placement engine answers where a box goes.
// Position is the port's existing Vec3; the core imports no renderer or contract.

import narchy.spacegraph.Vec3

typealias Position = Vec3

/** A box a placement engine places. */
data class PlacementItem<Meta>(
    val id: String,
    /** The box's width. */
    val width: Double,
    /** The box's height, for the engines that measure it. */
    val height: Double? = null,
    /** The depth recorded in Z. */
    val depth: Double? = null,
    /** The engine's own vocabulary. */
    val meta: Meta? = null,
)

/**
 * A placement engine. It answers "where does this box go"; it never decides which boxes exist.
 * `previous` holds the positions a layout already assigned; an engine that honours the append-only rule keeps them.
 */
interface Placement<Meta> {
    /** The engine's name, for the layout's diagnostics. */
    val name: String

    fun place(items: List<PlacementItem<Meta>>, previous: Map<String, Position>? = null): Map<String, Position>
}
