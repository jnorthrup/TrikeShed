@file:Suppress("UNCHECKED_CAST")

package borg.trikeshed.forge

import borg.trikeshed.forge.graph.ForgeGraphCamera
import borg.trikeshed.forge.graph.ForgeGraphMode
import borg.trikeshed.forge.graph.GRAPH_NODE_H
import borg.trikeshed.forge.graph.GRAPH_NODE_W
import borg.trikeshed.forge.graph.fitGraphCamera
import borg.trikeshed.forge.graph.graphEdgePath
import borg.trikeshed.forge.graph.graphEmptyText
import borg.trikeshed.forge.graph.graphLayerLabelAnchors
import borg.trikeshed.forge.graph.graphNodeLabel
import borg.trikeshed.forge.graph.graphRelationsOf
import borg.trikeshed.forge.graph.graphWheelFactor
import borg.trikeshed.forge.graph.pannedBy
import borg.trikeshed.forge.graph.zoomStepped
import borg.trikeshed.forge.graph.zoomedAt
import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.Element
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.KeyboardEvent
import org.w3c.dom.pointerevents.PointerEvent
import org.w3c.dom.events.WheelEvent

/**
 * Graph SVG adapter — the jsForge half of script.js "Render: graph". The layout ran server-side
 * (commonMain ForceLayout); here we only draw and move the camera. Camera math is commonMain
 * (`graph/ForgeGraphView.kt`); this file is SVG and event wiring only.
 */

const val SVG_NS = "http://www.w3.org/2000/svg"

fun svgEl(tag: String, attrs: Map<String, String> = emptyMap()): Element {
    val e = document.createElementNS(SVG_NS, tag)
    for ((k, v) in attrs) e.setAttribute(k, v)
    return e
}

fun ForgeBrowser.resetCam() {
    cam = graphLayout.camera
}

fun ForgeBrowser.setGraphMode(mode: ForgeGraphMode) {
    val layout = graphLayouts[mode] ?: return
    if (mode != graphMode) {
        graphMode = mode
        graphLayout = layout
        graphBuilt = false
        resetCam()
        mutate { s -> s.graphMode = mode }
    }

    fun updateBtn(btnId: String, isAct: Boolean) {
        val btn = el(btnId) ?: return
        btn.classList.toggle("active", isAct)
        if (isAct) btn.setAttribute("aria-current", "page")
        else btn.removeAttribute("aria-current")
    }
    updateBtn("graph-mode-causal", graphMode == ForgeGraphMode.Causal)
    updateBtn("graph-mode-concept", graphMode == ForgeGraphMode.Concept)
    updateBtn("graph-mode-docs", graphMode == ForgeGraphMode.Docs)

    el("graph-empty")?.textContent = graphEmptyText(graphMode)
    el("graph-inspector")?.hidden = true
    buildGraph()
    applyCamera()
}

fun ForgeBrowser.inspectNode(nodeId: String?) {
    val inspector = el("graph-inspector") ?: return
    val graphSvg = el("graph-canvas") ?: return
    if (nodeId == null) {
        inspector.hidden = true
        return
    }
    val n = graphLayout.nodes.firstOrNull { it.id == nodeId } ?: run {
        inspector.hidden = true
        return
    }
    val rels = graphRelationsOf(graphLayout, n.id).map { r ->
        r.arrow + "<b>" + r.rel + "</b> " + r.otherTitle
    }
    inspector.innerHTML = "<div class=\"gi-title\">" + n.title +
        (n.layer?.let { "<span class=\"gi-layer\">$it</span>" } ?: "") + "</div>" +
        (n.symbol?.let { "<div class=\"gi-symbol\">$it</div>" } ?: "") +
        "<div class=\"gi-file\">" + (n.file ?: n.id) + "</div>" +
        (if (rels.isNotEmpty()) "<div class=\"gi-rels\">" + rels.joinToString("<br>") + "</div>" else "")
    inspector.hidden = false
    val selected = graphSvg.querySelectorAll(".graph-node.selected")
    for (i in 0 until selected.length) (selected.item(i) as? Element)?.classList?.remove("selected")
    graphSvg.querySelector(".graph-node[data-id=\"${n.id}\"]")?.classList?.add("selected")
}

fun ForgeBrowser.buildGraph() {
    if (graphBuilt) return
    graphBuilt = true
    val graphSvg = el("graph-canvas") ?: return
    val graphEmptyEl = el("graph-empty")
    graphSvg.innerHTML = ""
    if (graphLayout.nodes.isEmpty()) {
        graphEmptyEl?.hidden = false
        return
    }
    graphEmptyEl?.hidden = true
    val defs = svgEl("defs")
    val marker = svgEl("marker", mapOf(
        "id" to "graph-arrow", "viewBox" to "0 0 10 10", "refX" to "10", "refY" to "5",
        "markerWidth" to "7", "markerHeight" to "7", "orient" to "auto-start-reverse",
    ))
    marker.appendChild(svgEl("path", mapOf("d" to "M 0 0 L 10 5 L 0 10 z", "fill" to "#aeaca6")))
    defs.appendChild(marker)
    graphSvg.appendChild(defs)
    val viewport = svgEl("g", mapOf("id" to "graph-viewport"))
    graphViewport = viewport
    graphSvg.appendChild(viewport)

    val byId = graphLayout.nodes.associateBy { it.id }
    val edgesG = svgEl("g", mapOf("class" to "graph-edges"))
    for (e in graphLayout.edges) {
        val a = byId[e.from] ?: continue
        val b = byId[e.to] ?: continue
        edgesG.appendChild(svgEl("path", mapOf(
            "class" to "graph-edge" + (e.rel?.let { " rel-$it" } ?: ""),
            "d" to graphEdgePath(a, b),
        )))
    }
    viewport.appendChild(edgesG)

    val nodesG = svgEl("g", mapOf("class" to "graph-nodes"))
    for (n in graphLayout.nodes) {
        val g = svgEl("g", mapOf(
            "class" to "graph-node" + (n.layer?.let { " layer-$it" } ?: ""),
            "data-id" to n.id,
            "transform" to "translate(${n.x - GRAPH_NODE_W / 2},${n.y - GRAPH_NODE_H / 2})",
            "tabindex" to "0",
            "role" to "button",
        ))
        g.setAttribute("aria-label", n.title + " (topo " + n.topo + ")")
        g.addEventListener("click", { ev -> ev.stopPropagation(); inspectNode(n.id) })
        g.addEventListener("keydown", { ev -> if ((ev as KeyboardEvent).key == "Enter") inspectNode(n.id) })
        g.appendChild(svgEl("rect", mapOf("width" to GRAPH_NODE_W.toString(), "height" to GRAPH_NODE_H.toString())))
        val label = svgEl("text", mapOf("x" to "10", "y" to "22"))
        label.textContent = graphNodeLabel(n.title)
        g.appendChild(label)
        val topo = svgEl("text", mapOf("x" to (GRAPH_NODE_W - 8).toString(), "y" to "12", "text-anchor" to "end", "class" to "graph-node-topo"))
        topo.textContent = "#" + n.topo
        g.appendChild(topo)
        val title = svgEl("title")
        title.textContent = n.title + "\n" + n.id
        g.appendChild(title)
        nodesG.appendChild(g)
    }
    viewport.appendChild(nodesG)

    if (graphLayout.layers.isNotEmpty()) {
        // Column captions for the lattice: lib → cursor → confix → facets → surface → widgets.
        val byLayer = graphLayerLabelAnchors(graphLayout)
        val labelsG = svgEl("g", mapOf("class" to "graph-layer-labels"))
        for (l in graphLayout.layers) {
            val top = byLayer[l] ?: continue
            val t = svgEl("text", mapOf(
                "class" to "graph-layer-label", "x" to top.x.toString(),
                "y" to (top.y - GRAPH_NODE_H).toString(), "text-anchor" to "middle",
            ))
            t.textContent = l
            labelsG.appendChild(t)
        }
        viewport.appendChild(labelsG)
    }
    applyCamera()
}

fun ForgeBrowser.wireGraph() {
    el("graph-mode-causal")?.addEventListener("click", { setGraphMode(ForgeGraphMode.Causal) })
    el("graph-mode-concept")?.addEventListener("click", { setGraphMode(ForgeGraphMode.Concept) })
    el("graph-mode-docs")?.addEventListener("click", { setGraphMode(ForgeGraphMode.Docs) })
    el("graph-canvas")?.addEventListener("click", { inspectNode(null) })

    val graphSvg = el("graph-canvas") ?: return
    var dragging = false
    var lastX = 0.0
    var lastY = 0.0
    graphSvg.addEventListener("pointerdown", { e ->
        val pe = e as PointerEvent
        dragging = true
        lastX = pe.clientX.toDouble(); lastY = pe.clientY.toDouble()
        graphSvg.classList.add("dragging")
        graphSvg.asDynamic().setPointerCapture(pe.pointerId)
    })
    graphSvg.addEventListener("pointermove", { e ->
        if (!dragging) return@addEventListener
        val pe = e as PointerEvent
        cam = cam.pannedBy(pe.clientX.toDouble() - lastX, pe.clientY.toDouble() - lastY)
        lastX = pe.clientX.toDouble(); lastY = pe.clientY.toDouble()
        applyCamera()
    })
    val end: (org.w3c.dom.events.Event) -> Unit = {
        dragging = false
        graphSvg.classList.remove("dragging")
    }
    graphSvg.addEventListener("pointerup", end)
    graphSvg.addEventListener("pointercancel", end)
    graphSvg.addEventListener("wheel", { e ->
        e.preventDefault()
        val we = e as WheelEvent
        val rect = graphSvg.getBoundingClientRect()
        cam = cam.zoomedAt(
            graphWheelFactor(we.deltaY),
            we.clientX.toDouble() - rect.left,
            we.clientY.toDouble() - rect.top,
            rect.width, rect.height,
        )
        applyCamera()
    }, js("({ passive: false })"))
    el("graph-fit")?.addEventListener("click", { fitGraph() })
    window.addEventListener("resize", { applyCamera() })
    document.addEventListener("keydown", { e ->
        val ke = e as KeyboardEvent
        val graphScroll = el("graph-scroll") ?: return@addEventListener
        if (graphScroll.hidden) return@addEventListener
        if ((ke.target as? HTMLElement)?.asDynamic()?.isContentEditable == true) return@addEventListener
        when (ke.key) {
            "f" -> fitGraph()
            "+", "=" -> { cam = cam.zoomStepped(up = true); applyCamera() }
            "-" -> { cam = cam.zoomStepped(up = false); applyCamera() }
        }
    })
}

/** world → screen: translate(-cam) → scale(zoom) → center in viewport. */
fun ForgeBrowser.applyCamera() {
    val viewport = graphViewport ?: return
    val graphScrollEl = el("graph-scroll") ?: return
    val w = graphScrollEl.clientWidth.takeIf { it > 0 }?.toDouble() ?: 800.0
    val h = graphScrollEl.clientHeight.takeIf { it > 0 }?.toDouble() ?: 600.0
    viewport.setAttribute(
        "transform",
        "translate(${w / 2},${h / 2}) scale(${cam.zoom}) translate(${-cam.x},${-cam.y})",
    )
    el("graph-zoom-pill")?.textContent = "${kotlin.math.round(cam.zoom * 100).toInt()}%"
}

fun ForgeBrowser.fitGraph() {
    val graphScrollEl = el("graph-scroll") ?: return
    val w = graphScrollEl.clientWidth.takeIf { it > 0 }?.toDouble() ?: 800.0
    val h = graphScrollEl.clientHeight.takeIf { it > 0 }?.toDouble() ?: 600.0
    fitGraphCamera(graphLayout, w, h)?.let { cam = it }
    applyCamera()
}
