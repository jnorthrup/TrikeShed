package borg.trikeshed.pointcut

import borg.trikeshed.couch.CouchStore
import borg.trikeshed.couch.Document
import borg.trikeshed.couch.Field
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
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

        // Subscribe to the shared flow
        adapter.flow.onEach { landing ->
            processLanding(landing)
        }.launchIn(scope)
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
            
            val fields = mutableListOf<Field>()
            fields.add(Field("facet", landing.facet.id))
            fields.add(Field("mark", landing.markRaw.toInt()))
            fields.add(Field("property", landing.propertyName))
            fields.add(Field("value", landing.value?.toString() ?: "null"))
            
            // Nested coordinate representation required
            val coordMap = mapOf(
                "className" to landing.coordinate.className,
                "methodName" to landing.coordinate.methodName,
                "bytecodeOffset" to landing.coordinate.bytecodeOffset,
                "timestamp" to landing.coordinate.timestamp,
                "threadId" to landing.coordinate.threadId
            )
            
            fields.add(Field("coordinate", coordMap))
            
            val doc = Document(docId, fields)
            if (store.put(doc, rev)) {
                break
            }
        }
    }
}
