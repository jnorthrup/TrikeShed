package borg.trikeshed.web.spacegraph

import borg.trikeshed.web.*
import borg.trikeshed.vm.VmSourceCoordinates
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.*

/** Controls the same admitted daemon run; this page never evaluates a node to step it. */
class PanelsDebug(private val panels: Panels) {
    internal fun launchJs(block: suspend () -> Unit) = panels.launchJs(block)
    var token = ""
        private set
    private var panel: dynamic = null
    private var state: dynamic = null
    private var pending: dynamic = null
    private var lastText = ""
    private var observedPause = false
    private var ended = false
    private var available = false
    private var runId = ""
    private var coordinates = VmSourceCoordinates.AUTHORED

    fun start(): String {
        open(str(js("crypto.randomUUID()")), "")
        return token
    }

    /** Observation belongs to this page, not a new execution or a detached poller. */
    suspend fun attach(token: String, run: String) = coroutineScope {
        open(token, run)
        val target = panel
        val stop = document.createElement("button").asDynamic()
        stop.textContent = "Stop observing"
        panel.querySelector("[data-debug-controls]").appendChild(stop)
        on(stop, "click", { stopObserving() })
        try { observe(reconnecting = true) }
        finally {
            stop.remove()
            if (panel === target && !ended) end("Observation stopped; the run was not cancelled or resumed. Reconnect to check its state.")
        }
    }

    fun stopObserving() {
        if (panel != null && !ended) end("Observation stopped; the run was not cancelled or resumed.")
    }

    private fun open(value: String, run: String) {
        token = value; runId = run; available = false
        state = null; pending = null; lastText = ""; observedPause = false; ended = false
        coordinates = VmSourceCoordinates.AUTHORED
        panel?.remove()
        panel = document.createElement("section").asDynamic()
        panel.setAttribute("aria-label", "Node debugger")
        panel.style.cssText = "position:fixed;left:16px;bottom:36px;width:min(760px,calc(100vw - 32px));height:600px;max-height:calc(100vh - 120px);display:flex;flex-direction:column;gap:6px;overflow:auto;overflow-wrap:anywhere;box-sizing:border-box;z-index:56;background:var(--panel,#101620);color:var(--ink,#dce6f3);border:1px solid var(--cyan,#57d3ff);border-radius:10px;padding:16px;font:12px/1.5 var(--mono,monospace);box-shadow:0 14px 42px #0008"
        panel.innerHTML = "<strong>NODE / SOURCE DEBUGGER</strong><div>Same run · no replay · pauses count toward timeouts.</div>" +
            "<details style='flex-shrink:0'><summary>Stepping and limits</summary>Next node enters rings and iterations. Source stepping supports in-process JS/Python/Ruby; other tiers refuse. Choose authored-body or generated-guest line numbers. Adapter stops have no authored line; frame lines stay generated. Run to line is one-shot in this guest source; Truffle may resolve a nearby executable line, and an unreached target may finish without a stop. Continue executes remaining effects. Locals are read-only; objects and getters are not evaluated.</details>" +
            "<div data-debug-links style='display:flex;gap:12px;flex-wrap:wrap;flex-shrink:0'></div>" +
            "<div data-debug-state style='flex-shrink:0'>Awaiting admission…</div>" +
            "<div style='display:grid;grid-template-columns:repeat(auto-fit,minmax(min(300px,100%),1fr));gap:12px;min-height:80px;flex:1;overflow:auto'>" +
            "<pre data-debug-code hidden style='white-space:pre-wrap;min-width:0;overflow:auto;margin:4px 0'></pre>" +
            "<pre data-debug-site style='white-space:pre-wrap;min-width:0;overflow:auto;margin:4px 0'></pre></div>" +
            "<label style='flex-shrink:0'>Coordinates <select data-debug-coordinates aria-label='Source line coordinates' disabled><option value='authored'>Authored body</option><option value='generated'>Generated guest</option></select> · line <input data-debug-line aria-label='Source line' type='number' min='1' step='1' disabled style='width:80px;padding:4px;background:var(--panel,#101620);color:inherit;border:1px solid var(--cyan,#57d3ff);border-radius:4px'></label>" +
            "<div data-debug-controls style='display:flex;gap:6px;flex-wrap:wrap;flex-shrink:0'></div><div data-debug-error role='status' style='flex-shrink:0'></div>"
        document.body!!.appendChild(panel)
        val coordinateControl = panel.querySelector("[data-debug-coordinates]")
        on(coordinateControl, "change", {
            coordinates = if (coordinateControl.value == "authored") VmSourceCoordinates.AUTHORED else VmSourceCoordinates.GENERATED
            sourceView(resetLine = true)
        })
        for ((label, action) in listOf("Step into next node" to "step", "Into guest source" to "source-into",
            "Step over source" to "source-over", "Step out of source" to "source-out",
            "Run to line" to "source-line",
            "Continue run" to "continue", "Cancel run" to "cancel")) {
            val button = document.createElement("button").asDynamic()
            button.textContent = label; button.setAttribute("data-command", action); button.disabled = true
            panel.querySelector("[data-debug-controls]").appendChild(button)
            on(button, "click", { command(action) })
        }
        link("Open Panels editor (saved scratch)", "/panels")
        if (runId.isNotEmpty()) recoveryLink()
    }

    private fun link(label: String, href: String) {
        val anchor = document.createElement("a").asDynamic()
        anchor.textContent = label; anchor.href = href
        anchor.style.cssText = "color:var(--cyan,#57d3ff);text-underline-offset:3px"
        panel.querySelector("[data-debug-links]").appendChild(anchor)
    }

    /** Inspect the admitted program without replacing this run's observation page. */
    private fun frozenProgram(cid: dynamic) {
        if (cid == null) return
        val href = ConfixNavigation.href(str(cid))
        val existing = panel.querySelector("[data-debug-program]")
        require(existing == null || existing.getAttribute("href") == href) { "Debug program identity changed" }
        if (existing != null) return
        link("Frozen program in Confix", href)
        val anchor = panel.querySelector("[data-debug-links]").lastElementChild
        anchor.setAttribute("data-debug-program", "")
        anchor.setAttribute("target", "_blank")
        anchor.setAttribute("rel", "noopener")
    }

    private fun recoveryLink(): String {
        val href = DebugNavigation.href(token, runId)
        link("Reconnect this run", href)
        return href
    }

    private fun buttons() {
        for (button in arr(panel.querySelectorAll("[data-command]"))) {
            val action = str(button.getAttribute("data-command"))
            button.disabled = ended || !available || state?.runId == null || (action != "cancel" &&
                (state.state != "paused" || pending == state.ordinal || !arr(state.actions).any { str(it) == action }))
            button.style.opacity = if (truthy(button.disabled)) "0.45" else "1"
        }
        panel.querySelector("[data-debug-line]").disabled = ended || !available || state?.state != "paused" || pending == state?.ordinal ||
            !arr(state?.actions).any { str(it) == "source-line" }
        panel.querySelector("[data-debug-coordinates]").disabled = ended || !available || state?.site?.kind != "source" || pending == state?.ordinal
    }

    private fun sourceCoordinates(): VmSourceCoordinates =
        if (coordinates == VmSourceCoordinates.AUTHORED && state?.site?.authored != null) coordinates else VmSourceCoordinates.GENERATED

    private fun sourceView(resetLine: Boolean = false) {
        val site = state?.site
        val source = site?.kind == "source"
        val code = panel.querySelector("[data-debug-code]")
        code.hidden = !source
        if (!source) { code.textContent = ""; return }
        val authored = sourceCoordinates() == VmSourceCoordinates.AUTHORED
        val view = if (authored) site.authored else site
        val select = panel.querySelector("[data-debug-coordinates]")
        select.querySelector("[value='authored']").disabled = site.authored == null
        select.value = if (authored) "authored" else "generated"
        val input = panel.querySelector("[data-debug-line]")
        input.setAttribute("aria-label", if (authored) "Authored source line" else "Generated source line")
        input.max = str(view.lineCount)
        if (resetLine) input.value = str(view.line ?: 1)
        val lines = arr(view.sourceLines)
        code.textContent = (if (authored) "Authored function body · generated line " + str(site.line) +
            (if (view.line == null) "\nAdapter boundary; no authored line at this stop.\n\n" else "\n\n")
            else "Generated guest source (includes adapter lines)\n\n") +
            (if (truthy(site.breakpoint?.hit)) "Breakpoint: requested " + str(view.breakpoint?.requestedLine ?: "outside body") +
                " → stopped at " + str(view.breakpoint?.resolvedLine ?: "outside body") + "\n" else "") +
            (if (lines.isNotEmpty()) {
                val first = (view.lineStart as Number).toInt()
                "Lines $first–${first + lines.size - 1} of " + str(view.lineCount) + "\n" +
                    lines.mapIndexed { i, line ->
                        (if (first + i == (view.line as? Number)?.toInt()) "> " else "  ") + "${first + i}  " + str(line)
                    }.joinToString("\n")
            } else if (authored) "The current generated window does not intersect the authored body."
            else str(site.code ?: "Source unavailable"))
    }

    /** Child of the original request or the explicit recovery page; GET never advances a run. */
    suspend fun observe(reconnecting: Boolean = false) {
        val target = panel
        val requestToken = token
        fun current(): Boolean = !ended && !panels.page.closing && panel === target && token == requestToken
        while (current()) {
            try {
                val response = fetchJs("/api/lcnc/run/debug?token=" + encodeURIComponent(requestToken) +
                    if (runId.isEmpty()) "" else "&runId=" + encodeURIComponent(runId))
                val snapshot = awaitJs(response.json())
                // An admitted read drains, but cannot repaint a stopped or replaced observation.
                if (!current()) return
                if (!truthy(response.ok) || snapshot.error != null) {
                    available = false; buttons()
                    if (runId.isNotEmpty() && (response.status == 404 || response.status == 409)) {
                        end("Live controller unavailable: " + str(snapshot.error) + ". No new run was requested.", snapshot.receipt)
                        return
                    }
                    // The first GET can precede admission of our own POST. Only that window is pending.
                    panel.querySelector("[data-debug-error]").textContent = if (!reconnecting && runId.isEmpty() && response.status == 404)
                        "Awaiting admission…" else "Observation unavailable: HTTP " + str(response.status) + " · " + str(snapshot.error)
                } else {
                    require(snapshot.runId != null && (runId.isEmpty() || snapshot.runId == runId)) { "Debug run identity changed" }
                    frozenProgram(snapshot.programCid)
                    if (runId.isEmpty()) {
                        runId = str(snapshot.runId)
                        // Reload is now an inert observation of this exact run, not scratch activation.
                        window.history.replaceState(null, "", recoveryLink())
                    }
                    val restored = !available
                    available = true
                    val newPause = snapshot.state == "paused" && (state?.state != "paused" || state?.ordinal != snapshot.ordinal)
                    if (snapshot.state == "paused" && snapshot.site != null) observedPause = true
                    state = snapshot
                    val source = snapshot.site?.kind == "source"
                    sourceView(resetLine = newPause)
                    if (newPause || restored) panel.querySelector("[data-debug-error]").textContent =
                        if (snapshot.state != "paused") "Observing the existing run."
                        else if (pending == snapshot.ordinal) "Pause still observed; previous command outcome unknown. No automatic retry was sent."
                        else if (truthy(snapshot.site?.breakpoint?.hit)) "Breakpoint reached. Choose a step, another line, or Continue."
                        else "Paused. Choose a command to advance this run."
                    panel.querySelector("[data-debug-state]").textContent = (if (snapshot.state == "paused")
                        if (source) "Paused " + str(snapshot.site.anchor) + " " + str(snapshot.site.element) + " · generated " + str(snapshot.site.sourceName) + ":" + str(snapshot.site.line) + ":" + str(snapshot.site.column) +
                            (if (snapshot.site.authored != null) " · authored " + (if (snapshot.site.authored.line == null) "adapter boundary" else str(snapshot.site.authored.line) + ":" + str(snapshot.site.authored.column)) else "")
                        else "Paused before " + str(snapshot.site?.nodeId ?: "omitted location") else str(snapshot.state)) + " · run " + str(snapshot.runId) +
                        " · ordinal " + str(snapshot.ordinal) + "\nFrozen program " + str(snapshot.programCid ?: "awaiting freeze")
                    val text = if (snapshot.site == null) lastText.ifEmpty { "Waiting for the first node/source boundary…" }
                        else if (source) "Read-only locals (frame lines are generated)\n\n" + arr(snapshot.site.frames).mapIndexed { index, frame ->
                                "#$index " + str(frame.name ?: "anonymous") + " · line " + str(frame.line) + "\n" +
                                    arr(frame.locals).joinToString("\n") { local ->
                                        "  " + str(local.name) + " = " + if (local.omitted != null) "<" + str(local.omitted) + ">"
                                        else executionPreview(local.value) + if (truthy(local.truncated)) "…" else ""
                                    }
                            }.joinToString("\n\n") +
                            if (truthy(snapshot.site.truncated)) "\nObservation truncated." else ""
                        else executionPreview(snapshot.site)
                    if (text != lastText) { panel.querySelector("[data-debug-site]").textContent = text; lastText = text }
                    buttons()
                }
            } catch (e: CancellationException) { throw e }
            catch (e: dynamic) {
                if (!current()) return
                available = false; buttons()
                panel.querySelector("[data-debug-error]").textContent = "Observation unavailable; controls disabled, snapshot not live: " + failureText(e)
            }
            delay(300)
        }
    }

    private fun command(action: String) {
        if (ended || !available) return
        val observed = state ?: return
        val ordinal = observed.ordinal
        val target = panel
        val requestToken = token
        val coordinate = sourceCoordinates()
        val source = if (coordinate == VmSourceCoordinates.AUTHORED) observed.site?.authored else observed.site
        val line = if (action == "source-line") str(panel.querySelector("[data-debug-line]").value).toIntOrNull() else null
        if (action == "source-line" && (line == null || line < 1 || line > ((source?.lineCount as? Number)?.toInt() ?: 0))) {
            panel.querySelector("[data-debug-error]").textContent = "Choose a whole " + coordinate.name.lowercase() + " line from 1 to " + str(source?.lineCount) + ". The run remains paused."
            return
        }
        pending = ordinal; buttons()
        launchJs {
            try {
                val request = objOf { it.token = requestToken; it.ordinal = ordinal; it.action = action; it.runId = observed.runId }
                if (line != null) { request.line = line; request.coordinates = coordinate.name.lowercase() }
                val response = fetchJson(if (action == "cancel") "/api/lcnc/run/cancel" else "/api/lcnc/run/debug",
                    jsonInit("POST", JSON.stringify(request)))
                if (ended || panel !== target || token != requestToken) return@launchJs
                if (state?.state != "paused" || state?.ordinal == ordinal || !truthy(response.ok))
                    panel.querySelector("[data-debug-error]").textContent = if (truthy(response.ok))
                    if (line != null) "Running toward " + coordinate.name.lowercase() + " line $line (one-shot); a nearby executable line may resolve, or the run may finish without a hit."
                    else "Command accepted; awaiting the run…"
                    else "Command refused or pause changed. Inspect the current state; no retry was sent."
                if (!truthy(response.ok)) { pending = null; buttons() }
            } catch (e: CancellationException) { throw e }
            catch (e: dynamic) { if (!ended && panel === target && token == requestToken)
                panel.querySelector("[data-debug-error]").textContent = "Command outcome unknown; no automatic retry: " + failureText(e) }
        }
    }

    fun finish() {
        val run = panels.lastProgramRun
        val retained = run != null && runId.isNotEmpty() && run.runId == runId
        if (!ended || retained) end(if (retained) "Request finished."
            else "Request ended. A lost response is not proof of cancellation; use Reconnect this run or inspect Evidence.",
            if (retained) run else null)
    }

    private fun end(message: String, receipt: dynamic = null) {
        val retained = receipt != null && receipt.runId == runId && receipt.receiptCid != null
        var receiptHref: String? = null
        var invocationsHref: String? = null
        var evidenceError = ""
        if (retained) try {
            receiptHref = ConfixNavigation.href(str(receipt.receiptCid), "[\"lcncRun\"]")
            if (receipt.invocationsCid != null)
                invocationsHref = ConfixNavigation.href(str(receipt.invocationsCid), "[\"entries\"]")
            frozenProgram(receipt.programCid)
        } catch (e: dynamic) {
            evidenceError = "Retained evidence refused: " + failureText(e)
        }
        ended = true; available = false
        state = null
        buttons()
        val accepted = retained && evidenceError.isEmpty()
        panel.querySelector("[data-debug-state]").textContent = message + if (accepted)
            "\nRecorded status: " + str(receipt.status) + " · " + runId + "\nReceipt " + str(receipt.receiptCid)
            else "\nNo matching durable outcome was returned. Do not infer success or cancellation."
        if (accepted && panel.querySelector("[data-debug-receipt]") == null) {
            link("Recorded receipt in Confix", requireNotNull(receiptHref))
            panel.querySelector("[data-debug-links]").lastElementChild.setAttribute("data-debug-receipt", "")
            if (invocationsHref != null) link("Invocations in Confix", invocationsHref)
        }
        panel.querySelector("[data-debug-error]").textContent = if (evidenceError.isNotEmpty()) evidenceError else if (observedPause)
            "The snapshot above is the last observed pause, not live state."
            else "No pause was observed. Inspect Evidence for the durable outcome."
        if (panel.querySelector("[data-debug-close]") == null) {
            val target = panel
            val close = document.createElement("button").asDynamic(); close.textContent = "Close debugger"
            close.setAttribute("data-debug-close", "")
            panel.querySelector("[data-debug-controls]").appendChild(close)
            on(close, "click", { target.remove() })
        }
    }
}
