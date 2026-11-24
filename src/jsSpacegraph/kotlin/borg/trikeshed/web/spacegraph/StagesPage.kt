package borg.trikeshed.web.spacegraph

import borg.trikeshed.lcnc.Expectation
import borg.trikeshed.lib.*
import borg.trikeshed.narsese.Challenge
import borg.trikeshed.narsese.IngestStages
import borg.trikeshed.parse.reify
import borg.trikeshed.web.*
import kotlinx.browser.document
import kotlinx.browser.window
import narchy.spacegraph.*
import org.w3c.dom.CanvasRenderingContext2D
import org.w3c.dom.HTMLCanvasElement
import org.w3c.dom.HTMLElement

/**
 * /stages: [StageBoard]'s machine on a canvas. The page holds the clock and nothing else of the picture: every frame
 * is [StageBoard.frame] at the clock's time. The controls drawn on the machine are found by [StageBoard.controls];
 * space, the arrows, R and N do the same. `?run=<id>` opens a retained run; a ball dropped here is read through the
 * stages on the daemon (`/api/lcnc/run`) with every ball already in the machine, and the address then names that run.
 */
object StagesPage {
    internal val page = PanelsPage { status("Page action failed: " + failureText(it)) }
    fun close() = page.close()
    lateinit var host: HTMLElement
    lateinit var canvas: HTMLCanvasElement
    var drop: StageBoard.Drop? = null
    /** The clock: seconds into the play, whether it runs, and how fast. */
    var t = 0.0
    var playing = false
    var speed = 1.0
    var last = 0.0
    var dirty = true
    var ratio = 1.0
    var mono = .6
    var animation = 0
    var resizeObserver: dynamic = null

    fun mount() { page.mount { install() } }

    fun install() {
        page.own { window.cancelAnimationFrame(animation); resizeObserver?.disconnect() }
        host = document.getElementById("stages") as HTMLElement
        canvas = (document.createElement("canvas") as HTMLCanvasElement).also { it.style.cssText = "position:absolute;inset:0;width:100%;height:100%"; host.append(it) }
        val c = canvas.getContext("2d") as CanvasRenderingContext2D
        c.font = "400 100px ui-monospace,Menlo,monospace"
        mono = c.measureText("M").width / 100
        resizeObserver = jsNew(js("ResizeObserver"), { _: dynamic -> if (!page.closing) resize() })
        resizeObserver.observe(host)
        resize()
        page.on(canvas, "click", { e: dynamic -> val r = canvas.getBoundingClientRect(); press(num(e.clientX) - r.left, num(e.clientY) - r.top) })
        page.on(window, "keydown", { e: dynamic ->
            if (str(e.target.tagName) !in setOf("TEXTAREA", "INPUT", "SELECT")) {
                val id = when (str(e.key)) { " " -> "play"; "ArrowRight" -> "next"; "ArrowLeft" -> "back"; "r", "R" -> "again"; "n", "N" -> "skip"; else -> null }
                if (id != null) { e.preventDefault(); control(id) }
            }
        })
        page.on(byId("stages-launch"), "submit", { e: dynamic -> e.preventDefault(); launch() })
        page.on(byId("stages-cancel"), "click", { _: dynamic -> byId("stages-launch").hidden = true })
        fun loop(now: Double) {
            if (page.closing) return
            val d = drop
            if (playing && d != null) {
                t += (now - last) / 1000 * speed
                if (t >= d.end + .5) { t = d.end + .5; playing = false }
                dirty = true
            }
            last = now
            if (dirty) { dirty = false; draw() }
            animation = window.requestAnimationFrame(::loop)
        }
        animation = window.requestAnimationFrame(::loop)
        run()
    }

    fun status(s: String) { document.getElementById("stages-status")?.textContent = s }
    fun viewport() = Viewport(maxOf(1, host.clientWidth), maxOf(1, host.clientHeight))

    fun resize() {
        ratio = window.devicePixelRatio.let { if (it == 0.0) 1.0 else it }
        val v = viewport()
        canvas.width = (v.width * ratio).toInt(); canvas.height = (v.height * ratio).toInt()
        dirty = true
    }

    fun draw() {
        val c = canvas.getContext("2d") as CanvasRenderingContext2D
        c.setTransform(ratio, 0.0, 0.0, ratio, 0.0, 0.0)
        FrameCanvas.draw(c, StageBoard.frame(drop, viewport(), t, playing, speed, mono))
    }

    /** A press on the machine: the control under it, or a contact of the strip. */
    fun press(x: Double, y: Double) {
        val hit = StageBoard.controls(viewport()).firstOrNull { (_, r) -> x >= r.x && x <= r.right && y >= r.y && y <= r.bottom } ?: return
        val d = drop
        if (hit.a == "strip" && d != null && d.contacts.isNotEmpty()) {
            val k = ((x - hit.b.x) / hit.b.width * d.contacts.size).toInt().coerceIn(0, d.contacts.size - 1)
            t = d.stops[k]; playing = false; dirty = true
        } else control(hit.a)
    }

    fun control(id: String) {
        val d = drop
        when (id) {
            "new" -> { byId("stages-launch").hidden = false; byId("stages-claim").focus(); return }
            "1", "0.5", "0.1" -> speed = id.toDouble()
            else -> if (d == null) return else when (id) {
                "play" -> { if (t >= d.end) t = 0.0; playing = !playing }
                "next" -> { t = d.stops.firstOrNull { it > t + .01 } ?: (d.end + .5); playing = false }
                "back" -> { t = d.stops.lastOrNull { it < t - .01 } ?: 0.0; playing = false }
                "again" -> { t = (d.balls.lastOrNull { it.start <= t } ?: d.balls.firstOrNull())?.start ?: 0.0; playing = true }
                "skip" -> { t = d.balls.firstOrNull { it.start > t }?.start ?: (d.end + .5); playing = t < d.end }
            }
        }
        dirty = true
    }

    /** The run the address names (`?run=<id>`), read from the daemon's retained receipt. */
    fun run() {
        val id = (jsNew(js("URLSearchParams"), window.location.search).get("run") as? String)?.takeIf { it.isNotBlank() } ?: return
        status("reading run $id")
        page.submit {
            try {
                val r = fetchJs("/api/lcnc/oroboros?runId=" + encodeURIComponent(id))
                val receipt = (reify(str(awaitJs(r.text()))) as? Map<*, *>)?.get("receipt") ?: throw IllegalStateException("the daemon retains no run $id")
                show(receipt, 0.0)
                status("run $id")
            } catch (e: dynamic) { status("run $id is not shown: " + failureText(e)) }
        }
    }

    /** A run [record] in the machine, played from [from]; its passage fills the launcher's passage box. */
    fun show(record: Any?, from: Double) {
        val next = StageBoard.Drop(StageBoard.output(record), Challenge.carried(record), StageBoard.passage(record))
        drop = next
        byId("stages-passage").value = next.passage
        t = from; playing = true; dirty = true
    }

    /** The launcher's ball, read through the stages with every ball already in the machine, then dropped. */
    fun launch() {
        val text = str(byId("stages-passage").value).trim()
        val note = byId("stages-launch-status")
        try {
            val author = str(byId("stages-author").value)
            val added = Challenge(author, str(byId("stages-claim").value).trim(), str(byId("stages-states").value) == "true",
                str(byId("stages-confidence").value).toDouble(), if (author == Expectation.MODEL) str(byId("stages-model").value).trim() else "")
            require(text.isNotBlank()) { "the machine needs a passage" }
            val held = drop?.takeIf { it.passage.trim() == text }
            val challenges = held?.challenges.orEmpty() + added
            // A ball already in the machine with no known outcome is read again as it was.
            val unknown = held?.balls.orEmpty().filter { it.challenge == null }.map { Expectation(it.author, it.claim) }
            val body = Challenge.request(Challenge.program(text, unknown + challenges.map { Expectation(it.author, it.claim) }, "", IngestStages.PARSERS, challenges))
            note.textContent = "reading ${unknown.size + challenges.size} claims through the stages"
            page.submit {
                try {
                    val r = fetchJs("/api/lcnc/run", jsonInit("POST", body))
                    val record = reify(str(awaitJs(r.text()))) as? Map<*, *> ?: throw IllegalStateException("the run answered no record")
                    record["error"]?.let { throw IllegalStateException(it.toString()) }
                    show(record, 0.0)
                    drop?.let { d -> t = d.balls.lastOrNull()?.start ?: 0.0 }
                    record["runId"]?.let { window.history.replaceState(null, "", window.location.pathname + "?run=" + encodeURIComponent(it.toString())) }
                    byId("stages-launch").hidden = true; byId("stages-claim").value = ""; note.textContent = ""
                    status("run ${record["runId"]}")
                } catch (e: dynamic) { note.textContent = "not dropped: " + failureText(e) }
            }
        } catch (refused: Throwable) { note.textContent = "not dropped: " + (refused.message ?: refused.toString()) }
    }
}
