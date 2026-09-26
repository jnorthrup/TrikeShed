package borg.trikeshed.web

import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.CancellationException
import kotlin.math.floor
import kotlin.math.min

/** The owner a shake runs against: the panels canvas or the blackboard's selected program. */
interface PatchShakeOwner {
    var shaking: Boolean
    val selected: String?
    val parentRevision: Int
    fun parentTarget(): dynamic
    fun document(): dynamic
    fun message(text: String)
}

/**
 * patch-shake.js: the daemon owns matching; both surfaces share request guards and verdicts.
 */
class PatchShake(private val s: PatchSurface, private val camera: PatchCamera) {
    /** node ids whose reach runs on nothing (redraw paints their cables) */
    var starved: MutableSet<String> = HashSet()
    /** [{nodeId,dir,port,cls,label,el,point}] — the shaken sockets and their outcome */
    private var verdicts = ArrayList<dynamic>()

    init { camera.projectVerdicts = { projectVerdicts() } }

    private fun shakeButton(): dynamic = document.getElementById("shakeBtn").asDynamic()

    suspend fun request(owner: PatchShakeOwner, options: dynamic, namespace: String? = null): Int? {
        if (owner.shaking || owner.selected.isNullOrEmpty()) return null
        val target = owner.parentTarget() ?: return null
        val name = owner.selected
        val revision = owner.parentRevision; val parentId = target.handle.nodeId
        val controller = jsNew(js("AbortController"))
        val timer = window.setTimeout({ controller.abort() }, 8000)
        owner.shaking = true; shakeButton()?.disabled = true; owner.message("Checking connections in " + name)
        try {
            val program = owner.document(); val snapshot = JSON.stringify(program)
            val opts = spread(options); opts.parentId = parentId
            val req = obj(); req.program = program; req.options = opts
            val body = JSON.stringify(req)
            if (num(js("new TextEncoder()").encode(body).length) > 1048576) throw Error("Connection request payload limit exceeded")
            val init = jsonInit("POST", body); init.signal = controller.signal
            val response = fetchJs("/api/lcnc/treeshake", init)
            val result = awaitJs(response.json())
            if (!truthy(response.ok) || !truthy(result.ok)) throw Error(str(result.detail ?: result.error ?: response.status))
            if (owner.selected != name || owner.parentRevision != revision || JSON.stringify(owner.document()) != snapshot) {
                owner.message("Connections or selected parent changed during the check; run Shake again"); return null
            }
            if (parentId != null && result.parentId != parentId) throw Error("Server did not confirm the selected parent; use an updated server")
            return apply(result, truthy(options?.optional), namespace)
        } catch (e: CancellationException) {
            throw e
        } catch (e: dynamic) {
            owner.message("Connections refused: " + (if (e.name == "AbortError") "check timed out; try again" else failureText(e)))
            return null
        } finally {
            window.clearTimeout(timer); owner.shaking = false; shakeButton()?.disabled = false
        }
    }

    private fun verdictLayer(): dynamic {
        var l = document.getElementById("verdicts").asDynamic()
        if (l == null) { l = document.createElement("div").asDynamic(); l.id = "verdicts"; s.viewport.appendChild(l) }
        return l
    }

    fun clearVerdicts() {
        for (e in arr(document.querySelectorAll(".port.v-ok,.port.v-dead,.port.v-open,.port.v-scope,.port.v-optional,.port.v-binding")))
            e.classList.remove("v-ok", "v-dead", "v-open", "v-scope", "v-optional", "v-binding")
        for (e in arr(document.querySelectorAll(".node.starved"))) e.classList.remove("starved")
        verdictLayer().textContent = ""
        verdicts = ArrayList(); starved = HashSet()
    }

    /** Build the badges once, staggered, so the pass reads as a pass. */
    private fun buildVerdicts() {
        val l = verdictLayer(); l.textContent = ""
        val fragment = document.createDocumentFragment().asDynamic()
        verdicts.forEachIndexed { i, v ->
            val c = s.portCenter(v.nodeId, v.dir, v.port) ?: return@forEachIndexed
            val d = document.createElement("div").asDynamic()
            d.className = "vmark " + v.cls
            d.textContent = when (v.cls) { "ok" -> "✓"; "binding" -> "="; "optional" -> "-"; "dead" -> "✕"; "scope" -> "⇱"; else -> "?" }
            d.style.left = "${c.x}px"; d.style.top = "${c.y}px"
            d.style.animationDelay = "${min(i * 12, 300)}ms"
            d.title = v.label ?: ""
            fragment.appendChild(d); v.el = d; v.point = c
        }
        l.appendChild(fragment); projectVerdicts()
    }

    fun projectVerdicts() {
        if (verdicts.isEmpty()) return
        val view = s.view
        verdictLayer().style.setProperty("--verdict-scale", min(1.0, num(view.z)))
        val width = num(s.viewport.clientWidth); val height = num(s.viewport.clientHeight)
        for (v in verdicts) {
            if (v.el == null) continue
            val c = v.point
            val x = if (c != null) num(c.x) * num(view.z) + num(view.x) else 0.0
            val y = if (c != null) num(c.y) * num(view.z) + num(view.y) else 0.0
            val display = if (c != null && x >= -32 && y >= -32 && x <= width + 32 && y <= height + 32) "" else "none"
            if (v.el.style.display != display) v.el.style.display = display
            if (display != "none") { v.el.style.left = "${x}px"; v.el.style.top = "${y}px" }
        }
    }

    /** Re-anchor on redraw — rebuilding here would restart every animation on every pointermove. */
    fun positionVerdicts() {
        if (verdicts.isEmpty()) return
        val positions = verdicts.map { v -> v to (if (v.el != null) s.portCenter(v.nodeId, v.dir, v.port) else null) }
        for (pair in positions) { val v = pair.first; if (v.el == null) continue; v.point = pair.second }
        projectVerdicts()
    }

    /** mark one of a node's OWN ports (a ring's DOM holds its children's ports too) */
    private fun markPort(nd: dynamic, dir: String, port: String, cls: String) {
        if (nd.el == null) return
        for (pe in arr(nd.el.querySelectorAll(".port[data-dir=\"$dir\"]"))) {
            if (pe.closest(".node") !== nd.el) continue
            if (pe.dataset.port != port) continue
            pe.classList.add(cls); return
        }
    }

    fun apply(resIn: dynamic, @Suppress("UNUSED_PARAMETER") inclOptional: Boolean, programName: String?): Int {
        var res = resIn
        if (programName != null) {
            val id = { local: dynamic -> programName + "::" + str(local) }
            res = spread(res)
            res.made = arr(resIn.made).map { m -> val c = spread(m); c.fromNode = id(m.fromNode); c.toNode = id(m.toNode); c }.toTypedArray()
            res.verdicts = arr(resIn.verdicts).map { v -> val c = spread(v); c.nodeId = id(v.nodeId); c }.toTypedArray()
            res.starved = arr(resIn.starved).map { id(it) }.toTypedArray()
        }
        clearVerdicts()
        val made = arr(res.made)
        val g = s.graph
        for (m in made) {
            val exists = arr(g.wires).any { w -> w.from[0] == m.fromNode && w.from[1] == m.fromPort && w.to[0] == m.toNode && w.to[1] == m.toPort }
            if (!exists) g.wires.push(wire(m.fromNode, m.fromPort, m.toNode, m.toPort))
        }
        if (made.isNotEmpty()) { s.redraw(); s.save() }

        val vs = arr(res.verdicts)
        for (v in vs) {
            val nd = s.node(v.nodeId)
            if (nd != null) {
                markPort(nd, str(v.dir), str(v.port), "v-" + str(v.status))
                val rec = obj(); rec.nodeId = v.nodeId; rec.dir = v.dir; rec.port = v.port; rec.cls = v.status; rec.label = v.label
                verdicts.add(rec)
            }
        }
        buildVerdicts()

        starved = arr(res.starved).map { str(it) }.toMutableSet()
        for (id in starved) { val nd = s.node(id); if (nd != null && nd.el != null) nd.el.classList.add("starved") }
        if (starved.isNotEmpty()) s.redraw()

        val reachable = vs.filter { it.status == "open" }
        val scoped = vs.filter { it.status == "scope" }
        val dead = vs.filter { it.status == "dead" }
        val parts = ArrayList<String>()
        parts += if (made.isNotEmpty()) "${made.size} cables connected" else "No cables changed"
        val coverage = res.coverage
        if (coverage != null && isInt(coverage.total) && num(coverage.total) > 0 && isInt(coverage.connected) &&
            num(coverage.connected) >= 0 && num(coverage.connected) <= num(coverage.total)) {
            val c = num(coverage.connected).toInt(); val t = num(coverage.total).toInt()
            parts += "$c/$t sockets connected (${floor(100.0 * c / t).toInt()}%)"
        }
        // Preserve the daemon's reason: proximity cannot authorize an effect input.
        val reasons = LinkedHashMap<String, Pair<IntArray, ArrayList<String>>>()
        for (v in reachable) {
            val label = if (truthy(v.label)) str(v.label) else "Connection unresolved"
            val group = reasons.getOrPut(label) { IntArray(1) to ArrayList() }
            group.first[0]++
            if (group.second.size < 3) {
                val node = s.node(v.nodeId)
                group.second += str(node?._localId ?: v.nodeId) + "." + str(v.port)
            }
        }
        for ((label, group) in reasons)
            parts += group.second.joinToString(", ") + (if (group.first[0] > 3) " and ${group.first[0] - 3} more inputs" else "") + ": " + label
        if (scoped.isNotEmpty()) parts += "⇱ ${scoped.size} scope-blocked"
        if (dead.isNotEmpty()) parts += "✕ ${dead.size} with no mate on the board"
        if (starved.isNotEmpty()) parts += "${starved.size} node" + (if (starved.size == 1) "" else "s") + " downstream run on nothing"
        if (truthy(res.outletBlocked)) parts += "⇱ ${res.outletBlocked} outlet" + (if (res.outletBlocked == 1) "" else "s") + " blocked by ring depth"
        val optional = vs.count { it.status == "optional" }
        if (optional > 0) parts += "$optional optional inputs unchanged"
        if (made.isEmpty() && reachable.isEmpty() && scoped.isEmpty() && dead.isEmpty() && starved.isEmpty()) parts += "No required cable gaps found"
        val text = parts.joinToString(" · ")
        s.status(text)
        if (programName != null) s.showConnections(programName, res, text)

        for (m in made) {
            for (el in arr(document.querySelectorAll(".port[data-port=\"" + js("CSS").escape(m.toPort) + "\"]"))) {
                val nd = s.node(m.toNode)
                if (nd != null && el.closest(".node") === nd.el) {
                    el.classList.add("just-shook")
                    window.setTimeout({ el.classList.remove("just-shook") }, 1400)
                }
            }
            val a = s.portCenter(m.fromNode, "out", m.fromPort); val b = s.portCenter(m.toNode, "in", m.toPort)
            if (a != null && b != null) {
                val gp = document.createElementNS("http://www.w3.org/2000/svg", "path").asDynamic()
                gp.classList.add("cand", "only", "just-merged")
                PatchNavigation.wireCurve(gp, a, b, s.viewport, s.view)
                gp.style.stroke = "var(--ok)"
                s.wiresSvg.appendChild(gp)
                window.setTimeout({ gp.remove() }, 1400)
            }
        }
        return made.size
    }

    private fun isInt(v: dynamic): Boolean = js("Number.isInteger(v)").unsafeCast<Boolean>()
}

/** {from:[fromNode,fromPort], to:[toNode,toPort]} */
fun wire(fromNode: dynamic, fromPort: dynamic, toNode: dynamic, toPort: dynamic): dynamic {
    val w = obj(); w.from = arrayOf(fromNode, fromPort); w.to = arrayOf(toNode, toPort); return w
}
