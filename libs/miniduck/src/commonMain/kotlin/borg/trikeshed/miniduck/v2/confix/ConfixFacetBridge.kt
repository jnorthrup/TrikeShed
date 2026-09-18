package borg.trikeshed.miniduck.v2.confix

import borg.trikeshed.lib.*
import borg.trikeshed.miniduck.v2.*
import borg.trikeshed.parse.confix.*
import borg.trikeshed.miniduck.MiniDuckBlockCodec
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

/**
 * ConfixFacetBridge — converts ConfixDoc → FacetedCursor with schema inference.
 *
 * This is the entry point of the continuum: Confix (JSON/HTX/YAML) → FacetedCursor
 * Each facet represents a semantic projection (columns, indices, hierarchies).
 */
class ConfixFacetBridge {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Parse Confix JSON and produce a FacetedCursor with inferred facets.
     */
    fun parse(jsonText: String): FacetedCursor = parse(json.decodeFromString<ConfixDoc>(jsonText))

    /**
     * Parse ConfixDoc directly into a FacetedCursor.
     * 
     * Facets created:
     * - "schema": ColumnMeta projection from row vectors
     * - "indices": KeyToChild/Depth/Tag/Index facets from ConfixIndexK
     * - "hierarchy": Parent-child relationships as navigable edges
     * - "payload": Raw leaf values for full-text search
     */
    fun parse(doc: ConfixDoc): FacetedCursor = FacetedCursor(doc.toCursor()).apply {
        // Facet 1: Schema - column metadata from row vectors
        addFacet("schema", Facet.Project(columns = doc.rowSchema.columns))

        // Facet 2: Confix index facets (KeyToChild, Tags, Depths, Spans)
        doc.indexFacets.forEach { (indexK, index) ->
            addFacet("index.${indexK.name}", index.toFacet())
        }

        // Facet 3: Hierarchy edges (parent → child navigation)
        if (doc.hierarchyEdges.isNotEmpty()) {
            addFacet("hierarchy", Facet.Project(columns = listOf("parent", "child", "edgeType")))
        }

        // Facet 4: Payload - all leaf scalar values for search
        addFacet("payload", Facet.Filter { row -> row["value"] != null })
    }

    /**
     * Parse HTX/JSONL stream into a sequence of FacetedCursors (one per document).
     */
    fun parseStream(stream: Sequence<String>): Sequence<FacetedCursor> =
        stream.map { parse(it) }

    /**
     * Parse from MiniDuck block (Confix encoded).
     */
    fun parseBlock(block: Block): FacetedCursor = block.toList()
        .map { row -> json.decodeFromString<ConfixDoc>(row["json"] as String) }
        .toList()
        .let { cursorFrom(it) }
        .faceted()

    private fun cursorFrom(docs: List<ConfixDoc>): Cursor = {
        val base = Cursor()
        docs.forEach { doc -> base.addBlock(Block.mutable().apply {
            doc.rows.forEach { row -> append(row.toMiniDuckSeries()) }
        }.seal()) }
        base
    }()
}

/**
 * Extension on ConfixDoc for faceted projections.
 */
private interface ConfixDocFaceted: ConfixDoc {
    val rowSchema: ColumnSchema
    val indexFacets: Map<ConfixIndexK<*>, ConfixFacetIndex>
    val hierarchyEdges: List<Triple<String, String, String>>
    val rows: List<ConfixRow>
    fun toCursor(): Cursor
}

/**
 * ConfixRow → MiniDuckSeries conversion.
 */
private fun ConfixRow.toMiniDuckSeries(): MiniDuckSeries =
    MiniDuckSeries.build(ColumnSchema(columns.map { it.name }, columns.map { it.type.toColumnType() })) {
        columns.forEach { col -> this[col.name] = col.value }
    }

/**
 * ConfixType → ColumnType mapping.
 */
private fun ConfixType.toColumnType(): ColumnType = when (this) {
    ConfixType.STRING -> ColumnType.STRING
    ConfixType.INT -> ColumnType.INT
    ConfixType.LONG -> ColumnType.LONG
    ConfixType.DOUBLE -> ColumnType.DOUBLE
    ConfixType.BOOLEAN -> ColumnType.BOOLEAN
    ConfixType.BYTES -> ColumnType.BYTES
    ConfixType.TIMESTAMP -> ColumnType.TIMESTAMP
    ConfixType.JSON -> ColumnType.JSON
}

/**
 * ConfixFacetIndex → Facet adapter.
 */
private fun ConfixFacetIndex.toFacet(): Facet = when (this) {
    is ConfixKeyIndex -> Facet.Project(columns = listOf("key", "child"))
    is ConfixTagIndex -> Facet.Project(columns = listOf("tag", "docId"))
    is ConfixDepthIndex -> Facet.Project(columns = listOf("depth", "count"))
    is ConfixSpanIndex -> Facet.Project(columns = listOf("start", "end", "spanType"))
}