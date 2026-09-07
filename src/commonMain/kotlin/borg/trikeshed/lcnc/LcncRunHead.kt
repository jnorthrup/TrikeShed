package borg.trikeshed.lcnc

import borg.trikeshed.job.ContentId
import borg.trikeshed.parse.json.JsonSupport
import borg.trikeshed.parse.json.ValueBudget
import kotlin.math.abs
import kotlin.math.floor

/**
 * THE RUN HEAD (AutoTools, Cut B): which recorded run IS the build of a target, and what lamp it burns.
 *
 * A build target on a page is a request, not a receipt: `(program at its current version, the inputs
 * the block names)`. This object answers, purely, the three questions a build frame asks —
 *
 *   1. which run receipt is this target's ARTIFACT (the newest completed one), and which is its
 *      LATEST (the newest of any terminal status, plus the ones still going);
 *   2. what word describes it — Never built, Running, Completed, Stale, Failed;
 *   3. which documents moved, when the answer is Stale.
 *
 * The identity is NOT `inputFingerprint`: that is computed over the cids a run READ
 * (`LcncConsumedLedger.fingerprint`) and moves whenever a document moves, which is exactly the
 * event a build frame must survive. The stable identity is what the request said — the program's
 * current `programCid` (derivable from the loader with [LcncBlackboard.cidOf], byte-for-byte the
 * cid `LcncRunService` freezes) plus the canonical form of the request's `inputs`.
 *
 * CANONICAL INPUTS. Every JSON number arrives as a Double from the parser, and a WAL-replayed
 * receipt comes back the same way, so `{"n":3}` and `{"n":3.0}` are one value and must be one key.
 * [canonical] is [WorkspaceSnapshot]'s own integral-Double fold plus a recursive key sort, and
 * [canonicalInputs] renders it through the one stringifier. Comparison is always between two texts
 * minted on the SAME target — a caller that receives canonical text from elsewhere re-canonicalises
 * the parsed value rather than comparing the text it was handed.
 *
 * The lamp decision mirrors `web/landscape.js`'s `LandscapeActivity` with two deliberate
 * divergences, both because this runs ON the server:
 *   - landscape's `connected` term is dropped: the daemon is by definition connected to its board;
 *   - landscape's sixth state `unknown` for an active run past its budget collapses into Running,
 *     with the elapsed time and the budget in the reason. The server saw no failure and does not
 *     claim one; the reader sees the frame is stuck rather than working.
 */
object LcncRunHead {

    /** The board's run receipts: `lcnc/run/<runId>`. */
    const val RUN_PREFIX = "lcnc/run/"

    const val STATUS_COMPLETED = "completed"

    /** The two non-terminal statuses `LcncRunService` writes before a run ends. */
    val ACTIVE = setOf("validating", "running")

    /**
     * A frame never carries more than this many rows of any list: the consumed documents, the
     * stale marker's moved inputs, or the moved names. The marker is the one that bites — it is
     * MERGED, never replaced, and never expires a row (`LcncStaleMarker.merge`), so one long-lived
     * completed run over a project whose documents keep moving accumulates a row per document,
     * up to the consumed ledger's own 1024. Projecting that whole list is how the read route's
     * `ValueBudget` preflight trips and answers 413 to the one target the cut exists for.
     */
    const val CONSUMED_LIMIT = 64

    /** How many moved documents a Stale reason names before it says "and N more". */
    const val NAMED_LIMIT = 8

    /** A run past `startedAtMs + timeoutMs` by this much is reported as stuck, never as failed. */
    const val OVERDUE_GRACE_MS = 5_000L

    /**
     * The grain the overdue reason's elapsed time is floored to. A page polls this answer every
     * three seconds; a reason that moved every second would repaint a frame under the reader's
     * cursor for nothing. The floor understates the elapsed time, it never overstates it.
     */
    const val OVERDUE_GRAIN_MS = 10_000L

    // ── the target's identity ───────────────────────────────────────────────

    /**
     * One canonical value: maps become key-sorted maps at every depth, integral floating point
     * becomes Long, every other integer type becomes Long, and lists keep their order.
     */
    fun canonical(value: Any?): Any? = when (value) {
        is Map<*, *> -> {
            val sorted = LinkedHashMap<String, Any?>()
            value.entries.map { it.key.toString() to it.value }
                .sortedBy { it.first }
                .forEach { (key, child) -> sorted[key] = canonical(child) }
            sorted
        }
        is List<*> -> value.map { canonical(it) }
        is Array<*> -> value.map { canonical(it) }
        is Double -> if (!value.isNaN() && !value.isInfinite() && value == floor(value) && abs(value) < 9.0e15) value.toLong() else value
        is Float -> canonical(value.toDouble())
        is Int -> value.toLong()
        is Short -> value.toLong()
        is Byte -> value.toLong()
        else -> value
    }

    /** The canonical text of a request's inputs; an absent `inputs` is the empty object, never null. */
    fun canonicalInputs(inputs: Any?): String =
        JsonSupport.stringify(canonical(inputs ?: emptyMap<String, Any?>()))

    /** A short stable handle for a target — a DOM id and a fetch de-duplication key, never an identity check. */
    fun inputsKey(program: String, canonicalInputs: String): String =
        ContentId.of((program + "\n" + canonicalInputs).encodeToByteArray()).value

    // ── the board's run receipts, read ──────────────────────────────────────

    /** One projected receipt as the board holds it. `raw` is the entry itself, for the bounded projection. */
    data class RunRow(
        val key: String,
        val runId: String,
        val status: String,
        val program: String?,
        val programKey: String?,
        val programCid: String?,
        val inputs: Any?,
        val startedAtMs: Long,
        val finishedAtMs: Long?,
        val sequence: Long,
        val timelineRevision: Long,
        val receiptCid: String?,
        val rebuildOf: String?,
        val rebuildOfRunId: String?,
        val phase: String?,
        val error: String?,
        val timeoutMs: Long?,
        val raw: Map<String, Any?>,
    )

    /** Numbers survive a WAL replay as Doubles and a KIF round trip as text; both read back as Long. */
    private fun num(value: Any?): Long? = (value as? Number)?.toLong() ?: (value as? String)?.trim()?.toLongOrNull()

    private fun text(value: Any?): String? = value?.toString()?.takeIf { it.isNotBlank() }

    fun row(key: String, entry: Any?): RunRow? {
        val m = entry as? Map<*, *> ?: return null
        val runId = text(m["runId"]) ?: return null
        return RunRow(
            key = key,
            runId = runId,
            status = m["status"]?.toString().orEmpty(),
            program = text(m["program"]),
            programKey = text(m["programKey"]),
            programCid = text(m["programCid"]),
            inputs = m["inputs"],
            startedAtMs = num(m["startedAtMs"]) ?: 0L,
            finishedAtMs = num(m["finishedAtMs"]),
            sequence = num(m["sequence"]) ?: 0L,
            timelineRevision = num(m["timelineRevision"]) ?: -1L,
            receiptCid = text(m["receiptCid"]),
            rebuildOf = text(m["rebuildOf"]),
            rebuildOfRunId = text(m["rebuildOfRunId"]),
            phase = text(m["phase"]),
            error = text(m["error"]),
            timeoutMs = num((m["budgets"] as? Map<*, *>)?.get("timeoutMs")),
            raw = m.entries.associate { (k, v) -> k.toString() to v },
        )
    }

    /** Every run receipt on a blackboard snapshot; entries that name no run are skipped, never guessed at. */
    fun rows(entries: Map<String, Any?>): List<RunRow> =
        entries.entries.filter { it.key.startsWith(RUN_PREFIX) }.mapNotNull { row(it.key, it.value) }

    // ── the head ────────────────────────────────────────────────────────────

    /**
     * [artifact] is the newest COMPLETED run — what a build frame shows, the way make keeps showing
     * the last good object file after a failed compile. [latest] is the newest run of any status —
     * what the lamp describes. They are the same row in the ordinary case and deliberately differ
     * when the newest run failed over a target that had built before.
     */
    data class Head(val artifact: RunRow?, val latest: RunRow?, val active: List<RunRow>, val matching: List<RunRow>)

    /**
     * The runs of exactly this target, newest first. The sort is `web/landscape.js`'s — started
     * descending, then the board sequence descending — and the sequence tiebreak is load-bearing:
     * two runs under one frozen clock share `startedAtMs`, and only the board's commit sequence
     * says which is newer.
     */
    fun head(rows: List<RunRow>, programKey: String, programCid: String, wantedInputs: String): Head {
        val matching = rows.filter {
            it.programKey == programKey && it.programCid == programCid && canonicalInputs(it.inputs) == wantedInputs
        }.sortedWith(compareByDescending<RunRow> { it.startedAtMs }.thenByDescending { it.sequence })
        return Head(
            artifact = matching.firstOrNull { it.status == STATUS_COMPLETED },
            latest = matching.firstOrNull(),
            active = matching.filter { it.status in ACTIVE },
            matching = matching,
        )
    }

    // ── the lamp ────────────────────────────────────────────────────────────

    /** The five words the plan names. There is no sixth: a stuck run is Running with the truth in its reason. */
    enum class Lamp(val word: String) {
        NEVER_BUILT("Never built"),
        RUNNING("Running"),
        COMPLETED("Completed"),
        STALE("Stale"),
        FAILED("Failed");

        /** The wire word, as the read route spells it: `never_built`. */
        val wire: String get() = name.lowercase()

        /** The css class suffix: `never-built`. */
        val slug: String get() = name.lowercase().replace('_', '-')

        companion object {
            fun ofWire(wire: String?): Lamp? = Lamp.entries.firstOrNull { it.wire == wire }
        }
    }

    data class Verdict(val lamp: Lamp, val reason: String, val moved: List<String>) {
        val word: String get() = lamp.word
    }

    /**
     * The verdict for one target. `marker` is the board's `lcnc/stale/<runId>` for the latest run
     * (null when nothing moved); `nowMs` is the daemon's clock, used only to describe an overdue
     * active run, never to decide staleness — staleness comes from the marker, never a timestamp.
     */
    fun decide(head: Head, marker: Any?, nowMs: Long): Verdict {
        val active = head.active.firstOrNull()
        if (active != null) {
            val concurrent = if (head.active.size > 1) "; ${head.active.size} concurrent runs" else ""
            val budget = active.timeoutMs
            val elapsed = nowMs - active.startedAtMs
            val overdue = budget != null && active.startedAtMs > 0L && elapsed > budget + OVERDUE_GRACE_MS
            val floored = (elapsed / OVERDUE_GRAIN_MS) * (OVERDUE_GRAIN_MS / 1000)
            val reason = if (budget != null && overdue)
                "The run is still ${active.status} after ${floored}s against a ${budget / 1000}s budget; no terminal receipt has landed$concurrent"
            else "The run is ${active.status}$concurrent"
            return Verdict(Lamp.RUNNING, reason, emptyList())
        }
        val latest = head.latest
            ?: return Verdict(Lamp.NEVER_BUILT, "No run of this program version with these inputs.", emptyList())
        if (latest.status == STATUS_COMPLETED) {
            if (marker == null) return Verdict(Lamp.COMPLETED, "Built from this program version and these inputs.", emptyList())
            val moved = movedNames(marker)
            // A marker that named hundreds of documents would put hundreds of file names into one
            // sentence; the reason names the first few and counts the rest, and `moved` still
            // carries the list for a reader that wants it.
            val named = when {
                moved.isEmpty() -> ((marker as? Map<*, *>)?.get("count")?.toString() ?: "an input")
                moved.size <= NAMED_LIMIT -> moved.joinToString(", ")
                else -> moved.take(NAMED_LIMIT).joinToString(", ") + " and ${moved.size - NAMED_LIMIT} more"
            }
            return Verdict(Lamp.STALE, "Completed against inputs that have since changed: $named", moved)
        }
        val status = latest.status.takeIf { it.isNotBlank() } ?: "unrecorded"
        val phase = latest.phase?.let { " in $it" }.orEmpty()
        val error = latest.error?.let { ": $it" }.orEmpty()
        return Verdict(Lamp.FAILED, "The last run $status$phase$error", emptyList())
    }

    /**
     * The documents a stale marker names. `RunStaleProduction` binds an empty `id` for a listing
     * change, so a blank id reads as the project's listing — the same fallback `web/landscape.js`
     * spells, kept identical so the two surfaces cannot drift apart.
     */
    fun movedNames(marker: Any?): List<String> {
        val rows = (marker as? Map<*, *>)?.get("inputs") as? List<*> ?: return emptyList()
        return rows.mapNotNull { row ->
            val m = row as? Map<*, *> ?: return@mapNotNull null
            val id = m["id"]?.toString().orEmpty()
            if (id.isNotBlank()) id else m["project"]?.toString().orEmpty() + "/ listing"
        }
    }

    // ── the bounded projection the read route answers with ──────────────────

    /**
     * What a page (or a curl reader) is told about one target: never the whole receipt. A completed
     * corpus receipt carries every node's output, its bindings and its consumed rows; sending that
     * into a frame is how a ValueBudget gets tripped. The named node's output (or the run's returns)
     * rides as `shown`, over its own budget: when it is too big the frame says so and still burns
     * the right lamp, because a lamp with no output still tells the reader the truth.
     *
     * EVERY LIST IS CAPPED at [CONSUMED_LIMIT], and each cap that bit says so beside its own field:
     * `movedTruncated`, `staleTruncated`, `consumedTruncated`, `shownTruncated`. Nothing here is
     * ever unbounded, because every one of these lists is grown by something a reader controls —
     * the marker by every document that moves, `consumed` by every document a run read, `shown` by
     * whatever the program computed — and one unbounded list refuses the whole frame.
     *
     * THE ENVELOPE. This body is an ANSWER: it carries `ok: true` and a `lamp`, and never a
     * top-level `error` — that word belongs to the route's refusals alone, which carry no `ok` and
     * no `lamp`. The run's own failure text rides as `runError` beside `status` and `phase`. Keeping
     * the two vocabularies apart is what lets any reader — the page's frame, a curl reader, Cut V's
     * sheet cells — tell "this target failed" from "this route could not answer".
     */
    fun headBody(
        program: String,
        programKey: String,
        programCid: String,
        inputs: Any?,
        head: Head,
        verdict: Verdict,
        marker: Any?,
        show: String?,
    ): Map<String, Any?> {
        val artifact = head.artifact
        val latest = head.latest
        val wanted = show?.takeIf { it.isNotBlank() }
        val outputs = artifact?.raw?.get("outputs") as? Map<*, *>
        var shown: Any? = if (wanted != null) outputs?.get(wanted) else artifact?.raw?.get("returns")
        val shownMissing = artifact != null && wanted != null && (outputs == null || !outputs.containsKey(wanted))
        var shownTruncated = false
        if (shown != null && ValueBudget(maxNodes = 4096, maxChars = 65536).violation(shown) != null) {
            shown = null; shownTruncated = true
        }
        val canonicalText = canonicalInputs(inputs)
        // EVERY list is capped, not only `consumed`. The marker's rows are the dangerous one: it
        // is merged per moved document and never expires a row, so a completed run over a busy
        // project grows one 6-key row (13 budget nodes, ~200 chars: two 71-character cids) per
        // document until the whole body is over the route's budget and the frame is refused.
        val markerRows = ((marker as? Map<*, *>)?.get("inputs") as? List<*>).orEmpty()
        val staleRows = markerRows.take(CONSUMED_LIMIT).mapNotNull { row ->
            (row as? Map<*, *>)?.let {
                linkedMapOf<String, Any?>(
                    "kind" to it["kind"], "project" to it["project"], "id" to it["id"],
                    "oldCid" to it["oldCid"], "newCid" to it["newCid"], "deleted" to it["deleted"],
                )
            }
        }
        val movedNames = verdict.moved.take(CONSUMED_LIMIT)
        val consumedRows = (artifact?.raw?.get("consumed") as? List<*>).orEmpty()
        val consumed = consumedRows.take(CONSUMED_LIMIT).mapNotNull { row ->
            (row as? Map<*, *>)?.let { linkedMapOf<String, Any?>("kind" to it["kind"], "id" to it["id"], "cid" to it["cid"]) }
        }
        return linkedMapOf(
            "ok" to true,
            "program" to program,
            "programKey" to programKey,
            "programCid" to programCid,
            "inputs" to canonical(inputs),
            "inputsCanonical" to canonicalText,
            "inputsKey" to inputsKey(program, canonicalText),
            "lamp" to verdict.lamp.wire,
            "word" to verdict.word,
            "reason" to verdict.reason,
            "moved" to movedNames,
            "movedTruncated" to (verdict.moved.size > movedNames.size),
            // What Rebuild names: the artifact's run when there is one, else whatever ran last.
            "runId" to (artifact?.runId ?: latest?.runId),
            "latestRunId" to latest?.runId,
            "status" to latest?.status,
            "phase" to latest?.phase,
            // NOT "error": a 200 body must never carry the word the REFUSAL envelope owns
            // (`{"error": "no_such_program"}`). A failed run is a verdict this route answered with,
            // not a read it could not answer, and a reader that discriminated on the field name
            // alone would have read every Failed lamp as an unavailable route.
            "runError" to latest?.error,
            // The receipt that produced `shown`, beside the newest receipt of any status.
            "receiptCid" to artifact?.receiptCid,
            "latestReceiptCid" to latest?.receiptCid,
            "rebuildOf" to artifact?.rebuildOf,
            "rebuildOfRunId" to artifact?.rebuildOfRunId,
            "startedAtMs" to latest?.startedAtMs,
            "finishedAtMs" to artifact?.finishedAtMs,
            "sequence" to latest?.sequence,
            "activeRuns" to head.active.size,
            "runs" to head.matching.size,
            // `count` is always the marker's own, whole count: the rows are what is capped, so a
            // reader is told 700 documents moved and shown the first 64 of them.
            "stale" to (marker as? Map<*, *>)?.let {
                linkedMapOf<String, Any?>("count" to (it["count"] as? Number)?.toInt(), "inputs" to staleRows)
            },
            "staleTruncated" to (markerRows.size > staleRows.size),
            "show" to wanted,
            "shown" to shown,
            "shownMissing" to shownMissing,
            "shownTruncated" to shownTruncated,
            "consumed" to consumed,
            "consumedTruncated" to (consumedRows.size > consumed.size || artifact?.raw?.get("consumedTruncated") == true),
        )
    }

    /**
     * The body one budget can carry, or null when even the skeleton cannot fit.
     *
     * [headBody] caps every list it projects, so an ordinary answer arrives here already inside
     * the budget and leaves unchanged. This is the belt to that pair of braces: an author's
     * `inputs`, a run's `returns` and a marker's rows are all reader-supplied, and the read route
     * must DEGRADE rather than refuse — a frame with no output still burns the right lamp and
     * still offers the button that would fix it, while a 413 leaves the reader an "Unavailable"
     * pill with nothing to press on exactly the target that needs pressing.
     *
     * The order is what a reader can most afford to lose: the shown output first (its receipt cid
     * is still in the frame, and `/api/lcnc/content` still has the bytes), then the stale rows
     * (the count and the names survive), then the consumed rows (history the receipt keeps), then
     * the moved names (the reason still names the first few). Only then is the answer refused.
     */
    fun fitToBudget(body: Map<String, Any?>, budget: ValueBudget = ValueBudget()): Map<String, Any?>? {
        if (budget.violation(body) == null) return body
        val out = LinkedHashMap(body)
        if (out["shown"] != null) {
            out["shown"] = null
            out["shownTruncated"] = true
            if (budget.violation(out) == null) return out
        }
        (out["stale"] as? Map<*, *>)?.let { stale ->
            if (!(stale["inputs"] as? List<*>).isNullOrEmpty()) {
                out["stale"] = linkedMapOf<String, Any?>("count" to stale["count"], "inputs" to emptyList<Any?>())
                out["staleTruncated"] = true
                if (budget.violation(out) == null) return out
            }
        }
        if (!(out["consumed"] as? List<*>).isNullOrEmpty()) {
            out["consumed"] = emptyList<Any?>()
            out["consumedTruncated"] = true
            if (budget.violation(out) == null) return out
        }
        if (!(out["moved"] as? List<*>).isNullOrEmpty()) {
            out["moved"] = emptyList<Any?>()
            out["movedTruncated"] = true
            if (budget.violation(out) == null) return out
        }
        return null
    }
}
