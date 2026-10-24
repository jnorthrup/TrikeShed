package borg.trikeshed.kanban

import borg.trikeshed.graal.ConfixBlackboard
import borg.trikeshed.kanban.rules.BoardRules
import borg.trikeshed.lcnc.AgentNodes
import borg.trikeshed.lcnc.AgentRunRequest
import borg.trikeshed.lcnc.AgentRunResult
import borg.trikeshed.lcnc.AgentRuns
import borg.trikeshed.lcnc.LcncNode
import borg.trikeshed.lcnc.LcncNodeRunner
import borg.trikeshed.lib.*
import borg.trikeshed.job.ContentId
import borg.trikeshed.parse.jsonOf
import borg.trikeshed.agent.modelmux.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope

/**
 * BoardClaimWorker — what a `claim` activation becomes once the sink has it:
 *
 *   1. Commit RUNNING with an immutable attempt identity.
 *   2. Retain the exact brief, sources, model identity and task harness before inference.
 *   3. Retain the answer and measured evidence compliance before applying the judge's move.
 *
 * Unspecified models use task-specific proficiency; explicit models retain precedence.
 * A receipt acknowledgment preserves lifecycle, lane order and transition age. A concurrent
 * card edit wins over subsequent worker moves. Every admitted store command is awaited.
 * Startup recovery restores answer projections and settles interrupted attempts.
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
    /**
     * The fact plane at claim time, for [PlaneBrief]. Delta (2026-09-04): the brief
     * was the title alone; the module hands one `ReteNetwork.snapshot()` here so the
     * brain sees the files, panels and daemon state that mention the card's terms.
     */
    private val plane: suspend () -> List<PlaneBrief.Row> = { emptyList() },
    /**
     * The coding-agent lane (Forge genesis, Cut A): the host's agent runs, looked up at claim
     * time like the runners (the daemon fills them in after the module attaches); null = no lane,
     * and an `AGENT:` card is not taken.
     */
    private val agents: () -> AgentRuns? = { null },
    private val mintRunId: () -> String = { "run-" + clock() },
) {
    companion object {
        const val RECEIPT_PREFIX: String = "kanban/claim/"
        const val LANGUAGE: String = "kanban-claim"
        const val HARNESS: String = "kanban-plane-v1"
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
            agent: PlaneBrief.AgentBlock? = null,
        ): String {
            val parsed = PlaneBrief.parseSpec(title, spec)
            return PlaneBrief.render(
                jobId, title, parsed,
                PlaneBrief.select(plane, title + " " + spec), PlaneBrief.state(plane), PlaneBrief.lessons(receipts),
                children, agent,
            )
        }

        /**
         * The children's receipts for a fan-IN claim: one [PlaneBrief.ChildReceipt] per child in [children]
         * that has a `kanban/claim/<child>` receipt on the blackboard, in the order given. [children] are the
         * card's dependencies whose row says `parent == this card` (the durable edge from S1) — an ordinary
         * dependency is never briefed as a child, however it was closed. A child WITHOUT a receipt is not
 * skipped silently by [claim]: it restores retained answers first, then refuses a merge whose
 * child never retained an answer.
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
                        contentId = m["receiptCid"] as? String,
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
        // ── 0. who works this card: the agent its spec names, else the brain ─────
        val card0 = store.card(jobId)
        val spec0 = PlaneBrief.parseSpec(card0?.title ?: jobId, card0?.spec.orEmpty())
        val lane = if (spec0.agent.isNotBlank()) agents() else null
        val agentInfo = if (spec0.agent.isNotBlank()) lane?.roster()?.firstOrNull { it.id == spec0.agent } else null
        if (spec0.agent.isNotBlank() && (agentInfo == null || !agentInfo.enabled)) {
            val why = agentInfo?.why?.takeIf { it.isNotEmpty() }?.let { " ($it)" }.orEmpty()
            return@coroutineScope receipt(
                jobId, owner, model = "", ok = false, revision = expectedRevision,
                body = "error" to "agent '${spec0.agent}' is not installed/enabled here$why, claim not taken",
            ) + ("review" to "not attempted")
        }
        val chat = runner("prompt.chat")
        if (agentInfo == null && chat == null) {
            return@coroutineScope receipt(
                jobId, owner, model = "", ok = false, revision = expectedRevision,
                body = "error" to "no brain: this daemon has no prompt.chat runner, claim not taken",
            ) + ("review" to "not attempted")
        }

        // The store acknowledges only after CAS/WAL flush and publication.
        val attemptId = "$jobId#${BoardRules.CLAIM}#$expectedRevision"
        val reply = CompletableDeferred<BoardApply>()
        store.intake.send(
            BoardIntake(
                mapOf(
                    "type" to "move",
                    "jobId" to jobId,
                    "idempotencyKey" to attemptId,
                    "expectedRevision" to expectedRevision,
                    "toColumn" to BoardCol.RUNNING.wire,
                    "owner" to owner,
                ),
                reply,
            ),
        )
        val verdict = reply.await()
        val landed = verdict as? BoardApply.Committed
        if (landed == null) {
            val why = "claim refused: ${(verdict as BoardApply.Rejected).reason}"
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
        restoreReceipts()
        val missingReceipts = childIds.filter { blackboard.get(RECEIPT_PREFIX + it) == null }
        if (missingReceipts.isNotEmpty()) {
            val current0 = landed.revision
            val written = receipt(
                jobId, owner, model = "", ok = false, revision = current0,
                body = "error" to "child receipts lost: ${missingReceipts.joinToString { RECEIPT_PREFIX + it }} — " +
                    "no merge without every child's answer; a person decides",
                attemptId = attemptId,
            )
            val reply2 = CompletableDeferred<BoardApply>()
            val receiptRevision = (written["receiptRevision"] as? Number)?.toLong() ?: current0
            if (receiptRevision != current0 + 1) return@coroutineScope written + ("review" to "card changed; result retained")
            store.intake.send(BoardIntake(mapOf("type" to "move", "jobId" to jobId, "idempotencyKey" to "$jobId#claim-review#$current0", "expectedRevision" to receiptRevision, "toColumn" to BoardCol.REVIEW.wire, "actor" to JUDGE_ACTOR), reply2))
            val r = reply2.await()
            return@coroutineScope written + ("review" to when (r) {
                is BoardApply.Committed -> "review r${r.revision}"
                is BoardApply.Rejected -> "review refused: ${r.reason}"
            })
        }
        val children = childReceipts(blackboard, childIds)
        // An agent runs its own model; the mux pick is the brain's business only.
        val harness = agentInfo?.let { "kanban-agent/${it.id}/${it.version}" } ?: HARNESS
        val selected: ProficiencyModel = if (agentInfo != null) spec.model j null
            else resolveModel(spec.taskKind, spec.model)
        val model = selected.a
        // A merge never gets less than a child did: the floor is MERGE_TOKENS, the same
        // rule as the worker's child floor (max of the parent's TOKENS: and 4096).
        val tokens = (if (children.isNotEmpty()) maxOf(spec.tokens ?: 0, MERGE_TOKENS) else (spec.tokens ?: MAX_TOKENS.toInt())).toString()
        val planeRows = runCatching { plane() }.getOrDefault(emptyList())
        val priorReceipts = blackboard.keys().filter { it.startsWith(RECEIPT_PREFIX) }.mapNotNull { k ->
            (blackboard.get(k) as? Map<*, *>)?.let { m ->
                PlaneBrief.Receipt(m["model"]?.toString().orEmpty(), m["ok"] == true, m["error"]?.toString().orEmpty())
            }
        }
        val agentRunId = if (agentInfo != null) mintRunId() else ""
        val agentBudget = AgentNodes.clampBudget(spec.agentBudget)
        val agentBlock = agentInfo?.let {
            PlaneBrief.AgentBlock(cli = it.id, version = it.version, repo = "the daemon's repository", evidenceId = AgentNodes.evidenceId(agentRunId), budgetSeconds = agentBudget)
        }
        val briefText = brief(jobId, title, specText, planeRows, priorReceipts, children, agentBlock)
        val mentioned = PlaneBrief.select(planeRows, title + " " + specText).size
        val sources = planeRows.map { r -> mapOf(
            "id" to PlaneBrief.evidenceId(r),
            "cid" to (r.contentId ?: ContentId.of(jsonOf(r.fields).encodeToByteArray()).value),
            "fields" to r.fields,
        ) } + children.map { c -> mapOf(
            "id" to c.evidenceId, "jobId" to c.jobId, "model" to c.model,
            "cid" to c.contentId,
            "ok" to c.ok, "decision" to c.decision, "content" to c.content,
        ) }
        val admission = store.recordReceipt(jobId, mapOf(
            "id" to "$attemptId/admission", "kind" to "claim-admission", "attemptId" to attemptId,
            "jobId" to jobId, "owner" to owner, "model" to model, "modelVersion" to selected.b,
            "harness" to harness, "taskKind" to spec.taskKind, "claimRevision" to landed.revision,
            "priority" to card?.priority, "order" to card?.order, "spec" to specText,
            "brief" to briefText, "sources" to sources, "atMs" to clock(),
        ))
        check(admission is BoardApply.Committed) { "Claim admission receipt rejected: $admission" }
        // A concurrent move wins. The receipt remains retained, but no model is called.
        if (admission.revision != landed.revision + 1 || store.card(jobId)?.col != BoardCol.RUNNING) {
            return@coroutineScope receipt(
                jobId, owner, model, false, "error" to "card changed before model admission",
                landed.revision, attemptId = attemptId,
            ) + ("review" to "card changed")
        }
        // Delta 2026-09-05 (receipt timing): this worker's clock brackets the brain call so the
        // receipt shows when the model was asked and when it answered, independent of what
        // prompt.chat reports (latencyMs there is the provider round trip, measured by the node).
        val startedAtMs = clock()
        var agentResult: AgentRunResult? = null
        val answer: Map<String, Any?> = try {
            if (agentInfo != null) {
                // The lane: the agent works a scratch clone; its last message is the reply the judge reads.
                val request = AgentRunRequest(agent = agentInfo.id, brief = briefText, model = model,
                    budgetSeconds = agentBudget, runId = agentRunId, jobId = jobId)
                val r = lane!!.run(request)
                check(r.runId == request.runId && r.jobId == request.jobId && r.agent == request.agent) {
                    "agent claim: result identity does not match the dispatched request"
                }
                agentResult = r
                mapOf(
                    "ok" to r.ok, "content" to r.summary, "model" to r.model.ifBlank { agentInfo.id },
                    "error" to r.error.ifEmpty { if (r.ok) "" else "agent exited ${r.exit}" },
                )
            } else chat!!.run(
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
        // A recorded diff is citable evidence; an empty one is not.
        if ((agentResult?.patchBytes ?: 0L) > 0L) planeIds.add(AgentNodes.evidenceId(agentRunId))
        // The judge matches a citation against the criterion that cited it, so it needs the fact's
        // text and not just its id: a fact whose body carries the path or symbol a MUST names is
        // what makes that MUST answered rather than merely footnoted.
        val planeText = HashMap<String, String>(planeRows.size * 2)
        for (r in planeRows) planeText[PlaneBrief.evidenceId(r)] =
            r.fields.entries.joinToString(" ") { (k, v) -> "$k ${v ?: ""}" }
        for (c in children) planeText[c.evidenceId] = c.jobId + " " + c.model + " " + c.content
        // The agent lane's evidence is its diff: the paths it touched, then its own summary. A
        // criterion naming a file is answered by the file appearing in the patch, not by the agent
        // saying it did the work.
        agentResult?.let { r ->
            planeText[AgentNodes.evidenceId(agentRunId)] =
                r.files.joinToString(" ") + " " + r.patchCid + " " + r.summary
        }
        val humanTag = card?.tags?.any { it.lowercase() in HUMAN_TAGS } == true
        val decision = if (agentResult?.killed == true) PlaneJudge.Decision(PlaneJudge.Outcome.REVIEW, "agent budget of ${agentBudget}s exceeded; a person decides", null)
            else PlaneJudge.decide(
                spec, humanTag, ok, answer["content"]?.toString().orEmpty(), planeIds, planeText,
                ownCriteria = card?.parent.isNullOrBlank(),
            )
        // The revision the card landed RUNNING on — NOT store.card().revision: a person who
        // moved the card during the brain call must win, and the CAS refusal records it.
        val current = admission.revision
        val judged = linkedMapOf<String, Any?>(
            "decision" to decision.outcome.name,
            "why" to decision.reason,
            "verdict" to (decision.reply?.verdict ?: ""),
            "criteria" to (decision.reply?.lines?.map { l -> mapOf("label" to l.label, "met" to l.met, "evidence" to l.evidence) } ?: emptyList<Any>()),
            "harness" to harness,
            "taskKind" to spec.taskKind,
            "modelVersion" to selected.b,
            "admissionId" to "$attemptId/admission",
            "basis" to "plane-evidence-v1",
            // Citation compliance is measurable here; semantic accuracy requires a separate assessment.
            "score" to when {
                !ok || decision.outcome == PlaneJudge.Outcome.REVIEW -> null
                decision.outcome == PlaneJudge.Outcome.DONE -> 1.0
                else -> 0.0
            },
            "correctness" to "UNKNOWN",
            "disposition" to "UNSELECTED",
        )
        agentResult?.let { r ->
            judged["agent"] = r.agent; judged["runId"] = r.runId; judged["patchCid"] = r.patchCid; judged["transcriptCid"] = r.transcriptCid
            judged["exit"] = r.exit; judged["killed"] = r.killed; judged["truncated"] = r.truncated; judged["patchBytes"] = r.patchBytes
        }
        val written = receipt(jobId, owner, model = answer["model"]?.toString() ?: model, ok = ok, body = body, revision = current, facts = mentioned, judged = judged, timing = timing, attemptId = attemptId)
        val retainedRevision = (written["receiptRevision"] as Number).toLong()
        if (retainedRevision != current + 1) return@coroutineScope written + ("moves" to listOf("card changed during claim; result retained"))
        val trail = ArrayList<String>()
        suspend fun move(to: BoardCol, rev: Long, key: String, extra: Map<String, Any?> = emptyMap()): BoardApply {
            val reply = CompletableDeferred<BoardApply>()
            store.intake.send(BoardIntake(mapOf("type" to "move", "jobId" to jobId, "idempotencyKey" to key, "expectedRevision" to rev, "toColumn" to to.wire) + extra, reply))
            val r = reply.await()
            trail += when (r) {
                is BoardApply.Committed -> "${to.wire} r${r.revision}"
                is BoardApply.Rejected -> "${to.wire} refused: ${r.reason}"
            }
            return r
        }
        when (decision.outcome) {
            PlaneJudge.Outcome.REVIEW -> move(BoardCol.REVIEW, retainedRevision, "$jobId#claim-review#$current", mapOf("actor" to JUDGE_ACTOR))
            PlaneJudge.Outcome.DONE -> {
                val r1 = move(BoardCol.REVIEW, retainedRevision, "$jobId#claim-review#$current", mapOf("actor" to JUDGE_ACTOR))
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
                if (block) move(BoardCol.BLOCKED, retainedRevision, "$jobId#judge-block#$current", mapOf("owner" to "", "strikes" to strike, "actor" to JUDGE_ACTOR))
                else move(BoardCol.READY, retainedRevision, "$jobId#judge-retry#$current", mapOf("strikes" to strike, "actor" to JUDGE_ACTOR))
            }
        }
        written + ("moves" to trail)
    }

    /** `mux.models#models[0].id` — the newest model Hermes ran here, or "" when the daemon offers none. */
    private suspend fun resolveModel(taskKind: String, explicit: String): Join<String, String?> {
        val models = runner("mux.models") ?: return explicit j null
        val out = try {
            models.run(LcncNode(id = "claim-models", type = "mux.models"), emptyMap())
        } catch (t: CancellationException) {
            throw t
        } catch (_: Throwable) {
            return explicit j null
        }
        val list = when (val m = out["models"]) {
            is List<*> -> m
            is Array<*> -> m.toList()
            else -> emptyList<Any?>()
        }
        val candidates = list.mapNotNull { it as? Map<*, *> }.toSeries()
        fun identity(value: Map<*, *>): Join<String, String?> =
            value["id"]?.toString().orEmpty() j (value["modelVersion"] as? String)
        if (explicit.isNotBlank()) return candidates.view.firstOrNull { it["id"] == explicit }?.let(::identity) ?: (explicit j null)
        val available = candidates.filter {
            it["quotaAvailable"] != false &&
                (InvokeLowering.listishOf(it["caps"])?.any { cap -> cap == "chat" } ?: true)
        }
        val grades = ArrayList<ModelGrade>()
        for (ref in store.receiptRefs().view) {
            val value = store.receipt(ref.id) ?: continue
            modelGrade(value, ref.sequence)?.takeIf { it.model.b != null }?.let { grades.add(it) }
        }
        val task = HARNESS j taskKind
        val evidence = rankModelProficiency(available, grades.toSeries(), task, "plane-evidence-v1", ::identity)
        val ranked = rankModelProficiency(evidence, grades.toSeries(), task, "human-correctness-v1", ::identity)
        return if (ranked.size == 0) "" j null else identity(ranked[0])
    }

    suspend fun restoreReceipts() {
        for (ref in store.receiptRefs().view) {
            val value = store.receipt(ref.id) ?: continue
            if (value["kind"] == "claim") blackboard.put(RECEIPT_PREFIX + ref.jobId,
                value.entries.associate { it.key.toString() to it.value } + ("receiptCid" to ref.cid.value), LANGUAGE)
        }
    }

    /** Called once before startup facts admit work; interrupted claims cannot disappear on restart. */
    suspend fun recoverClaims() {
        for (row in store.cards()) {
            if (row.col !in setOf(BoardCol.RUNNING, BoardCol.REVIEW) || !row.owner.startsWith(BoardStoreElement.CLAIM_OWNER_PREFIX) ||
                row.claimAttemptId.isBlank()) continue
            val attemptId = row.claimAttemptId
            var result = store.receipt("$attemptId/result")
            if (row.col == BoardCol.REVIEW) {
                val command = store.command(row.jobId)
                val key = command?.get("idempotencyKey") as? String ?: continue
                if (result?.get("decision") != PlaneJudge.Outcome.DONE.name || command?.get("actor") != JUDGE_ACTOR ||
                    !(key.startsWith("${row.jobId}#claim-review#") || key.startsWith("$attemptId/recover/review/"))) continue
                val reply = CompletableDeferred<BoardApply>()
                store.intake.send(BoardIntake(mapOf(
                    "type" to "move", "jobId" to row.jobId, "expectedRevision" to row.revision,
                    "idempotencyKey" to "$attemptId/recover/done/${row.revision}",
                    "toColumn" to BoardCol.DONE.wire, "actor" to JUDGE_ACTOR,
                ), reply))
                reply.await()
                continue
            }
            var revision = row.revision
            if (result == null) {
                val admission = store.receipt("$attemptId/admission")
                val ownedRevision = (admission?.get("claimRevision") as? Number)?.toLong()?.plus(1)
                    ?: attemptId.substringAfterLast('#').toLongOrNull()?.plus(1)
                val interrupted = linkedMapOf<String, Any?>(
                    "id" to "$attemptId/result", "kind" to "claim", "jobId" to row.jobId,
                    "attemptId" to attemptId, "owner" to row.owner, "revision" to ownedRevision,
                    "observedRevision" to row.revision,
                    "admissionId" to admission?.get("id"), "model" to admission?.get("model")?.toString().orEmpty(),
                    "modelVersion" to admission?.get("modelVersion"), "harness" to (admission?.get("harness") ?: HARNESS),
                    "taskKind" to (admission?.get("taskKind") ?: PlaneBrief.parseSpec(row.title, row.spec).taskKind),
                    "ok" to false, "error" to "claim interrupted before result retention",
                    "decision" to PlaneJudge.Outcome.RETRY.name, "atMs" to clock(),
                    "basis" to ModelGradeBasis.EVIDENCE, "score" to null,
                    "correctness" to ModelCorrectness.UNKNOWN.name, "disposition" to ModelDisposition.UNSELECTED.name,
                )
                val retained = store.recordReceipt(row.jobId, interrupted)
                check(retained is BoardApply.Committed) { "Interrupted claim receipt rejected: $retained" }
                result = interrupted
                if (retained.revision != row.revision + 1 || ownedRevision != row.revision) continue
                revision = retained.revision
            } else if ((result["revision"] as? Number)?.toLong()?.plus(1) != row.revision) continue
            suspend fun move(to: BoardCol, expected: Long, extra: Map<String, Any?> = emptyMap()): BoardApply {
                val reply = CompletableDeferred<BoardApply>()
                store.intake.send(BoardIntake(mapOf(
                    "type" to "move", "jobId" to row.jobId, "expectedRevision" to expected,
                    "idempotencyKey" to "$attemptId/recover/${to.wire}/$expected",
                    "toColumn" to to.wire, "actor" to JUDGE_ACTOR,
                ) + extra, reply))
                return reply.await()
            }
            when (result["decision"]) {
                PlaneJudge.Outcome.DONE.name -> {
                    val review = move(BoardCol.REVIEW, revision)
                    if (review is BoardApply.Committed) move(BoardCol.DONE, review.revision)
                }
                PlaneJudge.Outcome.RETRY.name -> {
                    val strikes = row.strikes + 1
                    val blocked = strikes >= BoardRules.REAPER_BLOCK_STRIKE
                    move(if (blocked) BoardCol.BLOCKED else BoardCol.READY, revision,
                        mapOf("strikes" to strikes, "owner" to if (blocked) "" else row.owner))
                }
                else -> move(BoardCol.REVIEW, revision)
            }
        }
    }

    private suspend fun receipt(
        jobId: String,
        owner: String,
        model: String,
        ok: Boolean,
        body: Pair<String, Any?>,
        revision: Long,
        facts: Int = 0,
        judged: Map<String, Any?> = emptyMap(),
        timing: Timing = Timing.NONE,
        attemptId: String? = null,
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
        if (attemptId != null) {
            value["id"] = "$attemptId/result"
            value["kind"] = "claim"
            value["jobId"] = jobId
            value["attemptId"] = attemptId
            val retained = store.recordReceipt(jobId, value)
            check(retained is BoardApply.Committed) { "Claim result receipt rejected: $retained" }
            value["receiptRevision"] = retained.revision
            value["receiptCid"] = retained.cid.value
        }
        blackboard.put(RECEIPT_PREFIX + jobId, value, LANGUAGE)
        return value
    }
}
