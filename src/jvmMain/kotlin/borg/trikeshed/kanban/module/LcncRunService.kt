package borg.trikeshed.kanban.module

import borg.trikeshed.job.ContentId
import borg.trikeshed.kanban.BoardApply
import borg.trikeshed.kanban.BoardIntake
import borg.trikeshed.kanban.BoardStoreElement
import borg.trikeshed.lcnc.*
import borg.trikeshed.lcnc.ccek.LcncCcekAssembly
import borg.trikeshed.litebike.JvmKanbanServer.HttpResponse
import borg.trikeshed.module.ModuleContext
import borg.trikeshed.parse.json.JsonSupport
import borg.trikeshed.parse.json.ValueBudget
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Execution resources belong to the caller; receipts project the existing durable work log. */
internal class LcncRunService(
    private val ctx: ModuleContext,
    private val store: BoardStoreElement,
    private val vocabulary: () -> Map<String, LcncPortContract>,
) {
    private val active = ConcurrentHashMap<String, Job>()
    private val slots = Semaphore(3)
    private var draining = false

    private fun response(status: Int, body: Map<String, Any?>) = HttpResponse(status, JsonSupport.stringify(body))

    suspend fun content(cidText: String?, view: String?, programKey: String?): HttpResponse = withContext(Dispatchers.IO) {
        val cid = runCatching { ContentId(cidText.orEmpty()) }.getOrNull()
            ?: return@withContext response(400, mapOf("error" to "invalid_content_id"))
        if (view != null && view !in listOf("raw", "sheet"))
            return@withContext response(400, mapOf("error" to "invalid_content_view"))
        val bytes = ctx.casStore.get(cid) ?: run {
            // A published, unexecuted preset may not yet have durable bytes.
            // The key only locates a candidate; the requested CID still decides identity.
            val entry = programKey?.takeIf { it.startsWith(LcncBlackboard.PROGRAM_PREFIX) }
                ?.let { ctx.blackboard.snapshot().values[it] }
                ?: return@withContext response(404, mapOf("error" to "content_not_found"))
            ValueBudget().violation(entry)?.let { return@withContext response(413, mapOf("error" to it)) }
            val program = LcncBlackboard.programOf(entry)
                ?: return@withContext response(422, mapOf("error" to "content_not_program"))
            LcncProgramConfix.toJson(program).encodeToByteArray()
        }
        if (bytes.size > 1_048_576) return@withContext response(413, mapOf("error" to "payload_limit"))
        if (ContentId.of(bytes) != cid) return@withContext response(409, mapOf("error" to "content_identity_mismatch"))
        val text = bytes.decodeToString()
        val value = runCatching { JsonSupport.parse(text) }.getOrElse {
            return@withContext response(422, mapOf("error" to "content_not_json"))
        }
        ValueBudget().violation(value)?.let { return@withContext response(413, mapOf("error" to it)) }
        if (view != "sheet") return@withContext HttpResponse(200, text)
        val doc = borg.trikeshed.parse.confix.confixDoc(bytes, borg.trikeshed.parse.confix.Syntax.JSON)
        val sheets = borg.trikeshed.forge.sheet.confixSheets(cid.value, cid.value, doc, maxRows = 512).map { it.toMap() }
        ValueBudget(maxNodes = 32768, maxChars = 131072).violation(sheets)?.let {
            return@withContext response(413, mapOf("error" to it))
        }
        HttpResponse(200, JsonSupport.stringify(sheets))
    }

    private fun project(receipt: Map<String, Any?>, commit: BoardApply.Committed): Map<String, Any?> {
        val value = receipt + mapOf(
            "jobId" to commit.jobId, "sequence" to commit.sequence,
            "timelineRevision" to commit.revision, "receiptCid" to commit.cid.value,
        )
        ctx.blackboard.putIf(commit.jobId, value, "lcnc-runner") { current ->
            val revision = ((current as? Map<*, *>)?.get("timelineRevision") as? Number)?.toLong() ?: -1L
            revision <= commit.revision
        }
        return value
    }

    private suspend fun commit(jobId: String, op: String, receipt: Map<String, Any?>, revision: Long): Map<String, Any?> {
        val reply = CompletableDeferred<BoardApply>()
        val raw = mapOf(
            "type" to op, "jobId" to jobId, "expectedRevision" to revision,
            "idempotencyKey" to "$jobId:${receipt["status"]}:$revision",
            "title" to "LCNC ${receipt["program"]}", "owner" to "lcnc-runner",
            "tags" to listOf("lcnc-run"),
            "lcncRun" to (receipt - setOf("sequence", "timelineRevision", "receiptCid")),
            "reason" to (receipt["error"] ?: receipt["status"]),
        )
        store.intake.send(BoardIntake(raw, reply))
        return when (val result = reply.await()) {
            is BoardApply.Committed -> project(receipt, result)
            is BoardApply.Rejected -> error("run commit refused: ${result.reason}")
        }
    }

    /** Rebuild from CAS pointers reconstructed by the board WAL, never from browser state. */
    suspend fun recover() {
        for (row in store.cards().filter { it.owner == "lcnc-runner" && it.jobId.startsWith("lcnc/run/") }) {
            @Suppress("UNCHECKED_CAST")
            val receipt = store.command(row.jobId)?.get("lcncRun") as? Map<String, Any?> ?: continue
            if (receipt["status"] in listOf("validating", "running")) {
                commit(row.jobId, "cancel", receipt + mapOf(
                    "ok" to false, "status" to "interrupted", "error" to "runtime_restarted",
                    "previousReceiptCid" to row.commandCid?.value,
                    "finishedAtMs" to ctx.clock(),
                ), row.revision)
            } else {
                project(receipt, BoardApply.Committed(row.jobId, row.lastSequence, row.revision, "replay", row.commandCid ?: continue))
            }
        }
    }

    fun cancel(runId: String): Boolean = active[runId]?.let { it.cancel(CancellationException("cancelled by user")); true } ?: false

    suspend fun drain() {
        draining = true
        val jobs = active.values.toList()
        jobs.forEach { it.cancel(CancellationException("module draining")) }
        withTimeoutOrNull(5000) { jobs.joinAll() }
    }

    suspend fun execute(name: String, program: LcncProgram, named: Boolean, inputs: Map<String, Any?>, request: Map<*, *>): HttpResponse {
        if (draining || !slots.tryAcquire()) return response(429, mapOf("ok" to false, "error" to "execution_capacity"))
        val runId = UUID.randomUUID().toString()
        val jobId = "lcnc/run/$runId"
        try {
            return coroutineScope {
                active[runId] = currentCoroutineContext().job
                val timeoutMs = ((request["timeoutMs"] as? Number)?.toLong() ?: 120000L).coerceIn(1L, 120000L)
                val maxNodes = ((request["maxNodes"] as? Number)?.toInt() ?: 10000).coerceIn(1, 10000)
                val versions = linkedMapOf<String, String>()
                val pinned = mutableMapOf<String, LcncProgram>()
                suspend fun freeze(label: String, source: LcncProgram): Pair<LcncProgram, String> = withContext(Dispatchers.IO) {
                    val bytes = LcncProgramConfix.toJson(source).encodeToByteArray()
                    require(bytes.size <= 1_048_576) { "program payload_limit" }
                    LcncProgramConfix.fromJson(label, bytes.decodeToString()) to ctx.casStore.put(bytes).value
                }
                val (frozen, cid) = freeze(name, program)
                if (named) { pinned[name] = frozen; versions[name] = cid }
                var receipt: Map<String, Any?> = mapOf(
                    "runId" to runId, "program" to name, "programKey" to if (named) LcncBlackboard.programKey(name) else null,
                    "programCid" to cid, "inputs" to inputs, "startedAtMs" to ctx.clock(),
                    "versionPolicy" to "root-at-admission,subprogram-at-first-use",
                    "budgets" to mapOf("timeoutMs" to timeoutMs, "maxNodes" to maxNodes, "maxPayloadChars" to 131072),
                )
                var revision = 0L
                suspend fun record(op: String, status: String, fields: Map<String, Any?> = emptyMap()): Map<String, Any?> {
                    receipt = commit(jobId, op, receipt + fields + mapOf(
                        "status" to status, "programVersions" to versions.toMap(),
                        "previousReceiptCid" to receipt["receiptCid"],
                    ), revision)
                    revision = (receipt["timelineRevision"] as Number).toLong()
                    return receipt
                }
                suspend fun finish(code: Int, op: String, status: String, fields: Map<String, Any?>): HttpResponse =
                    response(code, record(op, status, fields + ("finishedAtMs" to ctx.clock())))

                record("submit", "validating")
                try {
                    val contracts = vocabulary()
                    val violations = LcncTypeCheck.check(frozen, contracts, strict = false).map { it.toMap() }
                    if (violations.isNotEmpty()) return@coroutineScope finish(400, "fail", "refused", mapOf(
                        "ok" to false, "phase" to "validation", "error" to "type_check_failed", "violations" to violations.take(128),
                    ))
                    record("start", "running")
                    val walker = LcncRunner(ctx.lcncRunners).apply {
                        maxNodeExecutions = maxNodes
                        subprogramLoader = { label ->
                            pinned[label] ?: ctx.programLoader(label)?.let { source ->
                                val (body, version) = freeze(label, source)
                                val problems = LcncTypeCheck.check(body, contracts, strict = false)
                                require(problems.isEmpty()) { "subprogram type_check_failed: $label" }
                                pinned[label] = body; versions[label] = version
                                body
                            }
                        }
                    }
                    val result = withTimeout(timeoutMs) {
                        val binding = ctx.ccekBinding
                        if (binding == null) walker.runProcedure(frozen, inputs)
                        else {
                            val assembly = LcncCcekAssembly(binding, walker).launch(name, frozen, inputs)
                            try { assembly.result.await() } finally { assembly.cancel("run scope closed") }
                        }
                    }
                    val output = mapOf("returns" to result.returns, "outputs" to result.nodeOutputs,
                        "bindings" to result.bindings, "bindingsTruncated" to result.bindingsTruncated)
                    val limit = ValueBudget().violation(output)
                    if (limit != null) finish(413, "fail", "failed", mapOf("ok" to false, "phase" to "reporting", "error" to limit))
                    else finish(200, "complete", "completed", output + ("ok" to true))
                } catch (e: TimeoutCancellationException) {
                    finish(504, "cancel", "timed_out", mapOf("ok" to false, "error" to "time_limit"))
                } catch (e: CancellationException) {
                    withContext(NonCancellable) {
                        withTimeout(5000) { finish(499, "cancel", "cancelled", mapOf("ok" to false, "error" to (e.message ?: "cancelled"))) }
                    }
                    throw e
                } catch (e: Exception) {
                    finish(400, "fail", "failed", mapOf("ok" to false, "phase" to "execution", "error" to (e.message ?: "execution_failed").take(2048)))
                }
            }
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            @Suppress("UNCHECKED_CAST")
            val receipt = ctx.blackboard.get(jobId) as? Map<String, Any?>
            return response(499, receipt ?: mapOf("ok" to false, "runId" to runId, "error" to "cancelled"))
        } catch (e: Exception) {
            return response(503, mapOf("ok" to false, "runId" to runId, "error" to "receipt_commit_failed", "detail" to (e.message ?: "unavailable").take(2048)))
        } finally {
            active.remove(runId)
            slots.release()
        }
    }
}
