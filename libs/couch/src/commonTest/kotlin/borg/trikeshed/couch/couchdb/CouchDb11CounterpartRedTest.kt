package borg.trikeshed.couch.couchdb

import borg.trikeshed.couch.api.CouchDb11DesignDocument
import borg.trikeshed.couch.api.CouchDb11RowSet
import borg.trikeshed.couch.api.CouchDb11Spec
import borg.trikeshed.couch.api.CouchViewDefinition
import borg.trikeshed.couch.api.ViewQuery
import borg.trikeshed.couch.api.ViewQueryEncoder
import borg.trikeshed.couch.miniduck.*
import borg.trikeshed.lib.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CouchDb11CounterpartRedTest {
    @Test
    fun serializesDesignDocsUsingCouch11CompatibleShape() {
        val doc = CouchDb11DesignDocument(
            id = "_design/example",
            language = "javascript",
            views = mapOf(
                "by_brand" to CouchViewDefinition(
                    map = "function(doc){emit(doc.brand, doc);}",
                    reduce = "_count",
                ),
            ),
        )

        val json = doc.toJson()

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
    fun decodesMapViewRowSetsAsBlockRowVecOfViewRowVecsWithLazyDocChildren() {
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

        val rowSet = CouchDb11RowSet.fromJson(json)

        assertEquals(3, rowSet.totalRows)
        assertEquals(1, rowSet.offset)

        val block = rowSet.rows  // BlockRowVec
        assertEquals(1, block.rowCount)

        val row = block.child!![0] as ViewRowVec
        assertEquals("veh-1", row.id)
        assertEquals("vw", row.key)

        // doc child is a lazy DocRowVec
        val docChild = row.child
        assertNotNull(docChild)
        val doc = docChild[0] as DocRowVec
        assertEquals("veh-1", doc["_id"])
        assertEquals("vw", doc["brand"])
        assertEquals("Golf", doc["model"])
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
        val row = ViewRowVec(
            id = "veh-2",
            key = "audi",
            value = mapOf("model" to "A4"),
        )

        // scalar surface: [id, key, value]
        assertEquals("veh-2", row[0])
        assertEquals("audi", row[1])
        assertEquals(mapOf("model" to "A4"), row[2])
    }
}
