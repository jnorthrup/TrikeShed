package borg.trikeshed.couch.couchdb

import borg.trikeshed.couch.api.CouchDb11DesignDocument
import borg.trikeshed.couch.api.CouchDb11RowSet
import borg.trikeshed.couch.api.CouchDb11Spec
import borg.trikeshed.couch.api.CouchViewDefinition
import borg.trikeshed.couch.api.ViewQuery
import borg.trikeshed.couch.api.ViewQueryEncoder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CouchDb11CounterpartRedTest {
    @Test
    fun serializesDesignDocsUsingCouch11CompatibleShape() {
        val json = CouchDb11Spec.json.encodeToString(
            CouchDb11DesignDocument.serializer(),
            CouchDb11DesignDocument(
                id = "_design/example",
                language = "javascript",
                views = mapOf(
                    "by_brand" to CouchViewDefinition(
                        map = "function(doc){emit(doc.brand, doc);}",
                        reduce = "_count",
                    ),
                ),
            ),
        )

        assertTrue(json.contains("\"_id\":\"_design/example\""))
        assertTrue(json.contains("\"language\":\"javascript\""))
        assertTrue(json.contains("\"views\""))
        assertTrue(json.contains("\"map\":\"function(doc){emit(doc.brand, doc);}\""))
        assertTrue(json.contains("\"reduce\":\"_count\""))
    }

    @Test
    fun encodesViewQueriesWithCouch11ParameterNamesIncludingGroupLevel() {
        val query = ViewQuery(
            key = "vw",
            startKey = listOf("vw", 2024),
            endKey = listOf("vw", 2026),
            keys = listOf("vw", "audi"),
            limit = 25,
            skip = 10,
            descending = true,
            group = true,
            groupLevel = 2,
            includeDocs = true,
            reduce = true,
            startKeyDocId = "doc-a",
            endKeyDocId = "doc-z",
        )

        val encoded = ViewQueryEncoder.encode(query)

        assertTrue(encoded.contains("key=%22vw%22"))
        assertTrue(encoded.contains("keys=%5B%22vw%22%2C%22audi%22%5D"))
        assertTrue(encoded.contains("startkey=%5B%22vw%22%2C2024%5D"))
        assertTrue(encoded.contains("endkey=%5B%22vw%22%2C2026%5D"))
        assertTrue(encoded.contains("limit=25"))
        assertTrue(encoded.contains("skip=10"))
        assertTrue(encoded.contains("descending=true"))
        assertTrue(encoded.contains("group=true"))
        assertTrue(encoded.contains("group_level=2"))
        assertTrue(encoded.contains("include_docs=true"))
        assertTrue(encoded.contains("reduce=true"))
        assertTrue(encoded.contains("startkey_docid=doc-a"))
        assertTrue(encoded.contains("endkey_docid=doc-z"))
        assertFalse(encoded.contains("group=2"))
    }

    @Test
    fun decodesMapViewRowSetsWithTotalRowsOffsetAndDocs() {
        val json =
            """
            {
              "total_rows": 3,
              "offset": 1,
              "rows": [
                {
                  "id": "veh-1",
                  "key": "vw",
                  "value": {"model": "Golf"},
                  "doc": {"_id": "veh-1", "brand": "vw", "model": "Golf"}
                }
              ]
            }
            """.trimIndent()

        val rowSet = CouchDb11Spec.json.decodeFromString(CouchDb11RowSet.serializer<String, VehicleValue>(), json)

        assertEquals(3, rowSet.totalRows)
        assertEquals(1, rowSet.offset)
        assertEquals("veh-1", rowSet.rows.single().id)
        assertEquals("vw", rowSet.rows.single().key)
        assertEquals("Golf", rowSet.rows.single().value.model)
        assertEquals("veh-1", rowSet.rows.single().doc?.get("_id"))
    }

    @Test
    fun exposesRelaxFactoryCounterpartEndpointsForDesignDocsViewsAndBulkOps() {
        val spec = CouchDb11Spec.default()

        assertTrue(spec.paths.containsKey("/{db}"))
        assertTrue(spec.paths.containsKey("/{db}/_all_docs"))
        assertTrue(spec.paths.containsKey("/{db}/_bulk_docs"))
        assertTrue(spec.paths.containsKey("/{db}/_design/{ddoc}"))
        assertTrue(spec.paths.containsKey("/{db}/_design/{ddoc}/_view/{view}"))
        assertTrue(spec.paths.containsKey("/{db}/{docid}/{attachment}"))
    }

    @Test
    fun keepsViewRowsCompatibleWithRelaxFactoryTupleShape() {
        val rowSet = CouchDb11RowSet(
            totalRows = 1,
            offset = 0,
            rows = listOf(
                CouchDb11RowSet.Row(
                    id = "veh-2",
                    key = "audi",
                    value = VehicleValue("A4"),
                    doc = mapOf("_id" to "veh-2", "brand" to "audi"),
                ),
            ),
        )

        val tuple = rowSet.rows.single()
        assertEquals("veh-2", tuple.id)
        assertEquals("audi", tuple.key)
        assertEquals("A4", tuple.value.model)
        assertEquals("veh-2", tuple.doc?.get("_id"))
    }

    private data class VehicleValue(val model: String)
}
