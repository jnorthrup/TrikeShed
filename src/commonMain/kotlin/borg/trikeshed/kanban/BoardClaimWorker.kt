package borg.trikeshed.kanban

import borg.trikeshed.graal.ConfixBlackboard
import borg.trikeshed.kanban.rules.BoardRules
import borg.trikeshed.lcnc.LcncNode
import borg.trikeshed.lcnc.LcncNodeRunner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * BoardClaimWorker — what a `claim` activation becomes once the sink has it:
 *
 *   1. Move(RUNNING, owner=claim:brain), idempotency `<jobId>#claim#<rev>`;
 *      wait for THAT commit on the store's committed flow (subscribed before
 *      the send — the flow has no replay), [commitTimeoutMs] → give up, and
 *      the receipt says so. Nothing else happens for a claim that never landed.
 *   2. The card's title is the brief. `prompt.chat` — the daemon's own brain
 *      (ModelMux over KeyMux; quota it already has) — answers in ≤ 3 sentences,
 *      maxTokens 256. The model is the first entry of `mux.models`, which is
 *      (Delta 2026-09-04: MAX_TOKENS is 1024 — see the constant; 256 starved a thinking model.)
 *      the newest model Hermes ran here (LcncContracts: "the live list leads,
 *      and its first entry is the newest") — `prompt.chat` itself refuses a
 *      blank model, so the worker resolves it the way the picklist would.
 *   3. Receipt `kanban/claim/<jobId>` = {owner, model, ok, content|error, atMs,
 *      revision}, then Move(REVIEW) at the card's CURRENT revision (idempotency
 *      `<jobId>#claim-review#<rev>`), owner untouched. A failed brain call still
 *      goes to REVIEW with ok=false — a human reads the receipt. Nothing here
 *      ever moves a card to DONE; the store's claim guard would refuse it anyway.
 *
 * A daemon with NO `prompt.chat` runner at all (a reduced/test context) takes
 * no claim: the receipt says "no brain", the card stays READY for whoever has
 * one. A brain that is present and fails is step 3's ok=false, not this.
 *
 * Pure over the store, the blackboard and a runner lookup (looked up at claim
 * time so a registry the daemon fills after the module attached still counts).
 */
class BoardClaimWorker(
    private val store: BoardStoreElement,
    private val blackboard: ConfixBlackboard,
    private val runner: (String) -> LcncNodeRunner?,
    private val clock: () -> Long,
    private val commitTimeoutMs: Long = 10_000L,
    /**
     * The fact plane at claim time, for [PlaneBrief]. Delta (2026-09-04): the brief
     * was the title alone; the module hands one `ReteNetwork.snapshot()` here so the
     * brain sees the files, panels and daemon state that mention the card's terms.
     */
    private val plane: suspend () -> List<PlaneBrief.Row> = { emptyList() },
) {
    companion object {
        const val RECEIPT_PREFIX: String = "kanban/claim/"
        const val LANGUAGE: String = "kanban-claim"
        /** The actor the plane judge signs its REVIEW→DONE move with — never the claimant. */
        const val JUDGE_ACTOR: String = "judge:plane"
        /**
         * 1024, not 256: the newest Hermes card is a thinking model (glm-5.3-flash)
         * that spends its budget on reasoning first; at 256 every claim on
         * 2026-09-04 came back "provider billed 256 completion tokens but returned
         * no content". One claim costs ~1k tokens of quota the ledger already proved.
         */
        const val MAX_TOKENS: String = "2048"
        /** Tags that route a claimed card to a person no matter what the reply says. */
        val HUMAN_TAGS: Set<String> = setOf("human-review", "experiment", "review:human")

        fun brief(jobId: String, title: String): String = brief(jobId, title, "", emptyList(), emptyList())

        /** The RFC brief: goal, criteria, evidence from the plane, daemon state, lessons, reply shape ([PlaneBrief]). */
        fun brief(jobId: String, title: String, spec: String, plane: List<PlaneBrief.Row>, receipts: List<PlaneBrief.Receipt>): String {
            val parsed = PlaneBrief.parseSpec(title, spec)
            return PlaneBrief.render(jobId, title, parsed, PlaneBrief.select(plane, title + " " + spec), PlaneBrief.state(plane), PlaneBrief.lessons(receipts))
        }
    }

    /** One claim, start to finish. Returns the receipt as written (plus `review`: the REVIEW move's verdict). */
    suspend fun claim(jobId: String, expectedRevision: Long, owner: String = BoardRules.CLAIM_OWNER): Map<String, Any?> = coroutineScope {
        // ── 0. no brain, no claim ─────────────────────────────────────────────
        val chat = runner("prompt.chat")
        if (chat == null) {
            return@coroutineScope receipt(
                jobId, owner, model = "", ok = false, revision = expectedRevision,
                body = "error" to "no brain: this daemon has no prompt.chat runner, claim not taken",
            ) + ("review" to "not attempted")
        }

        // ── 1. RUNNING, and the commit that proves it ─────────────────────────
        val events = Channel<BoardCommitted>(Channel.UNLIMITED)
        val subscribed = CompletableDeferred<Unit>()
        val tap = launch {
            store.committed
                .onSubscription { subscribed.complete(Unit) }
                .collect { if (it.jobId == jobId) events.trySend(it) }
        }
        subscribed.await()
        val reply = CompletableDeferred<BoardApply>()
        store.intake.send(
            BoardIntake(
                mapOf(
                    "type" to "move",
                    "jobId" to jobId,
                    "idempotencyKey" to "$jobId#${BoardRules.CLAIM}#$expectedRevision",
                    "expectedRevision" to expectedRevision,
                    "toColumn" to BoardCol.RUNNING.wire,
                    "owner" to owner,
                ),
                reply,
            ),
        )
        val verdict = withTimeoutOrNull(commitTimeoutMs) { reply.await() }
        val landed: BoardCommitted? = when (verdict) {
            is BoardApply.Committed -> withTimeoutOrNull(commitTimeoutMs) {
                var found: BoardCommitted? = null
                while (found == null) {
                    val ev = events.receive()
                    if (ev.sequence == verdict.sequence && ev.col == BoardCol.RUNNING) found = ev
                }
                found
            }
            else -> null
        }
        tap.cancel()
        if (landed == null) {
            val why = when (verdict) {
                is BoardApply.Rejected -> "claim refused: ${verdict.reason}"
                is BoardApply.Committed -> "claim committed (seq ${verdict.sequence}) but no RUNNING event within ${commitTimeoutMs}ms"
                null -> "no reply from the store within ${commitTimeoutMs}ms"
            }
            return@coroutineScope receipt(jobId, owner, model = "", ok = false, body = "error" to why, revision = expectedRevision) +
                ("review" to "not attempted")
        }

        // ── 2. the brief is the card; the brain is the daemon's own ────────────
        val card = store.card(jobId)
        val title = card?.title ?: jobId
        val specText = card?.spec.orEmpty()
        val spec = PlaneBrief.parseSpec(title, specText)
        val model = spec.model.ifBlank { resolveModel() }
        val tokens = (spec.tokens ?: MAX_TOKENS.toInt()).toString()
        val planeRows = runCatching { plane() }.getOrDefault(emptyList())
        val priorReceipts = blackboard.keys().filter { it.startsWith(RECEIPT_PREFIX) }.mapNotNull { k ->
            (blackboard.get(k) as? Map<*, *>)?.let { m ->
                PlaneBrief.Receipt(m["model"]?.toString().orEmpty(), m["ok"] == true, m["error"]?.toString().orEmpty())
            }
        }
        val briefText = brief(jobId, title, specText, planeRows, priorReceipts)
        val mentioned = PlaneBrief.select(planeRows, title + " " + specText).size
        val answer: Map<String, Any?> = try {
            chat.run(
                LcncNode(
                    id = "claim-$jobId",
                    type = "prompt.chat",
                    params = mapOf("prompt" to briefText, "model" to model, "maxTokens" to tokens),
                ),
                emptyMap(),
            )
        } catch (t: CancellationException) {
            throw t
        } catch (t: Throwable) {
            mapOf("ok" to false, "error" to (t.message ?: t.toString()), "model" to model, "content" to "")
        }
        val ok = answer["ok"] == true
        val body: Pair<String, Any?> =
            if (ok) "content" to (answer["content"]?.toString() ?: "")
            else "error" to (answer["error"]?.toString()?.takeIf { it.isNotBlank() } ?: "brain answered without content")

        // ── 3. the plane judges; the receipt says why; the card moves accordingly ──
        // DONE  → RUNNING→REVIEW→DONE as actor "judge:plane" (the guard lets a non-claimant
        //         close from REVIEW; the claimant never can);
        // REVIEW→ a person decides;
        // RETRY → READY with a reaper strike receipt (3rd strike: BLOCKED, owner cleared).
        val planeIds = HashSet<String>(planeRows.size * 2)
        for (r in planeRows) planeIds.add(PlaneBrief.evidenceId(r))
        val humanTag = card?.tags?.any { it.lowercase() in HUMAN_TAGS } == true
        val decision = PlaneJudge.decide(spec, humanTag, ok, answer["content"]?.toString().orEmpty(), planeIds)
        // The revision the card landed RUNNING on — NOT store.card().revision: a person who
        // moved the card during the brain call must win, and the CAS refusal records it.
        val current = landed.snapshot.revision
        val judged = linkedMapOf<String, Any?>(
            "decision" to decision.outcome.name,
            "why" to decision.reason,
            "verdict" to (decision.reply?.verdict ?: ""),
            "criteria" to (decision.reply?.lines?.map { l -> mapOf("label" to l.label, "met" to l.met, "evidence" to l.evidence) } ?: emptyList<Any>()),
        )
        val written = receipt(jobId, owner, model = answer["model"]?.toString() ?: model, ok = ok, body = body, revision = current, facts = mentioned, judged = judged)
        val trail = ArrayList<String>()
        suspend fun move(to: BoardCol, rev: Long, key: String, extra: Map<String, Any?> = emptyMap()): BoardApply? {
            val reply = CompletableDeferred<BoardApply>()
            store.intake.send(BoardIntake(mapOf("type" to "move", "jobId" to jobId, "idempotencyKey" to key, "expectedRevision" to rev, "toColumn" to to.wire) + extra, reply))
            val r = withTimeoutOrNull(commitTimeoutMs) { reply.await() }
            trail += when (r) {
                is BoardApply.Committed -> "${to.wire} r${r.revision}"
                is BoardApply.Rejected -> "${to.wire} refused: ${r.reason}"
                null -> "${to.wire}: no reply within ${commitTimeoutMs}ms"
            }
            return r
        }
        when (decision.outcome) {
            PlaneJudge.Outcome.REVIEW -> move(BoardCol.REVIEW, current, "$jobId#claim-review#$current", mapOf("actor" to JUDGE_ACTOR))
            PlaneJudge.Outcome.DONE -> {
                val r1 = move(BoardCol.REVIEW, current, "$jobId#claim-review#$current", mapOf("actor" to JUDGE_ACTOR))
                if (r1 is BoardApply.Committed) move(BoardCol.DONE, r1.revision, "$jobId#judge-done#${r1.revision}", mapOf("actor" to JUDGE_ACTOR))
            }
            PlaneJudge.Outcome.RETRY -> {
                // Durable: the row carries strikes across restarts; the blackboard receipts are the same count while the process lives.
                val strike = maxOf(borg.trikeshed.kanban.rules.ReaperProduction.countPriorStrikes(blackboard, jobId), card?.strikes ?: 0) + 1
                val block = strike >= BoardRules.REAPER_BLOCK_STRIKE
                blackboard.put(
                    borg.trikeshed.kanban.rules.ReaperProduction.RECEIPT_PREFIX + "judge-$jobId-r$current",
                    mapOf("jobId" to jobId, "strike" to "$strike", "toColumn" to (if (block) BoardCol.BLOCKED.wire else BoardCol.READY.wire), "expectedRevision" to "$current", "why" to decision.reason, "by" to JUDGE_ACTOR),
                    LANGUAGE,
                )
                if (block) move(BoardCol.BLOCKED, current, "$jobId#judge-block#$current", mapOf("owner" to "", "strikes" to strike, "actor" to JUDGE_ACTOR))
                else move(BoardCol.READY, current, "$jobId#judge-retry#$current", mapOf("strikes" to strike, "actor" to JUDGE_ACTOR))
            }
        }
        written + ("moves" to trail)
    }

    /** `mux.models#models[0].id` — the newest model Hermes ran here, or "" when the daemon offers none. */
    private suspend fun resolveModel(): String {
        val models = runner("mux.models") ?: return ""
        val out = try {
            models.run(LcncNode(id = "claim-models", type = "mux.models"), emptyMap())
        } catch (t: CancellationException) {
            throw t
        } catch (_: Throwable) {
            return ""
        }
        val list = when (val m = out["models"]) {
            is List<*> -> m
            is Array<*> -> m.toList()
            else -> emptyList<Any?>()
        }
        return (list.firstOrNull() as? Map<*, *>)?.get("id")?.toString().orEmpty()
    }

    private fun receipt(jobId: String, owner: String, model: String, ok: Boolean, body: Pair<String, Any?>, revision: Long, facts: Int = 0, judged: Map<String, Any?> = emptyMap()): Map<String, Any?> {
        val value = linkedMapOf<String, Any?>(
            "owner" to owner,
            "model" to model,
            "ok" to ok,
            body.first to body.second,
            "atMs" to clock(),
            "revision" to revision,
            // how many plane facts the brief carried (0 = the title alone)
            "facts" to facts,
        )
        value.putAll(judged)
        blackboard.put(RECEIPT_PREFIX + jobId, value, LANGUAGE)
        return value
    }
}
