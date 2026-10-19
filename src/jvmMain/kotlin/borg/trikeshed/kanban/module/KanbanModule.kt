package borg.trikeshed.kanban.module

import borg.trikeshed.parse.reify
import borg.trikeshed.parse.jsonOf

import borg.trikeshed.job.ContentId
import borg.trikeshed.context.nuid.*
import borg.trikeshed.kanban.BoardActionElement
import borg.trikeshed.kanban.BoardObservationElement
import borg.trikeshed.kanban.BoardApply
import borg.trikeshed.lib.*
import borg.trikeshed.kanban.BoardIntake
import borg.trikeshed.kanban.BoardStoreElement
import borg.trikeshed.kanban.JvmBoardWal
import borg.trikeshed.lcnc.LcncKanbanExperience
import borg.trikeshed.lcnc.LcncNode
import borg.trikeshed.lcnc.LcncNodeRunner
import borg.trikeshed.lcnc.LcncBindingOrigins
import borg.trikeshed.lcnc.LcncBindingTerms
import borg.trikeshed.lcnc.boundLcnc
import borg.trikeshed.lcnc.LcncBlackboard
import borg.trikeshed.lcnc.LcncProgram
import borg.trikeshed.lcnc.LcncProgramConfix
import borg.trikeshed.lcnc.LcncTypeCheck
import borg.trikeshed.lcnc.ccek.*
import borg.trikeshed.litebike.JvmKanbanServer
import borg.trikeshed.module.ForgeModule
import borg.trikeshed.module.ModuleContext
import borg.trikeshed.module.ModuleHandle
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.lang.System
import kotlin.coroutines.CoroutineContext

/**
 * KanbanModule — THE dynamic module (D3): kanban end to end, assembled from
 * CCEK elements against the [ModuleContext], attached by default at boot and
 * upgradeable by detach → attach (state is the WAL, rebuilt on open).
 *
 * Claims (exact paths — shadowing the fossil parser is the point):
 *   GET  /api/board          WAL-backed live board (BoardCursor SoA → boundary map)
 *   POST /api/invoke         lowering → store intake → 202 with per-command results
 *   POST /api/board/import   tolerant plan-doc import → submit batch (partial > 500)
 *
 * No-arg proxy ctor: attachable by FQCN through POST /api/modules even when the
 * class arrived via hotswapFeed after the daemon booted.
 */
class KanbanModule : ForgeModule {
    override val id: String = "kanban"

    override suspend fun open(ctx: ModuleContext): ModuleHandle {
        val store = BoardStoreElement(
            wal = JvmBoardWal(File(ctx.stateDir, ".kanban")),
            cas = ctx.casStore,
            clock = ctx.clock,
            parentJob = ctx.scope.coroutineContext[Job],
        )
        var actionOwner: BoardActionElement? = null
        var observations: BoardObservationElement? = null
        var runService: LcncRunService? = null
        var runFanout: NuidFanoutElement? = null
        var runWorkgroups: LcncWorkgroupElement? = null
        val ruleDisposers = mutableListOf<AutoCloseable>()
        var retractFacts: (suspend () -> Unit)? = null
        var retractRunFacts: (suspend () -> Unit)? = null
        var releaseSink: (() -> Unit)? = null
        val runnerDisposers = mutableListOf<() -> Unit>()
        val lifecycle = Mutex()
        var shutdown: Result<Unit>? = null
        suspend fun cleanup(): Unit = withContext(NonCancellable) {
            lifecycle.withLock {
                shutdown?.let { it.getOrThrow(); return@withLock }
                val failures = mutableListOf<Throwable>()
                suspend fun release(action: suspend () -> Unit) {
                    try { action() } catch (failure: Throwable) { failures.add(failure) }
                }
                actionOwner?.stopPreparations()
                release { observations?.stopTicker() }
                // Release debugger holds while admitted routes can still enqueue their pinned work.
                release { runService?.stopAdmission() }
                release { actionOwner?.awaitPreparations() }
                release { runWorkgroups?.drainQueued() }
                release { runFanout?.stopAdmission() }
                release { runFanout?.drain() }
                release { runWorkgroups?.drain() }
                release { runService?.drain() }
                release {
                    // Projection completion can enqueue another action, whose commit needs projection.
                    do {
                        actionOwner?.awaitIdle()
                        val sequence = store.lastSequence
                        observations?.awaitSequence(sequence)
                        actionOwner?.awaitIdle()
                    } while (sequence != store.lastSequence)
                }
                release { actionOwner?.drain() }
                release { observations?.drain() }
                release { releaseSink?.invoke() }
                for (disposer in runnerDisposers.asReversed()) release { disposer() }
                for (disposer in ruleDisposers.asReversed()) release { disposer.close() }
                release { retractFacts?.invoke() }
                release { retractRunFacts?.invoke() }
                release { store.drain() }
                release { store.close() }
                release { store.failure?.let { throw it } }
                shutdown = if (failures.isEmpty()) Result.success(Unit) else Result.failure(
                    IllegalStateException("Kanban module cleanup failed", failures.first()).also { aggregate ->
                        failures.drop(1).forEach(aggregate::addSuppressed)
                    },
                )
                shutdown!!.getOrThrow()
            }
        }
        try {
        store.open()
        val actions = BoardActionElement(
            parentJob = ctx.scope.coroutineContext[Job],
            dependency = { runFanout?.currentInvocation() != null },
        ).also { actionOwner = it }
        actions.open()
        fun claim(moduleId: String, path: String, route: borg.trikeshed.module.ModuleRoute) {
            ctx.routes.claim(moduleId, path) { method, requested, text, respond ->
                try { actions.prepare { route(method, requested, text, respond) } }
                catch (_: BoardActionElement.AdmissionFailure) {
                    JvmKanbanServer.HttpResponse(503, """{"error":"board_admission_stopped"}""")
                }
            }
        }
        fun install(type: String, runner: LcncNodeRunner) {
            val previous = ctx.lcncRunners[type]
            val owned = object : LcncNodeRunner {
                override suspend fun execute(node: LcncNode, inputs: Map<String, Any?>): Map<String, Any?> =
                    actions.prepare { runner.execute(node, inputs) }
                override suspend fun sourceDebugging(node: LcncNode, inputs: Map<String, Any?>): Boolean =
                    actions.prepare { runner.sourceDebugging(node, inputs) }
                override fun outputSources(node: LcncNode, inputs: Map<String, Any?>): Map<String, String> =
                    runner.outputSources(node, inputs)
                override val isPure: Boolean get() = runner.isPure
                override fun retainedOutputs(node: LcncNode, inputs: Map<String, Any?>,
                                             retainedInputs: Map<String, Any?>, outputs: Map<String, Any?>): Map<String, Any?> =
                    runner.retainedOutputs(node, inputs, retainedInputs, outputs)
                override fun retainedOrigins(node: LcncNode, inputs: Map<String, Any?>,
                                             inputOrigins: Map<String, LcncBindingOrigins>, outputs: Map<String, Any?>,
                                             terms: LcncBindingTerms): Map<String, LcncBindingOrigins> =
                    runner.retainedOrigins(node, inputs, inputOrigins, outputs, terms)
            }
            ctx.lcncRunners[type] = owned
            runnerDisposers.add {
                if (ctx.lcncRunners[type] === owned) {
                    if (previous == null) ctx.lcncRunners.remove(type) else ctx.lcncRunners[type] = previous
                }
            }
        }
        fun install(registry: Map<String, LcncNodeRunner>) { registry.forEach { (type, runner) -> install(type, runner) } }
        val bag = ctx.beliefBag
        // kanban.alerts substrate: the sink retains each rule's last few
        // activations in a ring so the node answers from memory, bag OFF
        // included (rules fire without NARS). synchronized: the sink runs on
        // the commit path, the node on HTTP dispatch.
        val alerts = borg.trikeshed.kanban.BoardRuleAlertRing()
        fun alertTail(ruleId: String): List<Map<String, String>> = synchronized(alerts) {
            alerts.tail(ruleId).map { it.bindings + ("activationId" to it.activationId) }
        }
        fun alertMap(): Map<String, Any?> = mapOf(
            "breaches" to alertTail(borg.trikeshed.kanban.rules.BoardRules.WIP_BREACH),
            "stalls" to alertTail(borg.trikeshed.kanban.rules.BoardRules.STALL),
            "cycles" to alertTail(borg.trikeshed.kanban.rules.BoardRules.CYCLE_GUARD),
            "ready" to alertTail(borg.trikeshed.kanban.rules.BoardRules.DEPENDENCY_READY),
            // Delta 2026-09-05 (fan-out): the splits proposed and the parents parked behind a blocked child.
            "fanouts" to alertTail(borg.trikeshed.kanban.rules.BoardRules.FAN_OUT),
            "blocked" to alertTail(borg.trikeshed.kanban.rules.BoardRules.DEPENDENCY_BLOCKED),
        )
        // board.get / board.view / /api/board all read ONE projection: LcncKanbanExperience.boardView.
        val lcnc = LcncKanbanExperience(
            store,
            attention = if (bag == null) null else {
                { board ->
                    borg.trikeshed.kanban.BoardAttentionOrder.garnish(bag, board.cards())
                        .mapValues { (_, g) -> mapOf("attention" to g.score, "contested" to g.contested) }
                }
            },
            alerts = ::alertMap,
        )
        val lcncRegistry = lcnc.registry()
        install(lcncRegistry)
        val documentTasks = borg.trikeshed.lcnc.DocumentCurationTasks(ctx.casStore) { source ->
            val sourceId = source.metadata["evidenceId"]?.singleOrNull()
            if (sourceId == null) true else {
                val head = ctx.blackboard.snapshot().values["headhunter/$sourceId"] as? Map<*, *>
                val fields = head?.get("fields") as? Map<*, *>
                head?.get("cid") == source.metadata["evidenceCid"]?.singleOrNull() && fields?.get("deleted") != true &&
                    (fields?.get("text") as? String)?.let { ContentId.of(it.encodeToByteArray()) == source.extractedTextCid } == true
            }
        }
        install(documentTasks.registry(store))

        // ── NARS garnish (Phase 5): review bridge + attention order, iff the bag is live.
        //    Bag OFF ⇒ board JSON byte-identical minus the attention/contested fields.
        // NARS × kanban legos — the bag's board view as composable nodes:
        // kanban.attention = BoardAttentionOrder.garnish (per-card score/contested +
        // attention-descending order), kanban.drift = the Hotelling T² cohort alarm.
        if (bag != null) {
            install("kanban.attention", boundLcnc(store) { board, _, _ ->
                val g = borg.trikeshed.kanban.BoardAttentionOrder.garnish(bag, board.cards())
                mapOf(
                    "cards" to g.mapValues { (_, v) -> mapOf("attention" to v.score, "contested" to v.contested) },
                    "ordered" to g.entries.sortedByDescending { it.value.score }.map { it.key },
                )
            })
            install("kanban.drift", LcncNodeRunner { _, _ ->
                val t2 = borg.trikeshed.kanban.BoardAttentionOrder.driftT2(bag)
                mapOf("t2" to t2, "alarm" to (t2 > 9f))
            })
        }
        val bridge = if (bag != null && ctx.turnReview != null) {
            borg.trikeshed.kanban.BoardReviewBridge(
                review = ctx.turnReview!!,
                cardLookup = { store.card(it) },
                clock = ctx.clock,
            )
        } else null
        // ONE flush seam: every drain of the review window (threshold, periodic
        // tick, kanban.review node) lands its minted (angular, gloss) pairs as
        // blackboard receipts instead of dropping them. turnSucceeded defaults
        // inside the bridge to "no Fail commits since the last flush".
        suspend fun flushReview(): List<Pair<Long, String>> {
            val minted = bridge?.flush() ?: return emptyList()
            for ((angular, gloss) in minted) {
                ctx.blackboard.put("kanban/review/$angular", mapOf("gloss" to gloss), "kanban-nars")
            }
            return minted
        }
        // kanban.review = the TurnReview glosses, bag-gated beside kanban.attention.
        if (bridge != null) {
            install("kanban.review", LcncNodeRunner { _, _ ->
                val minted = flushReview()
                mapOf(
                    "minted" to minted.map { (angular, gloss) -> mapOf("angular" to angular, "gloss" to gloss) },
                    "count" to minted.size,
                )
            })
        }

        // Board JSON memo keyed by the commit watermark: rebuild only after a commit.
        // (Bag-on skips the memo: attention scores evolve independently of the board.)
        var cached: Pair<Long, String>? = null
        fun boardJson(): String {
            val seq = store.lastSequence
            if (bag == null) cached?.let { if (it.first == seq) return it.second }
            val json = jsonOf(lcnc.boardView())
            if (bag == null) cached = seq to json
            return json
        }

        // ── Rete: fact bridge + the board productions + activation sink ──
        //    (the four audit/flow rules, plus claim: the board proposes its own READY work,
        //     and reaper: a claim whose worker died goes back to READY — thrice, then BLOCKED;
        //     Delta 2026-09-05 (fan-out): fan-out splits a MODELS: card into child cards, and
        //     dependency-blocked parks a parent whose child struck out)
        val facts = borg.trikeshed.kanban.BoardFactElement(ctx.rete).also { retractFacts = it::retractAll }
        // What completed runs read, as facts in each project's partition (Forge genesis, Cut S).
        val runFacts = borg.trikeshed.lcnc.LcncRunFacts(ctx.rete).also { retractRunFacts = it::retractAll }
        fun register(production: borg.trikeshed.dag.ReteProduction): AutoCloseable =
            ctx.rete.register(production).also { ruleDisposers.add(it) }
        register(borg.trikeshed.kanban.rules.FanOutProduction())
        register(borg.trikeshed.kanban.rules.DependencyReadyProduction())
        register(borg.trikeshed.kanban.rules.DependencyBlockedProduction())
        register(borg.trikeshed.kanban.rules.ClaimProduction())
        register(borg.trikeshed.kanban.rules.WipBreachProduction())
        register(borg.trikeshed.kanban.rules.StallProduction())
        register(borg.trikeshed.kanban.rules.ReaperProduction(
            priorStrikes = { jobId -> borg.trikeshed.kanban.rules.ReaperProduction.countPriorStrikes(ctx.blackboard, jobId) },
        ))
        register(borg.trikeshed.kanban.rules.CycleGuardProduction())
        register(borg.trikeshed.lcnc.rules.RunStaleProduction())
        // kanban.alerts = the same tail board.view#alerts carries.
        install("kanban.alerts", LcncNodeRunner { _, _ -> alertMap() })
        // The claim worker: Move(RUNNING) → the daemon's brain → receipt → Move(REVIEW).
        // Runners are looked up at claim time (the daemon fills prompt.chat/mux.models
        // into ctx.lcncRunners after this module attaches). One worker per activation
        // id: a re-proposed claim (support invalidation) never doubles a brain call.
        val claimWorker = borg.trikeshed.kanban.BoardClaimWorker(
            store = store,
            blackboard = ctx.blackboard,
            runner = { ctx.lcncRunners[it] },
            clock = ctx.clock,
            // The brief is grounded in the plane: one snapshot per claim, selected by the card's terms.
            plane = { ctx.rete.snapshot().α { f -> borg.trikeshed.kanban.PlaneBrief.Row(f.factId.a, f.factId.b, f.fields) }.toList() },
            // The coding-agent lane: the daemon fills ctx.agentRuns after attach, like the runners.
            agents = { ctx.agentRuns },
            mintRunId = { java.util.UUID.randomUUID().toString() },
        )
        val claimsInFlight = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        // Delta 2026-09-05 (fan-out): the fan-out worker — child submits, READY moves, the
        // join, one receipt — next to the claim worker, same constructor, same runner lookup
        // (mux.models is filled in by the daemon after this module attaches). One worker per
        // activation id: a landing child un-refracts the rule and re-proposes the same split;
        // the in-flight set makes that a no-op while the worker runs.
        val fanOutWorker = borg.trikeshed.kanban.BoardFanOutWorker(
            store = store,
            blackboard = ctx.blackboard,
            runner = { ctx.lcncRunners[it] },
            clock = ctx.clock,
        )
        val fanOutsInFlight = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        // Non-job activations: receipt on the blackboard ALWAYS; dependency-ready also
        // lowers to a store Move with a derived idempotency key (dedupe compensates
        // any popped-but-unprocessed activation); claim hands the card to the worker.
        val previousSink = ctx.rete.productionSink
        val sink: (borg.trikeshed.dag.Activation) -> Unit = { a ->
            ctx.blackboard.put(
                "kanban/rule/${a.ruleId}/${a.activationId}",
                a.bindings + ("salience" to "${a.salience}"),
                "kanban-rete",
            )
            synchronized(alerts) { alerts.retain(a) }
            bridge?.onRuleFired(a)
            // run-stale (Forge genesis, Cut S): every firing folds into the run's one marker, a board-only
            // receipt (not admitted to the fact plane, so it never loops back into Rete).
            if (a.ruleId == borg.trikeshed.lcnc.rules.RunStaleProduction.RULE) {
                val runId = a.bindings["runId"]
                if (runId != null) {
                    val key = borg.trikeshed.lcnc.LcncStaleMarker.key(runId)
                    borg.trikeshed.lcnc.LcncStaleMarker.merge(ctx.blackboard.get(key), a.bindings, ctx.clock())?.let { marker ->
                        ctx.blackboard.put(key, marker, borg.trikeshed.lcnc.LcncStaleMarker.LANGUAGE)
                    }
                }
            }
            if (a.ruleId == borg.trikeshed.kanban.rules.BoardRules.DEPENDENCY_READY) {
                val jobId = a.bindings["jobId"]
                val rev = a.bindings["expectedRevision"]?.toLongOrNull()
                if (jobId != null && rev != null) {
                    actions.submit {
                        val reply = CompletableDeferred<BoardApply>()
                        store.intake.send(
                            BoardIntake(
                                mapOf(
                                    "type" to "move",
                                    "jobId" to jobId,
                                    "idempotencyKey" to "$jobId#${a.ruleId}#$rev",
                                    "expectedRevision" to rev,
                                    "toColumn" to a.bindings["toColumn"],
                                ), reply,
                            ),
                        )
                        reply.await()
                    }
                }
            }
            if (a.ruleId == borg.trikeshed.kanban.rules.BoardRules.CLAIM) {
                val jobId = a.bindings["jobId"]
                val rev = a.bindings["expectedRevision"]?.toLongOrNull()
                val owner = a.bindings["owner"] ?: borg.trikeshed.kanban.rules.BoardRules.CLAIM_OWNER
                if (jobId != null && rev != null && claimsInFlight.add(a.activationId)) {
                    actions.submit {
                        try {
                            val outcome = claimWorker.claim(jobId, rev, owner)
                            System.err.println("[KanbanModule] claim $jobId r$rev: ok=${outcome["ok"]} model=${outcome["model"]} ${outcome["review"]}")
                        } catch (t: kotlinx.coroutines.CancellationException) {
                            throw t
                        } catch (t: Throwable) {
                            System.err.println("[KanbanModule] claim $jobId r$rev failed: ${t.message}")
                            throw t
                        } finally {
                            claimsInFlight.remove(a.activationId)
                        }
                    } ?: run { claimsInFlight.remove(a.activationId) }
                }
            }
            // Delta 2026-09-05 (fan-out): a MODELS:/FANOUT: card in TODO/READY is split by the
            // worker — admitted to the owned stage: this sink runs under the Rete lock and
            // the worker awaits store replies that produce card facts. The activation id carries
            // the revision, so a re-proposal of a split already in flight is a no-op here.
            if (a.ruleId == borg.trikeshed.kanban.rules.BoardRules.FAN_OUT) {
                val jobId = a.bindings["jobId"]
                val rev = a.bindings["expectedRevision"]?.toLongOrNull()
                val models = a.bindings["models"].orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }
                val fanout = a.bindings["fanout"]?.toIntOrNull() ?: 0
                if (jobId != null && rev != null && fanOutsInFlight.add(a.activationId)) {
                    actions.submit {
                        try {
                            val outcome = fanOutWorker.fanOut(jobId, rev, models, fanout)
                            System.err.println("[KanbanModule] fan-out $jobId r$rev: ok=${outcome["ok"]} children=${outcome["children"]} ${outcome["error"] ?: ""}")
                        } catch (t: kotlinx.coroutines.CancellationException) {
                            throw t
                        } catch (t: Throwable) {
                            System.err.println("[KanbanModule] fan-out $jobId r$rev failed: ${t.message}")
                            throw t
                        } finally {
                            fanOutsInFlight.remove(a.activationId)
                        }
                    } ?: run { fanOutsInFlight.remove(a.activationId) }
                }
            }
            // Delta 2026-09-05 (fan-out, children that park): a TODO parent with a BLOCKED
            // dependency follows it into BLOCKED, signed by the plane judge and naming the
            // child — the same lowering shape as dependency-ready, derived key, dedupe
            // compensates a re-fire. Recovery is human; the receipt above says which child.
            if (a.ruleId == borg.trikeshed.kanban.rules.BoardRules.DEPENDENCY_BLOCKED) {
                val jobId = a.bindings["jobId"]
                val rev = a.bindings["expectedRevision"]?.toLongOrNull()
                if (jobId != null && rev != null) {
                    actions.submit {
                        val reply = CompletableDeferred<BoardApply>()
                        store.intake.send(
                            BoardIntake(
                                mapOf(
                                    "type" to "move",
                                    "jobId" to jobId,
                                    "idempotencyKey" to "$jobId#${a.ruleId}#$rev",
                                    "expectedRevision" to rev,
                                    "toColumn" to (a.bindings["toColumn"] ?: borg.trikeshed.kanban.BoardCol.BLOCKED.wire),
                                    "actor" to borg.trikeshed.kanban.BoardClaimWorker.JUDGE_ACTOR,
                                    "blockedBy" to a.bindings["blockedBy"],
                                ), reply,
                            ),
                        )
                        reply.await()
                    }
                }
            }
            // Reaper: strikes 1..2 hand the card back to READY (the claim production
            // takes the new revision afresh); the block strike parks it in BLOCKED with
            // the owner CLEARED — "owner" present and blank clears — so a human sees it.
            // The receipt above is the strike's durable record: the production counts
            // kanban/rule/reaper/* by jobId, so n is never carried in anybody's head.
            if (a.ruleId == borg.trikeshed.kanban.rules.BoardRules.REAPER) {
                val jobId = a.bindings["jobId"]
                val rev = a.bindings["expectedRevision"]?.toLongOrNull()
                val strike = a.bindings["strike"]?.toIntOrNull() ?: 1
                if (jobId != null && rev != null) {
                    val block = strike >= borg.trikeshed.kanban.rules.BoardRules.REAPER_BLOCK_STRIKE
                    val move = linkedMapOf<String, Any?>(
                        "type" to "move",
                        "jobId" to jobId,
                        "idempotencyKey" to "$jobId#${a.ruleId}#$rev",
                        "expectedRevision" to rev,
                        "toColumn" to if (block) borg.trikeshed.kanban.BoardCol.BLOCKED.wire else a.bindings["toColumn"],
                    )
                    if (block) move["owner"] = ""
                    actions.submit {
                        val reply = CompletableDeferred<BoardApply>()
                        store.intake.send(BoardIntake(move, reply))
                        val verdict = reply.await()
                        System.err.println("[KanbanModule] reaper $jobId r$rev strike $strike → ${move["toColumn"]}: $verdict")
                    }
                }
            }
        }

        ctx.rete.productionSink = sink
        releaseSink = { if (ctx.rete.productionSink === sink) ctx.rete.productionSink = previousSink }

        // The MCP lens onto this same asset (docs/marketability-kanban-mcp-audit.md,
        // KMFSM-004/005/006). It is handed the LCNC registry and a read port — never
        // the store — so an MCP write cannot become a second path to the intake.
        val receiptLog = borg.trikeshed.mcp.KanbanReceiptLog()
        val mcp = borg.trikeshed.mcp.LcncKanbanMcp(
            tools = lcncRegistry,
            reads = borg.trikeshed.mcp.BoardKanbanReadPort(store, lcnc, receiptLog),
        )

        // Receipts: every committed transition is blackboard-visible AND a Rete card fact.
        val observation = BoardObservationElement(
            store = store,
            seed = {
                receiptLog.seedFrom(store.cards())
                for (row in store.cards()) {
                    facts.onCommitted(
                        borg.trikeshed.kanban.BoardCommitted(
                            sequence = row.lastSequence,
                            jobId = row.jobId,
                            snapshot = borg.trikeshed.job.JobSnapshot(
                                jobId = borg.trikeshed.job.JobId(row.jobId),
                                revision = row.revision,
                                causalKey = "",
                                lifecycle = "replayed",
                                dependencies = row.dependencies.map { borg.trikeshed.job.JobId(it) },
                            ),
                            cid = ContentId.of("seed-${row.jobId}-${row.revision}".encodeToByteArray()),
                            command = borg.trikeshed.job.JobCommand.Acknowledge(
                                borg.trikeshed.job.JobId(row.jobId), "seed-${row.jobId}", row.revision,
                            ),
                            col = row.col,
                            previousCol = null,
                            lastMoveMs = row.lastMoveMs,
                            owner = row.owner,
                            // Delta 2026-09-05 (fan-out): the seed fact carries the spec and the
                            // tree edge, so a MODELS: card submitted before a restart (or before a
                            // brain was attached) is split from cold; without them the seed would
                            // read models=[] / parent="" and the rule would never fire. atMs stays
                            // 0: there is no committing command to stamp, and lastMoveMs is a
                            // different clock (the transition, not the commit).
                            spec = row.spec,
                            parent = row.parent,
                            executionFamily = row.executionFamily,
                        ),
                    )
                }
            },
            committed = { ev ->
                receiptLog.record(ev)
                ctx.blackboard.put(
                    "kanban/committed/${ev.jobId}/${ev.sequence}",
                    mapOf(
                        "op" to ev.command.operationName,
                        "col" to ev.col.wire,
                        "from" to (ev.previousCol?.wire ?: ""),
                        "revision" to ev.snapshot.revision.toString(),
                        "cid" to ev.cid.value,
                        // Delta 2026-09-05 (receipt timing): the store's stamp on the committing
                        // command (0 = no stamp: a seed or a test — the page prints t=?, never
                        // epoch 0) and the owner after the commit, so a witness surface shows
                        // WHEN and WHOSE each hop was without a second read of the row table.
                        "atMs" to ev.atMs,
                        "owner" to ev.owner,
                    ),
                    "kanban-module",
                )
                facts.onCommitted(ev)
                bridge?.let { b ->
                    b.onCommitted(ev)
                    if (b.pendingCount >= 12) flushReview()
                }
            },
            tick = {
                facts.tick(ctx.clock())
                flushReview()
                if (bag != null) {
                    val t2 = borg.trikeshed.kanban.BoardAttentionOrder.driftT2(bag)
                    if (t2 > 9f) { // χ²-ish alarm line: the board cohort has drifted from the field
                        ctx.blackboard.put(
                            "kanban/drift/${ctx.clock()}",
                            mapOf("t2" to "$t2"),
                            "kanban-nars",
                        )
                    }
                }
            },
            parentJob = ctx.scope.coroutineContext[Job],
        ).also { observations = it }
        observation.open()

        claim(id, "/api/board") { method, path, _, _ ->
            if (method != "GET") return@claim JvmKanbanServer.HttpResponse(405, """{"error":"method_not_allowed"}""")
            val query = borg.trikeshed.relaxfactory.CouchHttpSurface.parseQuery(path.substringAfter('?', ""))
            val taskId = query["documentTask"] ?: return@claim JvmKanbanServer.HttpResponse(200, boardJson())
            try {
                val view = query["view"]
                require(view == null || view == "receipt") { "Unknown document task view" }
                val task = documentTasks.inspect(store, taskId, includeReceipt = view == "receipt")
                    ?: return@claim JvmKanbanServer.HttpResponse(404, """{"error":"document_task_not_found"}""")
                if (query["receiptCid"] != null && query["receiptCid"] != task["receiptCid"])
                    return@claim JvmKanbanServer.HttpResponse(409, """{"error":"document_task_receipt_changed"}""")
                @Suppress("UNCHECKED_CAST")
                val program = if (task["kind"] == "document-proposal-review") null else borg.trikeshed.lcnc.LcncWorkspacePresets.correction(taskId,
                    task.getValue("revision").toString(), task.getValue("receiptCid") as String,
                    task.getValue("correctionSource") as Map<String, Any?>)
                val body = jsonOf(if (view == "receipt" || program == null) task else task + ("document" to
                    reify(borg.trikeshed.lcnc.LcncProgramConfix.toJson(program))))
                if (body.encodeToByteArray().size > borg.trikeshed.lcnc.WorkspaceFacets.MAX_BYTES)
                    JvmKanbanServer.HttpResponse(413, """{"error":"document_task_payload_limit"}""")
                else JvmKanbanServer.HttpResponse(200, body)
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) {
                JvmKanbanServer.HttpResponse(400, jsonOf(mapOf("error" to "document_task_unavailable", "detail" to e.message)))
            }
        }

        claim(id, "/api/invoke") { method, _, text, _ ->
            if (method != "POST") JvmKanbanServer.HttpResponse(405, """{"error":"method_not_allowed"}""")
            else invoke(store, text)
        }

        claim(id, "/api/board/import") { method, _, text, _ ->
            if (method != "POST") JvmKanbanServer.HttpResponse(405, """{"error":"method_not_allowed"}""")
            else import(store, text)
        }

        // KMFSM-006: MCP mounted in the daemon's own lifecycle — one server, one
        // board, no second process. Streamable HTTP's POST half: one JSON-RPC
        // document in, one out; a notification (no id) gets 202 and no body.
        // GET is answered with the handshake a client would otherwise have to
        // POST for, so `curl /api/mcp` is a useful thing to type.
        claim(id, "/api/mcp") { method, _, text, _ ->
            when (method) {
                // Streamable HTTP lets a client GET this path to open a
                // server-initiated SSE stream. We do not offer one — which is
                // exactly what `resources.subscribe: false` advertises — and the
                // transport says a server without that stream MUST answer 405.
                // Returning the human-readable card to a client that asked for
                // `text/event-stream` hands it JSON where it expects a stream,
                // which is a hang or a parse error rather than a clean refusal.
                // So: negotiate. curl gets the card, a client gets the 405.
                "GET" -> if (wantsEventStream(text)) JvmKanbanServer.HttpResponse(
                    405,
                    """{"error":"method_not_allowed","reason":"this server offers no GET event stream; POST JSON-RPC to this path"}""",
                ) else JvmKanbanServer.HttpResponse(
                    200,
                    jsonOf(
                        mapOf(
                            "server" to borg.trikeshed.mcp.LcncKanbanMcp.SERVER_NAME,
                            "protocolVersions" to borg.trikeshed.mcp.LcncKanbanMcp.SUPPORTED_PROTOCOLS,
                            "transport" to "POST this path with a JSON-RPC 2.0 document",
                            "tools" to listOf(
                                borg.trikeshed.mcp.LcncKanbanMcp.TOOL_SUBMIT,
                                borg.trikeshed.mcp.LcncKanbanMcp.TOOL_MOVE,
                            ),
                            "resources" to listOf(
                                borg.trikeshed.mcp.LcncKanbanMcp.URI_SCHEMA,
                                borg.trikeshed.mcp.LcncKanbanMcp.URI_SHEETS,
                                "${borg.trikeshed.mcp.LcncKanbanMcp.URI_CARD_PREFIX}{jobId}",
                                "${borg.trikeshed.mcp.LcncKanbanMcp.URI_RECEIPT_PREFIX}{sequence}",
                            ),
                        ),
                    ),
                )

                "POST" -> when (val reply = mcp.handle(rawBody(text))) {
                    "" -> JvmKanbanServer.HttpResponse(202, "")
                    else -> JvmKanbanServer.HttpResponse(200, reply)
                }

                else -> JvmKanbanServer.HttpResponse(405, """{"error":"method_not_allowed"}""")
            }
        }

        claim(id, "/api/lcnc/kanban") { method, _, _, _ ->
            if (method != "GET") JvmKanbanServer.HttpResponse(405, """{"error":"method_not_allowed"}""")
            else JvmKanbanServer.HttpResponse(200, jsonOf(lcnc.activeSheets()))
        }

        // /panels — the concentric construction canvas (revived editor) — is a
        // PAGE: JvmKanbanServer serves it from staticAssets (ModuleRouteRegistry
        // is exact /api/* by discipline). The server-rendered swimlane projection
        // moved to /api/lcnc/concentric; vocabulary hydrates from
        // /api/lcnc/contracts; constructions are stored via /api/panels.

        // Everything LCNC ON THE BLACKBOARD, by the one writer (LcncPublisher):
        // `lcnc/vocabulary` and `lcnc/program/<name>` with every cable typed and
        // every violation beside it. The routes below serve what is on the board.
        // The same ReteNetwork and KIF bank the daemon's publisher holds: the panels plane and
        // the vocabulary tuples come from one network and one bank however many publishers exist.
        val publisher = borg.trikeshed.lcnc.LcncPublisher(ctx.blackboard, { ctx.lcncRunners }, ctx.attachments, ctx.rete, ctx.kifBank)
        val runs = LcncRunService(ctx, store, runFacts, publisher::vocabulary).also { runService = it }
        val workspace = WorkspaceService(ctx, publisher::vocabulary)
        claim(id, "/api/lcnc/workspace") { method, _, text, _ -> workspace.route(method, rawBody(text)) }
        val archives = ArchiveService(ctx.casStore)
        // Three literal claims, not a loop over an interpolated path: the route-manifest parity
        // scan reads literals, and an interpolation is a route the gate cannot see.
        claim(id, "/api/archives/import") { method, path, text, _ -> archives.route(method, path, rawBody(text)) }
        claim(id, "/api/archives/manifest") { method, path, text, _ -> archives.route(method, path, rawBody(text)) }
        claim(id, "/api/archives/content") { method, path, text, _ -> archives.route(method, path, rawBody(text)) }
        runs.recover()
        val fanout = NuidFanoutElement(parentJob = ctx.scope.coroutineContext[Job]).also { runFanout = it }
        fanout.open()
        suspend fun releaseClaim(claim: NuidFanoutElement.Claim) {
            @Suppress("UNCHECKED_CAST")
            val entry = claim.payload as? LcncRunQueueEntry ?: return
            runs.releaseQueued(entry)
        }
        suspend fun executeClaim(claim: NuidFanoutElement.Claim, cid: ContentId): JvmKanbanServer.HttpResponse {
            @Suppress("UNCHECKED_CAST")
            val entry = requireNotNull(claim.payload as? LcncRunQueueEntry) { "LCNC queued run is required" }
            val selected = checkNotNull(currentCoroutineContext()[NuidInvocation]) { "LCNC workgroup invocation is absent" }
            return withContext(entry.b + currentCoroutineContext().get(Job)!! + fanout + selected.context) {
                try {
                    if (runs.queuedReceipt(entry)["programCid"] != cid.value)
                        runs.failQueued(entry, 409, "workgroup_program_changed")
                    else runs.executeQueued(entry, selected.value)
                } catch (failure: LcncRunService.QueueFailure) {
                    JvmKanbanServer.HttpResponse(failure.status, jsonOf(mapOf("ok" to false, "error" to failure.code)))
                }
            }
        }
        fanout.activate()
        lateinit var workgroups: LcncWorkgroupElement
        fun completed(response: JvmKanbanServer.HttpResponse): Deferred<Any?> =
            CompletableDeferred<Any?>().also { it.complete(response) }
        suspend fun dispatchQueued(entry: LcncRunQueueEntry): Deferred<Any?> {
            var reserved = entry
            var transferred = false
            try {
                reserved = runs.reserveQueued(entry)
                val receipt = runs.queuedReceipt(reserved)
                val cid = ContentId(receipt["programCid"] as String)
                val source = withContext(Dispatchers.IO) { checkNotNull(ctx.casStore.get(cid)) }
                check(ContentId.of(source) == cid) { "Queued program identity mismatch" }
                val program = LcncProgramConfix.fromJson(receipt["program"] as String, source.decodeToString())
                val violations = LcncTypeCheck.check(program, publisher.vocabulary(), strict = false)
                if (violations.isNotEmpty()) return completed(runs.failQueued(reserved, 400, "type_check_failed",
                    "validation", mapOf("violations" to violations.take(128).map { it.toMap() })))
                val declared = receipt["queueDeclaredSubnet"] as? String
                val request = receipt["queueRequest"] as Map<*, *>
                val subnet = Subnet.parse(request["subnet"] as String)
                workgroups.pin(program, cid, Subnet.parse(declared ?: "local"))
                val capability = Capability.Custom("lcnc", "program/${program.name}/${cid.value}")
                val admitted = withContext(reserved.b + currentCoroutineContext().get(Job)!! + fanout) {
                    fanout.dispatch(nuid(capability, Nonce.RandomBytes(), subnet), reserved)
                }
                if (admitted.winner == null) return completed(runs.failQueued(reserved, 404, "no_workgroup"))
                val completion = checkNotNull(admitted.completion) { "Queued workgroup has no executor" }
                transferred = true
                return completion
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: LcncRunService.QueueFailure) {
                return completed(runs.failQueued(reserved, failure.status, failure.code))
            } catch (failure: Exception) {
                return completed(runs.failQueued(reserved, 400, (failure.message ?: "queued_dispatch_failed").take(2048)))
            } finally {
                if (!transferred) withContext(NonCancellable) { runs.releaseQueued(reserved) }
            }
        }
        workgroups = LcncWorkgroupElement(
            ctx.blackboard, fanout, publisher::vocabulary,
            bind = { geometry, program, cid ->
                check(ctx.casStore.put(LcncProgramConfix.toJson(program).encodeToByteArray()) == cid)
                fanout.register(geometry, cleanup = ::releaseClaim) { claim -> executeClaim(claim, cid) }
            },
            parentJob = ctx.scope.coroutineContext[Job],
            dispatchQueued = ::dispatchQueued,
        )
        runWorkgroups = workgroups
        workgroups.open()
        workgroups.recover(runs.queuedRecovery().view.asSequence())
        suspend fun queuedResponse(
            prepare: suspend () -> Join<JvmKanbanServer.HttpResponse?, LcncRunQueueEntry?>,
        ): JvmKanbanServer.HttpResponse {
            val completion = try {
                workgroups.prepare {
                    val prepared = prepare()
                    prepared.a?.let(::completed) ?: workgroups.queue(checkNotNull(prepared.b))
                }
            } catch (_: IllegalStateException) {
                return JvmKanbanServer.HttpResponse(503, """{"error":"workgroup_unavailable"}""")
            }
            return checkNotNull(completion.await() as? JvmKanbanServer.HttpResponse)
        }
        suspend fun execute(
            name: String, program: borg.trikeshed.lcnc.LcncProgram, named: Boolean,
            inputs: Map<String, Any?>, request: Map<*, *>,
        ): JvmKanbanServer.HttpResponse {
            val declared = if (named) program.controls.subnet else null
            val subnet = if (request.containsKey("subnet")) request["subnet"] as? String else declared ?: "local"
            if (subnet == null || subnet.isBlank() || subnet.length > 256 ||
                subnet.split('.').any { part -> part.isEmpty() || part.any { it.isWhitespace() || it.isISOControl() } })
                return JvmKanbanServer.HttpResponse(400, """{"error":"invalid_subnet"}""")
            val dependency = fanout.currentInvocation() != null
            if (declared != null && !dependency && workgroups.state < borg.trikeshed.context.ElementState.DRAINING) try {
                workgroups.refresh()
            } catch (_: IllegalStateException) {
                if (workgroups.state < borg.trikeshed.context.ElementState.DRAINING)
                    return JvmKanbanServer.HttpResponse(503, """{"error":"workgroup_unavailable"}""")
            }
            val scope = Subnet.parse(declared ?: "local")
            val target = Subnet.parse(subnet)
            if (!(scope contains target) || target.level - scope.level > fanout.escalationBudget)
                return JvmKanbanServer.HttpResponse(404, """{"error":"no_workgroup"}""")
            if (declared != null && !dependency && workgroups.registrations.view.none { it.b.a == name })
                return JvmKanbanServer.HttpResponse(404, """{"error":"no_workgroup"}""")
            return queuedResponse {
                runs.enqueue(name, program, named, inputs, request + ("subnet" to subnet))
            }
        }
        runCatching { publisher.publishAll() }
            .onFailure {
                if (it is kotlinx.coroutines.CancellationException) throw it
                System.err.println("[KanbanModule] lcnc → blackboard publish failed: ${it.message}")
            }

        fun workgroupView(): Map<String, Any?> = mapOf(
            "state" to workgroups.state.name,
            "workgroups" to (workgroups.registrations α { registration -> mapOf(
                "name" to registration.a.name, "scope" to registration.a.scope.toString(),
                "program" to registration.b.a, "programCid" to registration.b.b.value,
                "capability" to "lcnc/program/${registration.b.a}",
            ) }).toList(),
            "refusals" to workgroups.refusals,
            "inventory" to (fanout.workgroups() α { mapOf("name" to it.name, "scope" to it.scope.toString()) }).toList(),
        )
        claim(id, "/api/lcnc/workgroups") { method, _, _, _ ->
            if (method != "GET") return@claim JvmKanbanServer.HttpResponse(405, """{"error":"method_not_allowed"}""")
            try { workgroups.refresh() } catch (_: IllegalStateException) {
                return@claim JvmKanbanServer.HttpResponse(503, """{"error":"workgroup_unavailable"}""")
            }
            JvmKanbanServer.HttpResponse(200, jsonOf(workgroupView()))
        }
        val network = borg.trikeshed.forge.server.LcncDhtWire(
            ctx.scope.coroutineContext[borg.trikeshed.ipns.IpnsNode], workgroups, ctx.muxContext,
        )
        for (path in borg.trikeshed.forge.server.LcncDhtWire.paths.view) claim(id, path) { method, requested, text, _ ->
            checkNotNull(network.route(method, requested, text))
        }
        claim(id, "/api/lcnc/concentric") { method, _, _, _ ->
            if (method != "GET") return@claim JvmKanbanServer.HttpResponse(405, """{"error":"method_not_allowed"}""")
            try { workgroups.refresh() } catch (_: IllegalStateException) {
                return@claim JvmKanbanServer.HttpResponse(503, """{"error":"workgroup_unavailable"}""")
            }
            val programs = ctx.blackboard.snapshot().values.entries.mapNotNull { (key, entry) ->
                if (!key.startsWith(LcncBlackboard.PROGRAM_PREFIX)) null
                else LcncBlackboard.documentJsonOf(entry)?.let { key.removePrefix(LcncBlackboard.PROGRAM_PREFIX) to it }
            }
            val surface = borg.trikeshed.lcnc.ConcentricSurface.render(
                contracts = publisher.vocabulary().values.toList(), programs = programs,
            )
            JvmKanbanServer.HttpResponse(200, jsonOf(surface + workgroupView()))
        }

        claim(id, "/api/lcnc/contracts") { method, _, _, _ ->
            if (method != "GET") JvmKanbanServer.HttpResponse(405, """{"error":"method_not_allowed"}""")
            else JvmKanbanServer.HttpResponse(200, jsonOf(publisher.publishVocabulary()))
        }

        // Rebuild (Forge genesis, Cut S): re-execute a completed receipt's exact program version with its
        // recorded inputs, budgets and pinned subprogram versions; the new receipt names the old as
        // rebuildOf and, once complete, the old run's consumed facts and stale marker are retired.
        claim(id, "/api/lcnc/run/rebuild") { method, _, text, _ ->
            if (method != "POST") return@claim JvmKanbanServer.HttpResponse(405, """{"error":"method_not_allowed"}""")
            val req = runCatching { reify(rawBody(text)) as? Map<*, *> }.getOrNull()
                ?: return@claim JvmKanbanServer.HttpResponse(400, """{"error":"bad_json"}""")
            val wantedRun = req["runId"]?.toString()?.takeIf { it.isNotBlank() }
            val wantedCid = req["receiptCid"]?.toString()?.takeIf { it.isNotBlank() }
            @Suppress("UNCHECKED_CAST")
            val receipt = (when {
                wantedRun != null -> ctx.blackboard.get("lcnc/run/$wantedRun")
                wantedCid != null -> ctx.blackboard.keys().filter { it.startsWith("lcnc/run/") }.map { ctx.blackboard.get(it) }
                    .firstOrNull { (it as? Map<*, *>)?.get("receiptCid") == wantedCid }
                else -> null
            } as? Map<String, Any?>) ?: return@claim JvmKanbanServer.HttpResponse(404, """{"error":"no_such_receipt"}""")
            val identity = runCatching { ContentId(receipt["receiptCid"] as? String ?: "") }.getOrNull()
                ?: return@claim JvmKanbanServer.HttpResponse(422, """{"error":"receipt_identity_missing"}""")
            if (wantedCid != null && wantedCid != identity.value)
                return@claim JvmKanbanServer.HttpResponse(409, """{"error":"receipt_identity_mismatch"}""")
            queuedResponse { runs.enqueueRebuild(identity) }
        }
        claim(id, "/api/lcnc/run/cancel") { method, _, text, _ ->
            if (method != "POST") return@claim JvmKanbanServer.HttpResponse(405, """{"error":"method_not_allowed"}""")
            val req = runCatching { reify(rawBody(text)) as? Map<*, *> }.getOrNull()
            val cancelled = req?.get("runId")?.toString()?.let(runs::cancel) ?: false
            JvmKanbanServer.HttpResponse(if (cancelled) 202 else 404, jsonOf(mapOf("ok" to cancelled)))
        }
        claim(id, "/api/lcnc/run/debug") { method, path, text, _ ->
            runs.debug(method, path, rawBody(text))
        }

        // The same vocabulary as a GRAPH: kinds, ports, and the binding edges,
        // as Turtle — queryable, countable, and the form LcncRdf's doc comment
        // promised for SHACL-minded readers.
        claim(id, "/api/lcnc/ontology") { method, _, _, _ ->
            if (method != "GET") JvmKanbanServer.HttpResponse(405, """{"error":"method_not_allowed"}""")
            else {
                val lb = publisher.lateBound()
                val triples = borg.trikeshed.lcnc.LcncRdf.ontology(lb.vocabulary.values.toList()) + borg.trikeshed.lcnc.LcncRdf.bindings(lb.bindings)
                JvmKanbanServer.HttpResponse(200, borg.trikeshed.lcnc.LcncRdf.turtle(triples) + "\n", contentType = "text/turtle; charset=utf-8")
            }
        }

        // The TUPLES themselves, verbatim: the vocabulary, the corpus's wires and
        // the bindings as a .kif file — what LcncFacts.parse reads back.
        claim(id, "/api/lcnc/facts") { method, _, _, _ ->
            if (method != "GET") JvmKanbanServer.HttpResponse(405, """{"error":"method_not_allowed"}""")
            else JvmKanbanServer.HttpResponse(200, publisher.lateBound().facts.toKifFile() + "\n", contentType = "text/plain; charset=utf-8")
        }

        claim(id, "/api/lcnc/treeshake") { method, _, text, _ ->
            if (method != "POST") return@claim JvmKanbanServer.HttpResponse(405, """{"error":"method_not_allowed"}""")
            val req = runCatching { reify(rawBody(text)) as? Map<*, *> }.getOrNull()
                ?: return@claim JvmKanbanServer.HttpResponse(400, """{"error":"bad_json"}""")
            val programData = req["program"]
                ?: return@claim JvmKanbanServer.HttpResponse(400, """{"error":"program_required"}""")
            val program = runCatching {
                borg.trikeshed.lcnc.LcncProgramConfix.fromJson("treeshake", jsonOf(programData))
            }.getOrElse {
                return@claim JvmKanbanServer.HttpResponse(400, jsonOf(mapOf("error" to "bad_program", "detail" to (it.message ?: ""))))
            }
            val result = try {
                val options = borg.trikeshed.lcnc.LcncTreeShakeOptions.fromMap(req["options"] as? Map<*, *>)
                borg.trikeshed.lcnc.LcncMating.treeshake(program, options, publisher.vocabulary())
            } catch (e: IllegalArgumentException) {
                return@claim JvmKanbanServer.HttpResponse(400, jsonOf(mapOf("error" to "bad_selection", "detail" to e.message)))
            }
            JvmKanbanServer.HttpResponse(200, jsonOf(result.toMap()))
        }

        claim(id, "/api/lcnc/content") { method, path, _, _ ->
            if (method != "GET") return@claim JvmKanbanServer.HttpResponse(405, """{"error":"method_not_allowed"}""")
            val query = borg.trikeshed.relaxfactory.CouchHttpSurface.parseQuery(path.substringAfter('?', ""))
            runs.content(query["cid"], query["view"], query["key"])
        }

        // The RUN HEAD (AutoTools, Cut B): which recorded run is the build of (a program's current
        // version, a canonical set of inputs), what lamp it burns, and which documents moved under
        // it. A page's `lcnc-run` block asks this and paints the answer; the decision itself lives
        // in commonMain (LcncRunHead) so the page, this route and a curl reader cannot disagree.
        // ModuleRouteRegistry claims are EXACT paths with the query stripped, so /api/lcnc/runs is
        // its own key and neither it nor /api/lcnc/run shadows the other.
        // The activity ring's drain: events after ?since= (a sequence) and node rows past ?nodes=.
        claim(id, "/api/lcnc/trail") { method, path, _, _ ->
            if (method != "GET") return@claim JvmKanbanServer.HttpResponse(405, """{"error":"method_not_allowed"}""")
            val query = borg.trikeshed.relaxfactory.CouchHttpSurface.parseQuery(path.substringAfter('?', ""))
            JvmKanbanServer.HttpResponse(200, jsonOf(borg.trikeshed.lcnc.LcncTrail.live.since(
                query["since"]?.toLongOrNull() ?: 0L, query["nodes"]?.toIntOrNull() ?: 0,
                query["epoch"]?.toLongOrNull() ?: 0L)))
        }

        claim(id, "/api/lcnc/runs") { method, path, _, _ ->
            if (method != "GET") return@claim JvmKanbanServer.HttpResponse(405, """{"error":"method_not_allowed"}""")
            val query = borg.trikeshed.relaxfactory.CouchHttpSurface.parseQuery(path.substringAfter('?', ""))
            runs.head(query["program"], query["inputs"], query["show"])
        }

        // The generic runner dispatch: ONE execution author. The browser (and any
        // client) posts {type, params?, inputs?} to run ONE node (a job), or
        // {program, inputs?} to run a WHOLE stored program (a procedure — spec
        // §6) with subprogram recursion via ctx.programLoader. Either way the
        // execution happens IN the daemon against the composed ctx.lcncRunners
        // registry — the very map webhook node dispatch resolves. Looked up at
        // request time so late-attached module registries (ace nodes, future
        // modules) are reachable too.
        claim(id, "/api/lcnc/run") { method, _, text, _ ->
            if (method != "POST") return@claim JvmKanbanServer.HttpResponse(405, """{"error":"method_not_allowed"}""")
            // A run may carry its input inline (a whole book as a text value): no body size is refused here.
            val body = rawBody(text)
            val requestDoc = runCatching { borg.trikeshed.parse.confix.confixDoc(body.encodeToByteArray(), borg.trikeshed.parse.confix.Syntax.JSON) }.getOrNull()
                ?: return@claim JvmKanbanServer.HttpResponse(400, """{"error":"bad_json"}""")
            val case = try { borg.trikeshed.lcnc.lcncCaseRequest(requestDoc) }
            catch (_: IllegalArgumentException) { return@claim JvmKanbanServer.HttpResponse(400, """{"error":"invalid_case_request"}""") }
            catch (_: borg.trikeshed.lcnc.LcncCaseException) { return@claim JvmKanbanServer.HttpResponse(400, """{"error":"invalid_case_request"}""") }
            val raw = runCatching { reify(body) as? Map<*, *> }.getOrNull()
                ?: return@claim JvmKanbanServer.HttpResponse(400, """{"error":"bad_json"}""")
            borg.trikeshed.parse.ValueBudget().violation(raw)?.let { limit ->
                return@claim JvmKanbanServer.HttpResponse(413, jsonOf(mapOf("error" to limit)))
            }
            val req = if (case == null) raw else raw + ("case" to case)
            @Suppress("UNCHECKED_CAST")
            if (req.containsKey("inputs") && req["inputs"] !is Map<*, *>)
                return@claim JvmKanbanServer.HttpResponse(400, """{"error":"inputs_must_be_object"}""")
            val inputs = (req["inputs"] as? Map<*, *>)?.entries
                ?.associate { (k, v) -> k.toString() to v } ?: emptyMap<String, Any?>()
            val programName = req["program"]?.toString()
            if (programName != null) {
                val program = ctx.programLoader(programName)
                    ?: return@claim JvmKanbanServer.HttpResponse(
                        404, jsonOf(mapOf("error" to "no_such_program", "program" to programName)),
                    )
                return@claim execute(programName, program, true, inputs, req)
            }
            // Inline ring: the panels canvas posts a scope's children as a
            // whole document — {name?, document, inputs} — and the ring runs
            // under the same frame nesting as a stored program. The document
            // parses through LcncProgramConfix (ALL DATA IS CONFIX); a bad
            // shape is a loud 400, never a silent flat sweep.
            val document = req["document"]
            if (document != null) {
                val label = req["name"]?.toString()?.takeIf { it.isNotBlank() } ?: "inline"
                val program = runCatching {
                    borg.trikeshed.lcnc.LcncProgramConfix.fromJson(label, jsonOf(document))
                }.getOrElse { e ->
                    return@claim JvmKanbanServer.HttpResponse(400, jsonOf(mapOf(
                        "error" to "bad_document", "detail" to (e.message ?: e.toString()),
                    )))
                }
                return@claim execute(label, program, false, inputs, req)
            }
            if (case != null) return@claim JvmKanbanServer.HttpResponse(400, """{"error":"case_requires_program"}""")
            if (req.containsKey("subnet")) return@claim JvmKanbanServer.HttpResponse(400,
                """{"error":"subnet_requires_program"}""")
            if (req.containsKey("sourceReceiptCid")) return@claim JvmKanbanServer.HttpResponse(400,
                """{"error":"source_receipt_requires_program"}""")
            if (req.containsKey("pulse") || req.containsKey("baseReceiptCid")) return@claim JvmKanbanServer.HttpResponse(400,
                """{"error":"pulse_requires_program"}""")
            if (req.containsKey("debugToken")) return@claim JvmKanbanServer.HttpResponse(400,
                """{"error":"debug_requires_program"}""")
            val type = req["type"]?.toString()
                ?: return@claim JvmKanbanServer.HttpResponse(400, """{"error":"type_required"}""")
            val runner = ctx.lcncRunners[type]
                ?: return@claim JvmKanbanServer.HttpResponse(
                    404, jsonOf(mapOf("error" to "no_runner", "type" to type)),
                )
            val rawParams = req["params"] as? Map<*, *>
            if (type == "panel.vote" && rawParams != null && "quorum" in rawParams && rawParams["quorum"] == null)
                return@claim JvmKanbanServer.HttpResponse(400, jsonOf(mapOf("ok" to false, "type" to type, "error" to "Invalid panel quorum")))
            val params = rawParams?.entries
                ?.associate { (k, v) -> k.toString() to (v?.toString() ?: "") } ?: emptyMap()
            val node = LcncNode(id = "http-run", type = type, params = params)
            // Match whole-program service precedence without borrowing a module's owning Job.
            val hostContext = ctx.scope.coroutineContext.minusKey(Job) +
                ctx.muxContext.minusKey(Job) + currentCoroutineContext()
            runCatching { withContext(hostContext) { runner.run(node, inputs) } }.fold(
                onSuccess = { out ->
                    JvmKanbanServer.HttpResponse(200, jsonOf(mapOf("ok" to true, "type" to type, "outputs" to out)))
                },
                onFailure = { e ->
                    JvmKanbanServer.HttpResponse(
                        400,
                        jsonOf(mapOf("ok" to false, "type" to type, "error" to (e.message ?: e.toString()))),
                    )
                },
            )
        }

        // Council case read-back: a thin veneer over the council.case runner
        // (blackboard/couch index → CAS transcript+verdict bytes), so this
        // module stays dependency-free. ModuleRouteRegistry claims are EXACT
        // paths — no /{caseId} segment is claimable — so the case id rides
        // the query string: GET /api/lcnc/council?caseId=<id>.
        claim(id, "/api/lcnc/council") { method, path, _, _ ->
            if (method != "GET") return@claim JvmKanbanServer.HttpResponse(405, """{"error":"method_not_allowed"}""")
            val caseId = path.substringAfter('?', "")
                .split('&')
                .firstOrNull { it.startsWith("caseId=") }
                ?.substringAfter('=')
                ?.let { java.net.URLDecoder.decode(it, "UTF-8") }
                ?.takeIf { it.isNotBlank() }
                ?: return@claim JvmKanbanServer.HttpResponse(400, """{"error":"caseId_required"}""")
            val runner = ctx.lcncRunners["council.case"]
                ?: return@claim JvmKanbanServer.HttpResponse(
                    404, jsonOf(mapOf("error" to "no_runner", "type" to "council.case")),
                )
            val out = runner.run(
                LcncNode(id = "council-case-get", type = "council.case", params = mapOf("caseId" to caseId)),
                emptyMap(),
            )
            JvmKanbanServer.HttpResponse(200, jsonOf(out))
        }

        claim(id, "/api/lcnc/kanban/move") { method, _, text, _ ->
            if (method != "POST") return@claim JvmKanbanServer.HttpResponse(405, """{"error":"method_not_allowed"}""")
            val req = runCatching { reify(rawBody(text)) as? Map<*, *> }.getOrNull()
                ?: return@claim JvmKanbanServer.HttpResponse(400, """{"error":"bad_json"}""")
            val jobId = req["itemId"]?.toString() ?: req["jobId"]?.toString()
                ?: return@claim JvmKanbanServer.HttpResponse(400, """{"error":"jobId_required"}""")
            val toColumn = req["to"]?.toString() ?: req["toColumn"]?.toString()
                ?: return@claim JvmKanbanServer.HttpResponse(400, """{"error":"toColumn_required"}""")
            val item = req["item"] as? Map<*, *>
            val revision = req["expectedRevision"] ?: item?.get("revision")
                ?: return@claim JvmKanbanServer.HttpResponse(400, """{"error":"expectedRevision_required"}""")
            // reify decodes JSON numbers as a numeric value whose
            // toString may be `3.0`; the LCNC reducer consumes a long.
            val normalizedRevision = revision.toString().toDoubleOrNull()?.toLong()?.toString()
                ?: return@claim JvmKanbanServer.HttpResponse(400, """{"error":"expectedRevision_invalid"}""")
            val node = LcncNode(
                id = "move-$jobId",
                type = "kanban.move",
                params = mapOf(
                    "jobId" to jobId,
                    "toColumn" to toColumn,
                    "expectedRevision" to normalizedRevision,
                    "idempotencyKey" to (req["idempotencyKey"]?.toString() ?: "lcnc#$jobId#$revision#$toColumn"),
                ),
            )
            val result = lcncRegistry.getValue("kanban.move").run(node, emptyMap())
            JvmKanbanServer.HttpResponse(if (result["accepted"] == true) 202 else 409, jsonOf(result))
        }

        return object : ModuleHandle {
            override val id: String = "kanban"

            override fun describe(): Map<String, Any?> = mapOf(
                "cards" to store.cards().size,
                "sequence" to store.lastSequence,
                "lcncWorkgroup" to mapOf("state" to fanout.state.name),
                "lcncWorkgroups" to workgroupView(),
                "routes" to listOf(
                    "/api/board", "/api/invoke", "/api/board/import",
                    "/api/lcnc/kanban", "/api/lcnc/kanban/move", "/api/lcnc/run",
                    "/api/lcnc/council", "/api/mcp",
                ),
                "mcp" to mapOf(
                    "protocolVersions" to borg.trikeshed.mcp.LcncKanbanMcp.SUPPORTED_PROTOCOLS,
                    "retainedReceipts" to receiptLog.size,
                ),
            )

            override suspend fun drain() = cleanup()
            override suspend fun close() = cleanup()
        }
        } catch (failure: Throwable) {
            try { cleanup() }
            catch (cleanupFailure: Throwable) {
                throw IllegalStateException("Kanban module open failed", failure).also { it.addSuppressed(cleanupFailure) }
            }
            throw failure
        }
    }

    /** `/api/invoke`: batched browser commands → store intake → per-command verdicts (202, no drops). */
    private suspend fun invoke(store: BoardStoreElement, text: String): JvmKanbanServer.HttpResponse {
        val body = rawBody(text)
        if (body.isBlank()) return JvmKanbanServer.HttpResponse(400, """{"error":"empty_body"}""")
        val parsed = runCatching { reify(body) }.getOrNull()
            ?: return JvmKanbanServer.HttpResponse(400, """{"error":"bad_json"}""")
        val commands: List<*> = borg.trikeshed.kanban.InvokeLowering.commandsOf(parsed)
        val results = commands.map { c ->
            val raw = c as? Map<*, *>
                ?: return@map mapOf("verdict" to "rejected", "reason" to "command is not an object")
            val reply = CompletableDeferred<BoardApply>()
            store.intake.send(BoardIntake(raw, reply))
            when (val r = reply.await()) {
                is BoardApply.Committed -> linkedMapOf(
                    "verdict" to "committed",
                    "idempotencyKey" to r.idempotencyKey,
                    "jobId" to r.jobId,
                    "revision" to r.revision,
                    "sequence" to r.sequence,
                )

                is BoardApply.Rejected -> linkedMapOf(
                    "verdict" to "rejected",
                    "idempotencyKey" to r.idempotencyKey,
                    "reason" to r.reason,
                )
            }
        }
        return JvmKanbanServer.HttpResponse(
            202,
            jsonOf(
                linkedMapOf(
                    "ok" to results.none { it["verdict"] == "rejected" },
                    "accepted" to results.count { it["verdict"] == "committed" },
                    "rejected" to results.count { it["verdict"] == "rejected" },
                    "sequence" to store.lastSequence,
                    "results" to results,
                ),
            ),
        )
    }

    /**
     * Tolerant plan-doc import: markdown in, submit batch out. A section-less
     * doc imports its bullets partially — the "6. Work packages" hard require
     * stays retired with the fossil parser. Idempotency keys derive from the
     * line content, so re-importing the same doc is a no-op (dedupe).
     */
    private suspend fun import(store: BoardStoreElement, text: String): JvmKanbanServer.HttpResponse {
        val body = rawBody(text)
        if (body.isBlank()) return JvmKanbanServer.HttpResponse(400, """{"error":"empty_body"}""")
        val bullets = body.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("- ") || it.startsWith("* ") || Regex("^\\d+\\.\\s").containsMatchIn(it) }
            .map { it.removePrefix("- ").removePrefix("* ").replace(Regex("^\\d+\\.\\s+"), "").trim() }
            .map { it.removePrefix("[ ]").removePrefix("[x]").trim() }
            .filter { it.length in 3..200 }
            .take(100)
            .toList()
        var accepted = 0
        var deduped = 0
        for (title in bullets) {
            val key = "import#" + ContentId.of(title.encodeToByteArray()).hex.take(16)
            val jobId = "card-" + ContentId.of(title.encodeToByteArray()).hex.take(12)
            val reply = CompletableDeferred<BoardApply>()
            store.intake.send(
                BoardIntake(mapOf("type" to "submit", "jobId" to jobId, "idempotencyKey" to key, "title" to title), reply),
            )
            when (reply.await()) {
                is BoardApply.Committed -> accepted++
                is BoardApply.Rejected -> deduped++
            }
        }
        return JvmKanbanServer.HttpResponse(
            200,
            jsonOf(
                linkedMapOf(
                    "ok" to true,
                    "parsed" to bullets.size,
                    "imported" to accepted,
                    "duplicates" to deduped,
                    "sequence" to store.lastSequence,
                ),
            ),
        )
    }

    /** True when the caller asked for an SSE stream rather than a document. */
    private fun wantsEventStream(text: String): Boolean =
        text.lineSequence()
            .takeWhile { it.isNotBlank() }
            .any { it.startsWith("accept:", ignoreCase = true) && "text/event-stream" in it.lowercase() }

    private fun rawBody(text: String): String = when {
        "\r\n\r\n" in text -> text.substringAfter("\r\n\r\n")
        "\n\n" in text -> text.substringAfter("\n\n")
        else -> text
    }
}
