package borg.trikeshed.pointcut

import borg.trikeshed.couch.CouchStore
import borg.trikeshed.couch.Document
import borg.trikeshed.couch.Field
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import borg.trikeshed.dag.DagCoordinate
import borg.trikeshed.pointcut.PointcutBlackboardAdapter.PointcutLanding

class PointcutCouchProjection(
    private val store: CouchStore,
    adapter: PointcutBlackboardAdapter,
    scope: CoroutineScope
) {
    init {
        // Seed the design document for pointcut views
        seedDesignDocument()

        // Subscribe to the shared flow.
        // UNDISPATCHED: the adapter's flow has replay = 0, so a landing emitted between this
        // constructor returning and a dispatched collector attaching would be dropped on the
        // floor (the daemon constructs the projection and the first landings can follow at
        // once). Starting undispatched runs collect() on the caller's thread up to its first
        // suspension, which is after the subscriber slot is registered — the collector is
        // attached by the time `PointcutCouchProjection(...)` returns.
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            adapter.flow.collect { landing -> processLanding(landing) }
        }
    }

    private fun seedDesignDocument() {
        val ddocId = "_design/pointcut"
        
        val viewsStr = """
            {
                "by_typedef": {
                    "map": "function(doc) { if (doc._id.startsWith('pointcut/')) { emit(doc.coordinate.className, 1); } }",
                    "reduce": "_count"
                },
                "recent": {
                    "map": "function(doc) { if (doc._id.startsWith('pointcut/')) { emit(doc.coordinate.timestamp, null); } }"
                }
            }
        """.trimIndent()
        
        val existing = store.get(ddocId)
        val currentViewsStr = existing?.fields?.find { it.name == "views" }?.value as? String
        
        // Check if design doc exists and matches exactly
        if (currentViewsStr == viewsStr) {
            return
        }
        
        var rev = store.head.getRev(ddocId)
        val fields = listOf(
            Field("views", viewsStr)
        )
        
        while (true) {
            val doc = Document(ddocId, fields)
            if (store.put(doc, rev)) {
                break
            }
            rev = store.head.getRev(ddocId)
        }
    }

    private fun processLanding(landing: PointcutLanding) {
        val docId = landing.key
        
        // Upsert discipline with read-modify-write
        while (true) {
            val rev = store.head.getRev(docId)
            
            // One author for the flattening: PointcutLanding.toFields() (the same map the
            // graal plane fact carries). The four scalar columns come from it verbatim ...
            // (toFields never lands a null: `value` is stringified, `"null"` when absent.)
            val flat = landing.toFields()
            val fields = mutableListOf<Field>()
            for (column in listOf("facet", "mark", "property", "value")) {
                fields.add(Field(column, flat.getValue(column)!!))
            }

            // ... and the coordinate stays NESTED here because `_design/pointcut` reads
            // `doc.coordinate.className` / `doc.coordinate.timestamp` — same five columns,
            // same author (coordinateFields), different shape.
            fields.add(Field("coordinate", landing.coordinateFields()))
            
            val doc = Document(docId, fields)
            if (store.put(doc, rev)) {
                break
            }
        }
    }
}
