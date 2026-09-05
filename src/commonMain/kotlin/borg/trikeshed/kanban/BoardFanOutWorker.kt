package borg.trikeshed.kanban

import borg.trikeshed.graal.ConfixBlackboard
import borg.trikeshed.kanban.rules.BoardRules
import borg.trikeshed.lcnc.LcncNode
import borg.trikeshed.lcnc.LcncNodeRunner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * BoardFanOutWorker — what a `fan-out` activation becomes once the sink has it
 * (Delta 2026-09-05, fan-out). Mirrors [BoardClaimWorker]'s constructor and its
 * intake discipline: every command goes through the store's [BoardIntake] with
 * a reply, every reply is awaited under [commitTimeoutMs], so the children land
 * in a deterministic order and the receipt says exactly what happened.
 *
 *   1. The targets: `MODELS:` ids as the fact carried them, else the first
 *      `FANOUT: n` ids of `mux.models` (the same walk as
 *      `BoardClaimWorker.resolveModel`: "the live list leads, and its first
 *      entry is the newest"). Fewer than two → Move(BLOCKED, actor
 *      `fanout:plane`) at the parent's revision and a receipt saying why. A
 *      card that asked to be split and cannot be is parked where a person sees
 *      it, never left stalling in TODO.
 *   2. One child per target, 1-based: Submit `<parent>-m<i>` (idempotency
 *      `<child>#fan-out`, title `[<model>] <parent title>`, the parent's spec
 *      minus the heads a child must not inherit plus `MODEL:`/`TOKENS:`,
 *      `parent`, priority, tags minus the human-routing ones plus `fan-out`),
 *      then Move(READY) at revision 1 (key `<child>#fan-out-ready#1`, actor
 *      `fanout:plane`). The submit carries NO expectedRevision — a child is a
 *      fresh card — and both keys are independent of the parent's revision, so
 *      a re-run after a refused join is a duplicate at the store, never a double.
 *   3. The join: re-Submit the parent with `dependencies` = the child ids and
 *      `expectedRevision` (key `<parent>#fan-out-join#<rev>`) — the store's
 *      "set dependencies" op (JobReducer keeps prior deps only when none are
 *      given; CAS on the revision). Column → TODO; title/spec/owner/parent kept
 *      (the raw map carries none of them, advanceRow keeps the previous).
 *      Someone moved the parent meanwhile → refused → the production re-fires at
 *      the new revision and step 2 dedupes.
 *   4. Receipt `kanban/fanout/<parent>` = {models, children, submitted, readied,
 *      join, ok, startedAtMs, finishedAtMs, atMs, revision} — the worker's own
 *      clock, never a guess.
 *
 * From there the fan-IN is the board's existing causality: DependencyReady moves
 * the parent to READY once every child is Done and the claim merges the
 * children's receipts. Pure over the store, the blackboard and a runner lookup.
 */
class BoardFanOutWorker(
    private val store: BoardStoreElement,
    private val blackboard: ConfixBlackboard,
    private val runner: (String) -> LcncNodeRunner?,
    private val clock: () -> Long,
    private val commitTimeoutMs: Long = 10_000L,
) {
    companion object {
        const val RECEIPT_PREFIX: String = "kanban/fanout/"
        const val LANGUAGE: String = "kanban-fanout"

        /** The tag every child carries, so a board reader can tell a split from intake. */
        const val CHILD_TAG: String = "fan-out"

        /**
         * The floor on a child's `TOKENS:`: the parent's budget or 4096, whichever is
         * larger. Every child is a plain claim on a thinking model (the 2026-09-04
         * lesson: 256 starved one; 2048 is the ordinary default); the tree pays
         * for N answers and must not starve any of them.
         */
        const val CHILD_TOKENS_FLOOR: Int = 4096

        /**
         * Spec heads a child does NOT inherit: the split itself (`MODELS`, `FANOUT`),
         * the parent's MERGE model (`MODEL`), the parent's budget (`TOKENS`, replaced
         * by the floor), and `REVIEW` (a child is judged by the plane like any claim;
         * the human gate, if any, is the parent's). Same head grammar as
         * [PlaneBrief.parseSpec] so what the parser would read is what is stripped.
         */
        val STRIPPED_HEADS: Set<String> = setOf("MODELS", "FANOUT", "MODEL", "TOKENS", "REVIEW")

        /** The i-th (1-based) child's jobId — the shape [BoardRules.isMintedChild] recognises. */
        fun childJobId(parent: String, index: Int): String = "$parent-m$index"

        /** The child's spec: the parent's lines minus [STRIPPED_HEADS], plus its own MODEL and TOKENS. */
        fun childSpec(parentSpec: String, model: String, tokens: Int): String {
            val kept = ArrayList<String>()
            for (raw in parentSpec.lines()) {
                if (raw.isBlank()) continue
                val head = PlaneBrief.specHead(raw)
                // MODELS/FANOUT count only with their colon, exactly as parseSpec reads them.
                if (head != null && head.name in STRIPPED_HEADS && (head.colon || head.name !in setOf("MODELS", "FANOUT"))) continue
                kept.add(raw.trimEnd())
            }
            kept.add("MODEL: $model")
            kept.add("TOKENS: $tokens")
            return kept.joinToString("\n")
        }

        /** The child's tags: the parent's minus the ones that route to a person, plus [CHILD_TAG]. */
        fun childTags(parentTags: List<String>): List<String> {
            val out = LinkedHashSet<String>()
            for (t in parentTags) if (t.lowercase() !in BoardClaimWorker.HUMAN_TAGS) out.add(t)
            out.add(CHILD_TAG)
            return out.toList()
        }
    }

    /**
     * One fan-out, start to finish. [models] and [fanout] are the activation's bindings
     * (what the card fact carried). Returns the receipt as written.
     */
    suspend fun fanOut(jobId: String, expectedRevision: Long, models: List<String>, fanout: Int): Map<String, Any?> {
        val startedAtMs = clock()
        val card = store.card(jobId)
        if (card == null) {
            return receipt(
                jobId, expectedRevision, startedAtMs, ids = models, children = emptyList(),
                ok = false, error = "no such card: $jobId is not on the board",
            )
        }
        // Delta 2026-09-05 (build stage): a re-proposal at a revision the board has already
        // left (the children's cids sit in the rule's support, so every child landing
        // re-proposes; the sink's in-flight key is per activation id and the collector
        // lags the worker) must not run a second split and overwrite the live receipt
        // with "join refused". Nothing is written: the worker at the current revision
        // owns `kanban/fanout/<parent>`.
        if (card.revision != expectedRevision) {
            return mapOf(
                "ok" to false, "children" to emptyList<String>(), "skipped" to true,
                "error" to "stale activation: expected r$expectedRevision, board is at r${card.revision}",
            )
        }
        // The children this worker (or an earlier run) minted, as the store's rows say —
        // archived rows included, which is the one way this differs from the rule's view
        // of the fact plane. The predicate is the rule's own ([BoardRules.fanOutPending]):
        // a split whose join has landed is never re-run. That branch is reachable only
        // when the two views disagree, so it is not silent: a receipt says so once, and
        // never over a live one.
        val existingChildren = store.cards().filter { it.parent == jobId && BoardRules.isMintedChild(jobId, it.jobId) }.map { it.jobId }
        if (!BoardRules.fanOutPending(jobId, card.dependencies, existingChildren)) {
            val why = "already joined: the dependencies of $jobId name its children; " +
                "a corrected MODELS: line needs a fresh title (children ${existingChildren.ifEmpty { card.dependencies.filter { BoardRules.isMintedChild(jobId, it) } }})"
            if (blackboard.get(RECEIPT_PREFIX + jobId) == null) {
                receipt(jobId, expectedRevision, startedAtMs, ids = models, children = existingChildren, ok = false, error = why)
            }
            return mapOf("ok" to false, "children" to existingChildren, "skipped" to true, "error" to why)
        }

        // ── 1. the targets ────────────────────────────────────────────────────
        val ids = if (models.isNotEmpty()) models else resolveModels(fanout)
        if (ids.size < 2) {
            val why = if (models.isNotEmpty()) "fan-out needs at least two models, MODELS: named ${models.size}"
            else "fan-out needs at least two models, FANOUT: $fanout resolved ${ids.size} from mux.models"
            val parked = send(
                mapOf(
                    "type" to "move",
                    "jobId" to jobId,
                    "idempotencyKey" to "$jobId#${BoardRules.FAN_OUT}-blocked#$expectedRevision",
                    "expectedRevision" to expectedRevision,
                    "toColumn" to BoardCol.BLOCKED.wire,
                    "actor" to BoardRules.FAN_OUT_ACTOR,
                ),
            )
            return receipt(
                jobId, expectedRevision, startedAtMs, ids = ids, children = emptyList(),
                ok = false, error = why, extra = mapOf("blocked" to describe(BoardCol.BLOCKED.wire, parked)),
            )
        }

        // ── 2. one child per target: Submit, then READY ───────────────────────
        val parentSpec = PlaneBrief.parseSpec(card.title, card.spec)
        val tokens = maxOf(parentSpec.tokens ?: 0, CHILD_TOKENS_FLOOR)
        val tags = childTags(card.tags)
        val childIds = ArrayList<String>(ids.size)
        val submitted = ArrayList<String>(ids.size)
        val readied = ArrayList<String>(ids.size)
        for ((index0, model) in ids.withIndex()) {
            val childId = childJobId(jobId, index0 + 1)
            childIds.add(childId)
            val submit = send(
                mapOf(
                    "type" to "submit",
                    "jobId" to childId,
                    "idempotencyKey" to "$childId#${BoardRules.FAN_OUT}",
                    "title" to "[$model] ${card.title}",
                    "spec" to childSpec(card.spec, model, tokens),
                    "parent" to jobId,
                    "priority" to card.priority,
                    "tags" to tags,
                ),
            )
            submitted.add(describe(childId, submit))
            // A READY move for a child that is NOT on the board (its submit refused at the
            // door — the parent died meanwhile) would only burn the parent-revision-
            // independent key at the reducer, which checks the key BEFORE the revision;
            // a later re-run could then create the child but never ready it. Skip it.
            if (submit !is BoardApply.Committed && store.card(childId) == null) {
                readied.add("$childId: not readied, the submit did not land")
                continue
            }
            val ready = send(
                mapOf(
                    "type" to "move",
                    "jobId" to childId,
                    "idempotencyKey" to "$childId#${BoardRules.FAN_OUT}-ready#1",
                    "expectedRevision" to 1L,
                    "toColumn" to BoardCol.READY.wire,
                    "actor" to BoardRules.FAN_OUT_ACTOR,
                ),
            )
            readied.add(describe(childId, ready))
        }

        // ── 3. the join: the parent depends on its children ───────────────────
        // The union, so the join establishes exactly what the predicate checks and
        // nothing is dropped: the parent's own prior dependencies (a real gate it
        // declared at submit keeps gating dependency-ready), every minted child
        // already on the board (a re-run that resolved FEWER targets than before
        // must still name m<k> for k past this run's count, or the split never
        // terminates), then this run's children.
        val joinedChildren = (existingChildren + childIds).distinct()
        val join = send(
            mapOf(
                "type" to "submit",
                "jobId" to jobId,
                "idempotencyKey" to "$jobId#${BoardRules.FAN_OUT}-join#$expectedRevision",
                "dependencies" to (card.dependencies + joinedChildren).distinct(),
                "expectedRevision" to expectedRevision,
            ),
        )

        // ── 4. the receipt ────────────────────────────────────────────────────
        return receipt(
            jobId, expectedRevision, startedAtMs, ids = ids, children = joinedChildren,
            ok = join is BoardApply.Committed,
            error = when (join) {
                is BoardApply.Committed -> ""
                is BoardApply.Rejected -> "join refused: ${join.reason}"
                null -> "join: no reply from the store within ${commitTimeoutMs}ms"
            },
            extra = mapOf("submitted" to submitted, "readied" to readied, "join" to describe(jobId, join)),
        )
    }

    /** One intake command, one awaited reply (null = the store did not answer in time). */
    private suspend fun send(raw: Map<String, Any?>): BoardApply? {
        val reply = CompletableDeferred<BoardApply>()
        store.intake.send(BoardIntake(raw, reply))
        return withTimeoutOrNull(commitTimeoutMs) { reply.await() }
    }

    private fun describe(subject: String, verdict: BoardApply?): String = when (verdict) {
        is BoardApply.Committed -> "$subject r${verdict.revision} seq ${verdict.sequence}"
        is BoardApply.Rejected -> "$subject refused: ${verdict.reason}"
        null -> "$subject: no reply within ${commitTimeoutMs}ms"
    }

    /**
     * The first [count] ids of `mux.models` — the same walk as `BoardClaimWorker.resolveModel`,
     * which is private there and returns one id; "" when the daemon offers no roster.
     */
    private suspend fun resolveModels(count: Int): List<String> {
        if (count < 1) return emptyList()
        val roster = runner("mux.models") ?: return emptyList()
        val out = try {
            roster.run(LcncNode(id = "fan-out-models", type = "mux.models"), emptyMap())
        } catch (t: CancellationException) {
            throw t
        } catch (_: Throwable) {
            return emptyList()
        }
        val list = when (val m = out["models"]) {
            is List<*> -> m
            is Array<*> -> m.toList()
            else -> emptyList<Any?>()
        }
        return list.mapNotNull { (it as? Map<*, *>)?.get("id")?.toString()?.takeIf { id -> id.isNotBlank() } }.take(count)
    }

    private fun receipt(
        jobId: String,
        revision: Long,
        startedAtMs: Long,
        ids: List<String>,
        children: List<String>,
        ok: Boolean,
        error: String,
        extra: Map<String, Any?> = emptyMap(),
    ): Map<String, Any?> {
        val finishedAtMs = clock()
        val value = linkedMapOf<String, Any?>(
            "models" to ids,
            "children" to children,
            "ok" to ok,
            "revision" to revision,
            "actor" to BoardRules.FAN_OUT_ACTOR,
            // Delta 2026-09-05 (receipt timing): this worker's clock around the whole split;
            // atMs stays "the moment the receipt was in hand" as every kanban/ receipt does.
            "startedAtMs" to startedAtMs,
            "finishedAtMs" to finishedAtMs,
            "atMs" to finishedAtMs,
        )
        if (error.isNotEmpty()) value["error"] = error
        value.putAll(extra)
        // Two workers can be live at once (a drag mid-split fires the rule at the new
        // revision while the old one still runs); the newer revision owns the key. A
        // stale worker never overwrites it — its own map is still returned to the sink.
        val standing = (blackboard.get(RECEIPT_PREFIX + jobId) as? Map<*, *>)?.get("revision") as? Number
        if (standing == null || standing.toLong() <= revision) blackboard.put(RECEIPT_PREFIX + jobId, value, LANGUAGE)
        return value
    }
}
