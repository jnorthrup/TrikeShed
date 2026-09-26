package borg.trikeshed.web.harness

import kotlinx.browser.window
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.promise

/**
 * `window.Harness`, `window.HarnessArguments`: the names the patch surface, camera, shake, landscape
 * and application nav read. Fields are live accessors over the Kotlin objects; methods return
 * Promises where the original was async.
 */
@OptIn(DelicateCoroutinesApi::class)
object HarnessFacade {
    private fun property(o: dynamic, name: String, get: () -> dynamic, set: ((dynamic) -> Unit)? = null) {
        val d: dynamic = jsObject()
        d.enumerable = true; d.configurable = true
        d.get = get
        if (set != null) d.set = set
        js("Object").defineProperty(o, name, d)
    }

    fun build(): dynamic {
        val h = Harness
        val o: dynamic = jsObject()
        property(o, "surface", { h.surface.key })
        property(o, "epoch", { h.epoch })
        property(o, "board", { h.board })
        property(o, "seq", { h.seq })
        property(o, "selected", { h.selected })
        property(o, "applying", { h.applying }, { v -> h.applying = v == true })
        property(o, "dirty", { h.dirty })
        property(o, "ready", { h.ready })
        property(o, "live", { h.live })
        property(o, "running", { h.running })
        property(o, "shaking", { h.shaking }, { v -> h.shaking = v == true })
        property(o, "drafts", { h.drafts })
        property(o, "previews", { h.previews })
        property(o, "positions", { h.positions })
        property(o, "flashes", { h.flashes })
        property(o, "mounts", { h.mounts })
        property(o, "actors", { h.actors })
        property(o, "parentRevision", { h.parentRevision })
        property(o, "dragMoved", { h.dragMoved })
        property(o, "terrainBookmark", { h.terrainBookmark }, { v -> h.terrainBookmark = v })
        property(o, "stream", { h.stream })
        property(o, "reconnectTimer", { h.reconnectTimer })
        property(o, "sheetTicket", { h.sheetTicket }, { v -> h.sheetTicket = jsNumber(v).toInt() })
        property(o, "inspectionController", { h.inspectionController })
        o.el = { tag: String, cls: String?, value: dynamic -> el(tag, cls, value) }
        o.message = { v: dynamic -> h.message(jsString(v)) }
        o.inspectionOnly = { name: String? -> if (name == undefined) h.inspectionOnly() else h.inspectionOnly(name) }
        o.programs = { h.programs().toTypedArray() }
        o.openExample = { name: String, doc: dynamic -> h.openExample(name, doc) }
        o.changed = { h.changed() }
        o.document = { name: String? -> if (name == undefined) h.document() else h.document(name) }
        o.select = { name: String, focus: Boolean?, preserve: Boolean? -> h.select(name, focus != false, preserve == true) }
        o.selectParent = { node: dynamic -> h.selectParent(node) }
        o.parentTarget = { h.parentTarget()?.js() }
        o.dragParent = { e: dynamic, leaf: dynamic -> h.dragParent(e, leaf) }
        o.replaceSelected = { doc: dynamic -> h.replaceSelected(doc) }
        o.schedule = { h.schedule() }
        o.render = { h.render() }
        o.programRight = { h.programRight() }
        o.inspect = { key: String -> h.inspect(key) }
        o.beginInspection = { key: String, actor: String, raw: String? -> h.beginInspection(key, actor, raw ?: "") }
        o.loadSheets = { sources: Array<dynamic>, cur: String? ->
            GlobalScope.promise { h.loadSheets(sources.map { Harness.SheetSource(it.url as String, truthy(it.kanban)) }, cur) }
        }
        o.openSheets = { prefixes: Array<String> -> h.openSheets(prefixes.toList()) }
        o.rawSheets = { on: Boolean -> h.rawSheets(on) }
        o.rememberView = { h.rememberView() }
        o.observeZoom = { px: Double, py: Double -> h.observeZoom(px, py) }
        o.applyTerrainBookmark = { h.applyTerrainBookmark() }
        o.focus = { box: dynamic, identity: String?, remember: Boolean?, scale: Double? -> h.focus(box, identity ?: "", remember != false, scale ?: 1.0) }
        o.focusNode = { node: dynamic -> h.focusNode(node) }
        o.focusElement = { e: dynamic, identity: String? -> h.focusElement(e, identity ?: "") }
        o.fit = { all: Boolean -> h.fit(all) }
        o.previousView = { h.previousView() }
        o.enclosingView = { h.enclosingView() }
        o.prominent = { h.prominent() }
        o.zoomCeiling = { px: Double, py: Double, camera: dynamic -> if (camera == undefined) h.zoomCeiling(px, py) else h.zoomCeiling(px, py, camera) }
        o.output = { r: dynamic -> h.output(r) }
        o.run = { GlobalScope.promise { h.run() } }
        o.cancelRun = { GlobalScope.promise { h.cancelRun() } }
        o.rebuild = { r: dynamic -> GlobalScope.promise { h.rebuild(r) } }
        o.publish = { base: dynamic -> GlobalScope.promise { h.publish(base) } }
        o.snapshot = { GlobalScope.promise { h.snapshot() } }
        o.layoutHints = { doc: dynamic, parentId: dynamic -> h.layoutHints(doc, parentId) }
        o.shake = { options: dynamic -> h.shake(options) }
        o.showConnections = { program: dynamic, result: dynamic, summary: String -> h.showConnections(program, result, summary) }
        o.openNeighbors = { h.openNeighbors() }
        o.connect = { GlobalScope.promise { h.connect() } }
        o.reconnect = { reason: String? -> h.reconnect(reason ?: "Reconnecting") }
        o.flash = { key: String? -> h.flash(key) }
        return o
    }

    fun install() {
        window.asDynamic().Harness = Harness.facade
        val a: dynamic = jsObject()
        a.inputs = { name: String -> HarnessArguments.inputs(name) }
        a.open = { HarnessArguments.open() }
        a.render = { HarnessArguments.render() }
        a.validate = { HarnessArguments.validate() }
        a.add = { HarnessArguments.add() }
        a.record = { r: dynamic -> HarnessArguments.record(r) }
        a.renderReceipt = { HarnessArguments.renderReceipt() }
        window.asDynamic().HarnessArguments = a
    }
}
