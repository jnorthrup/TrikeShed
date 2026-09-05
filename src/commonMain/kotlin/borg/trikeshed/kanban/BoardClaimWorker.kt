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
 * Delta 2026-09-05 (fan-out): the token budget is `TOKENS:` on the spec, else
 * [MAX_TOKENS] (2048 now; the 256/1024 figures above are history). A card whose
 * dependencies carry `kanban/claim/<child>` receipts is a fan-IN: those receipts
 * ride the brief as a CHILDREN block ([PlaneBrief.ChildReceipt]), their evidence
 * ids (`blackboard/kanban/claim/<child>`) are accepted by the judge, and the
 * default budget rises to 4096 because one merge reads N answers. The receipt
 * also carries `startedAtMs`/`finishedAtMs` from this worker's clock around the
 * brain call, and `latencyMs`/`inputTokens`/`outputTokens`/`cachedHit` copied from
 * `prompt.chat`'s answer when it reports them (omitted when it does not, never
 * computed here, never a zero) — the board page shows real progress from these, not a guess.
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
        /**
         * Delta 2026-09-05 (fan-out): the default budget for a fan-IN merge when the
         * spec names no `TOKENS:`. One merge reads N child answers and must reconcile
         * them; the plan's floor for a thinking model on a tree is 4096.
         */
        const val MERGE_TOKENS: Int = 4096
        /** Tags that route a claimed card to a person no matter what the reply says. */
        val HUMAN_TAGS: Set<String> = setOf("human-review", "experiment", "review:human")

        fun brief(jobId: String, title: String): String = brief(jobId, title, "", emptyList(), emptyList())

        /**
         * The RFC brief: goal, criteria, evidence from the plane, daemon state, lessons, reply shape ([PlaneBrief]).
         * Delta 2026-09-05 (fan-out): [children] — the receipts of the card's Done children — become the
         * CHILDREN block and the MERGE line; empty for an ordinary card.
         */
        fun brief(
            jobId: String,
            title: String,
            spec: String,
            plane: List<PlaneBrief.Row>,
            receipts: List<PlaneBrief.Receipt>,
            children: List<PlaneBrief.ChildReceipt> = emptyList(),
        ): String {
            val parsed = PlaneBrief.parseSpec(title, spec)
            return PlaneBrief.render(
                jobId, title, parsed,
                PlaneBrief.select(plane, title + " " + spec), PlaneBrief.state(plane), PlaneBrief.lessons(receipts),
                children,
            )
        }

        /**
         * The children's receipts for a fan-IN claim: one [PlaneBrief.ChildReceipt] per child in [children]
         * that has a `kanban/claim/<child>` receipt on the blackboard, in the order given. [children] are the
         * card's dependencies whose row says `parent == this card` (the durable edge from S1) — an ordinary
         * dependency is never briefed as a child, however it was closed. A child WITHOUT a receipt is not
         * skipped silently by [claim]: it refuses the merge with a receipt naming the missing keys (the
         * blackboard is in-memory; a restart between the children's Done and the merge loses them).
         * The evidence id is the blackboard key as the plane names it, so the reply can cite it and the judge accepts it.
         */
        fun childReceipts(blackboard: ConfixBlackboard, children: List<String>): List<PlaneBrief.ChildReceipt> =
            children.mapNotNull { dep ->
                (blackboard.get(RECEIPT_PREFIX + dep) as? Map<*, *>)?.let { m ->
                    PlaneBrief.ChildReceipt(
                        evidenceId = "blackboard/" + RECEIPT_PREFIX + dep,
                        jobId = dep,
                        model = m["model"]?.toString().orEmpty(),
                        ok = m["ok"] == true,
                        decision = m["decision"]?.toString().orEmpty(),
                        content = (m["content"] ?: m["error"])?.toString().orEmpty(),
                    )
                }
            }
    }

    /**
     * What the brain call cost, as the receipt records it. [startedAtMs]/[finishedAtMs] are this
     * worker's clock around `chat.run` (null when no brain was asked); the rest are copied from the
     * answer map when `prompt.chat` reports them (Number-safe: a fake brain in a test rig may hand
     * back none) and NULL otherwise — a field that was not measured is not written, never a zero
     * a page could mistake for "instant". [NONE] is what an early-return receipt (no brain, claim
     * refused) carries.
     */
    private data class Timing(
        val startedAtMs: Long?,
        val finishedAtMs: Long?,
        val latencyMs: Long?,
        val inputTokens: Int?,
        val outputTokens: Int?,
        val cachedHit: Boolean?,
    ) {
        companion object {
            val NONE = Timing(null, null, null, null, null, null)

            fun of(startedAtMs: Long, finishedAtMs: Long, answer: Map<String, Any?>): Timing = Timing(
                startedAtMs = startedAtMs,
                finishedAtMs = finishedAtMs,
                latencyMs = (answer["latencyMs"] as? Number)?.toLong(),
                inputTokens = (answer["inputTokens"] as? Number)?.toInt(),
                outputTokens = (answer["outputTokens"] as? Number)?.toInt(),
                // `cachedHit` is the attributed value (prompt.chat omits it when the mux receipt
                // could not be matched to this call); the legacy `cached` key is only a fallback.
                cachedHit = (answer["cachedHit"] as? Boolean) ?: (answer["cached"] as? Boolean),
            )
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
        // Delta 2026-09-05 (fan-out): a fan-IN card's children are the dependencies whose row
        // says `parent == this card`; their claim receipts ride the brief. The merge model is the
        // parent's own MODEL: (or the newest Hermes model) — the children's models are named in
        // the CHILDREN block, not reused. A child whose receipt is not on this blackboard (a
        // restart lost it; a person closed the child without a claim) makes the merge a lie, so
        // it is refused with a receipt naming the keys and the card parks in REVIEW for a person.
        val childIds = card?.dependencies.orEmpty().filter { store.card(it)?.parent == jobId }
        val missingReceipts = childIds.filter { blackboard.get(RECEIPT_PREFIX + it) == null }
        if (missingReceipts.isNotEmpty()) {
            val current0 = landed.snapshot.revision
            val written = receipt(
                jobId, owner, model = "", ok = false, revision = current0,
                body = "error" to "child receipts lost: ${missingReceipts.joinToString { RECEIPT_PREFIX + it }} — " +
                    "no merge without every child's answer; a person decides",
            )
            val reply2 = CompletableDeferred<BoardApply>()
            store.intake.send(BoardIntake(mapOf("type" to "move", "jobId" to jobId, "idempotencyKey" to "$jobId#claim-review#$current0", "expectedRevision" to current0, "toColumn" to BoardCol.REVIEW.wire, "actor" to JUDGE_ACTOR), reply2))
            val r = withTimeoutOrNull(commitTimeoutMs) { reply2.await() }
            return@coroutineScope written + ("review" to when (r) {
                is BoardApply.Committed -> "review r${r.revision}"
                is BoardApply.Rejected -> "review refused: ${r.reason}"
                null -> "review: no reply within ${commitTimeoutMs}ms"
            })
        }
        val children = childReceipts(blackboard, childIds)
        val model = spec.model.ifBlank { resolveModel() }
        // A merge never gets less than a child did: the floor is MERGE_TOKENS, the same
        // rule as the worker's child floor (max of the parent's TOKENS: and 4096).
        val tokens = (if (children.isNotEmpty()) maxOf(spec.tokens ?: 0, MERGE_TOKENS) else (spec.tokens ?: MAX_TOKENS.toInt())).toString()
        val planeRows = runCatching { plane() }.getOrDefault(emptyList())
        val priorReceipts = blackboard.keys().filter { it.startsWith(RECEIPT_PREFIX) }.mapNotNull { k ->
            (blackboard.get(k) as? Map<*, *>)?.let { m ->
                PlaneBrief.Receipt(m["model"]?.toString().orEmpty(), m["ok"] == true, m["error"]?.toString().orEmpty())
            }
        }
        val briefText = brief(jobId, title, specText, planeRows, priorReceipts, children)
        val mentioned = PlaneBrief.select(planeRows, title + " " + specText).size
        // Delta 2026-09-05 (receipt timing): this worker's clock brackets the brain call so the
        // receipt shows when the model was asked and when it answered, independent of what
        // prompt.chat reports (latencyMs there is the provider round trip, measured by the node).
        val startedAtMs = clock()
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
        val finishedAtMs = clock()
        val timing = Timing.of(startedAtMs, finishedAtMs, answer)
        val ok = answer["ok"] == true
        val body: Pair<String, Any?> =
            if (ok) "content" to (answer["content"]?.toString() ?: "")
            else "error" to (answer["error"]?.toString()?.takeIf { it.isNotBlank() } ?: "brain answered without content")

        // ── 3. the plane judges; the receipt says why; the card moves accordingly ──
        // DONE  → RUNNING→REVIEW→DONE as actor "judge:plane" (the guard lets a non-claimant
        //         close from REVIEW; the claimant never can);
        // REVIEW→ a person decides;
        // RETRY → READY with a reaper strike receipt (3rd strike: BLOCKED, owner cleared).
        val planeIds = HashSet<String>(planeRows.size * 2 + children.size)
        for (r in planeRows) planeIds.add(PlaneBrief.evidenceId(r))
        // A child receipt id is evidence the judge accepts even where the blackboard is not
        // on the plane (the test rig has no BlackboardChangesFactElement; the daemon does).
        for (c in children) planeIds.add(c.evidenceId)
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
        val written = receipt(jobId, owner, model = answer["model"]?.toString() ?: model, ok = ok, body = body, revision = current, facts = mentioned, judged = judged, timing = timing)
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

    private fun receipt(
        jobId: String,
        owner: String,
        model: String,
        ok: Boolean,
        body: Pair<String, Any?>,
        revision: Long,
        facts: Int = 0,
        judged: Map<String, Any?> = emptyMap(),
        timing: Timing = Timing.NONE,
    ): Map<String, Any?> {
        // atMs stays the moment the answer was in hand (what the page has always shown); an
        // early-return receipt never asked a brain, so its atMs is simply now and it carries
        // no timing fields at all.
        val atMs = timing.finishedAtMs ?: clock()
        val value = linkedMapOf<String, Any?>(
            "owner" to owner,
            "model" to model,
            "ok" to ok,
            body.first to body.second,
            "atMs" to atMs,
            "revision" to revision,
            // how many plane facts the brief carried (0 = the title alone)
            "facts" to facts,
        )
        // Delta 2026-09-05 (receipt timing): worker clock around the brain call, then what
        // prompt.chat itself measured/was billed — each key only when it was measured. A
        // receipt that never asked a brain has none of them; the page then shows nothing
        // rather than an invented "0 ms".
        timing.startedAtMs?.let { value["startedAtMs"] = it }
        timing.finishedAtMs?.let { value["finishedAtMs"] = it }
        timing.latencyMs?.let { value["latencyMs"] = it }
        timing.inputTokens?.let { value["inputTokens"] = it }
        timing.outputTokens?.let { value["outputTokens"] = it }
        timing.cachedHit?.let { value["cachedHit"] = it }
        value.putAll(judged)
        blackboard.put(RECEIPT_PREFIX + jobId, value, LANGUAGE)
        return value
    }
}
