package borg.trikeshed.lcnc

import borg.trikeshed.lib.*
import borg.trikeshed.parse.reify

object LcncClockKey : LcncServiceKey<() -> Long>("LcncClockKey")

/**
 * Shared pure/presentation runners for whole programs and receipt-backed Live pulses.
 * The browser owns timer cadence and rendering, not these data semantics.
 *
 * Semantics under a headless run:
 *  - `timer` emits ONE tick per run — a server run is one pulse, no interval.
 *  - data nodes (`pick`, `list.groupBy`, `list.format`) run in common Kotlin.
 *    `list.format` is the declarative reshaper — the
 *    eval-free lane from map-shaped outputs (kanban.attention `cards`/
 *    `ordered`) to the `lines` port `read.construct`/`nal.mint` take, where
 *    the only alternative is the canvas-only `js` node.
 *  - display nodes (`dom.board`, `sheet.concentric`) retain their primary
 *    projection on `rendered`, plus supplied options under their input names;
 *    `gauge` retains `x`. These are read-only render data, with NO gesture
 *    outputs, so gesture-shaping
 *    downstream (`js` → `kanban.move`) skips silently per walker readiness.
 *  - `js` is bound by the host to the existing owned polyglot function. This
 *    pure registry neither evaluates strings nor invents a guest backend.
 */
object PureNodes {
    private val FIELD = Regex("""\{([^{}]+)\}""")

    internal fun select(value: Any?, path: List<String>): Any? = path.fold(value) { current, key ->
        when (current) {
            is Map<*, *> -> current[key]
            is List<*> -> key.toIntOrNull()?.let(current::getOrNull)
            else -> null
        }
    }

    private fun present(value: Any?, path: List<String>): Boolean {
        var current = value
        for (key in path) current = when (val item = current) {
            is Map<*, *> -> if (item.containsKey(key)) item[key] else return false
            is List<*> -> {
                val index = key.toIntOrNull() ?: return false
                if (index !in item.indices) return false
                item[index]
            }
            else -> return false
        }
        return current !== LcncProjection.Absent
    }

    private fun groupKey(item: Any?, key: String, path: List<String>): String {
        // Literal keys, including their null values, precede nested selection.
        val value = when {
            item is Map<*, *> && item.containsKey(key) -> item[key]
            path.isEmpty() -> null
            else -> select(item, path)
        }
        return value?.toString() ?: ""
    }

    fun registry(clock: () -> Long): Map<String, LcncNodeRunner> = mapOf(
        "timer" to boundLcnc(LcncClockKey(clock)) { source, _, _ -> mapOf("tick" to source.value()) },
        RdfSelectNode.TYPE to RdfSelectNode.runner,
        "pick" to object : LcncPureRunner {
            private fun path(node: LcncNode): List<String> =
                node.params["path"].orEmpty().split('.').filter { it.isNotBlank() }

            override suspend fun execute(node: LcncNode, inputs: Map<String, Any?>): Map<String, Any?> =
                mapOf("y" to select(inputs["x"], path(node)))

            override fun retainedOutputs(node: LcncNode, inputs: Map<String, Any?>,
                                         retainedInputs: Map<String, Any?>, outputs: Map<String, Any?>): Map<String, Any?> {
                if ("y" !in outputs) return outputs
                val path = path(node)
                return when {
                    "x" in retainedInputs && present(retainedInputs["x"], path) ->
                        outputs + ("y" to select(retainedInputs["x"], path))
                    present(inputs["x"], path) -> outputs - "y"
                    else -> outputs // An originally missing path computed ordinary null, not an event.
                }
            }

            override fun retainedOrigins(node: LcncNode, inputs: Map<String, Any?>,
                                         inputOrigins: Map<String, LcncBindingOrigins>, outputs: Map<String, Any?>,
                                         terms: LcncBindingTerms): Map<String, LcncBindingOrigins> {
                if ("y" !in outputs) return emptyMap()
                var current = inputs["x"]
                val selected = ArrayList<Either<String, Int>>()
                for (key in path(node)) current = when (val item = current) {
                    is Map<*, *> -> {
                        if (!item.containsKey(key)) return emptyMap()
                        selected.add(Either.left(key))
                        item[key]
                    }
                    is List<*> -> {
                        val index = key.toIntOrNull()?.takeIf { it in item.indices } ?: return emptyMap()
                        selected.add(Either.right(index))
                        item[index]
                    }
                    else -> return emptyMap()
                }
                return mapOf("y" to terms.select(inputOrigins["x"] ?: emptySeriesOf(), selected.toSeries()))
            }
        },
        "list.groupBy" to object : LcncPureRunner {
            override suspend fun execute(node: LcncNode, inputs: Map<String, Any?>): Map<String, Any?> {
                val key = node.params["key"]
                val xs = inputs["x"] as? List<*> ?: emptyList<Any?>()
                val groups = LinkedHashMap<String, MutableList<Any?>>()
                val path = key.orEmpty().split('.').filter { it.isNotBlank() }
                if (key != null) for (item in xs) {
                    groups.getOrPut(groupKey(item, key, path)) { mutableListOf() }.add(item)
                }
                return mapOf("groups" to groups)
            }

            override fun retainedOutputs(node: LcncNode, inputs: Map<String, Any?>,
                                         retainedInputs: Map<String, Any?>, outputs: Map<String, Any?>): Map<String, Any?> {
                if ("groups" !in outputs) return outputs
                val actual = inputs["x"] as? List<*> ?: return outputs
                val key = node.params["key"] ?: return outputs
                if (actual.isEmpty()) return outputs
                val projected = retainedInputs["x"] as? List<*> ?: return outputs - "groups"
                val path = key.split('.').filter { it.isNotBlank() }
                val groups = LinkedHashMap<String, MutableList<Any?>>()
                for (index in actual.indices) {
                    val retained = if (index < projected.size) projected[index] else LcncProjection.Absent
                    groups.getOrPut(groupKey(actual[index], key, path)) { mutableListOf() }.add(retained)
                }
                return outputs + ("groups" to groups)
            }

            override fun retainedOrigins(node: LcncNode, inputs: Map<String, Any?>,
                                         inputOrigins: Map<String, LcncBindingOrigins>, outputs: Map<String, Any?>,
                                         terms: LcncBindingTerms): Map<String, LcncBindingOrigins> {
                if ("groups" !in outputs) return emptyMap()
                val actual = inputs["x"] as? List<*> ?: return emptyMap()
                val key = node.params["key"] ?: return emptyMap()
                if (actual.isEmpty()) return emptyMap()
                val source = inputOrigins["x"] ?: emptySeriesOf()
                val parts = ArrayList<LcncBindingOrigins>()
                parts.add(terms.guard(terms.presence(source)))
                val positions = HashMap<String, Int>()
                val path = key.split('.').filter { it.isNotBlank() }
                for ((index, item) in actual.withIndex()) {
                    val bucket = groupKey(item, key, path)
                    val position = positions[bucket] ?: 0
                    positions[bucket] = position + 1
                    parts.add(terms.prefix(terms.select(source, s_[Either.right<String, Int>(index)]),
                        s_[Either.left<String, Int>(bucket), Either.right<String, Int>(position)]))
                }
                return mapOf("groups" to terms.join(parts.toSeries()))
            }
        },
        "list.format" to LcncPureRunner { node, inputs ->
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
        "text.value" to LcncPureRunner { node, _ -> mapOf("value" to (node.params["value"] ?: "")) },
        // The if/else join: exactly one guarded ring ran, so exactly one of a?/b? is present.
        "merge" to object : LcncPureRunner {
            fun input(node: LcncNode, inputs: Map<String, Any?>): String? {
                val a = listOf("a", "a?").firstOrNull { inputs[it] != null }
                val b = listOf("b", "b?").firstOrNull { inputs[it] != null }
                require(a == null || b == null) { "merge ${node.id}: both branches yielded" }
                return a ?: b
            }
            override suspend fun execute(node: LcncNode, inputs: Map<String, Any?>): Map<String, Any?> =
                mapOf("value" to input(node, inputs)?.let { inputs[it] })
            override fun outputSources(node: LcncNode, inputs: Map<String, Any?>): Map<String, String> =
                input(node, inputs)?.let { mapOf("value" to it) } ?: emptyMap()
        },
        "json.value" to LcncPureRunner { node, _ ->
            val raw = (node.params["value"] ?: "").trim()
            // A literal that will not parse must say so rather than quietly
            // emitting nothing: an empty socket downstream is the exact failure
            // this node exists to end.
            if (raw.isEmpty()) mapOf("value" to emptyList<Any?>())
            else runCatching { mapOf("value" to reify(raw)) }
                .getOrElse { mapOf("error" to "json.value: not valid json") }
        },
        // The list widget's value. It had a contract and a canvas runner and no
        // server runner, so preset-pairs answered "no runner registered for node
        // type 'list.pairs'" the moment it ran anywhere but a browser.
        "list.pairs" to LcncPureRunner { node, _ ->
            val raw = (node.params["pairs"] ?: "").trim()
            if (raw.isEmpty()) mapOf("pairs" to emptyList<Any?>())
            else runCatching { mapOf("pairs" to reify(raw)) }
                .getOrElse { mapOf("error" to "list.pairs: not valid json") }
        },
        // Gesture sources. A press is an EDGE that only exists when a person
        // makes it, so headless they emit NOTHING — but they must still RUN, or
        // a graph containing a button dies at load instead of simply sitting
        // idle. These sources do not invent gestures in a headless run.
        "button" to LcncPureRunner { _, _ -> emptyMap() },
        "dom.events" to LcncPureRunner { node, _ -> mapOf("source" to node.id) },
        "graal.events" to LcncPureRunner { _, _ -> emptyMap() },
        "vm.events" to LcncPureRunner { _, _ -> emptyMap() },
        // A receipt describes one DOM operation, never a fabricated media observation.
        // The page sends observed state/ended back as a separate root pulse.
        "media.player" to LcncPureRunner { _, inputs ->
            mapOf("rendered" to buildMap {
                put("url", inputs["url"])
                for (port in s_["play", "stop", "rewind", "volume"].view) {
                    if (port in inputs) put(port, inputs[port])
                    else if ("$port?" in inputs) put(port, inputs["$port?"])
                }
            })
        },
        "slider" to LcncPureRunner { node, _ ->
            mapOf("value" to ((node.params["value"] ?: "0").toDoubleOrNull() ?: 0.0))
        },
        "dom.board" to LcncPureRunner { _, inputs ->
            buildMap {
                put("rendered", inputs["groups"])
                if ("columns" in inputs) put("columns", inputs["columns"])
                else if ("columns?" in inputs) put("columns", inputs["columns?"])
                for (port in s_["fields", "events", "result"].view) {
                    val key = if (port in inputs) port else "$port?"
                    if (key in inputs) {
                        if (port == "fields") LcncDomForm.fields(inputs[key])
                        if (port == "events") require((inputs[key] as? String)?.isNotBlank() == true) { "dom_event_source_required" }
                        put(port, inputs[key])
                    }
                }
            }
        },
        "sheet.concentric" to LcncPureRunner { _, inputs ->
            buildMap {
                put("rendered", inputs["board"])
                for (port in s_["byStatus", "byPriority", "orchestration"].view) {
                    if (port in inputs) put(port, inputs[port])
                    else if ("$port?" in inputs) put(port, inputs["$port?"])
                }
            }
        },
        "gauge" to LcncPureRunner { _, inputs -> mapOf("x" to inputs["x"]) },
    ) + ViewNodes.registry()
}
