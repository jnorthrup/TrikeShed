package borg.trikeshed.forge.concept

/**
 * The code-traversal sample: how a value gets from `borg.trikeshed.lib` to a pixel.
 *
 * Every node names a real symbol and the file that declares it (paths relative to `src/commonMain/kotlin`),
 * so the Forge graph view doubles as a map of the conventions in this tree:
 * lib → cursor → confix → facets → surface → widgets. Edges are typed relations, always from the
 * lower layer to the higher one, so the lattice is a DAG and lays out as columns.
 *
 * Emitted into the seed as `conceptGraph` with the same `{nodes, edges, camera}` shape as the causal
 * `graphLayout`, so `script.js` draws both with one renderer.
 */
data class ConceptNode(val id: String, val title: String, val layer: String, val symbol: String, val file: String)

data class ConceptEdge(val from: String, val to: String, val rel: String)

object ConceptGraph {
    /** Layer order = column order in the layout. */
    val LAYERS: List<String> = listOf("lib", "cursor", "confix", "facets", "surface", "widgets")

    val nodes: List<ConceptNode> = listOf(
        // lib — the algebra everything else is an alias of
        ConceptNode("lib.join", "Join<A,B>", "lib", "borg.trikeshed.lib.Join", "borg/trikeshed/lib/Join.kt"),
        ConceptNode("lib.series", "Series<T>", "lib", "borg.trikeshed.lib.Series", "borg/trikeshed/lib/Series.kt"),
        ConceptNode("lib.facetedRow", "FacetedRow<K>", "lib", "borg.trikeshed.lib.FacetedRow", "borg/trikeshed/lib/FacetedRow.kt"),
        // cursor — the dataframe substrate
        ConceptNode("cursor.columnMeta", "ColumnMeta", "cursor", "borg.trikeshed.cursor.ColumnMeta", "borg/trikeshed/cursor/Cursor.kt"),
        ConceptNode("cursor.rowVec", "RowVec", "cursor", "borg.trikeshed.cursor.RowVec", "borg/trikeshed/cursor/Cursor.kt"),
        ConceptNode("cursor.cursor", "Cursor", "cursor", "borg.trikeshed.cursor.Cursor", "borg/trikeshed/cursor/Cursor.kt"),
        ConceptNode("cursor.colK", "ColK / asFaceted", "cursor", "borg.trikeshed.cursor.asFaceted", "borg/trikeshed/cursor/ColK.kt"),
        // confix — hierarchy as an index over flat bytes
        ConceptNode("confix.flatIndex", "FlatIndex", "confix", "borg.trikeshed.parse.confix.FlatIndex", "borg/trikeshed/parse/confix/Confix.kt"),
        ConceptNode("confix.index", "ConfixIndex", "confix", "borg.trikeshed.parse.confix.ConfixIndex", "borg/trikeshed/parse/confix/Confix.kt"),
        ConceptNode("confix.doc", "ConfixDoc", "confix", "borg.trikeshed.parse.confix.ConfixDoc", "borg/trikeshed/parse/confix/ConfixKit.kt"),
        ConceptNode("confix.cell", "ConfixCell", "confix", "borg.trikeshed.parse.confix.ConfixCell", "borg/trikeshed/parse/confix/ConfixKit.kt"),
        ConceptNode("confix.typeDefOracle", "TypeDefOracle", "confix", "borg.trikeshed.parse.confix.TypeDefOracle", "borg/trikeshed/parse/confix/TypeDefOracle.kt"),
        // facets — what a value means for rendering / epistemics / storage
        ConceptNode("facets.layoutHint", "LayoutHint", "facets", "borg.trikeshed.cursor.LayoutHint", "borg/trikeshed/cursor/CcekChoreography.kt"),
        ConceptNode("facets.wtkHint", "WtkHint", "facets", "borg.trikeshed.cursor.WtkHint", "borg/trikeshed/cursor/CcekChoreography.kt"),
        ConceptNode("facets.lcncFacetGroup", "LcncFacetGroup", "facets", "borg.trikeshed.cursor.LcncFacetGroup", "borg/trikeshed/cursor/CcekChoreography.kt"),
        ConceptNode("facets.blackboardContext", "BlackboardContext", "facets", "borg.trikeshed.cursor.BlackboardContext", "borg/trikeshed/cursor/BlackboardOverlay.kt"),
        ConceptNode("facets.slabFacet", "SlabFacet", "facets", "borg.trikeshed.classfile.slab.SlabFacet", "borg/trikeshed/classfile/slab/SlabKernel.kt"),
        // surface — Cursor-shaped projections the UI reads
        ConceptNode("surface.blackboard", "BlackboardSurface.asCursor", "surface", "borg.trikeshed.blackboard.BlackboardSurface", "borg/trikeshed/blackboard/BlackboardSurface.kt"),
        ConceptNode("surface.lcncGrid", "LcncGrid", "surface", "borg.trikeshed.lcnc.LcncGrid", "borg/trikeshed/lcnc/LcncGrid.kt"),
        ConceptNode("surface.databaseView", "DatabaseView", "surface", "borg.trikeshed.lcnc.editor.DatabaseView", "borg/trikeshed/lcnc/editor/DatabaseView.kt"),
        ConceptNode("surface.cursorSheet", "sheetSeed / confixSheets", "surface", "borg.trikeshed.forge.sheet.sheetSeed", "borg/trikeshed/forge/sheet/CursorSheet.kt"),
        ConceptNode("surface.forceLayout", "forceLayout", "surface", "borg.trikeshed.forge.blackboard.forceLayout", "borg/trikeshed/forge/blackboard/ForceLayout.kt"),
        // widgets — what the browser actually draws (gallery ids / script.js views)
        ConceptNode("widgets.confixDoc", "confix.doc", "widgets", "ForgeGalleryCatalog confix.doc", "borg/trikeshed/forge/gallery/ForgeGalleryCatalog.kt"),
        ConceptNode("widgets.confixCursor", "confix.cursor", "widgets", "ForgeGalleryCatalog confix.cursor", "borg/trikeshed/forge/gallery/ForgeGalleryCatalog.kt"),
        ConceptNode("widgets.confixFacet", "confix.facet", "widgets", "ForgeGalleryCatalog confix.facet", "borg/trikeshed/forge/gallery/ForgeGalleryCatalog.kt"),
        ConceptNode("widgets.sheetView", "Sheet view", "widgets", "script.js setView('sheet')", "borg/trikeshed/forge/ForgeApp.kt"),
        ConceptNode("widgets.graphView", "Graph view", "widgets", "script.js setView('graph')", "borg/trikeshed/forge/ForgeApp.kt"),
        ConceptNode("widgets.boardView", "Board view", "widgets", "script.js setView('board')", "borg/trikeshed/forge/ForgeApp.kt"),
    )

    val edges: List<ConceptEdge> = listOf(
        ConceptEdge("lib.series", "cursor.cursor", "is-a"),          // Cursor = Series<RowVec>
        ConceptEdge("lib.join", "cursor.rowVec", "is-a"),            // RowVec = Series2<Any?, ColumnMeta↻>
        ConceptEdge("lib.join", "cursor.columnMeta", "is-a"),        // ColumnMeta : Join<name, Join<type, child>>
        ConceptEdge("lib.facetedRow", "cursor.colK", "projects-to"), // RowVec ↔ FacetedRow<ColK>
        ConceptEdge("cursor.rowVec", "cursor.colK", "projects-to"),
        ConceptEdge("cursor.columnMeta", "cursor.rowVec", "types"),
        ConceptEdge("cursor.rowVec", "cursor.cursor", "is-a"),
        ConceptEdge("lib.facetedRow", "confix.index", "is-a"),       // ConfixIndex = FacetedRow<Any>
        ConceptEdge("confix.flatIndex", "confix.index", "indexes"),
        ConceptEdge("confix.index", "confix.doc", "is-a"),           // ConfixDoc = Join<ConfixIndex, Series<Byte>>
        ConceptEdge("cursor.rowVec", "confix.cell", "is-a"),         // ConfixCell = Join<RowVec, Series<Byte>>
        ConceptEdge("confix.doc", "confix.cell", "projects-to"),
        ConceptEdge("confix.doc", "confix.typeDefOracle", "indexes"),
        ConceptEdge("cursor.cursor", "facets.lcncFacetGroup", "types"),
        ConceptEdge("facets.layoutHint", "facets.lcncFacetGroup", "is-a"),
        ConceptEdge("facets.wtkHint", "facets.lcncFacetGroup", "is-a"),
        ConceptEdge("confix.cell", "facets.slabFacet", "types"),
        ConceptEdge("cursor.cursor", "surface.blackboard", "projects-to"),
        ConceptEdge("facets.blackboardContext", "surface.blackboard", "types"),
        ConceptEdge("cursor.cursor", "surface.lcncGrid", "is-a"),
        ConceptEdge("confix.doc", "surface.lcncGrid", "indexes"),
        ConceptEdge("cursor.cursor", "surface.cursorSheet", "projects-to"),
        ConceptEdge("confix.doc", "surface.cursorSheet", "projects-to"),
        ConceptEdge("surface.blackboard", "surface.cursorSheet", "projects-to"),
        ConceptEdge("surface.lcncGrid", "surface.databaseView", "renders"),
        ConceptEdge("facets.blackboardContext", "surface.forceLayout", "types"),
        ConceptEdge("surface.cursorSheet", "widgets.sheetView", "renders"),
        ConceptEdge("surface.databaseView", "widgets.confixCursor", "renders"),
        ConceptEdge("facets.wtkHint", "widgets.confixCursor", "renders"),
        ConceptEdge("confix.doc", "widgets.confixDoc", "renders"),
        ConceptEdge("facets.slabFacet", "widgets.confixFacet", "renders"),
        ConceptEdge("surface.forceLayout", "widgets.graphView", "renders"),
        ConceptEdge("surface.blackboard", "widgets.boardView", "renders"),
    )

    fun layerIndex(layer: String): Int = LAYERS.indexOf(layer)

    /**
     * Layered DAG layout: one column per layer (x), nodes stacked in declaration order (y), centred per column.
     * Deterministic, overlap-free, and the proven idiom for a lattice — no force simulation needed.
     */
    fun layoutSeed(colGap: Double = 300.0, rowGap: Double = 58.0): Map<String, Any?> {
        val byLayer = nodes.groupBy { it.layer }
        val tallest = byLayer.values.maxOfOrNull { it.size } ?: 1
        val laidOut = nodes.map { n ->
            val col = layerIndex(n.layer)
            val column = byLayer.getValue(n.layer)
            val row = column.indexOf(n)
            val yOffset = (tallest - column.size) * rowGap / 2.0
            mapOf(
                "id" to n.id,
                "title" to n.title,
                "layer" to n.layer,
                "symbol" to n.symbol,
                "file" to "src/commonMain/kotlin/" + n.file,
                "x" to col * colGap,
                "y" to yOffset + row * rowGap,
                "topo" to col,
            )
        }
        val width = (LAYERS.size - 1) * colGap
        val height = (tallest - 1) * rowGap
        return mapOf(
            "nodes" to laidOut,
            "edges" to edges.map { mapOf("from" to it.from, "to" to it.to, "rel" to it.rel) },
            "layers" to LAYERS,
            "camera" to mapOf("x" to width / 2.0, "y" to height / 2.0, "zoom" to 0.7),
        )
    }
}
