package borg.trikeshed.kanban.module

import borg.trikeshed.job.ContentId
import borg.trikeshed.kanban.BoardApply
import borg.trikeshed.kanban.BoardCursor
import borg.trikeshed.kanban.BoardIntake
import borg.trikeshed.kanban.BoardStoreElement
import borg.trikeshed.kanban.JvmBoardWal
import borg.trikeshed.kanban.toBoardMap
import borg.trikeshed.lcnc.LcncKanbanExperience
import borg.trikeshed.lcnc.LcncNode
import borg.trikeshed.lcnc.LcncContracts
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
        val lcnc = LcncKanbanExperience(store)
        val lcncRegistry = lcnc.registry()
        ctx.lcncRunners.putAll(lcncRegistry)

        // ── NARS garnish (Phase 5): review bridge + attention order, iff the bag is live.
        //    Bag OFF ⇒ board JSON byte-identical minus the attention/contested fields.
        val bag = ctx.beliefBag
        val bridge = if (bag != null && ctx.turnReview != null) {
            borg.trikeshed.kanban.BoardReviewBridge(
                review = ctx.turnReview!!,
                cardLookup = { store.card(it) },
                clock = ctx.clock,
            )
        } else null

        // Board JSON memo keyed by the commit watermark: rebuild only after a commit.
        // (Bag-on skips the memo: attention scores evolve independently of the board.)
        var cached: Pair<Long, String>? = null
        fun boardJson(): String {
            val seq = store.lastSequence
            if (bag == null) cached?.let { if (it.first == seq) return it.second }
            val cursor = BoardCursor.of(store.cards())
            val base = cursor.toBoardMap(seq, title = "Oroboros board")
            val withOwners = base + ("items" to ((base["items"] as List<*>).map { item ->
                val m = item as Map<*, *>
                m + mapOf("owner" to store.card(m["id"].toString())?.owner.orEmpty())
            }))
            val map = if (bag == null) withOwners else {
                val garnish = borg.trikeshed.kanban.BoardAttentionOrder.garnish(bag, store.cards())
                val items = (withOwners["items"] as List<*>).map { item ->
                    val m = item as Map<*, *>
                    val g = garnish[m["id"]]
                    val owner = store.card(m["id"].toString())?.owner.orEmpty()
                    (if (g == null) m else m + mapOf("attention" to g.score, "contested" to g.contested)) + mapOf("owner" to owner)
                }
                base + ("items" to items)
            }
            val json = JsonSupport.stringify(map)
            if (bag == null) cached = seq to json
            return json
        }

        // ── Rete: fact bridge + the four board productions + activation sink ──
        val facts = borg.trikeshed.kanban.BoardFactElement(ctx.rete)
        val ruleDisposers = listOf(
            ctx.rete.register(borg.trikeshed.kanban.rules.DependencyReadyProduction()),
            ctx.rete.register(borg.trikeshed.kanban.rules.WipBreachProduction()),
            ctx.rete.register(borg.trikeshed.kanban.rules.StallProduction()),
            ctx.rete.register(borg.trikeshed.kanban.rules.CycleGuardProduction()),
        )
        // Non-job activations: receipt on the blackboard ALWAYS; dependency-ready also
        // lowers to a store Move with a derived idempotency key (dedupe compensates
        // any popped-but-unprocessed activation).
        ctx.rete.productionSink = { a ->
            ctx.blackboard.put(
                "kanban/rule/${a.ruleId}/${a.activationId}",
                a.bindings + ("salience" to "${a.salience}"),
                "kanban-rete",
            )
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
        }

        // Receipts: every committed transition is blackboard-visible AND a Rete card fact.
        val receipts = ctx.scope.launch {
            store.committed.collect { ev ->
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
                    if (b.pendingCount >= 12) b.flush()
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
                    ),
                )
            }
            while (true) {
                facts.tick(ctx.clock())
                bridge?.flush()
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

        ctx.routes.claim(id, "/api/lcnc/contracts") { method, _, _, _ ->
            if (method != "GET") JvmKanbanServer.HttpResponse(405, """{"error":"method_not_allowed"}""")
            else JvmKanbanServer.HttpResponse(200, JsonSupport.stringify(mapOf(
                // The FULL contract: title, ports, kinds, cardinality, functions,
                // param defaults, source/sink/wide — every field the retired JS
                // TYPES table used to carry. Kotlin is the ONE vocabulary author;
                // the browser renders (and fetches) but never invents.
                "contracts" to LcncContracts.all().map { c -> mapOf(
                    "type" to c.type, "title" to c.title,
                    "inputs" to c.inputs, "outputs" to c.outputs,
                    "inputKinds" to c.inputKinds, "outputKinds" to c.outputKinds,
                    "cardinality" to c.cardinality.mapValues { it.value.name }, "functions" to c.functions,
                    "params" to c.params.mapValues { p ->
                        mapOf(
                            "v" to p.value.v, "opts" to p.value.opts,
                            "ta" to p.value.ta, "ph" to p.value.ph,
                        )
                    },
                    "source" to c.isSource, "sink" to c.isSink, "wide" to c.wide,
                ) },
            )))
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

    private fun rawBody(text: String): String = when {
        "\r\n\r\n" in text -> text.substringAfter("\r\n\r\n")
        "\n\n" in text -> text.substringAfter("\n\n")
        else -> text
    }
}
