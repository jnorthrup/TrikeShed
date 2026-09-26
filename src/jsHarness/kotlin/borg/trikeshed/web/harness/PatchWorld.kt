package borg.trikeshed.web.harness

import kotlinx.browser.window

/**
 * The patch surface and landscape the harness drives (patch.js, patch-camera.js, patch-shake.js,
 * patch-layout.js, landscape.js, graal-terrain.js). They are not part of this app: every name is
 * resolved on `globalThis` under the global it had in the original scripts, so whichever bundle
 * ports them publishes those names and the harness binds to them unchanged.
 */
object Patch {
    private val g: dynamic get() = window.asDynamic()

    fun present(name: String): Boolean = jsTypeOf(g[name]) == "function"

    val G: dynamic get() = g.G
    val view: dynamic get() = g.view
    val BOARD: dynamic get() = g.BOARD
    val CONTRACTS: dynamic get() = g.CONTRACTS
    val UNDO: dynamic get() = g.UNDO
    val REDO: dynamic get() = g.REDO
    var lastDoc: dynamic
        get() = g.lastDoc
        set(value) { g.lastDoc = value }
    var AUTOSAVE: dynamic
        get() = g.AUTOSAVE
        set(value) { g.AUTOSAVE = value }
    val Landscape: dynamic get() = g.Landscape
    val PatchLayout: dynamic get() = g.PatchLayout

    fun histButtons() { g.histButtons() }
    fun clearVerdicts() { g.clearVerdicts() }
    fun nodeDoc(n: dynamic): dynamic = g.nodeDoc(n)
    fun buildNode(n: dynamic) { g.buildNode(n) }
    fun refreshRingChrome(n: dynamic) { g.refreshRingChrome(n) }
    fun layoutRing(n: dynamic, preserve: Boolean? = null): dynamic = if (preserve == null) g.layoutRing(n) else g.layoutRing(n, preserve)
    fun resolveTopLevelOverlaps() { g.resolveTopLevelOverlaps() }
    fun fromConfix(doc: dynamic): dynamic = g.fromConfix(doc)
    fun redraw() { g.redraw() }
    fun save() { g.save() }
    fun resizeParentFrames(parent: dynamic) { g.resizeParentFrames(parent) }
    fun ringScaleOf(n: dynamic): Double = g.ringScaleOf(n) as Double
    fun killMomentum() { g.killMomentum() }
    fun applyView() { g.applyView() }
    fun scopeZoomCeiling(px: Double, py: Double, camera: dynamic): Double = g.scopeZoomCeiling(px, py, camera) as Double
    fun setResult(n: dynamic, out: dynamic) { g.setResult(n, out) }
    fun syncContracts(payload: dynamic): dynamic = g.syncContracts(payload)
    fun syncLanes(): dynamic = g.syncLanes()
    fun buildPalette(): dynamic = g.buildPalette()
    fun showMateMenu(cx: Double, cy: Double, node: dynamic, port: dynamic, wx: dynamic, wy: dynamic, scope: dynamic, dir: String): dynamic =
        g.showMateMenu(cx, cy, node, port, wx, wy, scope, dir)
    fun renderConcentric(n: dynamic) { g.renderConcentric(n) }
    fun syncRingFrame(n: dynamic) { g.syncRingFrame(n) }
    fun requestTreeShake(owner: dynamic, options: dynamic, namespace: dynamic): dynamic = g.requestTreeShake(owner, options, namespace)

    fun nodes(): Array<dynamic> = G.nodes.unsafeCast<Array<dynamic>>()
    fun wires(): Array<dynamic> = G.wires.unsafeCast<Array<dynamic>>()
}
