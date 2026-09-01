package borg.trikeshed.lcnc

import borg.trikeshed.parse.json.JsonSupport

/**
 * Server-side runners for the pure/presentation node types the panels canvas
 * executes in-browser. Registered in the daemon so a stored program authored
 * for the canvas (preset-kanban) also completes HEADLESS through
 * `POST /api/lcnc/run {"program": …}` — the curl-able smoke-test lane.
 *
 * Semantics under a headless run:
 *  - `timer` emits ONE tick per run — a server run is one pulse, no interval.
 *  - data nodes (`list.groupBy`, `list.format`) compute exactly as the canvas
 *    does. JVM `pick` deliberately lives in CanvasJsPureNodes: the daemon
 *    executes the canvas's own JavaScript method through sandboxed GraalJS.
 *    `list.format` is the declarative reshaper — the
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
        // The two literals. text.value had a contract and no server runner at
        // all, so a stored program that fed a url or a prompt through one went
        // silent the moment it ran headless — the canvas was the only place it
        // worked. Both run everywhere now.
        "text.value" to LcncNodeRunner { node, _ -> mapOf("value" to (node.params["value"] ?: "")) },
        "json.value" to LcncNodeRunner { node, _ ->
            val raw = (node.params["value"] ?: "").trim()
            // A literal that will not parse must say so rather than quietly
            // emitting nothing: an empty socket downstream is the exact failure
            // this node exists to end.
            if (raw.isEmpty()) mapOf("value" to emptyList<Any?>())
            else runCatching { mapOf("value" to JsonSupport.parse(raw)) }
                .getOrElse { mapOf("error" to "json.value: not valid json") }
        },
        // The list widget's value. It had a contract and a canvas runner and no
        // server runner, so preset-pairs answered "no runner registered for node
        // type 'list.pairs'" the moment it ran anywhere but a browser.
        "list.pairs" to LcncNodeRunner { node, _ ->
            val raw = (node.params["pairs"] ?: "").trim()
            if (raw.isEmpty()) mapOf("pairs" to emptyList<Any?>())
            else runCatching { mapOf("pairs" to JsonSupport.parse(raw)) }
                .getOrElse { mapOf("error" to "list.pairs: not valid json") }
        },
        // Gesture sources. A press is an EDGE that only exists when a person
        // makes it, so headless they emit NOTHING — but they must still RUN, or
        // a graph containing a button dies at load instead of simply sitting
        // idle. `js` stays unregistered on purpose; these do not shape anything.
        "button" to LcncNodeRunner { _, _ -> emptyMap() },
        "slider" to LcncNodeRunner { node, _ ->
            mapOf("value" to ((node.params["value"] ?: "0").toDoubleOrNull() ?: 0.0))
        },
        "dom.board" to LcncNodeRunner { _, inputs -> mapOf("rendered" to inputs["groups"]) },
        "sheet.concentric" to LcncNodeRunner { _, inputs -> mapOf("rendered" to inputs["board"]) },
    )
}
