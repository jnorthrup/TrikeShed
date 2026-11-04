package borg.trikeshed.docs

import kotlinx.browser.document
import org.w3c.dom.Element
import org.w3c.dom.HTMLElement

/** A bounded drawing of observed dependency edges, including multiple parents and cycles. */
internal object DocumentsSyntaxGraph {
    private const val TOKEN_LIMIT = 48
    private const val EDGE_LIMIT = 128
    private var ordinal = 0
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
        val shown = tokens.take(TOKEN_LIMIT)
        val positions = buildMap<Int, Double> {
            shown.forEachIndexed { index, token -> put((token["index"] as Number).toInt(), 140.0 + index * 100) }
        }
        val visible = edges.filter { edge ->
            val governor = (edge["governor"] as Number).toInt(); val dependent = (edge["dependent"] as Number).toInt()
            (governor == 0 || governor in positions) && dependent in positions
        }.take(EDGE_LIMIT)
        val description = document.createElement("p") as HTMLElement
        description.className = "ds-curate-label"
        description.setAttribute("data-english-graph-counts", "1")
        description.textContent = "Dependency graph: ${shown.size}/${tokens.size} tokens, ${visible.size}/${edges.size} edges. " +
            if (shown.size != tokens.size || visible.size != edges.size) "Display limited; full observations remain in the tables below." else "Arrows run from governor to dependent. This is a dependency graph, not a constituency tree."
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
            val governor = (edge["governor"] as Number).toInt(); val dependent = (edge["dependent"] as Number).toInt()
            val from = if (governor == 0) 40.0 else positions.getValue(governor)
            val to = positions.getValue(dependent)
            val middle = (from + to) / 2
            val control = maxOf(-145.0, 155.0 - kotlin.math.abs(from - to) * .32 - (index % 7) * 17)
            val path = svg("path")
            path.setAttribute("d", "M $from 210 Q $middle $control $to 210")
            path.setAttribute("fill", "none"); path.setAttribute("stroke", "var(--accent, #8bd5ca)")
            path.setAttribute("stroke-width", "1.2"); path.setAttribute("marker-end", "url(#$arrowId)")
            path.setAttribute("data-english-dependency-edge", "$governor:$dependent")
            val relation = edge["relation"].toString(); val title = svg("title")
            title.textContent = "$governor → $dependent · $relation"; path.appendChild(title); graph.appendChild(path)
            text(graph, short(relation, 28), middle, (210 + control) / 2 - 4, relation)
        }
        text(graph, "0 · ROOT", 40.0, 235.0)
        for (token in shown) {
            val index = (token["index"] as Number).toInt(); val x = positions.getValue(index)
            text(graph, "$index · ${short(token["word"].toString(), 15)}", x, 235.0, "$index · ${token["word"]}")
            text(graph, token["pos"].toString(), x, 253.0)
        }
    }
}
