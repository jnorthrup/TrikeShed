package borg.trikeshed.lcnc

/**
 * Server-side runners for the pure/presentation node types the panels canvas
 * executes in-browser. Registered in the daemon so a stored program authored
 * for the canvas (preset-kanban) also completes HEADLESS through
 * `POST /api/lcnc/run {"program": …}` — the curl-able smoke-test lane.
 *
 * Semantics under a headless run:
 *  - `timer` emits ONE tick per run — a server run is one pulse, no interval.
 *  - data nodes (`pick`, `list.groupBy`, `list.format`) compute exactly as
 *    the canvas does. `list.format` is the declarative reshaper — the
 *    eval-free lane from map-shaped outputs (kanban.attention `cards`/
 *    `ordered`) to the `lines` port `read.construct`/`nal.mint` take, where
 *    the only alternative is the canvas-only `js` node.
 *  - display nodes (`dom.board`, `sheet.concentric`) echo their projection on
 *    a `rendered` port and emit NO gesture outputs, so gesture-shaping
 *    downstream (`js` → `kanban.move`) skips silently per walker readiness.
 *  - `js` stays UNREGISTERED server-side on purpose: a gesture shaper with
 *    nothing to shape must stay loud if ever fed, not eval strings in-daemon.
 */
object PureNodes {
    private val FIELD = Regex("""\{([^{}]+)\}""")

    fun registry(clock: () -> Long): Map<String, LcncNodeRunner> = mapOf(
        "timer" to LcncNodeRunner { _, _ -> mapOf("tick" to clock()) },
        "pick" to LcncNodeRunner { node, inputs ->
            var v: Any? = inputs["x"]
            for (k in (node.params["path"] ?: "").split('.').filter { it.isNotBlank() }) {
                v = when (v) {
                    is Map<*, *> -> v[k]
                    is List<*> -> k.toIntOrNull()?.let { idx -> v.getOrNull(idx) }
                    else -> null
                }
            }
            mapOf("y" to v)
        },
        "list.groupBy" to LcncNodeRunner { node, inputs ->
            val key = node.params["key"]
            val xs = inputs["x"] as? List<*> ?: emptyList<Any?>()
            val groups = LinkedHashMap<String, MutableList<Any?>>()
            if (key != null) for (item in xs) {
                val k = (item as? Map<*, *>)?.get(key)?.toString() ?: ""
                groups.getOrPut(k) { mutableListOf() }.add(item)
            }
            mapOf("groups" to groups)
        },
        "list.format" to LcncNodeRunner { node, inputs ->
            val template = node.params["template"] ?: ""
            val limit = node.params["limit"]?.toIntOrNull()
            val xs = inputs["x"] as? List<*> ?: listOfNotNull(inputs["x"])
            val bounded = if (limit != null) xs.take(limit.coerceAtLeast(0)) else xs
            val lines = bounded.map { item ->
                when {
                    item !is Map<*, *> -> item?.toString() ?: ""
                    template.isEmpty() -> item.toString()
                    else -> FIELD.replace(template) { m -> item[m.groupValues[1]]?.toString() ?: "" }
                }
            }
            mapOf("lines" to lines)
        },
        "dom.board" to LcncNodeRunner { _, inputs -> mapOf("rendered" to inputs["groups"]) },
        "sheet.concentric" to LcncNodeRunner { _, inputs -> mapOf("rendered" to inputs["board"]) },
    )
}
