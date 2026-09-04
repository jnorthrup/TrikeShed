package borg.trikeshed.kanban.module

import borg.trikeshed.job.ContentId
import borg.trikeshed.kanban.BoardApply
import borg.trikeshed.lib.get
import borg.trikeshed.lib.size
import borg.trikeshed.kanban.BoardCursor
import borg.trikeshed.kanban.BoardIntake
import borg.trikeshed.kanban.BoardStoreElement
import borg.trikeshed.kanban.JvmBoardWal
import borg.trikeshed.kanban.toBoardMap
import borg.trikeshed.lcnc.LcncKanbanExperience
import borg.trikeshed.lcnc.LcncNode
import borg.trikeshed.lcnc.LcncContracts
import borg.trikeshed.lcnc.LcncNodeRunner
import borg.trikeshed.lcnc.LcncRunner
import borg.trikeshed.lcnc.ccek.LcncCcekAssembly
import borg.trikeshed.litebike.JvmKanbanServer
import borg.trikeshed.module.ForgeModule
import borg.trikeshed.module.ModuleContext
import borg.trikeshed.module.ModuleHandle
import borg.trikeshed.parse.json.JsonSupport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File
import kotlin.concurrent.Volatile

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
        store.open()
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
        )
        // board.get / board.view / /api/board all read ONE projection: LcncKanbanExperience.boardView.
        val lcnc = LcncKanbanExperience(
            store,
            attention = if (bag == null) null else {
                {
                    borg.trikeshed.kanban.BoardAttentionOrder.garnish(bag, store.cards())
                        .mapValues { (_, g) -> mapOf("attention" to g.score, "contested" to g.contested) }
                }
            },
            alerts = ::alertMap,
        )
        val lcncRegistry = lcnc.registry()
        ctx.lcncRunners.putAll(lcncRegistry)

        // ── NARS garnish (Phase 5): review bridge + attention order, iff the bag is live.
        //    Bag OFF ⇒ board JSON byte-identical minus the attention/contested fields.
        // NARS × kanban legos — the bag's board view as composable nodes:
        // kanban.attention = BoardAttentionOrder.garnish (per-card score/contested +
        // attention-descending order), kanban.drift = the Hotelling T² cohort alarm.
        if (bag != null) {
            ctx.lcncRunners["kanban.attention"] = LcncNodeRunner { _, _ ->
                val g = borg.trikeshed.kanban.BoardAttentionOrder.garnish(bag, store.cards())
                mapOf(
                    "cards" to g.mapValues { (_, v) -> mapOf("attention" to v.score, "contested" to v.contested) },
                    "ordered" to g.entries.sortedByDescending { it.value.score }.map { it.key },
                )
            }
            ctx.lcncRunners["kanban.drift"] = LcncNodeRunner { _, _ ->
                val t2 = borg.trikeshed.kanban.BoardAttentionOrder.driftT2(bag)
                mapOf("t2" to t2, "alarm" to (t2 > 9f))
            }
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
            ctx.lcncRunners["kanban.review"] = LcncNodeRunner { _, _ ->
                val minted = flushReview()
                mapOf(
                    "minted" to minted.map { (angular, gloss) -> mapOf("angular" to angular, "gloss" to gloss) },
                    "count" to minted.size,
                )
            }
        }

        // Board JSON memo keyed by the commit watermark: rebuild only after a commit.
        // (Bag-on skips the memo: attention scores evolve independently of the board.)
        var cached: Pair<Long, String>? = null
        fun boardJson(): String {
            val seq = store.lastSequence
            if (bag == null) cached?.let { if (it.first == seq) return it.second }
            val json = JsonSupport.stringify(lcnc.boardView())
            if (bag == null) cached = seq to json
            return json
        }

        // ── Rete: fact bridge + the board productions + activation sink ──
        //    (the four audit/flow rules, plus claim: the board proposes its own READY work,
        //     and reaper: a claim whose worker died goes back to READY — thrice, then BLOCKED)
        val facts = borg.trikeshed.kanban.BoardFactElement(ctx.rete)
        val ruleDisposers = listOf(
            ctx.rete.register(borg.trikeshed.kanban.rules.DependencyReadyProduction()),
            ctx.rete.register(borg.trikeshed.kanban.rules.ClaimProduction()),
            ctx.rete.register(borg.trikeshed.kanban.rules.WipBreachProduction()),
            ctx.rete.register(borg.trikeshed.kanban.rules.StallProduction()),
            ctx.rete.register(
                borg.trikeshed.kanban.rules.ReaperProduction(
                    priorStrikes = { jobId -> borg.trikeshed.kanban.rules.ReaperProduction.countPriorStrikes(ctx.blackboard, jobId) },
                ),
            ),
            ctx.rete.register(borg.trikeshed.kanban.rules.CycleGuardProduction()),
        )
        // kanban.alerts = the same tail board.view#alerts carries.
        ctx.lcncRunners["kanban.alerts"] = LcncNodeRunner { _, _ -> alertMap() }
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
            plane = { ctx.rete.snapshot().map { f -> borg.trikeshed.kanban.PlaneBrief.Row(f.factId.partitionId, f.factId.localId, f.fields) } },
        )
        val claimsInFlight = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        // Non-job activations: receipt on the blackboard ALWAYS; dependency-ready also
        // lowers to a store Move with a derived idempotency key (dedupe compensates
        // any popped-but-unprocessed activation); claim hands the card to the worker.
        ctx.rete.productionSink = { a ->
            ctx.blackboard.put(
                "kanban/rule/${a.ruleId}/${a.activationId}",
                a.bindings + ("salience" to "${a.salience}"),
                "kanban-rete",
            )
            synchronized(alerts) { alerts.retain(a) }
            bridge?.onRuleFired(a)
            if (a.ruleId == borg.trikeshed.kanban.rules.BoardRules.DEPENDENCY_READY) {
                val jobId = a.bindings["jobId"]
                val rev = a.bindings["expectedRevision"]?.toLongOrNull()
                if (jobId != null && rev != null) {
                    ctx.scope.launch {
                        store.intake.send(
                            BoardIntake(
                                mapOf(
                                    "type" to "move",
                                    "jobId" to jobId,
                                    "idempotencyKey" to "$jobId#${a.ruleId}#$rev",
                                    "expectedRevision" to rev,
                                    "toColumn" to a.bindings["toColumn"],
                                ),
                            ),
                        )
                    }
                }
            }
            if (a.ruleId == borg.trikeshed.kanban.rules.BoardRules.CLAIM) {
                val jobId = a.bindings["jobId"]
                val rev = a.bindings["expectedRevision"]?.toLongOrNull()
                val owner = a.bindings["owner"] ?: borg.trikeshed.kanban.rules.BoardRules.CLAIM_OWNER
                if (jobId != null && rev != null && claimsInFlight.add(a.activationId)) {
                    ctx.scope.launch {
                        try {
                            val outcome = claimWorker.claim(jobId, rev, owner)
                            System.err.println("[KanbanModule] claim $jobId r$rev: ok=${outcome["ok"]} model=${outcome["model"]} ${outcome["review"]}")
                        } catch (t: kotlinx.coroutines.CancellationException) {
                            throw t
                        } catch (t: Throwable) {
                            System.err.println("[KanbanModule] claim $jobId r$rev failed: ${t.message}")
                        } finally {
                            claimsInFlight.remove(a.activationId)
                        }
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
                    ctx.scope.launch {
                        val reply = CompletableDeferred<BoardApply>()
                        store.intake.send(BoardIntake(move, reply))
                        val verdict = reply.await()
                        System.err.println("[KanbanModule] reaper $jobId r$rev strike $strike → ${move["toColumn"]}: $verdict")
                    }
                }
            }
        }

        // The MCP lens onto this same asset (docs/marketability-kanban-mcp-audit.md,
        // KMFSM-004/005/006). It is handed the LCNC registry and a read port — never
        // the store — so an MCP write cannot become a second path to the intake.
        val receiptLog = borg.trikeshed.mcp.KanbanReceiptLog()
        receiptLog.seedFrom(store.cards())
        val mcp = borg.trikeshed.mcp.LcncKanbanMcp(
            tools = lcncRegistry,
            reads = borg.trikeshed.mcp.BoardKanbanReadPort(store, lcnc, receiptLog),
        )

        // Receipts: every committed transition is blackboard-visible AND a Rete card fact.
        val receipts = ctx.scope.launch {
            store.committed.collect { ev ->
                receiptLog.record(ev)
                ctx.blackboard.put(
                    "kanban/committed/${ev.jobId}/${ev.sequence}",
                    mapOf(
                        "op" to ev.command.operationName,
                        "col" to ev.col.wire,
                        "from" to (ev.previousCol?.wire ?: ""),
                        "revision" to ev.snapshot.revision.toString(),
                        "cid" to ev.cid.value,
                    ),
                    "kanban-module",
                )
                facts.onCommitted(ev)
                bridge?.let { b ->
                    b.onCommitted(ev)
                    if (b.pendingCount >= 12) flushReview()
                }
            }
        }
        // Seed working memory from the replayed board (attach-after-facts correctness),
        // then pulse the now-fact so StallProduction sees a clock.
        val ticker = ctx.scope.launch {
            for (row in store.cards()) {
                if (row.col == borg.trikeshed.kanban.BoardCol.ARCHIVED) continue
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
                    ),
                )
            }
            while (true) {
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
                kotlinx.coroutines.delay(60_000)
            }
        }

        ctx.routes.claim(id, "/api/board") { method, _, _, _ ->
            if (method != "GET") JvmKanbanServer.HttpResponse(405, """{"error":"method_not_allowed"}""")
            else JvmKanbanServer.HttpResponse(200, boardJson())
        }

        ctx.routes.claim(id, "/api/invoke") { method, _, text, _ ->
            if (method != "POST") JvmKanbanServer.HttpResponse(405, """{"error":"method_not_allowed"}""")
            else invoke(store, text)
        }

        ctx.routes.claim(id, "/api/board/import") { method, _, text, _ ->
            if (method != "POST") JvmKanbanServer.HttpResponse(405, """{"error":"method_not_allowed"}""")
            else import(store, text)
        }

        // KMFSM-006: MCP mounted in the daemon's own lifecycle — one server, one
        // board, no second process. Streamable HTTP's POST half: one JSON-RPC
        // document in, one out; a notification (no id) gets 202 and no body.
        // GET is answered with the handshake a client would otherwise have to
        // POST for, so `curl /api/mcp` is a useful thing to type.
        ctx.routes.claim(id, "/api/mcp") { method, _, text, _ ->
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
                    JsonSupport.stringify(
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

        ctx.routes.claim(id, "/api/lcnc/kanban") { method, _, _, _ ->
            if (method != "GET") JvmKanbanServer.HttpResponse(405, """{"error":"method_not_allowed"}""")
            else JvmKanbanServer.HttpResponse(200, JsonSupport.stringify(lcnc.activeSheets()))
        }

        // The concentric composition surface: modules + rings + wizard roster,
        // projected purely from contracts, stored programs, and the sub-VM substrate.
        ctx.routes.claim(id, "/api/lcnc/concentric") { method, _, _, _ ->
            if (method != "GET") return@claim JvmKanbanServer.HttpResponse(405, """{"error":"method_not_allowed"}""")
            val programs = runCatching {
                borg.trikeshed.lcnc.LcncPresets.all().map { (name, doc) -> name to doc }
            }.getOrNull().orEmpty()
            val surface = borg.trikeshed.lcnc.ConcentricSurface.render(programs = programs)
            JvmKanbanServer.HttpResponse(200, JsonSupport.stringify(surface))
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
        runCatching { publisher.publishAll() }
            .onFailure { System.err.println("[KanbanModule] lcnc → blackboard publish failed: ${it.message}") }

        ctx.routes.claim(id, "/api/lcnc/contracts") { method, _, _, _ ->
            if (method != "GET") JvmKanbanServer.HttpResponse(405, """{"error":"method_not_allowed"}""")
            else JvmKanbanServer.HttpResponse(200, JsonSupport.stringify(publisher.publishVocabulary()))
        }

        // The same vocabulary as a GRAPH: kinds, ports, and the binding edges,
        // as Turtle — queryable, countable, and the form LcncRdf's doc comment
        // promised for SHACL-minded readers.
        ctx.routes.claim(id, "/api/lcnc/ontology") { method, _, _, _ ->
            if (method != "GET") JvmKanbanServer.HttpResponse(405, """{"error":"method_not_allowed"}""")
            else {
                val lb = publisher.lateBound()
                val triples = borg.trikeshed.lcnc.LcncRdf.ontology(lb.vocabulary.values.toList()) + borg.trikeshed.lcnc.LcncRdf.bindings(lb.bindings)
                JvmKanbanServer.HttpResponse(200, borg.trikeshed.lcnc.LcncRdf.turtle(triples) + "\n", contentType = "text/turtle; charset=utf-8")
            }
        }

        // The TUPLES themselves, verbatim: the vocabulary, the corpus's wires and
        // the bindings as a .kif file — what LcncFacts.parse reads back.
        ctx.routes.claim(id, "/api/lcnc/facts") { method, _, _, _ ->
            if (method != "GET") JvmKanbanServer.HttpResponse(405, """{"error":"method_not_allowed"}""")
            else JvmKanbanServer.HttpResponse(200, publisher.lateBound().facts.toKifFile() + "\n", contentType = "text/plain; charset=utf-8")
        }

        ctx.routes.claim(id, "/api/lcnc/treeshake") { method, _, text, _ ->
            if (method != "POST") return@claim JvmKanbanServer.HttpResponse(405, """{"error":"method_not_allowed"}""")
            val req = runCatching { JsonSupport.parse(rawBody(text)) as? Map<*, *> }.getOrNull()
                ?: return@claim JvmKanbanServer.HttpResponse(400, """{"error":"bad_json"}""")
            val programData = req["program"]
                ?: return@claim JvmKanbanServer.HttpResponse(400, """{"error":"program_required"}""")
            val program = runCatching {
                borg.trikeshed.lcnc.LcncProgramConfix.fromJson("treeshake", JsonSupport.stringify(programData))
            }.getOrElse {
                return@claim JvmKanbanServer.HttpResponse(400, JsonSupport.stringify(mapOf("error" to "bad_program", "detail" to (it.message ?: ""))))
            }
            val optMap = req["options"] as? Map<*, *>
            val reach = (optMap?.get("reach") as? Number)?.toDouble() ?: 340.0
            val inclOpt = optMap?.get("optional") == true
            val options = borg.trikeshed.lcnc.LcncTreeShakeOptions(reach = reach, includeOptional = inclOpt)
            val result = borg.trikeshed.lcnc.LcncMating.treeshake(program, options, publisher.vocabulary())
            JvmKanbanServer.HttpResponse(200, JsonSupport.stringify(result.toMap()))
        }

        // The generic runner dispatch: ONE execution author. The browser (and any
        // client) posts {type, params?, inputs?} to run ONE node (a job), or
        // {program, inputs?} to run a WHOLE stored program (a procedure — spec
        // §6) with subprogram recursion via ctx.programLoader. Either way the
        // execution happens IN the daemon against the composed ctx.lcncRunners
        // registry — the very map webhook node dispatch resolves. Looked up at
        // request time so late-attached module registries (ace nodes, future
        // modules) are reachable too.
        ctx.routes.claim(id, "/api/lcnc/run") { method, _, text, _ ->
            if (method != "POST") return@claim JvmKanbanServer.HttpResponse(405, """{"error":"method_not_allowed"}""")
            val req = runCatching { JsonSupport.parse(rawBody(text)) as? Map<*, *> }.getOrNull()
                ?: return@claim JvmKanbanServer.HttpResponse(400, """{"error":"bad_json"}""")
            @Suppress("UNCHECKED_CAST")
            val inputs = (req["inputs"] as? Map<*, *>)?.entries
                ?.associate { (k, v) -> k.toString() to v } ?: emptyMap<String, Any?>()
            // ONE execution path for named and inline rings: CCEK assembly when
            // bound (structured child scope, reactor + LcncScopeFrame in one
            // context, projected receipts), direct walk in reduced/test contexts.
            val execute: suspend (String, borg.trikeshed.lcnc.LcncProgram) -> JvmKanbanServer.HttpResponse =
                { label, program ->
                    // Patch type-system compliance, enforced where it counts. The canvas
                    // refuses a kind-mismatched drag, but the canvas is not the authority:
                    // a stored panel, an import, a Kotlin preset or a raw POST never passed
                    // that check and used to execute anyway. One rule, one author
                    // (LcncContracts), stated on the deciding side — loudly, before the run.
                    // strict=false: the registry may carry types the contract table does
                    // not describe, and LcncRunner throws for one that is truly absent.
                    // THE BOARD IS OBEYED: a named program's entry (refreshed by the
                    // loader a moment ago) carries the violations the publisher
                    // recorded; an inline document, which has no entry, is checked now.
                    val entry = ctx.blackboard.get(borg.trikeshed.lcnc.LcncBlackboard.programKey(label))
                    val violations = borg.trikeshed.lcnc.LcncBlackboard.violationsOf(entry)
                        ?: borg.trikeshed.lcnc.LcncTypeCheck.check(program, publisher.vocabulary(), strict = false).map { it.toMap() }
                    if (violations.isNotEmpty()) {
                        JvmKanbanServer.HttpResponse(400, JsonSupport.stringify(mapOf(
                            "ok" to false, "program" to label, "error" to "type_check_failed",
                            "violations" to violations,
                        )))
                    } else {
                    val walker = LcncRunner(ctx.lcncRunners).apply { subprogramLoader = ctx.programLoader }
                    runCatching {
                        val binding = ctx.ccekBinding
                        if (binding != null) {
                            LcncCcekAssembly(binding, walker)
                                .launch(label, program, inputs)
                                .result.await()
                        } else {
                            walker.runProcedure(program, inputs)
                        }
                    }.fold(
                        onSuccess = { res ->
                            JvmKanbanServer.HttpResponse(200, JsonSupport.stringify(mapOf(
                                "ok" to true, "program" to label,
                                "returns" to res.returns, "outputs" to res.nodeOutputs,
                            )))
                        },
                        onFailure = { e ->
                            JvmKanbanServer.HttpResponse(400, JsonSupport.stringify(mapOf(
                                "ok" to false, "program" to label, "error" to (e.message ?: e.toString()),
                            )))
                        },
                    )
                    }
                }
            val programName = req["program"]?.toString()
            if (programName != null) {
                val program = ctx.programLoader(programName)
                    ?: return@claim JvmKanbanServer.HttpResponse(
                        404, JsonSupport.stringify(mapOf("error" to "no_such_program", "program" to programName)),
                    )
                return@claim execute(programName, program)
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
                    borg.trikeshed.lcnc.LcncProgramConfix.fromJson(label, JsonSupport.stringify(document))
                }.getOrElse { e ->
                    return@claim JvmKanbanServer.HttpResponse(400, JsonSupport.stringify(mapOf(
                        "error" to "bad_document", "detail" to (e.message ?: e.toString()),
                    )))
                }
                return@claim execute(label, program)
            }
            val type = req["type"]?.toString()
                ?: return@claim JvmKanbanServer.HttpResponse(400, """{"error":"type_required"}""")
            val runner = ctx.lcncRunners[type]
                ?: return@claim JvmKanbanServer.HttpResponse(
                    404, JsonSupport.stringify(mapOf("error" to "no_runner", "type" to type)),
                )
            val params = (req["params"] as? Map<*, *>)?.entries
                ?.associate { (k, v) -> k.toString() to (v?.toString() ?: "") } ?: emptyMap()
            val node = LcncNode(id = "http-run", type = type, params = params)
            runCatching { runner.run(node, inputs) }.fold(
                onSuccess = { out ->
                    JvmKanbanServer.HttpResponse(200, JsonSupport.stringify(mapOf("ok" to true, "type" to type, "outputs" to out)))
                },
                onFailure = { e ->
                    JvmKanbanServer.HttpResponse(
                        400,
                        JsonSupport.stringify(mapOf("ok" to false, "type" to type, "error" to (e.message ?: e.toString()))),
                    )
                },
            )
        }

        // Council case read-back: a thin veneer over the council.case runner
        // (blackboard/couch index → CAS transcript+verdict bytes), so this
        // module stays dependency-free. ModuleRouteRegistry claims are EXACT
        // paths — no /{caseId} segment is claimable — so the case id rides
        // the query string: GET /api/lcnc/council?caseId=<id>.
        ctx.routes.claim(id, "/api/lcnc/council") { method, path, _, _ ->
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
                    404, JsonSupport.stringify(mapOf("error" to "no_runner", "type" to "council.case")),
                )
            val out = runner.run(
                LcncNode(id = "council-case-get", type = "council.case", params = mapOf("caseId" to caseId)),
                emptyMap(),
            )
            JvmKanbanServer.HttpResponse(200, JsonSupport.stringify(out))
        }

        ctx.routes.claim(id, "/api/lcnc/kanban/move") { method, _, text, _ ->
            if (method != "POST") return@claim JvmKanbanServer.HttpResponse(405, """{"error":"method_not_allowed"}""")
            val req = runCatching { JsonSupport.parse(rawBody(text)) as? Map<*, *> }.getOrNull()
                ?: return@claim JvmKanbanServer.HttpResponse(400, """{"error":"bad_json"}""")
            val jobId = req["itemId"]?.toString() ?: req["jobId"]?.toString()
                ?: return@claim JvmKanbanServer.HttpResponse(400, """{"error":"jobId_required"}""")
            val toColumn = req["to"]?.toString() ?: req["toColumn"]?.toString()
                ?: return@claim JvmKanbanServer.HttpResponse(400, """{"error":"toColumn_required"}""")
            val item = req["item"] as? Map<*, *>
            val revision = req["expectedRevision"] ?: item?.get("revision")
                ?: return@claim JvmKanbanServer.HttpResponse(400, """{"error":"expectedRevision_required"}""")
            // JsonSupport decodes JSON numbers as a numeric value whose
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
            JvmKanbanServer.HttpResponse(if (result["accepted"] == true) 202 else 409, JsonSupport.stringify(result))
        }

        return object : ModuleHandle {
            override val id: String = "kanban"

            override fun describe(): Map<String, Any?> = mapOf(
                "cards" to store.cards().size,
                "sequence" to store.lastSequence,
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

            override suspend fun drain() {
                store.drain()
            }

            override suspend fun close() {
                ticker.cancel()
                receipts.cancel()
                ctx.rete.productionSink = null
                ruleDisposers.forEach { runCatching { it.close() } }
                runCatching { facts.retractAll() }
                store.close()
            }
        }
    }

    /** `/api/invoke`: batched browser commands → store intake → per-command verdicts (202, no drops). */
    private suspend fun invoke(store: BoardStoreElement, text: String): JvmKanbanServer.HttpResponse {
        val body = rawBody(text)
        if (body.isBlank()) return JvmKanbanServer.HttpResponse(400, """{"error":"empty_body"}""")
        val parsed = runCatching { JsonSupport.parse(body) }.getOrNull()
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
            JsonSupport.stringify(
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
            JsonSupport.stringify(
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
