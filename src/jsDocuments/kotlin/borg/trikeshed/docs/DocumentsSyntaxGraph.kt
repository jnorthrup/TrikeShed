package borg.trikeshed.docs

import borg.trikeshed.lib.*
import kotlinx.browser.document
import org.w3c.dom.Element
import org.w3c.dom.HTMLElement

/** A bounded drawing of observed dependency edges, including multiple parents and cycles. */
internal object DocumentsSyntaxGraph {
    private const val NODE_LIMIT = 48
    private const val EDGE_LIMIT = 128
    private var ordinal = 0

    /** Old receipts have token nodes only; nonzero copies must never be reconstructed as tokens. */
    fun nodes(sentence: Map<*, *>): List<Map<*, *>> {
        val explicit = sentence["nodes"]
        if (explicit != null) return (explicit as List<*>).map { it as Map<*, *> }
        for (raw in sentence["dependencies"] as List<*>) {
            val edge = raw as Map<*, *>
            for (side in s_["governor", "dependent"].view) {
                require((edge[side + "Copy"] as? Number)?.toInt().let { it == null || it == 0 } &&
                    (edge[side + "Node"] == null || edge[side + "Node"] == "${(edge[side] as Number).toInt()}:0")) {
                    "Parser copy nodes are missing from this inspection"
                }
            }
        }
        return (sentence["tokens"] as List<*>).map { raw ->
            val token = raw as Map<*, *>
            token + _m["id" j "${(token["index"] as Number).toInt()}:0", "copy" j 0, "kind" j "token"]
        }
    }

    fun nodeId(edge: Map<*, *>, side: String): String = edge[side + "Node"] as? String
        ?: "${(edge[side] as Number).toInt()}:${(edge[side + "Copy"] as? Number)?.toInt() ?: 0}"

    fun nodeLabel(node: Map<*, *>): String {
        val index = (node["index"] as Number).toInt(); val copy = (node["copy"] as Number).toInt()
        return if (copy == 0) "$index" else "$index′$copy"
    }
    private fun svg(tag: String): Element = document.createElementNS("http://www.w3.org/2000/svg", tag)
    private fun short(value: String, limit: Int): String {
        if (value.length <= limit) return value
        val end = if (value[limit - 1].isHighSurrogate() && value[limit].isLowSurrogate()) limit - 1 else limit
        return value.substring(0, end) + "…"
    }
    private fun text(parent: Element, value: String, x: Double, y: Double, full: String = value): Element = svg("text").also {
        it.setAttribute("x", x.toString()); it.setAttribute("y", y.toString())
        it.setAttribute("text-anchor", "middle"); it.setAttribute("fill", "var(--ink, #e6e9eb)")
        it.setAttribute("font-family", "system-ui, sans-serif"); it.setAttribute("font-size", "11")
        it.textContent = value
        val title = svg("title"); title.textContent = full; it.appendChild(title); parent.appendChild(it)
    }

    fun mount(parent: HTMLElement, sentence: Map<*, *>) {
        val tokens = (sentence["tokens"] as List<*>).map { it as Map<*, *> }
        val edges = (sentence["dependencies"] as List<*>).map { it as Map<*, *> }
        val nodes = nodes(sentence)
        val shown = nodes.take(NODE_LIMIT)
        val positions = buildMap<String, Double> {
            shown.forEachIndexed { index, node -> put(node["id"] as String, 140.0 + index * 100) }
        }
        val visible = edges.filter { edge ->
            val governor = nodeId(edge, "governor"); val dependent = nodeId(edge, "dependent")
            (governor == "0:0" || governor in positions) && dependent in positions
        }.take(EDGE_LIMIT)
        val description = document.createElement("p") as HTMLElement
        description.className = "ds-curate-label"
        description.setAttribute("data-english-graph-counts", "1")
        description.textContent = "Dependency graph: ${shown.size}/${nodes.size} parser nodes (${tokens.size} source tokens; ${nodes.size - tokens.size} parser copies), ${visible.size}/${edges.size} edges, plus ROOT. " +
            if (shown.size != nodes.size || visible.size != edges.size) "Display limited; full observations remain in the tables below." else "Arrows run from governor to dependent. This is a dependency graph, not a constituency tree."
        parent.appendChild(description)
        val scroll = document.createElement("div") as HTMLElement
        scroll.style.cssText = "overflow:auto;max-height:290px;border:1px solid var(--rule);border-radius:6px;background:var(--side)"
        parent.appendChild(scroll)
        val graph = svg("svg")
        graph.setAttribute("data-english-syntax-graph", "1"); graph.setAttribute("role", "img")
        graph.setAttribute("aria-label", "Parser dependency graph with directed, labelled edges")
        graph.setAttribute("width", maxOf(560, 190 + shown.size * 100).toString()); graph.setAttribute("height", "270")
        scroll.appendChild(graph)
        val arrowId = "ds-english-arrow-${ordinal++}"
        val defs = svg("defs"); val marker = svg("marker")
        marker.setAttribute("id", arrowId); marker.setAttribute("viewBox", "0 0 10 10")
        marker.setAttribute("refX", "9"); marker.setAttribute("refY", "5")
        marker.setAttribute("markerWidth", "7"); marker.setAttribute("markerHeight", "7"); marker.setAttribute("orient", "auto")
        val arrow = svg("path"); arrow.setAttribute("d", "M 0 0 L 10 5 L 0 10 z")
        arrow.setAttribute("fill", "var(--accent, #8bd5ca)"); marker.appendChild(arrow); defs.appendChild(marker); graph.appendChild(defs)
        for ((index, edge) in visible.withIndex()) {
            val governor = nodeId(edge, "governor"); val dependent = nodeId(edge, "dependent")
            val from = if (governor == "0:0") 40.0 else positions.getValue(governor)
            val to = positions.getValue(dependent)
            val middle = (from + to) / 2
            val control = maxOf(-145.0, 155.0 - kotlin.math.abs(from - to) * .32 - (index % 7) * 17)
            val path = svg("path")
            path.setAttribute("d", "M $from 210 Q $middle $control $to 210")
            path.setAttribute("fill", "none"); path.setAttribute("stroke", "var(--accent, #8bd5ca)")
            path.setAttribute("stroke-width", "1.2"); path.setAttribute("marker-end", "url(#$arrowId)")
            path.setAttribute("data-english-dependency-edge", "$governor>$dependent")
            val relation = edge["relation"].toString(); val title = svg("title")
            title.textContent = "$governor → $dependent · $relation"; path.appendChild(title); graph.appendChild(path)
            text(graph, short(relation, 28), middle, (210 + control) / 2 - 4, relation)
        }
        text(graph, "0:0 · ROOT", 40.0, 235.0).setAttribute("data-english-syntax-root", "0:0")
        for (node in shown) {
            val id = node["id"] as String; val x = positions.getValue(id)
            val copy = (node["copy"] as Number).toInt()
            val title = "$id · ${node["word"]}" + if (copy == 0) " · source token" else " · parser copy of source token ${node["index"]}; not an additional source word"
            text(graph, "${nodeLabel(node)} · ${short(node["word"].toString(), if (copy == 0) 13 else 10)}", x, 235.0, title)
                .setAttribute("data-english-syntax-node", id)
            text(graph, (if (copy == 0) "" else "copy · ") + node["pos"].toString(), x, 253.0)
        }
    }
}
