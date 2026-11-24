package borg.trikeshed.graal.console

import borg.trikeshed.lib.*
import borg.trikeshed.ontology.SumoHyperarchy
import borg.trikeshed.parse.reify
import borg.trikeshed.transfers.*
import borg.trikeshed.web.FrameCanvas
import borg.trikeshed.web.TransferLinkBar
import borg.trikeshed.web.graal.terrainRows
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.await
import kotlinx.coroutines.launch
import borg.trikeshed.web.graal.fetchResponse
import narchy.spacegraph.*
import org.w3c.dom.events.KeyboardEvent
import org.w3c.dom.events.MouseEvent
import org.w3c.dom.events.WheelEvent
import kotlin.math.abs

/**
 * The Graal's ground mode: the store's territories stand at the homes of their SUMO classes ([StoreClasses.ground]) and
 * each transfer in flight at the home of what it is filling, with its progress ([TransferGround.stands]), on a
 * [GroundScene] that tilts and turns and moves through the SUMO hyperarchy. The view and the path stand in the URL hash;
 * a transfer clicked opens on /transfers.
 */
object GraalGround {
    val scene = GroundScene()
    var storeOf: Array<dynamic>? = null
    var store: Series<Stand> = emptySeriesOf()
    var transfers: Series<TransferRow> = emptySeriesOf()
    var press: DoubleArray? = null
    var last: DoubleArray? = null
    var turning = false
    var travelled = 0.0

    /** The stands of the store and the transfers, and the figure: rebuilt when either changes, not per frame. */
    fun stage() {
        scene.stands = (store.view.toList() + TransferGround.stands(transfers).view.toList()).toSeries()
        scene.figure = (listOf("Graal ground · ${store.size} store " + (if (store.size == 1) "territory" else "territories") + " at their SUMO class homes") +
            TransferGround.figure(transfers).view.toList()).toSeries()
    }

    /** The camera or the path moved: keep the view in the URL. */
    fun moved() { window.history.replaceState(null, "", window.location.pathname + window.location.search + scene.hash()) }
}

/**
 * The ground's viewport, and the bands the page's own chrome covers: the vitals strip above, the crumb and help lines
 * below. Taken before anything frames the camera, not only when a frame is drawn: a class reached before the first
 * frame was framed for a viewport of one pixel.
 */
fun GraalConsole.groundSize() {
    val scene = GraalGround.scene
    scene.viewport = Viewport(maxOf(1, innerWidth.toInt()), maxOf(1, innerHeight.toInt()))
    scene.top = q("hud").getBoundingClientRect().bottom + 8
    val below = listOf("crumb", "help").map { q(it).getBoundingClientRect() }.filter { it.height > 0 }.minOfOrNull { it.top } ?: innerHeight
    scene.bottom = innerHeight - below + 8
}

/** Reads the hyperarchy once and the transfers every two seconds; a ground view in the URL opens the ground. */
fun GraalConsole.loadGround() {
    groundSize()
    if (GroundView.decode(window.location.hash) != null) { mode = "ground"; GraalGround.scene.restore(window.location.hash) }
    // Links to the transfers asked for by the document open in the inspector: its context id is its row id, `<db>/<document>`.
    val links = document.createElement("span")
    q("docTitle").insertAdjacentElement("afterend", links)
    TransferLinkBar(links, "document") { if (q("doc").style.display == "none") null else sel?.id }.start()
    scope.launch {
        try {
            val r = fetchResponse("/api/sumo/hyperarchy")
            if (!r.ok) error("HTTP " + r.status)
            val hyperarchy = SumoHyperarchy.of(reify(r.text().await()) as Map<*, *>)
            groundSize(); GraalGround.scene.load(hyperarchy)
        } catch (e: Throwable) { river("deopt", "SUMO hyperarchy unavailable: " + errText(e)) }
    }
    fun poll() {
        scope.launch {
            try {
                val r = fetchResponse("/api/transfers")
                if (r.ok) {
                    val list = (reify(r.text().await()) as? List<*>).orEmpty()
                    GraalGround.transfers = list.filterIsInstance<Map<*, *>>().map { TransferRow.of(it) }.toSeries()
                    GraalGround.stage()
                }
            } catch (_: Throwable) { }
            window.setTimeout({ poll() }, 2000)
        }
    }
    poll()
    if (mode != "ground") toForm(mode)
}

fun GraalConsole.drawGround() {
    val g = GraalGround
    val rows = lastRows
    if (rows !== g.storeOf) { g.storeOf = rows; g.store = StoreClasses.ground(if (rows == null) emptyList() else terrainRows(rows)); g.stage() }
    groundSize()
    val dpr = window.devicePixelRatio
    ctx.setTransform(dpr, 0.0, 0.0, dpr, 0.0, 0.0)
    FrameCanvas.draw(ctx, g.scene.frame())
}

fun GraalConsole.groundDown(e: MouseEvent) {
    val p = doubleArrayOf(e.clientX.toDouble(), e.clientY.toDouble())
    groundSize()
    GraalGround.press = p; GraalGround.last = p; GraalGround.travelled = 0.0
    GraalGround.turning = e.button.toInt() == 2 || e.shiftKey
}

fun GraalConsole.groundMove(e: MouseEvent) {
    val g = GraalGround; val l = g.last ?: return
    val x = e.clientX.toDouble(); val y = e.clientY.toDouble()
    if (g.turning) g.scene.orbit(x - l[0], y - l[1]) else g.scene.drag(l[0], l[1], x, y)
    g.travelled += abs(x - l[0]) + abs(y - l[1]); g.last = doubleArrayOf(x, y); g.moved()
}

/** A release that did not travel is a click: a transfer opens on /transfers, a store territory names itself, a class is reached. */
fun GraalConsole.groundUp() {
    val g = GraalGround; val p = g.press; g.press = null; g.last = null
    if (p == null || g.travelled > 4) return
    val id = g.scene.click(p[0], p[1]); g.moved()
    val row = id?.let { i -> g.transfers.view.firstOrNull { it.id == i || i.startsWith(it.id + "/") } }
    if (row != null) window.open(TransferLinks.transfer(row.id), "_blank")
    else if (id != null) g.scene.stands.view.firstOrNull { it.id == id }?.let { crumb(it.label.view.joinToString("  ·  ")) }
}

fun GraalConsole.groundWheel(e: WheelEvent) {
    groundSize()
    GraalGround.scene.wheel(e.deltaY, e.deltaMode.toInt(), e.clientX.toDouble(), e.clientY.toDouble()); GraalGround.moved()
}

/** The ground takes its keys (hyperarchy moves, turn, tilt, Home and 0); M and H keep the Graal's own meanings (modes, the Hermes runner). */
fun GraalConsole.groundKey(e: KeyboardEvent): Boolean {
    if (e.key == "m" || e.key == "M" || e.key == "h" || e.key == "H") return false
    groundSize()
    if (!GraalGround.scene.key(e.key)) return false
    e.preventDefault(); GraalGround.moved()
    return true
}
