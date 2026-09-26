package borg.trikeshed.web

import org.w3c.dom.Element
import org.w3c.dom.HTMLElement

/**
 * The construction surface the shared patch camera, shake and layout drive. Node objects are the
 * page's JS graph nodes ({id,type,x,y,params,children,el,_parentScope,_childHost,_ringWorld,_view}).
 */
interface PatchSurface {
    val view: dynamic
    val viewport: HTMLElement
    val world: HTMLElement
    val wiresSvg: Element
    val graph: dynamic
    fun redraw()
    fun save()
    fun status(text: String)
    fun blipLeave()
    fun applyWireBox()
    fun portCenter(nodeId: String, dir: String, name: String): dynamic
    fun fitToContent()
    fun ringScaleOf(node: dynamic): Double
    fun resizeParentFrames(parent: dynamic)
    fun showConnections(programName: String, result: dynamic, text: String) {}
}

fun PatchSurface.nodes(): Array<dynamic> = arr(graph.nodes)
fun PatchSurface.node(id: dynamic): dynamic = nodes().firstOrNull { it.id == id }
