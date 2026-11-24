package narchy.spacegraph.representations

// Port of upstream src/edges/ladder.ts at 125b369: one edge ladder, bundle -> hair -> arrow -> labelled causal ribbon.
// The library selects a rung from a ScaleDomain sample; the host owns the styles and may replace or reorder every rung.
// Deviation: EdgeRungStyle is a data class of nullable fields; `labelLod` is kept as a list of distances.

import narchy.spacegraph.zoom.ScaleSample

val EDGE_RUNG_IDS: List<String> = listOf("bundle", "hair", "arrow", "ribbon")

data class EdgeLabelLod(val distance: Double)

data class EdgeRungStyle(
    val thickness: Double? = null,
    val arrowhead: String? = null,
    val dashed: Boolean? = null,
    val labelLod: List<EdgeLabelLod>? = null,
)

/** A host may adopt these as a starting point; none is a recommended default. */
val DEFAULT_EDGE_STYLES: Map<String, EdgeRungStyle> = mapOf(
    "bundle" to EdgeRungStyle(thickness = 1.0, dashed = true),
    "hair" to EdgeRungStyle(thickness = 1.0),
    "arrow" to EdgeRungStyle(thickness = 2.0, arrowhead = "target"),
    "ribbon" to EdgeRungStyle(thickness = 4.0, arrowhead = "target", labelLod = listOf(EdgeLabelLod(0.0))),
)

interface LadderEdge : LadderEntry

data class EdgeRung(
    override val id: String,
    override val at: Double,
    /** Edge kinds served by this rung; null means every kind. */
    override val kinds: List<String>? = null,
    override val minImportance: Double? = null,
    val style: EdgeRungStyle? = null,
) : Rung, RungGate

data class EdgeSelection(
    val id: String,
    val rung: EdgeRung,
    val style: EdgeRungStyle,
)

class EdgeLadder(
    rungs: List<EdgeRung> = emptyList(),
    hysteresis: Double? = null,
) : LadderBase<LadderEdge, EdgeRung, EdgeSelection>(rungs, hysteresis) {

    override fun createSelection(edge: LadderEdge, sample: ScaleSample, rung: EdgeRung): EdgeSelection =
        EdgeSelection(id = edge.id, rung = rung, style = rung.style ?: EdgeRungStyle())
}

/** Align rungs with scale stops; a missing style removes that rung. */
fun edgeLadderFromStops(
    stops: List<RungStop>,
    styles: Map<String, EdgeRungStyle>,
    hysteresis: Double? = null,
): EdgeLadder = EdgeLadder(
    rungsFromStops(stops, styles) { at, id, style -> EdgeRung(id = id, at = at, style = style) },
    hysteresis,
)
