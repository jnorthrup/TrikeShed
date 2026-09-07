package borg.trikeshed.docs

import borg.trikeshed.lcnc.LcncRunHead
import borg.trikeshed.parse.json.JsonSupport

/**
 * THE RUN BLOCK (AutoTools, Cut B): a build target written into a page.
 *
 * A Markdown page carries a fenced block whose info string is `lcnc-run` and whose body is exactly
 * the request `POST /api/lcnc/run` already takes, plus an optional `show` naming which node's
 * output to paint:
 *
 * ```lcnc-run
 * {"program": "corpus", "inputs": {"project": "genesis-notes"}, "show": "n-show"}
 * ```
 *
 * The renderer turns that into a frame: the shown output, a lamp (Never built, Running, Completed,
 * Stale naming the moved documents, Failed), the receipt cid, and ONE button — Build when there is
 * nothing to refresh, Rebuild when there is. Build posts the block's body verbatim; Rebuild posts
 * the artifact's run id to `/api/lcnc/run/rebuild`. There is no pin in the page bytes: the version
 * a block builds is whatever the daemon holds for that program name right now, and the read route
 * decides the head against it.
 *
 * Everything here is pure — text in, HTML out. The page's only job is to fetch and to click.
 * The body is author-controlled page bytes, so every character of it that reaches the frame goes
 * through [DocumentMarkdown.escape] first.
 *
 * A NOTE ON AUTHORITY. A run block is an executable request written in a document: whoever can
 * write a page into a mounted project can make this daemon run any program with any inputs. That
 * is the authority the harness already grants any browser on the operator port, but it is a new
 * path to it — a page, not a canvas — and it is written down rather than discovered later.
 */
object RunBlock {

    /** The fence's info string. Nothing else in a page is a build target. */
    const val INFO = "lcnc-run"

    /**
     * The frame's element id. The page repaints ONE frame in place on a poll rather than the pane
     * that holds it, so this id is part of the contract between the renderer and the DOM glue and
     * belongs here with everything else the two share.
     */
    fun frameId(ordinal: Int): String = "ds-run-$ordinal"

    sealed class Block {
        abstract val ordinal: Int
    }

    /** A block the page can build. `body` is sent to `/api/lcnc/run` verbatim, `show` and all. */
    data class Spec(
        override val ordinal: Int,
        val program: String,
        val inputs: Any?,
        val show: String?,
        val body: String,
        val canonicalInputs: String,
        val key: String,
    ) : Block()

    /** A block that is not a request. It renders its own bytes back, escaped, with no button. */
    data class Bad(override val ordinal: Int, val body: String, val detail: String) : Block()

    /**
     * One fence body as a block. The refusals are the run route's own words where they overlap, so
     * a block refused here would have been refused there.
     *
     * `document` is refused deliberately: an inline ring runs with a null `programKey`, which the
     * head route can never find again, so the frame would grow a Build button whose result it could
     * never show. A page names a stored program.
     */
    fun parse(ordinal: Int, body: String): Block {
        val parsed = runCatching { JsonSupport.parse(body) }.getOrElse {
            return Bad(ordinal, body, "the block body is not JSON: " + (it.message ?: "unparsed"))
        }
        val request = parsed as? Map<*, *>
            ?: return Bad(ordinal, body, "the block body is not a JSON object")
        if (request.containsKey("document"))
            return Bad(ordinal, body, "a run block names a stored program, never an inline document")
        val program = request["program"]?.toString()?.takeIf { it.isNotBlank() }
            ?: return Bad(ordinal, body, "the block names no program")
        if (request.containsKey("inputs") && request["inputs"] !is Map<*, *>)
            return Bad(ordinal, body, "inputs_must_be_object")
        val show = request["show"]?.toString()?.takeIf { it.isNotBlank() }
        if (request.containsKey("show") && request["show"] !is String)
            return Bad(ordinal, body, "show names one node id")
        val inputs = request["inputs"]
        val canonical = LcncRunHead.canonicalInputs(inputs)
        return Spec(ordinal, program, inputs, show, body, canonical, LcncRunHead.inputsKey(program, canonical))
    }

    /**
     * Every `lcnc-run` fence on a page, in order, numbered by their own count (a `kotlin` fence
     * between two of them takes no ordinal). The scan walks lines the way [DocumentMarkdown]'s
     * top-level loop does, so the ordinals it hands out are exactly the ones the renderer's fence
     * handler counts. A fence quoted inside a blockquote is not a target — quoting a block is
     * quoting it, not arming it — and neither surface counts one.
     */
    fun scan(markdown: String): List<Block> {
        val lines = markdown.replace("\r\n", "\n").split('\n')
        val blocks = ArrayList<Block>()
        var i = 0
        var ordinal = 0
        while (i < lines.size) {
            val trimmed = lines[i].trim()
            if (!trimmed.startsWith("```")) { i++; continue }
            val info = trimmed.removePrefix("```").trim()
            val raw = StringBuilder()
            i++
            while (i < lines.size && !lines[i].trim().startsWith("```")) { raw.append(lines[i]).append('\n'); i++ }
            i++
            if (info == INFO) blocks.add(parse(ordinal++, raw.toString()))
        }
        return blocks
    }

    // ── what the read route said about one block ────────────────────────────

    /**
     * The frame's state. `lamp` is null when the route refused, and when an answer names a word
     * this build does not know (a newer daemon); the frame then says what it was told rather than
     * guessing a word of its own. A block the page has not asked about yet has no `State` at all.
     *
     * `refused` is the ENVELOPE, not a lamp: the route could not answer for this target at all (the
     * program is not on this daemon), so the frame offers nothing to press. A run that FAILED is not
     * refused — it is an answer, it burns the Failed lamp, and it keeps its Build button.
     */
    data class State(
        val lamp: LcncRunHead.Lamp?,
        val word: String,
        val reason: String,
        val refused: Boolean = false,
        val moved: List<String> = emptyList(),
        /** The route capped the moved list; `staleCount` still says how many documents moved. */
        val movedTruncated: Boolean = false,
        val runId: String? = null,
        val receiptCid: String? = null,
        val latestReceiptCid: String? = null,
        val programCid: String? = null,
        val rebuildOf: String? = null,
        val show: String? = null,
        val shown: Any? = null,
        val shownMissing: Boolean = false,
        val shownTruncated: Boolean = false,
        val staleCount: Int = 0,
        val activeRuns: Int = 0,
        /** The pressing tab's optimistic Running, set before the POST that blocks for the whole run. */
        val pending: Boolean = false,
    )

    /**
     * The route's answer as a frame state.
     *
     * THE DISCRIMINATION IS THE ENVELOPE, NEVER A FIELD NAME. An ANSWER carries `ok: true` and a
     * `lamp` and becomes the verdict it holds — including a Failed one, whose run's own message
     * rides as `runError` and whose frame keeps its Build button. A REFUSAL carries `error` with no
     * `ok` (`{"error": "no_such_program"}`) and becomes a lamp-less, button-less state that says the
     * program is not on this daemon rather than offering a Build that would 404. Anything else is
     * null, and the page keeps the state it had.
     *
     * Reading `error` first was the bug this shape exists to prevent: every failure status the
     * runner writes — failed, refused, timed_out, cancelled, and the `runtime_restarted` a daemon
     * restart stamps on an in-flight run — carries a non-null message, so a name-based test read
     * every one of them as an unavailable route and left the block permanently unbuildable.
     */
    fun state(json: Any?): State? {
        val m = json as? Map<*, *> ?: return null
        if (m["lamp"] == null) {
            val error = m["error"]?.toString()
            if (error != null && m["ok"] != true) return State(
                lamp = null,
                word = "Unavailable",
                reason = when (error) {
                    "no_such_program" -> "No program named " + (m["program"]?.toString() ?: "that") + " on this daemon."
                    else -> "The daemon refused the read: $error"
                },
                refused = true,
            )
            return null
        }
        val stale = m["stale"] as? Map<*, *>
        return State(
            lamp = LcncRunHead.Lamp.ofWire(m["lamp"]?.toString()),
            word = m["word"]?.toString().orEmpty(),
            reason = m["reason"]?.toString().orEmpty(),
            moved = (m["moved"] as? List<*>).orEmpty().map { it.toString() },
            movedTruncated = m["movedTruncated"] == true,
            runId = m["runId"]?.toString(),
            receiptCid = m["receiptCid"]?.toString(),
            latestReceiptCid = m["latestReceiptCid"]?.toString(),
            programCid = m["programCid"]?.toString(),
            rebuildOf = m["rebuildOf"]?.toString(),
            show = m["show"]?.toString(),
            shown = m["shown"],
            shownMissing = m["shownMissing"] == true,
            shownTruncated = m["shownTruncated"] == true,
            staleCount = (stale?.get("count") as? Number)?.toInt() ?: 0,
            activeRuns = (m["activeRuns"] as? Number)?.toInt() ?: 0,
        )
    }

    // ── the three requests a frame can make ─────────────────────────────────

    /**
     * The read the frame lives on, and the two the button sends. These are the cut's central
     * interaction — "Build posts the body to /api/lcnc/run, Rebuild posts the run id to
     * /api/lcnc/run/rebuild" — so they are decided here, on every target, beside the rule that
     * decides WHICH button to draw, and not in the page's fetch call where no test can see them.
     * The page's only remaining say is percent-encoding, which is genuinely the browser's.
     */
    const val HEAD_PATH = "/api/lcnc/runs"
    const val RUN_PATH = "/api/lcnc/run"
    const val REBUILD_PATH = "/api/lcnc/run/rebuild"

    /**
     * The head read's query parameters, in order and unencoded: which program, which canonical
     * inputs (the text both sides re-canonicalise, never the author's spelling), and — only when
     * the block named one — which node's output to paint.
     */
    fun headQuery(spec: Spec): List<Pair<String, String>> = listOfNotNull(
        "program" to spec.program,
        "inputs" to spec.canonicalInputs,
        spec.show?.let { "show" to it },
    )

    /** Build: the block's own bytes, verbatim — `timeoutMs`, `maxNodes`, `show` and all. */
    fun buildRequest(spec: Spec): Pair<String, String> = RUN_PATH to spec.body

    /**
     * Rebuild: the artifact's run id, and null when there is none. That null is the same guard
     * [buttonHtml] already makes by drawing Build instead of Rebuild for a frame with no run —
     * one rule, so a press can never send a rebuild of nothing.
     */
    fun rebuildRequest(state: State?): Pair<String, String>? =
        state?.runId?.takeIf { it.isNotBlank() }?.let { REBUILD_PATH to JsonSupport.stringify(mapOf("runId" to it)) }

    /** The optimistic state the pressing tab wears while its POST is in flight; the artifact stays visible. */
    fun pending(prior: State?): State = (prior ?: State(null, "", "")).copy(
        lamp = LcncRunHead.Lamp.RUNNING,
        word = LcncRunHead.Lamp.RUNNING.word,
        reason = "Submitted from this page; the run holds the request open until it ends.",
        // A press only happens from a button, and a refused frame has none: the flag cannot outlive it.
        refused = false,
        pending = true,
    )

    // ── the frame ───────────────────────────────────────────────────────────

    fun blockHtml(block: Block, states: Map<Int, State>): String = when (block) {
        is Spec -> frameHtml(block, states[block.ordinal])
        is Bad -> refusalHtml(block)
    }

    fun frameHtml(spec: Spec, state: State?): String = buildString {
        val lamp = state?.lamp
        val word = state?.word?.takeIf { it.isNotBlank() } ?: lamp?.word ?: "Reading the run history"
        // The pill says which of the three it is: a named lamp, a route that refused, or a frame
        // the daemon has not answered for yet. `refused` and `unread` share one style.
        val slug = lamp?.slug ?: if (state?.refused == true) "refused" else "unread"
        append("<section class=\"ds-run\" id=\"").append(frameId(spec.ordinal)).append("\" data-run=\"").append(spec.ordinal).append("\">")
        append("<header class=\"ds-run-head\">")
        append("<span class=\"ds-run-lamp ds-run-lamp-").append(slug).append("\">").append(esc(word)).append("</span>")
        append("<b class=\"ds-run-program\">").append(esc(spec.program)).append("</b>")
        spec.show?.let { append("<span class=\"ds-dim\">shows ").append(esc(it)).append("</span>") }
        append(buttonHtml(spec, lamp, state))
        append("</header>")
        if (state != null && state.reason.isNotBlank())
            append("<p class=\"ds-run-reason\">").append(esc(state.reason)).append("</p>")
        append("<dl class=\"ds-run-meta\">")
        append("<dt>inputs</dt><dd><code>").append(esc(spec.canonicalInputs)).append("</code></dd>")
        val receipt = state?.receiptCid ?: state?.latestReceiptCid
        if (receipt != null) {
            append("<dt>receipt</dt><dd><code class=\"ds-run-cid\" title=\"").append(esc(receipt)).append("\">")
                .append(esc(DocumentSurface.shortCid(receipt))).append("</code></dd>")
        }
        state?.rebuildOf?.let {
            append("<dt>rebuildOf</dt><dd><code class=\"ds-run-cid\" title=\"").append(esc(it)).append("\">")
                .append(esc(DocumentSurface.shortCid(it))).append("</code></dd>")
        }
        state?.programCid?.let {
            append("<dt>version</dt><dd><code class=\"ds-run-cid\" title=\"").append(esc(it)).append("\">")
                .append(esc(DocumentSurface.shortCid(it))).append("</code></dd>")
        }
        // The route caps the moved list at 64 names; the frame says so rather than showing the
        // first 64 of 700 as if they were all of them.
        val moved = if (state == null) "" else buildString {
            append(state.moved.joinToString(", "))
            if (state.movedTruncated) {
                if (isNotEmpty()) append(", ")
                val more = state.staleCount - state.moved.size
                append(if (more > 0) "and $more more" else "and more")
            }
        }
        if (moved.isNotEmpty()) append("<dt>moved</dt><dd>").append(esc(moved)).append("</dd>")
        append("</dl>")
        append(outputHtml(state))
        append("</section>")
    }

    /**
     * ONE button, and never one that cannot work. The only frame with nothing to press is a REFUSED
     * one — the route could not answer for this target, so a Build would 404. Every answered frame
     * keeps a button, Failed most of all: a run that broke is exactly the one a reader wants to
     * press again, and after a restart stamps `interrupted` on an in-flight run that is the only
     * way back. A body whose lamp word this build does not know (a newer daemon) is still an
     * answer, so it keeps its button too.
     */
    private fun buttonHtml(spec: Spec, lamp: LcncRunHead.Lamp?, state: State?): String = when {
        state == null -> "<button class=\"ds-run-button\" disabled>Build</button>"
        state.refused -> ""
        lamp == LcncRunHead.Lamp.RUNNING -> "<button class=\"ds-run-button\" disabled>Building…</button>"
        lamp == LcncRunHead.Lamp.NEVER_BUILT || lamp == LcncRunHead.Lamp.FAILED ->
            "<button class=\"ds-run-button\" data-run-build=\"" + spec.ordinal + "\">Build</button>"
        state.runId == null -> "<button class=\"ds-run-button\" data-run-build=\"" + spec.ordinal + "\">Build</button>"
        else -> "<button class=\"ds-run-button\" data-run-rebuild=\"" + spec.ordinal + "\">Rebuild</button>"
    }

    private fun outputHtml(state: State?): String {
        if (state == null) return ""
        if (state.shownTruncated)
            return "<p class=\"ds-run-note\">The recorded output is too large to show in the page; read it with /api/lcnc/content.</p>"
        if (state.shownMissing)
            return "<p class=\"ds-run-note\">No output was recorded for " + esc(state.show ?: "that node") + " in this run.</p>"
        val value = state.shown ?: return ""
        val prose = prose(value)
        return if (prose != null) "<div class=\"ds-run-output\">" + DocumentMarkdown.render(prose) + "</div>"
        else "<div class=\"ds-run-output\"><pre>" + esc(JsonSupport.stringify(value)) + "</pre></div>"
    }

    /**
     * The prose inside a shown value, when there is any. A display sink's output map is keyed
     * differently by rig — the daemon's returns `{"x": …}`, a test stub `{"shown": …}` — so the key
     * is never named here: a one-entry map holding one string IS that string, and everything else
     * is shown as the JSON it is.
     */
    fun prose(value: Any?): String? = when {
        value is String -> value
        value is Map<*, *> && value.size == 1 -> value.values.first() as? String
        else -> null
    }

    fun refusalHtml(bad: Bad): String = buildString {
        append("<section class=\"ds-run ds-run-refused\" id=\"").append(frameId(bad.ordinal)).append("\">")
        append("<header class=\"ds-run-head\"><span class=\"ds-run-lamp ds-run-lamp-refused\">Not a run block</span></header>")
        append("<p class=\"ds-run-reason\">").append(esc(bad.detail)).append("</p>")
        append("<pre>").append(esc(bad.body)).append("</pre>")
        append("</section>")
    }

    private fun esc(s: String): String = DocumentMarkdown.escape(s)
}
