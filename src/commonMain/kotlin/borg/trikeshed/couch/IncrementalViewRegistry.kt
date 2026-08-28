package borg.trikeshed.couch

import borg.trikeshed.context.AsyncContextElement
import borg.trikeshed.context.AsyncContextKey
import borg.trikeshed.context.ElementState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.coroutineContext

/**
 * P2 production wiring: the daemon-side registry of live [IncrementalViewElement]s,
 * handed to [CouchWireRouter] as its `incrementalView` hook. A design doc marked
 * `"incremental": true` gets exactly one tendon over the daemon's [CouchDatabase] —
 * opened here, in this element's scope, the same pattern as the Changes→Rete
 * tendon — so its `_view` reads never rescan the corpus.
 *
 * Discovery rides `_changes`: the registry scans design docs at open, then re-scans
 * on every change tick, so a ddoc committed later (local write or peer replication)
 * opens its tendon before the next `_view` read reaches the router. The router hook
 * itself is a pure map read — creation is never on the request path.
 *
 * Views that don't qualify (unmarked, no map, or a non-builtin reducer) are left
 * OUT of the registry on purpose: the router's null falls through to the eager
 * [ViewRoute] path, which stays the one evaluator for everything else.
 */
class IncrementalViewRegistry(
    private val db: CouchDatabase,
    parentJob: kotlinx.coroutines.Job? = null,
    private val log: (String) -> Unit = {},
) : AsyncContextElement(ElementState.CREATED, parentJob) {
    companion object Key : AsyncContextKey<IncrementalViewRegistry>()
    override val key: kotlin.coroutines.CoroutineContext.Key<*> get() = Key

    private val elements = ConcurrentHashMap<String, IncrementalViewElement>()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private var cancelSubscription: (() -> Unit)? = null

    /** The [CouchWireRouter] hook: the live tendon for a marked view, or null (eager path). */
    fun lookup(ddoc: String, view: String): IncrementalViewElement? = elements[viewKey(ddoc, view)]

    override suspend fun open() {
        if (state != ElementState.CREATED) return
        super.open()
        scanDesignDocs()
        cancelSubscription = db.store.changes.subscribe { wake.trySend(Unit) }
        CoroutineScope(supervisor + Dispatchers.Default).launch {
            try {
                for (unit in wake) scanDesignDocs()
            } catch (_: kotlinx.coroutines.CancellationException) {
            }
        }
        state = ElementState.ACTIVE
    }

    /** Open a tendon for every design-doc view marked incremental that lacks one. Idempotent. */
    suspend fun scanDesignDocs() {
        for (doc in db.store.all()) {
            if (!doc.id.startsWith("_design/") || db.isTombstone(doc)) continue
            val body = db.docJson(doc.id) ?: continue
            val views = body["views"] as? Map<*, *> ?: continue
            for ((viewNameAny, specAny) in views) {
                val viewName = viewNameAny?.toString() ?: continue
                val spec = specAny as? Map<*, *> ?: continue
                val marked = spec["incremental"] == true || spec["incremental"]?.toString() == "true"
                if (!marked) continue
                openIfAbsent(doc.id, viewName, spec)
            }
        }
    }

    private suspend fun openIfAbsent(ddoc: String, view: String, spec: Map<*, *>) {
        val key = viewKey(ddoc, view)
        if (elements.containsKey(key)) return
        val def = definitionFor(ddoc, view, spec) ?: return
        val created = IncrementalViewElement(db, def, log, supervisor)
        val existing = elements.putIfAbsent(key, created)
        if (existing != null) {
            // A concurrent scan won the race; the duplicate never opened a subscription.
            runCatching { created.close() }
            return
        }
        created.open()
        log("incremental-view registry: opened ${def.fullName}")
    }

    /** Lowered from the design doc — the same expression shapes the eager route builds. */
    private fun definitionFor(ddoc: String, view: String, spec: Map<*, *>): ViewDefinition? {
        val map = spec["map"] as? Map<*, *> ?: return null
        val reduceFn = when (val r = spec["reduce"]) {
            null -> null
            is String -> if (r in BOUNDED_REDUCERS) ReduceFunction.Builtin(r) else return null
            else -> return null // dsl-shaped or custom reducers stay on the eager path
        }
        return ViewDefinition(
            ddoc = ddoc,
            viewName = view,
            mapFn = MapFunction.Emit(key = keyExpr(map["key"]), value = valueExpr(map["value"])),
            reduceFn = reduceFn,
        )
    }

    private fun keyExpr(spec: Any?): KeyExpr = when (spec) {
        null -> KeyExpr.DocId
        is String -> if (spec.startsWith("doc.")) KeyExpr.DocField(spec.removePrefix("doc.")) else KeyExpr.DocField(spec)
        is Map<*, *> -> when {
            spec["path"] is String -> KeyExpr.JsPathExpr(spec["path"] as String)
            spec["field"] is String -> KeyExpr.DocField(spec["field"] as String)
            spec.containsKey("const") -> KeyExpr.Const(spec["const"])
            else -> KeyExpr.DocId
        }
        else -> KeyExpr.Const(spec)
    }

    private fun valueExpr(spec: Any?): ValueExpr = when (spec) {
        null -> ValueExpr.Const(1)
        "doc" -> ValueExpr.DocValue
        is String -> if (spec.startsWith("doc.")) ValueExpr.DocField(spec.removePrefix("doc.")) else ValueExpr.DocField(spec)
        is Map<*, *> -> when {
            spec["path"] is String -> ValueExpr.JsPathExpr(spec["path"] as String)
            spec["field"] is String -> ValueExpr.DocField(spec["field"] as String)
            spec.containsKey("const") -> ValueExpr.Const(spec["const"])
            else -> ValueExpr.DocValue
        }
        else -> ValueExpr.Const(spec)
    }

    private fun viewKey(ddoc: String, view: String): String = "$ddoc/$view"

    /** Shutdown seam (finally block of mainImpl): close every tendon. */
    suspend fun closeAll() {
        for ((_, element) in elements) runCatching { element.close() }
        elements.clear()
        runCatching { close() }
        Unit
    }
}

/** The bounded reducer set [IncrementalViewElement] accepts — nothing else is incremental. */
private val BOUNDED_REDUCERS: Set<String> = setOf("_count", "_sum", "_stats", "rollup-count")
