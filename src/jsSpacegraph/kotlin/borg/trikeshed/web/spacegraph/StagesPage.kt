package borg.trikeshed.web.spacegraph

import borg.trikeshed.lcnc.Expectation
import borg.trikeshed.lib.*
import borg.trikeshed.narsese.Challenge
import borg.trikeshed.narsese.IngestStages
import borg.trikeshed.narsese.Setting
import borg.trikeshed.parse.reify
import borg.trikeshed.web.*
import kotlinx.browser.document
import kotlinx.browser.window
import narchy.spacegraph.*
import narchy.spacegraph.graphics.spi.SvgGraphicsProvider
import org.w3c.dom.HTMLElement

/**
 * /stages: the ingest.stages boards against challenges. [StageBoard] builds the boards, [SceneProjection] frames them
 * and [SvgGraphicsProvider] writes the frame into the page. A challenge is typed here or added as a model's rows. Read
 * runs the passage, every challenge's claim and every claim already on the board through the stages on the daemon
 * (`/api/lcnc/run`) and draws the board that comes back; a run record pasted is drawn as it stands. Wheel and drag
 * move the view; when its scale doubles or halves, the boards are built again to the depth the new scale shows.
 */
object StagesPage {
    internal val page = PanelsPage { status("Page action failed: " + failureText(it)) }
    fun close() = page.close()
    lateinit var host: HTMLElement
    val challenges = ArrayList<Challenge>()
    var output: Map<String, Any?>? = null
    var spec: GraphSpec? = null
    /** The scale [spec] was built for. */
    var built = 0.0
    var pressed: DoubleArray? = null
    var moved = 0.0
    var pending = 0
    var reading = false
    var shown = ""
    var resizeObserver: dynamic = null

    fun mount() {
        page.mount { install() }
    }

    fun install() {
        host = document.getElementById("stages") as HTMLElement
        byId("stages-parsers").value = IngestStages.PARSERS
        page.on(byId("stages-challenge"), "submit", { e: dynamic -> e.preventDefault(); add(e.target) })
        page.on(byId("stages-add-rows"), "click", { _: dynamic -> rows() })
        page.on(byId("stages-challenges"), "click", { e: dynamic ->
            val drop = e.target.closest("[data-drop]")
            if (drop != null) { challenges.removeAt(str(drop.getAttribute("data-drop")).toInt()); changed() }
        })
        page.on(byId("stages-read"), "click", { _: dynamic -> read() })
        page.on(byId("stages-load"), "click", { _: dynamic -> load() })
        input()
        resizeObserver = jsNew(js("ResizeObserver"), { _: dynamic -> if (!page.closing) view(spec?.camera) })
        resizeObserver.observe(host)
        page.own { resizeObserver?.disconnect() }
        changed()
        run()
    }

    /** The run the address names (`?run=<id>`), read from the daemon's retained receipt and shown as a pasted record is. */
    fun run() {
        val id = (jsNew(js("URLSearchParams"), window.location.search).get("run") as? String)?.takeIf { it.isNotBlank() } ?: return
        status("reading run $id…")
        page.submit {
            try {
                val r = fetchJs("/api/lcnc/oroboros?runId=" + encodeURIComponent(id))
                val receipt = (reify(str(awaitJs(r.text()))) as? Map<*, *>)?.get("receipt") ?: throw IllegalStateException("the daemon retains no run $id")
                show(receipt)
            } catch (e: dynamic) { status("run $id is not shown: " + failureText(e)) }
        }
    }

    /** The address names the run on the boards, so the address opens it again. */
    fun named(id: Any?) { if (id != null) window.history.replaceState(null, "", window.location.pathname + "?run=" + encodeURIComponent(id.toString())) }

    fun esc(s: Any?) = s.toString().replace("&", "&amp;").replace("<", "&lt;")
    fun status(s: String) { document.getElementById("stages-status")?.textContent = s }
    fun viewport() = Viewport(maxOf(1, host.clientWidth), maxOf(1, host.clientHeight))

    /** A challenge typed on the page: a person's outcome is the truth, a model's a candidate under the model's id. */
    fun add(form: dynamic) {
        try {
            val author = str(form.author.value)
            challenges.add(Challenge(author, str(form.claim.value).trim(), str(form.states.value) == "true", num(form.confidence.value),
                if (author == Expectation.MODEL) str(form.model.value).trim() else ""))
            form.claim.value = ""
            changed()
        } catch (refused: IllegalArgumentException) { status("challenge refused: " + refused.message) }
    }

    /** A model's challenges, as [Challenge.rows]. */
    fun rows() {
        val box = byId("stages-rows")
        try { challenges.addAll(Challenge.rows(str(box.value))); box.value = ""; changed() }
        catch (refused: Throwable) { status("rows refused: " + (refused.message ?: refused.toString())) }
    }

    fun changed() {
        byId("stages-challenges").innerHTML = challenges.withIndex().joinToString("") { (i, c) ->
            "<li><b>${esc(c.origin)}</b>: ${if (c.states) "states it" else "does not state it"}, ${c.confidence} · ${esc(c.claim)}" +
                "<button type='button' data-drop='$i' title='Remove'>×</button></li>"
        }
        build(spec?.camera)
    }

    /** The boards built for [camera]'s scale, or fitted to the view when there is no camera yet. */
    fun build(camera: GraphCamera?) {
        val out = output ?: return panel()
        val next = if (camera == null) StageBoard.fit(out, challenges, viewport()) else StageBoard.graph(out, challenges, camera.zoom).copy(camera = camera)
        spec = next; built = next.camera.zoom
        draw(); panel()
    }

    fun draw() {
        val s = spec ?: return
        host.innerHTML = SvgGraphicsProvider.encode(SceneProjection.frame(SpaceGraph(s), viewport())).a
    }

    /** The view moved to [camera]: drawn at the next frame, built again when the scale has doubled or halved since the last build. */
    fun view(camera: GraphCamera?) {
        val s = spec ?: return
        if (camera == null) return
        spec = s.copy(camera = camera)
        if (pending != 0) return
        pending = page.frame {
            pending = 0
            val now = spec ?: return@frame
            if (now.camera.zoom > built * 2 || now.camera.zoom < built / 2) build(now.camera) else draw()
        }
    }

    fun input() {
        fun at(e: dynamic): Vec3 { val r = host.getBoundingClientRect(); return Vec3(num(e.clientX) - r.left, num(e.clientY) - r.top) }
        val opts = obj(); opts.passive = false
        page.on(host, "wheel", { e: dynamic ->
            e.preventDefault()
            spec?.let { view(it.camera.zoomAt(kotlin.math.exp(-num(e.deltaY) / 400), at(e), viewport())) }
        }, opts)
        page.on(host, "pointerdown", { e: dynamic -> if (num(e.button) == 0.0) { pressed = doubleArrayOf(num(e.clientX), num(e.clientY)); moved = 0.0 } })
        page.on(host, "pointermove", { e: dynamic ->
            val p = pressed ?: return@on
            val camera = spec?.camera ?: return@on
            val dx = num(e.clientX) - p[0]; val dy = num(e.clientY) - p[1]
            pressed = doubleArrayOf(num(e.clientX), num(e.clientY)); moved += kotlin.math.abs(dx) + kotlin.math.abs(dy)
            val delta = Vec3(-dx / camera.zoom, dy / camera.zoom)
            view(camera.copy(center = camera.center + delta, position = camera.position + delta))
        })
        page.on(host, "pointerup", { _: dynamic -> pressed = null })
        page.on(host, "pointerleave", { _: dynamic -> pressed = null })
        page.on(host, "click", { e: dynamic ->
            if (moved > 4) return@on
            val hit = e.target.closest("[data-entity]") ?: return@on
            detail(str(hit.getAttribute("data-entity")))
        })
    }

    /** Read: the passage, every challenge's claim and every claim on the board no challenge answers, through the stages on the daemon. */
    @Suppress("UNCHECKED_CAST")
    fun read() {
        if (reading) return
        val text = str(byId("stages-passage").value)
        val board = (output?.get("board") as? List<*>).orEmpty().map { it as Map<String, Any?> }
        val unanswered = Challenge.answers(board, challenges).filter { it.b == null }.map { Expectation(it.a["author"].toString(), it.a["claim"].toString()) }
        val expectations = challenges.map { Expectation(it.author, it.claim) } + unanswered
        if (text.isBlank() || expectations.isEmpty()) { status("Read needs a passage and a challenge"); return }
        val body = Challenge.request(Challenge.program(text, expectations, str(byId("stages-generic").value), str(byId("stages-parsers").value)))
        reading = true
        status("reading ${expectations.size} claims through the stages…")
        page.submit {
            try {
                val r = fetchJs("/api/lcnc/run", jsonInit("POST", body))
                val run = reify(str(awaitJs(r.text()))) as? Map<*, *> ?: throw IllegalStateException("the run answered no record")
                run["error"]?.let { throw IllegalStateException(it.toString()) }
                output = StageBoard.output(run)
                shown = ""
                build(null)
                named(run["runId"])
                status("read · run ${run["runId"]}")
            } catch (e: dynamic) { status("the run failed: " + failureText(e)) }
            finally { reading = false }
        }
    }

    /** A pasted run record or stages output, drawn as it stands; a record's passage (the preset's passage node, or this page's text node) fills the passage box. */
    fun load() {
        try { show(reify(str(byId("stages-record").value))) }
        catch (refused: Throwable) { status("not shown: " + (refused.message ?: refused.toString())) }
    }

    /** [value], a run record or a stages output, on the boards; a record's passage fills the passage box. */
    fun show(value: Any?) {
        val out = StageBoard.output(value)
        val outputs = (value as? Map<*, *>)?.get("outputs") as? Map<*, *>
        val text = ((outputs?.get("passage") as? Map<*, *>)?.get("text") ?: (outputs?.get("text") as? Map<*, *>)?.get("value")) as? String
        if (text != null) byId("stages-passage").value = text
        output = out
        shown = ""
        build(null)
        status("shown · ${(out["board"] as List<*>).size} claims")
    }

    /** What a clicked circle stands for, read whole: labels on the boards are cut to their circles. */
    fun detail(id: String) {
        val node = spec?.nodes?.view?.firstOrNull { it.id == id }
        val claim = node?.data?.number("claim", -1.0)?.toInt()?.let { c -> ((output?.get("board") as? List<*>)?.getOrNull(c) as? Map<*, *>)?.get("claim") }
        shown = if (node == null) "" else "<h3>selected</h3><dl><dt>what</dt><dd>${esc(node.data.string("kind"))}</dd>" +
            (node.data.string("stage").takeIf { it.isNotEmpty() }?.let { "<dt>stage</dt><dd>${esc(it)}</dd>" } ?: "") +
            (claim?.let { "<dt>claim</dt><dd>${esc(it)}</dd>" } ?: "") + "<dt>reads</dt><dd>${esc(node.label)}</dd></dl>"
        panel()
    }

    /** Per setting, each origin's score and every claim it disagrees on; each model held against the persons; the challenges the board has not read. */
    @Suppress("UNCHECKED_CAST")
    fun panel() {
        val el = document.getElementById("stages-panel") ?: return
        val html = StringBuilder()
        val out = output
        if (out != null) {
            val answers = Challenge.answers((out["board"] as List<*>).map { it as Map<String, Any?> }, challenges)
            html.append("<h3>scores</h3>")
            for (setting in Setting.of(out)) {
                html.append("<h4>${esc(setting.name)}</h4><ul>")
                val scores = Challenge.score(answers, setting)
                if (scores.isEmpty()) html.append("<li>no challenge knows an outcome</li>")
                for (s in scores) {
                    html.append("<li><b>${esc(s.origin)}</b>: agrees ${s.agreed} · disagrees ${s.disagreed}</li>")
                    for (miss in s.disagreements) html.append("<li class='miss'>✗ ${esc(miss)}</li>")
                }
                html.append("</ul>")
            }
            val unread = challenges.toMutableList().also { left -> for (a in answers) a.b?.let { left.remove(it) } }
            if (unread.isNotEmpty()) html.append("<h3>not on the board: Read drops them</h3><ul>" + unread.joinToString("") { "<li>${esc(it.claim)}</li>" } + "</ul>")
        }
        val held = Challenge.against(challenges)
        if (held.isNotEmpty()) {
            html.append("<h3>models against the persons</h3><ul>")
            for (s in held) {
                html.append("<li><b>${esc(s.origin)}</b>: agrees ${s.agreed} · differs ${s.disagreed}</li>")
                for (miss in s.disagreements) html.append("<li class='miss'>✗ ${esc(miss)}</li>")
            }
            html.append("</ul>")
        }
        el.innerHTML = html.append(shown).toString()
    }
}
