package borg.trikeshed.web.harness

import borg.trikeshed.landscape.LandscapeNavigation
import borg.trikeshed.web.AppNav
import borg.trikeshed.web.MuxIcons
import borg.trikeshed.web.graal.AllocationInspector
import borg.trikeshed.web.graal.GraalTerrain
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.HTMLSelectElement
import org.w3c.dom.set
import kotlin.math.hypot

/** Entry point of the `harness` Kotlin/JS app: the blackboard page, and the archive decoder worker. */
fun main() {
    if (js("typeof document") == "undefined") { ArchiveWorker.serve(js("self")); return }
    // application-nav, lucide-mux, allocation-inspector
    AppNav.mount()
    MuxIcons.install()
    AllocationInspector.install()
    // d3-force + patch-layout, landscape-navigation, patch, patch-shake, patch-camera
    HarnessPatchGlobals.install()
    HarnessPatchGlobals.listen()
    HarnessPatch.camera.landscapeSchedule = { HarnessLandscape.schedule() }
    HarnessPatch.camera.install()
    // graal-terrain, landscape (published with the patch globals), harness-arguments, fflate + archive-core, graal-file-viewer, archive-ui, harness
    GraalTerrain.install()
    ArchiveCore.install(window.asDynamic())
    GraalFileViewer.install()
    HarnessFacade.install()
    ArchiveUI.install()
    boot()
}

@OptIn(DelicateCoroutinesApi::class)
private fun boot() {
    val h = Harness
    val viewport = byId("viewport")
    Patch.AUTOSAVE = false
    document.body!!.dataset["surface"] = h.surface.key
    document.title = h.surface.title
    q("#bar b").textContent = document.title.uppercase()
    q("#argumentsBtn").addEventListener("click", { HarnessArguments.open() })
    qOrNull("#snapshotBtn")?.addEventListener("click", { GlobalScope.launch { h.snapshot() } })
    q("#argumentAdd").addEventListener("click", { HarnessArguments.add() })
    q("#argumentRun").addEventListener("click", { if (HarnessArguments.validate()) GlobalScope.launch { h.run() } })
    q("#programSelect").addEventListener("change", { e -> h.select(e.target.unsafeCast<HTMLSelectElement>().value) })
    q("#boardHome").addEventListener("click", { h.fit(true) })
    q("#objectsBtn").addEventListener("click", { h.focus(Patch.Landscape.objectBox, LandscapeNavigation.`object`("")) })
    q("#viewBack").addEventListener("click", { h.previousView() })
    q("#viewUp").addEventListener("click", { h.enclosingView() })
    q("#cancelRun").addEventListener("click", { GlobalScope.launch { try { h.cancelRun() } catch (e: Throwable) { h.message(errorMessage(e)) } } })
    q("#parentHandle").addEventListener("pointerdown", { e -> h.dragParent(e) })
    q("#parentHandle").addEventListener("click", { if (!h.dragMoved) h.fit(false) })
    q("#sheetsBtn").addEventListener("click", { h.openSheets(emptyList()) })
    q("#neighborsBtn").addEventListener("click", { h.openNeighbors() })
    q("#neighborKey").addEventListener("change", { e ->
        val value = e.target.unsafeCast<HTMLInputElement>().value
        if (hasOwn(h.board, value)) h.inspect(value)
    })
    q("#sheetRawBtn").addEventListener("click", { h.rawSheets(!q("#factInspector").classList.contains("raw")) })
    q("#factInspector").addEventListener("close", {
        h.inspectionController?.abort(); h.sheetTicket++
        h.schedule()
    })
    q("#terrainLayer").addEventListener("change", { e ->
        Patch.Landscape.terrain?.setLayer(jsNumber(e.target.unsafeCast<HTMLSelectElement>().value)); Patch.Landscape.schedule()
    })
    var parentDragGesture = false
    viewport.addEventListener("pointerdown", { ev ->
        val e: dynamic = ev
        parentDragGesture = false
        if (e.metaKey == true && e.button == 0 && h.parentTarget() != null) {
            parentDragGesture = true; e.stopImmediatePropagation(); h.dragParent(e)
        } else {
            val element = e.target.closest(".node")
            val n: dynamic = if (truthy(element)) Patch.nodes().firstOrNull { it.el === element } else null
            if (truthy(n?._program) && n._program != h.selected) { e.preventDefault(); e.stopImmediatePropagation() }
        }
    }, true)
    viewport.addEventListener("click", { ev ->
        val e: dynamic = ev
        if (parentDragGesture && jsNumber(e.detail) > 0) { e.preventDefault(); e.stopImmediatePropagation() }
    }, true)
    viewport.addEventListener("dblclick", { ev ->
        val e: dynamic = ev
        if (parentDragGesture) { e.preventDefault(); e.stopImmediatePropagation() }
        else if (!truthy(e.target.closest(".node,button,input,textarea,select"))) {
            val hit: dynamic = Patch.Landscape.hit(e)
            if (truthy(hit)) {
                e.preventDefault(); e.stopImmediatePropagation()
                if (truthy(hit.`object`)) h.focus(hit.`object`.rect, LandscapeNavigation.`object`(if (truthy(hit.`object`.id)) jsString(hit.`object`.id) else ""))
                else if (truthy(hit.node)) h.focusNode(hit.node)
                else if (jsTypeOf(hit.key) == "string" && (hit.key as String).startsWith(HarnessKeys.PROGRAM)) h.select(HarnessKeys.programName(hit.key as String))
                else h.focus(hit.box)
            }
        }
    }, true)
    var landscapePressX = 0.0; var landscapePressY = 0.0; var landscapePressed = false
    viewport.addEventListener("pointerdown", { ev -> val e: dynamic = ev; landscapePressed = true; landscapePressX = jsNumber(e.clientX); landscapePressY = jsNumber(e.clientY) }, true)
    viewport.addEventListener("click", { ev ->
        val e: dynamic = ev
        if (!truthy(e.target.closest(".node,button,input,textarea,select")) && landscapePressed &&
            hypot(jsNumber(e.clientX) - landscapePressX, jsNumber(e.clientY) - landscapePressY) <= 4) {
            val hit: dynamic = Patch.Landscape.hit(e)
            if (truthy(hit?.`object`?.leaf)) Patch.Landscape.inspect(hit.`object`.id)
        }
    })
    window.addEventListener("resize", { Patch.applyView(); Patch.redraw(); Patch.Landscape.schedule() })
    window.addEventListener("pagehide", { h.stream?.close(); window.clearTimeout(h.reconnectTimer) })
    GlobalScope.launch { h.connect() }
}
