package borg.trikeshed.kanban.module

import borg.trikeshed.couch.CouchDatabase
import borg.trikeshed.couch.CouchStoreFactory
import borg.trikeshed.dag.ReteNetwork
import borg.trikeshed.dag.ReteProductionRegistry
import borg.trikeshed.graal.ConfixBlackboard
import borg.trikeshed.job.CasStore
import borg.trikeshed.kanban.BoardClaimWorker
import borg.trikeshed.kanban.BoardFanOutWorker
import borg.trikeshed.kanban.PlaneBrief
import borg.trikeshed.kanban.rules.BoardRules
import borg.trikeshed.lcnc.LcncNodeRunner
import borg.trikeshed.litebike.JvmKanbanServer
import borg.trikeshed.module.ModuleContext
import borg.trikeshed.module.ModuleRouteRegistry
import borg.trikeshed.module.ModuleSupervisor
import borg.trikeshed.parse.json.JsonSupport
import borg.trikeshed.util.oroboros.CouchAttachmentGateway
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The fan-out / fan-in tree, end to end through the module (Delta 2026-09-05):
 * a card whose spec says `MODELS: a, b, c` is split by [borg.trikeshed.kanban.rules.FanOutProduction]
 * into three child cards on the board (each `MODEL:` one id, `TOKENS:` the floor,
 * `parent` the card it branched off), every child is claimed and judged by the
 * ordinary loop, and when all three are Done the parent fans back IN by the
 * board's own causality — `DependencyReadyProduction` moves it to Ready, the
 * claim briefs the merge model with the children's receipts (the CHILDREN block
 * and the MERGE line), and the judge closes it citing a child receipt id.
 *
 * Same rig as [KanbanClaimLoopTest]: a fake `prompt.chat` in ctx.lcncRunners is
 * the brain (the seam the daemon fills with ModelMux), the board is polled over
 * HTTP the way the page polls it, every wait is bounded. The fake brain answers
 * a merge brief by citing the first child receipt id it finds in the prompt, an
 * ordinary brief by citing the card's own fact on the plane, and always reports
 * the timing fields prompt.chat now carries so the receipt can be checked for them.
 */
class KanbanFanOutLoopTest {

    private fun tempDir(name: String): File =
        File(System.getProperty("java.io.tmpdir"), "kanban-fanout-$name-${System.nanoTime()}").apply { mkdirs() }

    private class Rig(val server: JvmKanbanServer, val ctx: ModuleContext, val supervisor: ModuleSupervisor)

    private fun rig(name: String, brain: LcncNodeRunner?, models: List<String> = listOf("hermes-newest", "older")): Rig {
        val cas = CasStore.inMemory()
        val couchStore = CouchStoreFactory.casBacked(cas)
        val routes = ModuleRouteRegistry()
        val ctx = ModuleContext(
            couchDb = CouchDatabase("kanban-fanout-test-$name", couchStore, cas),
            rete = ReteNetwork(),
            productions = ReteProductionRegistry(),
            beliefBag = null,
            turnReview = null,
            blackboard = ConfixBlackboard.empty(),
            casStore = cas,
            attachments = CouchAttachmentGateway(couchStore, cas),
            routes = routes,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            clock = { System.currentTimeMillis() },
            stateDir = tempDir(name),
        )
        brain?.let { ctx.lcncRunners["prompt.chat"] = it }
        ctx.lcncRunners["mux.models"] = LcncNodeRunner { _, _ -> mapOf("models" to models.map { mapOf("id" to it, "caps" to emptyList<String>(), "provider" to "fake") }) }
        val server = JvmKanbanServer(moduleRoutes = routes)
        val supervisor = ModuleSupervisor(ctx)
        runBlocking { supervisor.attach(KanbanModule()) }
        return Rig(server, ctx, supervisor)
    }

    private fun tearDown(rig: Rig) {
        runBlocking { rig.supervisor.detach("kanban") }
        rig.ctx.scope.coroutineContext[Job]?.cancel()
    }

    // ── the fake brain ──────────────────────────────────────────────────────

    /** A child receipt id as the CHILDREN block prints it; the MUST line's `blackboard/kanban/claim/ receipt id` (a space, no id) never matches. */
    private val childReceiptId = Regex("blackboard/kanban/claim/[A-Za-z0-9_-]+")

    /** What the fake brain reports for every answer, so the receipt's copied fields are checkable to the digit. */
    private val fakeLatencyMs = 7L
    private val fakeInputTokens = 11
    private val fakeOutputTokens = 5

    /**
     * The brain: a merge brief (CHILDREN in the prompt) → MET citing the first child
     * receipt id in the prompt; any other brief → MET citing the card's own plane fact
     * `board/<jobId>` (the id is parsed from the brief's first line, `Card <id> — brief`).
     * A model in [dead] throws — the claim worker turns that into ok=false, a strike.
     */
    private fun fakeBrain(prompts: MutableList<Map<String, String>>, dead: Set<String> = emptySet()): LcncNodeRunner =
        LcncNodeRunner { node, _ ->
            prompts.add(node.params)
            val prompt = node.params["prompt"].orEmpty()
            val model = node.params["model"].orEmpty()
            if (model in dead) throw IllegalStateException("$model is dead: provider returned 404")
            val content = if ("CHILDREN" in prompt) {
                val child = childReceiptId.find(prompt)?.value ?: "none"
                "VERDICT: MET\nMUST-1: MET — evidence: $child\nACTION: merged"
            } else {
                val id = prompt.substringAfter("Card ").substringBefore(" ")
                "VERDICT: MET\nMUST-1: MET — evidence: board/$id\nACTION: $model says hello"
            }
            mapOf(
                "ok" to true,
                "content" to content,
                "model" to model,
                "cached" to false,
                "cachedHit" to false,
                "latencyMs" to fakeLatencyMs,
                "inputTokens" to fakeInputTokens,
                "outputTokens" to fakeOutputTokens,
            )
        }

    // ── HTTP, as the page does it ───────────────────────────────────────────

    private suspend fun getAsync(server: JvmKanbanServer, path: String): JvmKanbanServer.HttpResponse =
        server.routeHttp("GET $path HTTP/1.1\r\nHost: t\r\n\r\n".toByteArray(StandardCharsets.UTF_8))

    private fun post(server: JvmKanbanServer, path: String, body: String): JvmKanbanServer.HttpResponse = runBlocking {
        server.routeHttp("POST $path HTTP/1.1\r\nHost: t\r\nContent-Type: application/json\r\n\r\n$body".toByteArray(StandardCharsets.UTF_8))
    }

    @Suppress("UNCHECKED_CAST")
    private fun json(resp: JvmKanbanServer.HttpResponse): Map<String, Any?> = JsonSupport.parse(resp.body) as Map<String, Any?>

    private fun arr(v: Any?): List<*> = when (v) {
        is List<*> -> v
        is Array<*> -> v.toList()
        else -> error("not an array: $v")
    }

    private fun strings(v: Any?): List<String> = arr(v).map { it.toString() }

    private fun invoke(rig: Rig, vararg commands: Map<String, Any?>): List<Map<*, *>> {
        val resp = post(rig.server, "/api/invoke", JsonSupport.stringify(mapOf("commands" to commands.toList())))
        assertEquals(202, resp.status, resp.body)
        return arr(json(resp)["results"]).map { it as Map<*, *> }
    }

    private suspend fun fetchItems(rig: Rig): Map<String, Map<*, *>> =
        arr(json(getAsync(rig.server, "/api/board"))["items"]).map { it as Map<*, *> }.associateBy { it["id"] as String }

    private fun items(rig: Rig): Map<String, Map<*, *>> = runBlocking { fetchItems(rig) }

    /** Poll the rendered board until [ready] holds, or fail with what was seen. Bounded by [timeoutMs], never open-ended. */
    private fun awaitBoard(rig: Rig, what: String, timeoutMs: Long = 15_000, ready: (Map<String, Map<*, *>>) -> Boolean): Map<String, Map<*, *>> {
        var seen: Map<String, Map<*, *>> = emptyMap()
        val hit = runBlocking {
            withTimeoutOrNull(timeoutMs) {
                while (true) {
                    seen = fetchItems(rig)
                    if (ready(seen)) break
                    delay(100)
                }
            }
        }
        if (hit == null) fail("$what did not happen within ${timeoutMs}ms; board: ${seen.mapValues { it.value["status"] to it.value["owner"] }}")
        return seen
    }

    /** Poll the rendered board until every named card sits in [status], and the blackboard has caught up with each ([settle]). */
    private fun awaitStatus(rig: Rig, status: String, vararg jobIds: String, timeoutMs: Long = 15_000): Map<String, Map<*, *>> {
        val board = awaitBoard(rig, "cards ${jobIds.toList()} in '$status'", timeoutMs) { b -> jobIds.all { b[it]?.get("status") == status } }
        settle(rig, board, *jobIds)
        return board
    }

    /**
     * The row table is swapped BEFORE the module's collector puts `kanban/committed/<id>/<seq>`
     * on the blackboard (one coroutine hop, no happens-before to a board read), so a trail read
     * straight after the board shows the final column can be one receipt short. Wait, bounded,
     * until the receipt for each card's current board revision has landed.
     */
    private fun settle(rig: Rig, board: Map<String, Map<*, *>>, vararg jobIds: String, timeoutMs: Long = 10_000) {
        for (id in jobIds) {
            val rev = (board.getValue(id)["revision"] as Number).toLong()
            val hit = runBlocking {
                withTimeoutOrNull(timeoutMs) {
                    while (committed(rig, id).none { it["revision"].toString().toLong() == rev }) delay(50)
                }
            }
            if (hit == null) fail("kanban/committed/$id/* never caught up with the board's r$rev; trail: ${trail(rig, id)}")
        }
    }

    /** Poll the blackboard until [key] exists, or fail. */
    private fun awaitKey(rig: Rig, key: String, timeoutMs: Long = 10_000): Map<*, *> {
        val hit = runBlocking {
            withTimeoutOrNull(timeoutMs) {
                while (rig.ctx.blackboard.get(key) == null) delay(50)
            }
        }
        if (hit == null) fail("blackboard key $key never appeared within ${timeoutMs}ms; keys: ${rig.ctx.blackboard.keys().filter { it.startsWith("kanban/") }}")
        return rig.ctx.blackboard.get(key) as Map<*, *>
    }

    // ── the blackboard, as the page reads it ────────────────────────────────

    /** The card's column trail from its `kanban/committed/<jobId>/<seq>` receipts, in revision order. */
    private fun trail(rig: Rig, jobId: String): List<String> = committed(rig, jobId).map { it["col"].toString() }

    private fun committed(rig: Rig, jobId: String): List<Map<*, *>> =
        blackboardKeys(rig).filter { it.startsWith("kanban/committed/$jobId/") }
            .mapNotNull { rig.ctx.blackboard.get(it) as? Map<*, *> }.sortedBy { it["revision"].toString().toLong() }

    /** `keys()` snapshots an unsynchronised map that the module's writers grow concurrently; a snapshot that tears is simply taken again. */
    private fun blackboardKeys(rig: Rig): List<String> {
        var last: Throwable? = null
        repeat(20) {
            try { return rig.ctx.blackboard.keys() } catch (t: ConcurrentModificationException) { last = t; Thread.sleep(5) }
        }
        throw last!!
    }

    private fun claimReceipt(rig: Rig, jobId: String): Map<*, *> =
        rig.ctx.blackboard.get(BoardClaimWorker.RECEIPT_PREFIX + jobId) as? Map<*, *>
            ?: fail("no claim receipt for $jobId; keys: ${rig.ctx.blackboard.keys().filter { it.startsWith("kanban/claim/") }}")

    /** Every committed receipt on the board carries the store's stamp (Design B: a missing atMs prints `t=?`; a live commit never has one). */
    private fun assertEveryCommitIsStamped(rig: Rig) {
        val keys = blackboardKeys(rig).filter { it.startsWith("kanban/committed/") }
        assertTrue(keys.isNotEmpty(), "no committed receipts at all")
        for (key in keys) {
            val fact = rig.ctx.blackboard.get(key) as Map<*, *>
            val atMs = (fact["atMs"] as? Number)?.toLong() ?: fail("$key carries no atMs: $fact")
            assertTrue(atMs > 0L, "$key has atMs $atMs — a live commit is stamped by the store, never 0: $fact")
            assertTrue(fact.containsKey("owner"), "$key carries no owner: $fact")
        }
    }

    /** The prompts the brain saw for one card, in the order they were asked (`Card <id> — brief` is the first line). */
    private fun promptsFor(prompts: List<Map<String, String>>, jobId: String): List<Map<String, String>> =
        prompts.filter { it["prompt"].orEmpty().substringAfter("Card ").substringBefore(" ") == jobId }

    // TOKENS: 512 on the parent is deliberately BELOW both floors: a child still gets 4096
    // (the worker's floor) and so does the merge (the claim worker's) — never less than a child.
    private val parentSpec = "GOAL: three models answer, one merges\nMUST: cite one blackboard/kanban/claim/ receipt id\nMODELS: alpha, beta, gamma\nTOKENS: 512"

    /** The fan-out receipt says the split landed: ok, no error, the join a committed revision of the parent. */
    private fun assertFanOutLanded(fanout: Map<*, *>, parent: String, revision: Long) {
        assertEquals(true, fanout["ok"], "the fan-out receipt is the split that landed: $fanout")
        assertNull(fanout["error"], "a landed split carries no error: $fanout")
        assertTrue(fanout["join"].toString().startsWith("$parent r"), "the join is a committed revision of the parent: ${fanout["join"]}")
        assertEquals(revision, (fanout["revision"] as Number).toLong(), "the revision that owns the receipt: $fanout")
    }

    private fun submitModelsCard(jobId: String, title: String): Map<String, Any?> = mapOf(
        "type" to "submit", "jobId" to jobId, "idempotencyKey" to "$jobId#submit", "title" to title,
        // No human-routing tag on the parent: the judge would park it in REVIEW and the tree
        // would never reach Done by itself (the stripping of such tags from children is a
        // pure companion check below, not a board run).
        "spec" to parentSpec, "tags" to listOf("demo"),
    )

    private fun childrenOf(jobId: String): List<String> = (1..3).map { BoardFanOutWorker.childJobId(jobId, it) }

    @Test
    fun aModelsCardFansOutAndFansBackInThroughTheBoard() {
        val prompts = CopyOnWriteArrayList<Map<String, String>>()
        val rig = rig("tree", fakeBrain(prompts))
        try {
            invoke(rig, submitModelsCard("p1", "Reconcile three answers"))
            val kids = childrenOf("p1")
            val done = awaitStatus(rig, "done", "p1", *kids.toTypedArray(), timeoutMs = 20_000)

            // ── the tree on the board: exactly three children, each branched off p1 ──
            val children = done.values.filter { it["parent"] == "p1" }
            assertEquals(kids.toSet(), children.map { it["id"] }.toSet(), "exactly three children, one per MODELS: id; board: ${done.keys}")
            assertEquals(3, children.size)
            for ((i, model) in listOf("alpha", "beta", "gamma").withIndex()) {
                val child = done.getValue(kids[i])
                assertEquals("[$model] Reconcile three answers", child["title"])
                val spec = child["spec"].toString()
                assertEquals(BoardFanOutWorker.childSpec(parentSpec, model, BoardFanOutWorker.CHILD_TOKENS_FLOOR), spec)
                assertTrue(spec.endsWith("MODEL: $model\nTOKENS: ${BoardFanOutWorker.CHILD_TOKENS_FLOOR}"), spec)
                assertFalse("MODELS:" in spec, "a child never inherits the split: $spec")
                assertTrue("MUST: cite one blackboard/kanban/claim/ receipt id" in spec, "a child keeps the parent's criteria: $spec")
                val tags = strings(child["tags"])
                assertEquals(listOf("demo", BoardFanOutWorker.CHILD_TAG), tags, "the parent's ordinary tags ride along, plus the split tag")
                assertEquals("claim:brain", child["owner"], "a child is closed by the judge with its claimant still the owner")
                assertEquals(listOf("todo", "ready", "running", "review", "done"), trail(rig, kids[i]), "child ${kids[i]}")
            }

            // ── the parent: split, joined, fanned in, claimed, merged, closed ──
            val parent = done.getValue("p1")
            assertEquals(kids, strings(parent["dependencies"]), "the join made the children the parent's dependencies")
            assertEquals("", parent["parent"], "a root has no parent")
            // A child is judged by the plane like any claim; the parent's human-routing tags stay with the parent.
            assertEquals(listOf("demo", BoardFanOutWorker.CHILD_TAG), BoardFanOutWorker.childTags(listOf("demo", "human-review", "experiment")))
            // submit (todo) → join (todo) → dependency-ready (ready) → claim (running) → judge (review → done)
            assertEquals(listOf("todo", "todo", "ready", "running", "review", "done"), trail(rig, "p1"))
            assertEquals("claim:brain", parent["owner"])

            // ── the receipts on the blackboard: why each hop happened ──
            assertTrue(rig.ctx.blackboard.get("kanban/rule/${BoardRules.FAN_OUT}/fan-out-p1-r1") != null, "the split was proposed from the submit revision")
            assertTrue(
                rig.ctx.blackboard.keys().any { it.startsWith("kanban/rule/${BoardRules.DEPENDENCY_READY}/dependency-ready-p1-r") },
                "the fan-in is the board's own dependency-ready rule; keys: ${rig.ctx.blackboard.keys().filter { it.startsWith("kanban/rule/") }}",
            )
            val fanout = awaitKey(rig, BoardFanOutWorker.RECEIPT_PREFIX + "p1")
            assertEquals(BoardFanOutWorker.LANGUAGE, rig.ctx.blackboard.getProvenance(BoardFanOutWorker.RECEIPT_PREFIX + "p1")?.language)
            assertEquals(listOf("alpha", "beta", "gamma"), fanout["models"])
            assertEquals(kids, fanout["children"])
            assertEquals(BoardRules.FAN_OUT_ACTOR, fanout["actor"])
            assertFanOutLanded(fanout, "p1", revision = 1L)
            val fanStarted = (fanout["startedAtMs"] as Number).toLong()
            val fanFinished = (fanout["finishedAtMs"] as Number).toLong()
            assertTrue(fanStarted in 1..fanFinished, "fan-out timing is the worker's clock: $fanout")
            assertEquals(fanFinished, (fanout["atMs"] as Number).toLong())

            // ── the prompts: three plain briefs, one merge ──
            assertEquals(4, prompts.size, "three children and one merge: ${prompts.map { it["prompt"]?.lineSequence()?.first() }}")
            for ((i, model) in listOf("alpha", "beta", "gamma").withIndex()) {
                val childPrompts = promptsFor(prompts, kids[i])
                assertEquals(1, childPrompts.size, "child ${kids[i]} is claimed once")
                val p = childPrompts.single()
                assertEquals(model, p["model"], "a child is briefed to its own MODEL:")
                assertEquals("${BoardFanOutWorker.CHILD_TOKENS_FLOOR}", p["maxTokens"], "a child's budget is its TOKENS: line")
                assertFalse("CHILDREN" in p["prompt"].orEmpty(), "a child brief carries no CHILDREN block")
                assertTrue(p["prompt"]!!.startsWith("Card ${kids[i]} — brief (RFC 2119"), p["prompt"]!!)
            }
            val merge = promptsFor(prompts, "p1").single()
            val mergePrompt = merge["prompt"]!!
            assertTrue("CHILDREN" in mergePrompt, mergePrompt)
            assertTrue(PlaneBrief.MERGE_LINE in mergePrompt, mergePrompt)
            for (kid in kids) assertTrue("blackboard/kanban/claim/$kid" in mergePrompt, "the merge brief cites $kid's receipt: $mergePrompt")
            for (model in listOf("alpha", "beta", "gamma")) assertTrue("$model says hello" in mergePrompt, "the child's answer rides the brief: $mergePrompt")
            assertEquals("hermes-newest", merge["model"], "no MODEL: on the parent → the merge model is the newest mux.models card")
            assertEquals("${BoardClaimWorker.MERGE_TOKENS}", merge["maxTokens"], "a merge reads N answers: TOKENS: 512 on the parent is floored to the merge floor")
            assertTrue(mergePrompt.indexOf("CHILDREN") < mergePrompt.indexOf(PlaneBrief.MERGE_LINE), "the MERGE line closes the CHILDREN block")

            // ── the merge receipt: the judge accepted a child receipt id as evidence; timing is real ──
            val receipt = claimReceipt(rig, "p1")
            assertEquals(true, receipt["ok"])
            assertEquals("DONE", receipt["decision"], receipt.toString())
            assertEquals("MET", receipt["verdict"])
            assertEquals("hermes-newest", receipt["model"])
            val criterion = (receipt["criteria"] as List<*>).first() as Map<*, *>
            assertEquals("MUST-1", criterion["label"])
            assertEquals(true, criterion["met"])
            assertEquals("blackboard/kanban/claim/${kids[0]}", criterion["evidence"], "the first child in dependency order is the one the fake brain cites")
            val started = (receipt["startedAtMs"] as Number).toLong()
            val finished = (receipt["finishedAtMs"] as Number).toLong()
            assertTrue(started in 1..finished, "startedAtMs ≤ finishedAtMs, both stamped by the worker's clock: $receipt")
            assertEquals(finished, (receipt["atMs"] as Number).toLong(), "atMs stays the moment the answer was in hand")
            assertEquals(fakeLatencyMs, (receipt["latencyMs"] as Number).toLong(), "latencyMs is copied from prompt.chat, never computed")
            assertEquals(fakeInputTokens, (receipt["inputTokens"] as Number).toInt())
            assertEquals(fakeOutputTokens, (receipt["outputTokens"] as Number).toInt())
            assertEquals(false, receipt["cachedHit"])
            for (kid in kids) {
                val r = claimReceipt(rig, kid)
                assertEquals(true, r["ok"], "child $kid: $r")
                assertEquals("DONE", r["decision"], "child $kid: $r")
                assertEquals(fakeLatencyMs, (r["latencyMs"] as Number).toLong())
            }

            // ── every hop the page will show has a server time ──
            assertEveryCommitIsStamped(rig)
        } finally {
            tearDown(rig)
        }
    }

    @Test
    fun aBlockedChildBlocksTheParentVisibly() {
        val prompts = CopyOnWriteArrayList<Map<String, String>>()
        val rig = rig("blocked", fakeBrain(prompts, dead = setOf("beta")))
        try {
            invoke(rig, submitModelsCard("p2", "One of three is dead"))
            val kids = childrenOf("p2")
            // beta strikes out: READY → RUNNING → READY twice (strikes 1, 2), then BLOCKED with the owner cleared;
            // the parent, waiting in TODO on its children, follows it into BLOCKED naming the child.
            val seen = awaitStatus(rig, "blocked", "p2", kids[1], timeoutMs = 30_000)
            val beta = seen.getValue(kids[1])
            assertEquals("", beta["owner"], "BLOCKED clears the claimant so a person sees it")
            assertEquals(3, (beta["strikes"] as Number).toInt(), "three strikes ride the row")
            assertEquals(listOf("todo", "ready", "running", "ready", "running", "ready", "running", "blocked"), trail(rig, kids[1]))
            val betaReceipt = claimReceipt(rig, kids[1])
            assertEquals(false, betaReceipt["ok"])
            assertEquals("RETRY", betaReceipt["decision"])
            assertTrue(betaReceipt["error"].toString().startsWith("beta is dead"), betaReceipt.toString())
            assertEquals(3, promptsFor(prompts, kids[1]).size, "one brain call per strike")

            val parent = seen.getValue("p2")
            assertEquals("", parent["owner"], "the parent was never claimed: nobody owns it")
            assertEquals(kids, strings(parent["dependencies"]))
            assertEquals(listOf("todo", "todo", "blocked"), trail(rig, "p2"), "submitted, joined, parked — never claimed whole")
            assertNull(rig.ctx.blackboard.get(BoardClaimWorker.RECEIPT_PREFIX + "p2"), "a parent behind a blocked child is never claimed")
            assertTrue(promptsFor(prompts, "p2").isEmpty(), "no merge without every child")

            // the receipt says WHICH child: kanban/rule/dependency-blocked/<activationId> with blockedBy
            val blockedRules = rig.ctx.blackboard.keys()
                .filter { it.startsWith("kanban/rule/${BoardRules.DEPENDENCY_BLOCKED}/") }
                .map { rig.ctx.blackboard.get(it) as Map<*, *> }
                .filter { it["jobId"] == "p2" }
            assertTrue(blockedRules.isNotEmpty(), "the dependency-blocked rule fired for p2; rule keys: ${rig.ctx.blackboard.keys().filter { it.startsWith("kanban/rule/") }}")
            assertTrue(blockedRules.all { it["blockedBy"] == kids[1] }, "the blocking child is named: $blockedRules")
            assertTrue(blockedRules.all { it["toColumn"] == "blocked" }, blockedRules.toString())
            val lastCommit = committed(rig, "p2").last()
            assertEquals("blocked", lastCommit["col"])
            assertEquals("todo", lastCommit["from"])

            // the two live children still finish on their own: the tree is not torn down, it waits for a person
            val rest = awaitStatus(rig, "done", kids[0], kids[2], timeoutMs = 20_000)
            assertEquals("claim:brain", rest.getValue(kids[0])["owner"])
            assertEquals("claim:brain", rest.getValue(kids[2])["owner"])
            runBlocking { delay(300) }
            assertEquals("blocked", items(rig).getValue("p2")["status"], "BLOCKED is not TODO: dependency-ready never lifts a parked parent")
            assertEveryCommitIsStamped(rig)
        } finally {
            tearDown(rig)
        }
    }

    @Test
    fun aModelsCardDraggedToReadyIsFannedOutNotClaimedWhole() {
        val prompts = CopyOnWriteArrayList<Map<String, String>>()
        val rig = rig("dragged", fakeBrain(prompts))
        try {
            // A person drags the MODELS: card straight to Ready. The claim production must skip
            // it (fanOutPending), the fan-out production splits it from Ready, and the join
            // brings it back to Todo until the children are Done.
            invoke(
                rig,
                submitModelsCard("p3", "Dragged to ready at once"),
                mapOf("type" to "move", "jobId" to "p3", "idempotencyKey" to "p3#drag", "expectedRevision" to 1, "toColumn" to "ready", "actor" to "jim"),
            )
            val kids = childrenOf("p3")
            val done = awaitStatus(rig, "done", "p3", *kids.toTypedArray(), timeoutMs = 20_000)

            assertEquals(kids.toSet(), done.values.filter { it["parent"] == "p3" }.map { it["id"] }.toSet(), "exactly three children")
            assertEquals(kids, strings(done.getValue("p3")["dependencies"]))
            // submit → drag → join (back to Todo) → fan-in → claim → review → done: claimed exactly once, after the join
            assertEquals(listOf("todo", "ready", "todo", "ready", "running", "review", "done"), trail(rig, "p3"))

            val parentPrompts = promptsFor(prompts, "p3")
            assertEquals(1, parentPrompts.size, "exactly one parent prompt — the merge, never the whole card: ${parentPrompts.map { it["prompt"]?.take(80) }}")
            assertTrue("CHILDREN" in parentPrompts.single()["prompt"].orEmpty(), "the one parent prompt is the merge")
            assertEquals(4, prompts.size, "three children and one merge: ${prompts.map { it["prompt"]?.lineSequence()?.first() }}")

            // the split was proposed from both the submitted and the dragged revision; the claim only from the fanned-in one
            assertTrue(rig.ctx.blackboard.get("kanban/rule/${BoardRules.FAN_OUT}/fan-out-p3-r1") != null, "proposed from Todo (r1)")
            assertTrue(rig.ctx.blackboard.get("kanban/rule/${BoardRules.FAN_OUT}/fan-out-p3-r2") != null, "proposed again from Ready (r2)")
            val claimRules = rig.ctx.blackboard.keys().filter { it.startsWith("kanban/rule/${BoardRules.CLAIM}/claim-p3-r") }
            assertEquals(1, claimRules.size, "one claim proposal for the parent: $claimRules")
            assertFalse("kanban/rule/${BoardRules.CLAIM}/claim-p3-r2" in claimRules, "the dragged revision was never claimed whole")
            val fanout3 = awaitKey(rig, BoardFanOutWorker.RECEIPT_PREFIX + "p3")
            assertEquals(kids, fanout3["children"])
            // the dragged revision owns the receipt: the r1 worker's join was refused (or never run) and never overwrote it
            assertFanOutLanded(fanout3, "p3", revision = 2L)
            assertEveryCommitIsStamped(rig)
        } finally {
            tearDown(rig)
        }
    }

    @Test
    fun fanOutSurvivesAColdStart() {
        val prompts = CopyOnWriteArrayList<Map<String, String>>()
        // Life 1: no brain. The split happens (it needs no model call), the claim declines
        // every child ("no brain"), the children wait in Ready and the parent in Todo.
        val rig = rig("cold", brain = null)
        try {
            invoke(rig, submitModelsCard("p4", "Survives a restart"))
            val kids = childrenOf("p4")
            awaitBoard(rig, "the split and the join") { board ->
                kids.all { board[it]?.get("status") == "ready" } && board["p4"]?.let { strings(it["dependencies"]) } == kids
            }
            for (kid in kids) {
                val declined = awaitKey(rig, BoardClaimWorker.RECEIPT_PREFIX + kid)
                assertEquals(false, declined["ok"])
                assertTrue(declined["error"].toString().startsWith("no brain"), declined.toString())
            }
            runBlocking { delay(300) }
            val parked = items(rig)
            assertEquals("todo", parked.getValue("p4")["status"])
            for (kid in kids) assertEquals("ready", parked.getValue(kid)["status"], "no brain, no claim: $kid waits")
            assertTrue(prompts.isEmpty())
            val childRevisions = kids.associateWith { (parked.getValue(it)["revision"] as Number).toLong() }

            // Restart: detach, hand the context a brain, attach on the same stateDir. The WAL
            // replays the tree (parent, spec, dependencies), the seed re-asserts the facts, and
            // the loop resumes: children claimed, fan-in, merge, done — once.
            runBlocking { rig.supervisor.detach("kanban") }
            rig.ctx.lcncRunners["prompt.chat"] = fakeBrain(prompts)
            runBlocking { rig.supervisor.attach(KanbanModule()) }

            val done = awaitStatus(rig, "done", "p4", *kids.toTypedArray(), timeoutMs = 20_000)
            assertEquals(kids.toSet(), done.values.filter { it["parent"] == "p4" }.map { it["id"] }.toSet(), "the replayed tree has exactly the three children — no second split")
            assertEquals(kids, strings(done.getValue("p4")["dependencies"]), "the join survived the restart")
            assertEquals("claim:brain", done.getValue("p4")["owner"])
            for (kid in kids) {
                assertEquals(1, promptsFor(prompts, kid).size, "child $kid is claimed exactly once after the restart")
                assertTrue((done.getValue(kid)["revision"] as Number).toLong() > childRevisions.getValue(kid), "the child moved after the restart")
                assertEquals("done", trail(rig, kid).last())
            }
            val merges = promptsFor(prompts, "p4")
            assertEquals(1, merges.size, "one merge: ${merges.map { it["prompt"]?.take(80) }}")
            assertTrue("CHILDREN" in merges.single()["prompt"].orEmpty())
            assertEquals(4, prompts.size, "three children and one merge, nothing twice: ${prompts.map { it["prompt"]?.lineSequence()?.first() }}")

            val receipt = claimReceipt(rig, "p4")
            assertEquals("DONE", receipt["decision"], receipt.toString())
            assertEquals("blackboard/kanban/claim/${kids[0]}", ((receipt["criteria"] as List<*>).first() as Map<*, *>)["evidence"])
            // submit, join (life 1) → dependency-ready, claim, judge (life 2): the tree completes ONCE —
            // the seed re-asserts the parent before its children and must not re-split or re-join it
            assertEquals(listOf("todo", "todo", "ready", "running", "review", "done"), trail(rig, "p4"), "one split, one join, one fan-in across both lives")
            val fanout4 = awaitKey(rig, BoardFanOutWorker.RECEIPT_PREFIX + "p4")
            assertFanOutLanded(fanout4, "p4", revision = 1L)
            assertEquals(kids, fanout4["children"])
            assertEquals(1, committed(rig, "p4").count { it["op"] == "submit" && it["revision"].toString() != "1" }, "exactly one join: ${committed(rig, "p4")}")
            assertEveryCommitIsStamped(rig)
        } finally {
            tearDown(rig)
        }
    }

    /**
     * The split predicate terminates on the parent's OWN fact, so neither a hand-submitted
     * sub-card (`parent` set, not worker-minted) nor archived children ever re-split or
     * withhold a joined parent: a person who cancels the children and drags the parent to
     * Ready gets it claimed (here: declined by "no brain", with a receipt), never a card
     * that sits in Ready with nothing on the blackboard saying why.
     */
    @Test
    fun archivedChildrenAndHandSubmittedSubCardsNeverStallAJoinedParent() {
        val rig = rig("archived", brain = null)
        try {
            invoke(rig, submitModelsCard("p5", "Children come and go"))
            val kids = childrenOf("p5")
            val joined = awaitBoard(rig, "the split and the join") { board ->
                kids.all { board[it]?.get("status") == "ready" } && board["p5"]?.let { strings(it["dependencies"]) } == kids
            }
            assertEquals(2L, (joined.getValue("p5")["revision"] as Number).toLong(), "submit, then the join")

            // a person's sub-card under the parent is part of the tree, not of the split
            invoke(rig, mapOf("type" to "submit", "jobId" to "p5-note", "idempotencyKey" to "p5-note#submit", "title" to "a note under p5", "parent" to "p5"))
            awaitBoard(rig, "the sub-card") { it["p5-note"]?.get("parent") == "p5" }
            runBlocking { delay(400) }
            val after = items(rig)
            assertEquals(2L, (after.getValue("p5")["revision"] as Number).toLong(), "no second join: the sub-card is not a minted child")
            assertEquals(kids, strings(after.getValue("p5")["dependencies"]))
            assertNull(rig.ctx.blackboard.get("kanban/rule/${BoardRules.FAN_OUT}/fan-out-p5-r2"), "the split never re-fires once joined")

            // cancel every child (never claimed: no brain), then drag the parent to Ready
            val cancelled = invoke(rig, *kids.map { kid ->
                mapOf("type" to "cancel", "jobId" to kid, "idempotencyKey" to "$kid#cancel", "expectedRevision" to (after.getValue(kid)["revision"] as Number).toLong())
            }.toTypedArray())
            assertTrue(cancelled.all { it["verdict"] == "committed" }, "cancels landed: $cancelled")
            awaitBoard(rig, "children archived") { b -> kids.all { b[it]?.get("status") == "archived" } }
            invoke(rig, mapOf("type" to "move", "jobId" to "p5", "idempotencyKey" to "p5#drag", "expectedRevision" to 2, "toColumn" to "ready", "actor" to "jim"))

            // claimed (declined with a receipt), not withheld, not re-split
            val receipt = awaitKey(rig, BoardClaimWorker.RECEIPT_PREFIX + "p5")
            assertTrue(receipt["error"].toString().startsWith("no brain"), "the parent was claimed like any card, and the receipt says why it stopped: $receipt")
            assertTrue(blackboardKeys(rig).any { it.startsWith("kanban/rule/${BoardRules.CLAIM}/claim-p5-r3") }, "the claim rule proposed the parent at the dragged revision")
            assertNull(rig.ctx.blackboard.get("kanban/rule/${BoardRules.FAN_OUT}/fan-out-p5-r3"), "archived children do not re-open the split")
            assertEquals("ready", items(rig).getValue("p5")["status"], "no brain, no move — but claimed, with a receipt")
        } finally {
            tearDown(rig)
        }
    }
}
