package borg.trikeshed.kanban.module

import borg.trikeshed.parse.reify
import borg.trikeshed.parse.jsonOf

import borg.trikeshed.context.nuid.*
import borg.trikeshed.collections.associative.*
import borg.trikeshed.job.*
import borg.trikeshed.kanban.BoardApply
import borg.trikeshed.kanban.BoardIntake
import borg.trikeshed.kanban.BoardStoreElement
import borg.trikeshed.lcnc.*
import borg.trikeshed.lcnc.ccek.LcncCcekAssembly
import borg.trikeshed.lcnc.ccek.LcncWorkgroupKey
import borg.trikeshed.lcnc.ccek.LcncRunQueueEntry
import borg.trikeshed.lcnc.ccek.LcncWorkgroupElement
import borg.trikeshed.lib.*
import borg.trikeshed.litebike.JvmKanbanServer.HttpResponse
import borg.trikeshed.module.ModuleContext
import borg.trikeshed.parse.ValueBudget
import borg.trikeshed.vm.VmSourceCoordinates
import borg.trikeshed.userspace.nio.file.spi.StorageDurability
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import java.lang.System
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Execution resources belong to the caller; receipts project the existing durable work log. */
internal class LcncRunService(
    private val ctx: ModuleContext,
    private val store: BoardStoreElement,
    /** Consumed-document facts per completed run (Forge genesis, Cut S); null keeps the plane silent. */
    private val facts: borg.trikeshed.lcnc.LcncRunFacts? = null,
    private val vocabulary: () -> Map<String, LcncPortContract>,
) {
    private val active = ConcurrentHashMap<String, Job>()
    private data class DebugRun(val runId: String, val control: LcncDebug)
    private val debuggers = ConcurrentHashMap<String, DebugRun>()
    val oroboros = LcncOroboros(ctx)
    private val slots = Semaphore(3)
    private val admission = Mutex()
    private class RunPermit(val runId: String, val parentRunId: String?, val family: PermitFamily,
                            val queued: Boolean = false, val preparation: Boolean = false) : AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<RunPermit>
    }
    private class PermitFamily(val runId: String, var references: Int = 0)
    private val permits = mutableMapOf<RunPermit, PermitFamily>()
    private val drained = CompletableDeferred<Unit>()
    private var admittedRuns = 0
    /** Completes at [stopAdmission]; [drain] then awaits the admitted runs. */
    val stopped: CompletableJob = Job()
    private var recoveredQueue: Series<LcncRunQueueEntry> = emptySeriesOf()

    private fun response(status: Int, body: Map<String, Any?>) = HttpResponse(status, jsonOf(body))

    private class EvidenceFailure(val status: Int, val code: String) : IllegalArgumentException(code)
    class QueueFailure(val status: Int, val code: String) : IllegalStateException(code)

    private data class QueuedRun(val receipt: Map<String, Any?>, val program: LcncProgram,
                                 val request: Map<String, Any?>, val inputs: Map<String, Any?>,
                                 val pins: Map<String, Join<LcncProgram, String>>, val case: LcncCaseFrame?)

    private fun caseStatus(failure: LcncCaseFailure): Int = when (failure) {
        LcncCaseFailure.NOT_FOUND -> 404
        LcncCaseFailure.CAPACITY -> 429
        LcncCaseFailure.INVALID -> 400
        LcncCaseFailure.CONFLICT, LcncCaseFailure.UNSUPPORTED -> 409
    }

    private suspend fun caseFrame(program: LcncProgram, cid: ContentId, inputs: Map<String, Any?>,
                                  request: Map<*, *>, retained: Boolean): LcncCaseFrame? {
        if (inputs.keys.any { it.startsWith(LcncCaseProgram.BINDING_PREFIX) })
            throw QueueFailure(400, "reserved_case_input")
        if (!request.containsKey("case")) return null
        if (setOf("pulse", "baseReceiptCid", "sourceReceiptCid", "selectedPulse").any { request.containsKey(it) } || inputs.containsKey("evidence"))
            throw QueueFailure(400, "case_execution_protocol_conflict")
        val frame = try {
            when (val value = request["case"]) {
                is LcncCaseRequest -> {
                    if (retained) throw QueueFailure(400, "invalid_retained_case_request")
                    LcncCaseFrame.retain(ctx.casStore, value, cid, inputs)
                }
                is Map<*, *> -> {
                    if (!retained || value.keys != setOf("intent", "requestCid")) throw QueueFailure(400, "invalid_case_request")
                    val frame = LcncCaseFrame.load(ctx.casStore, ContentId(value["requestCid"] as? String ?: ""))
                    if (value["intent"] != frame.request.intent) throw QueueFailure(400, "invalid_retained_case_request")
                    frame
                }
                else -> throw QueueFailure(400, "invalid_case_request")
            }
        } catch (failure: CancellationException) { throw failure }
        catch (failure: QueueFailure) { throw failure }
        catch (failure: LcncCaseException) { throw QueueFailure(caseStatus(failure.failure), failure.message ?: "invalid_case_request") }
        catch (_: IllegalArgumentException) { throw QueueFailure(400, "invalid_case_request") }
        // The immutable CBOR envelope owns inputs; the ordinary JSON receipt is only its preview.
        if (frame.programCid != cid || reify(jsonOf(frame.inputs)) != reify(jsonOf(inputs)))
            throw QueueFailure(409, "retained_case_request_identity_mismatch")
        try { if (frame.request !is LcncCaseRequest.Read) LcncCaseProgram.validate(program) }
        catch (_: IllegalArgumentException) { throw QueueFailure(400, "invalid_case_program") }
        return frame
    }

    /** Private NUID ownership and the exact queued row, beyond public invocation metadata. */
    private suspend fun caseRun(runId: String, cid: String, queued: QueuedRun?,
                                routing: Join<Workgroup, NuidFanoutElement.Claim>?): LcncCaseRun {
        fun refuse(): Nothing = throw LcncCaseException(LcncCaseFailure.CONFLICT, "case_requires_live_queued_claim")
        val live = currentCoroutineContext()[NuidFanoutElement]?.currentInvocation() ?: refuse()
        if (routing == null || live.a != routing.a || live.b !== routing.b || queued == null) refuse()
        val payload = live.b.payload as? Join<*, *> ?: refuse()
        val ref = payload.a as? BoardApply.Committed ?: refuse()
        val expected = queuedRef(queued.receipt)
        if (ref.jobId != "lcnc/run/$runId" || ref.jobId != expected.jobId || ref.cid != expected.cid ||
            ref.sequence != expected.sequence || ref.revision != 1L || queued.receipt["programCid"] != cid) refuse()
        return LcncCaseRun(runId, live.a.name, live.b.claimId, ref.cid)
    }

    private suspend fun pinnedVersions(program: LcncProgram, cid: ContentId,
                                       request: Map<*, *>): Map<String, Join<LcncProgram, String>> {
        if (!request.containsKey("pinnedVersions")) return emptyMap()
        val versions = request["pinnedVersions"] as? Map<*, *>
            ?: throw QueueFailure(400, "invalid_pinned_versions")
        val contracts = vocabulary()
        val pins = linkedMapOf<String, Join<LcncProgram, String>>()
        for ((label, version) in versions) {
            val name = (label as? String)?.takeIf { it.isNotBlank() }
                ?: throw QueueFailure(400, "invalid_pinned_versions")
            val value = version as? String ?: throw QueueFailure(400, "invalid_pinned_versions")
            val expected = runCatching { ContentId(value) }.getOrElse { throw QueueFailure(400, "invalid_pinned_version_cid") }
            if (name == program.name && expected != cid) throw QueueFailure(409, "pinned_root_identity_mismatch")
            val frozen = if (name == program.name) program else {
                val bytes = try { withContext(Dispatchers.IO) { ctx.casStore.get(expected) } }
                    catch (failure: CancellationException) { throw failure }
                    catch (_: Exception) { throw QueueFailure(409, "pinned_version_unreadable") }
                    ?: throw QueueFailure(404, "pinned_version_not_found")
                if (ContentId.of(bytes) != expected) throw QueueFailure(409, "pinned_version_identity_mismatch")
                try { LcncProgramConfix.fromJson(name, bytes.decodeToString()) }
                    catch (failure: CancellationException) { throw failure }
                    catch (_: Exception) { throw QueueFailure(400, "invalid_pinned_program") }
            }
            if (frozen.controls.inspectionOnly || LcncTypeCheck.check(frozen, contracts, strict = false).isNotEmpty())
                throw QueueFailure(400, "pinned_program_type_check_failed")
            if (name != program.name) pins[name] = frozen j value
        }
        return pins
    }

    private suspend fun receiptCommand(cid: ContentId, maxBytes: Int? = null): Map<String, Any?> = withContext(Dispatchers.IO) {
        fun invalid(): Nothing = throw EvidenceFailure(400, "invalid_source_receipt")
        val bytes = try { ctx.casStore.get(cid) }
            catch (failure: CancellationException) { throw failure }
            catch (_: Exception) { throw EvidenceFailure(409, "source_receipt_unreadable") }
            ?: throw EvidenceFailure(404, "source_receipt_not_found")
        if (ContentId.of(bytes) != cid) throw EvidenceFailure(409, "source_receipt_identity_mismatch")
        if (maxBytes != null && bytes.size > maxBytes) throw EvidenceFailure(413, "source_receipt_payload_limit")
        val command = runCatching { reify(bytes) as? Map<*, *> }.getOrNull() ?: invalid()
        val receipt = command["lcncRun"] as? Map<*, *> ?: invalid()
        val runId = (receipt["runId"] as? String)?.takeIf { it.isNotBlank() } ?: invalid()
        val jobId = command["jobId"] as? String ?: invalid()
        runCatching { ContentId(receipt["programCid"] as? String ?: "") }.getOrNull() ?: invalid()
        if (command["owner"] != "lcnc-runner" || jobId != "lcnc/run/$runId") invalid()
        if (receipt.keys.any { it !is String } || command.keys.any { it !is String }) invalid()
        @Suppress("UNCHECKED_CAST")
        command as Map<String, Any?>
    }

    private suspend fun committedReceipt(cid: ContentId, maxBytes: Int? = null): Map<String, Any?> {
        val command = receiptCommand(cid, maxBytes)
        if (store.card(command["jobId"] as String)?.commandCid != cid)
            throw EvidenceFailure(409, "source_receipt_not_committed")
        return command
    }

    private suspend fun completedReceipt(cid: ContentId, currentHead: Boolean = true): Map<String, Any?> {
        val command = if (currentHead) committedReceipt(cid, 1_048_576) else receiptCommand(cid, 1_048_576)
        @Suppress("UNCHECKED_CAST")
        val receipt = command["lcncRun"] as Map<String, Any?>
        if (command["type"] != "complete" || receipt["status"] != "completed" || receipt["ok"] != true)
            throw EvidenceFailure(409, "source_receipt_not_completed")
        val returns = receipt["returns"] as? Map<*, *> ?: throw EvidenceFailure(400, "invalid_source_receipt")
        if (returns.keys.any { it !is String }) throw EvidenceFailure(400, "invalid_source_receipt")
        return receipt
    }

    private suspend fun sourceEvidence(cid: ContentId): Join<String, Map<String, Any?>> {
        val receipt = completedReceipt(cid)
        return ("lcnc/run/" + receipt["runId"]) j mapOf("receiptCid" to cid.value,
            "runId" to receipt["runId"], "programCid" to receipt["programCid"], "returns" to receipt["returns"])
    }

    private data class PulseAdmission(val selection: LcncPulse, val receiptCid: String, val runId: String,
                                      val versions: Map<String, String>, val runners: Map<String, LcncNodeRunner>)

    private suspend fun pulseBytes(cid: ContentId): ByteArray = withContext(Dispatchers.IO) {
        val bytes = try { ctx.casStore.get(cid) }
            catch (failure: CancellationException) { throw failure }
            catch (_: Exception) { throw EvidenceFailure(409, "pulse_content_unreadable") }
            ?: throw EvidenceFailure(404, "pulse_content_not_found")
        if (bytes.size > 1_048_576) throw EvidenceFailure(413, "pulse_state_payload_limit")
        if (ContentId.of(bytes) != cid) throw EvidenceFailure(409, "pulse_content_identity_mismatch")
        bytes
    }

    /** Reloadable selection: no browser memo and no transient CoroutineContext-only mode. */
    private suspend fun pulseAdmission(program: LcncProgram, inputs: Map<String, Any?>,
                                       request: Map<*, *>, retainedBase: Boolean = false): PulseAdmission? {
        if (!request.containsKey("pulse") && !request.containsKey("baseReceiptCid")) return null
        fun invalid(): Nothing = throw EvidenceFailure(400, "invalid_pulse")
        val cid = runCatching { ContentId(request["baseReceiptCid"] as? String ?: "") }.getOrNull() ?: invalid()
        val event = request["pulse"] as? Map<*, *> ?: invalid()
        if (event.keys != setOf("nodeId", "outputs")) invalid()
        val nodeId = event["nodeId"] as? String ?: invalid()
        val eventOutputs = event["outputs"] as? Map<*, *> ?: invalid()
        if (eventOutputs.keys.any { it !is String }) invalid()
        // Only authenticated queued/rebuild records supply retainedBase; public requests never do.
        val base = completedReceipt(cid, currentHead = !retainedBase)
        if (base["program"] != program.name || base["inputs"] != reify(jsonOf(inputs)))
            throw EvidenceFailure(409, "pulse_invocation_drift")
        val programCid = ContentId(base["programCid"] as String)
        val programBytes = pulseBytes(programCid)
        val previous = try { LcncProgramConfix.fromJson(program.name, programBytes.decodeToString()) }
            catch (_: Exception) { invalid() }
        if (!LcncProgramConfix.sameDefinition(previous, program))
            throw EvidenceFailure(409, "pulse_definition_drift")
        val stateCid = (base["rootStateCid"] as? String)?.let { runCatching { ContentId(it) }.getOrNull() }
            ?: throw EvidenceFailure(409, "pulse_state_unavailable")
        if (base["rootStateFormat"] != "cbor") throw EvidenceFailure(409, "pulse_state_unavailable")
        val stateBytes = pulseBytes(stateCid)
        val state = try {
            val reader = Cbor.Reader(stateBytes, 0, maxDepth = 32, maxItems = 65536)
            val value = reader.readItem().toAny()
            require(reader.pos == stateBytes.size)
            value as? Map<*, *> ?: invalid()
        } catch (failure: CancellationException) { throw failure }
        catch (_: Exception) { invalid() }
        if (state["kind"] != "lcnc-root-state" || state["programCid"] != programCid.value) invalid()
        if (state["retention"] != "execution-v4") throw EvidenceFailure(409, "pulse_state_unavailable")
        val bindings = state["bindings"] as? Map<*, *> ?: invalid()
        if (bindings.keys.any { it !is String }) invalid()
        val raw = state["outputs"] as? Map<*, *> ?: invalid()
        val retained = raw.entries.associate { (key, value) ->
            if (key !is String || value !is Map<*, *> || value.keys.any { it !is String }) invalid()
            @Suppress("UNCHECKED_CAST")
            key to (value as Map<String, Any?>)
        }
        val origins = state["origins"] as? Map<*, *> ?: throw EvidenceFailure(409, "pulse_state_unavailable")
        if (origins.keys.any { it !is String }) throw EvidenceFailure(409, "pulse_state_unavailable")
        @Suppress("UNCHECKED_CAST")
        val retainedBindings = bindings as Map<String, Any?>
        // A valid CID authenticates bytes; exact occurrence paths must also match this saved frame.
        try {
            currentCoroutineContext().ensureActive()
            val terms = LcncBindingTerms()
            val decoded = terms.decode(origins, retainedBindings.keys)
            terms.validate(retained, decoded)
            val live = terms.evaluate(retainedBindings.keys)
            terms.account(decoded.size)
            for ((path, term) in decoded.view) {
                require(path.size >= 2 && path[0].leftOrNull != null && path[1].leftOrNull != null) {
                    "binding_origin_path"
                }
                require(live[term]) { "binding_origin_retired" }
            }
            currentCoroutineContext().ensureActive()
        } catch (failure: CancellationException) { throw failure }
        catch (_: Exception) { throw EvidenceFailure(409, "pulse_state_unavailable") }
        val versions = (base["programVersions"] as? Map<*, *>)?.entries?.associate { (key, value) ->
            if (key !is String || value !is String) invalid()
            key to value
        } ?: invalid()
        // The existing pin map reserves the root label. An anonymous document
        // with a same-named stored callee cannot unambiguously reuse that map.
        if (base["programKey"] == null && program.name in versions)
            throw EvidenceFailure(409, "pulse_program_name_collision")
        val pins = try { pinnedVersions(previous, programCid, mapOf("pinnedVersions" to versions)) }
            catch (failure: CancellationException) { throw failure }
            catch (failure: QueueFailure) { throw EvidenceFailure(failure.status, failure.code) }
        @Suppress("UNCHECKED_CAST")
        val selection = LcncPulse(nodeId, eventOutputs as Map<String, Any?>,
            LcncRetainedFrame(retained, retainedBindings, origins = origins as Map<String, Any?>),
            definitions = pins.mapValues { it.value.a })
        val runners = ctx.lcncRunners.toMap()
        try { selection.roots(program, runners.keys, runners.filterValues { it.isPure }.keys) }
        catch (failure: IllegalArgumentException) {
            val code = failure.message ?: "invalid_pulse"
            throw EvidenceFailure(if (code == LcncPulse.BINDING_ORIGIN_UNAVAILABLE) 409 else 400, code)
        }
        return PulseAdmission(selection, cid.value, base["runId"] as String, versions.filterKeys { it != program.name }, runners)
    }

    private fun pulseRequest(request: Map<*, *>, pulse: PulseAdmission?): Map<*, *> {
        if (pulse == null) return request
        val supplied = if (request.containsKey("pinnedVersions")) request["pinnedVersions"] as? Map<*, *>
            ?: throw QueueFailure(400, "invalid_pinned_versions") else emptyMap<Any?, Any?>()
        if (pulse.versions.any { (name, version) -> name in supplied && supplied[name] != version })
            throw EvidenceFailure(409, "pulse_version_drift")
        return request + ("pinnedVersions" to (supplied + pulse.versions))
    }

    /** Exact executable state is separate from bounded receipt previews. Unavailable is explicit. */
    private suspend fun rootState(programCid: String, retained: LcncRetainedFrame): Map<String, Any?> {
        retained.unavailable?.let { return mapOf("rootStateUnavailable" to it) }
        val origins = retained.origins ?: return mapOf("rootStateUnavailable" to "binding_origin_unavailable")
        val state = mapOf("kind" to "lcnc-root-state", "programCid" to programCid,
            "retention" to "execution-v4", "outputs" to retained.outputs, "bindings" to retained.bindings, "origins" to origins)
        ValueBudget(maxNodes = 65536, maxChars = 524288, maxDepth = 32).violation(state)?.let {
            return mapOf("rootStateUnavailable" to it)
        }
        // Do not let the plain-value codec stringify unsupported numbers or map keys.
        fun encodable(value: Any?): Boolean = when (value) {
            is Map<*, *> -> value.keys.all { it is String } && value.values.all(::encodable)
            is List<*> -> value.all(::encodable)
            is Array<*> -> value.all(::encodable)
            is Number -> value is Long || value is Int || value is Short || value is Byte || value is Double || value is Float
            else -> true // ValueBudget above has already refused other object types.
        }
        if (!encodable(state)) return mapOf("rootStateUnavailable" to "unsupported_value")
        // Every minted checkpoint must fit the same strict reload path as a fresh pulse.
        try {
            currentCoroutineContext().ensureActive()
            val terms = LcncBindingTerms()
            val decoded = terms.decode(origins, retained.bindings.keys)
            terms.validate(retained.outputs, decoded)
            val live = terms.evaluate(retained.bindings.keys)
            terms.account(decoded.size)
            for ((path, term) in decoded.view) {
                require(path.size >= 2 && path[0].leftOrNull != null && path[1].leftOrNull != null) {
                    "binding_origin_path"
                }
                require(live[term]) { "binding_origin_retired" }
            }
            currentCoroutineContext().ensureActive()
        } catch (failure: CancellationException) { throw failure }
        catch (failure: LcncBindingTerms.Limit) { return mapOf("rootStateUnavailable" to failure.reason) }
        catch (_: Exception) { return mapOf("rootStateUnavailable" to "binding_origin_format") }
        return try {
            // The common Confix CBOR codec preserves integer identities that JSON's
            // Double reifier cannot. Receipts remain JSON previews, not replay state.
            val bytes = CanonicalCbor.encodeMap(state)
            if (bytes.size > 1_048_576) mapOf("rootStateUnavailable" to "payload_limit")
            else withContext(Dispatchers.IO) {
                val cid = ctx.casStore.put(bytes)
                if (cid != ContentId.of(bytes)) mapOf("rootStateUnavailable" to "identity_mismatch")
                else mapOf("rootStateCid" to cid.value, "rootStateFormat" to "cbor")
            }
        } catch (failure: CancellationException) { throw failure }
        catch (_: Exception) { mapOf("rootStateUnavailable" to "store_failed") }
    }

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
        val value = runCatching { reify(text) }.getOrElse {
            return@withContext response(422, mapOf("error" to "content_not_json"))
        }
        ValueBudget().violation(value)?.let { return@withContext response(413, mapOf("error" to it)) }
        if (view != "sheet") return@withContext HttpResponse(200, text)
        val doc = borg.trikeshed.parse.confix.confixDoc(bytes, borg.trikeshed.parse.confix.Syntax.JSON)
        val sheets = borg.trikeshed.forge.sheet.confixSheets(cid.value, cid.value, doc, maxRows = 512).map { it.toMap() }
        ValueBudget(maxNodes = 32768, maxChars = 131072).violation(sheets)?.let {
            return@withContext response(413, mapOf("error" to it))
        }
        HttpResponse(200, jsonOf(sheets))
    }

    /**
     * THE RUN HEAD (AutoTools, Cut B): which recorded run IS the build of `(this program's current
     * version, these inputs)`, and what lamp it burns. The decision itself is
     * [borg.trikeshed.lcnc.LcncRunHead] in commonMain — pinned on jvm and js — so a curl reader and
     * a page's block frame are told the same verdict, and Cut V's sheet cells get it for free.
     *
     * The version is DERIVED, never read off a board entry: the loader hands back the program the
     * next Build would run, and [LcncBlackboard.cidOf] mints byte-for-byte the cid [execute]'s
     * freeze mints. That is what makes this answer true in a bare rig where no entry exists, and
     * what makes a re-published program read Never built rather than showing the old artifact.
     */
    suspend fun head(programName: String?, inputsText: String?, show: String?): HttpResponse = withContext(Dispatchers.IO) {
        val name = programName?.takeIf { it.isNotBlank() }
            ?: return@withContext response(400, mapOf("error" to "program_required"))
        val inputsValue: Any? = if (inputsText.isNullOrBlank()) emptyMap<String, Any?>()
        else runCatching { reify(inputsText) }.getOrElse {
            return@withContext response(400, mapOf("error" to "bad_inputs", "detail" to (it.message ?: "unparsed")))
        }
        if (inputsValue !is Map<*, *>) return@withContext response(400, mapOf("error" to "inputs_must_be_object"))
        val program = ctx.programLoader(name)
            ?: return@withContext response(404, mapOf("error" to "no_such_program", "program" to name))
        val programCid = LcncBlackboard.cidOf(program)
        val programKey = LcncBlackboard.programKey(name)
        // ONE snapshot: the immutable store map, no key copy and no per-key get, and a consistent
        // view of the run entries and the stale markers that name them.
        val entries = ctx.blackboard.snapshot().values
        val head = LcncRunHead.head(LcncRunHead.rows(entries), programKey, programCid, LcncRunHead.canonicalInputs(inputsValue))
        val marker = head.latest?.takeIf { it.status == LcncRunHead.STATUS_COMPLETED }
            ?.let { entries[LcncStaleMarker.key(it.runId)] }
        val verdict = LcncRunHead.decide(head, marker, ctx.clock())
        val body = LcncRunHead.headBody(name, programKey, programCid, inputsValue, head, verdict, marker, show)
        // The preflight DEGRADES rather than refuses. Every list in the body is already capped, so
        // this only fires on a reader-supplied extreme (a vast `inputs` echo, a `returns` that
        // slipped under its own budget); shedding the output and the rows still answers with the
        // lamp and the button, where a 413 would leave the frame "Unavailable" with nothing to
        // press — on exactly the stale target the reader opened the page to rebuild.
        val fitted = LcncRunHead.fitToBudget(body)
            ?: return@withContext response(413, mapOf("error" to (ValueBudget().violation(body) ?: "work_limit")))
        response(200, fitted)
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

    private suspend fun commit(jobId: String, op: String, receipt: Map<String, Any?>, revision: Long,
                               idempotencyKey: String = "$jobId:${receipt["status"]}:$revision", queue: Boolean = false): Map<String, Any?> {
        val reply = CompletableDeferred<BoardApply>()
        val raw = mapOf(
            "type" to op, "jobId" to jobId, "expectedRevision" to revision,
            "idempotencyKey" to idempotencyKey,
            "title" to "LCNC ${receipt["program"]}", "owner" to "lcnc-runner",
            "tags" to listOf("lcnc-run"),
            "lcncRun" to (receipt - setOf("sequence", "timelineRevision", "receiptCid")),
            "reason" to (receipt["error"] ?: receipt["status"]),
        )
        store.intake.send(BoardIntake(raw, reply))
        return when (val result = reply.await()) {
            is BoardApply.Committed -> {
                fun evidence(value: Map<*, *>?): Map<String, Any?> = value?.entries?.filter {
                    it.key in setOf("jobId", "status", "timelineRevision", "receiptCid")
                }?.associate { it.key as String to it.value } ?: emptyMap()
                val before = evidence(ctx.blackboard.get(jobId) as? Map<*, *>)
                val value = project(receipt, result)
                // Deliver the acknowledged revision even if cancellation arrives during observation.
                withContext(NonCancellable) {
                    (receipt["runId"] as? String)?.let { oroboros.executions[it] }?.control?.persistent(
                        (receipt["claimedAtSubnet"] as? String).orEmpty(), "receipt", before,
                        evidence(value) + ("durability" to store.durability.name.lowercase()),
                        durable = store.durability == StorageDurability.DURABLE)
                }
                value
            }
            is BoardApply.Rejected -> if (queue) throw QueueFailure(409, "run_commit_refused: ${result.reason}")
                else error("run commit refused: ${result.reason}")
        }
    }

    private fun queuedRef(receipt: Map<String, Any?>): BoardApply.Committed = BoardApply.Committed(
        receipt["jobId"] as String, (receipt["sequence"] as Number).toLong(),
        (receipt["timelineRevision"] as Number).toLong(), "queued", ContentId(receipt["receiptCid"] as String),
    )

    private suspend fun queuedBytes(cid: ContentId): ByteArray {
        val value = try { withContext(Dispatchers.IO) { ctx.casStore.get(cid) } }
            catch (failure: CancellationException) { throw failure }
            catch (_: Exception) { throw QueueFailure(409, "queued_content_unreadable") }
            ?: throw QueueFailure(404, "queued_content_not_found")
        if (ContentId.of(value) != cid) throw QueueFailure(409, "queued_content_identity_mismatch")
        return value
    }

    private suspend fun queuedMetadata(entry: LcncRunQueueEntry): Map<String, Any?> {
        val ref = entry.a
        fun invalid(): Nothing = throw QueueFailure(400, "invalid_queued_receipt")
        if (ref.revision != 1L) invalid()
        val rawBytes = queuedBytes(ref.cid)
        val raw = runCatching { reify(rawBytes) as? Map<*, *> }.getOrNull() ?: invalid()
        val receipt = raw["lcncRun"] as? Map<*, *> ?: invalid()
        val runId = (receipt["runId"] as? String)?.takeIf { it.isNotBlank() } ?: invalid()
        if (raw["type"] != "submit" || raw["owner"] != "lcnc-runner" || raw["jobId"] != ref.jobId ||
            ref.jobId != "lcnc/run/$runId" || (raw["expectedRevision"] as? Number)?.toDouble() != 0.0 ||
            receipt["status"] != "queued" || receipt.keys.any { it !is String }) invalid()
        val row = store.card(ref.jobId)
        if (row?.revision != 1L || row.commandCid != ref.cid) throw QueueFailure(409, "queue_not_pending")
        val family = receipt["executionFamily"] as? String ?: invalid()
        if (family != row.executionFamily || receipt["parentRunId"] != row.parentRunId) invalid()
        (receipt["program"] as? String)?.takeIf { it.isNotBlank() } ?: invalid()
        runCatching { ContentId(receipt["programCid"] as? String ?: "") }.getOrNull() ?: invalid()
        val request = receipt["queueRequest"] as? Map<*, *> ?: invalid()
        if (listOf("pulse", "baseReceiptCid").any { key ->
                receipt.containsKey(key) != request.containsKey(key) || receipt[key] != request[key]
            } || request.containsKey("pulse") != request.containsKey("baseReceiptCid")) invalid()
        val inputs = receipt["inputs"] as? Map<*, *> ?: invalid()
        if (request.keys.any { it !is String } || inputs.keys.any { it !is String } || receipt["queueNamed"] !is Boolean) invalid()
        if (receipt.containsKey("sourceReceiptCid")) {
            val source = runCatching { ContentId(receipt["sourceReceiptCid"] as? String ?: "") }.getOrNull() ?: invalid()
            val evidence = inputs["evidence"] as? Map<*, *> ?: invalid()
            if (evidence["receiptCid"] != source.value || (evidence["runId"] as? String).isNullOrBlank()) invalid()
        }
        @Suppress("UNCHECKED_CAST")
        return (receipt as Map<String, Any?>) + mapOf("jobId" to ref.jobId, "sequence" to ref.sequence,
            "timelineRevision" to 1L, "receiptCid" to ref.cid.value)
    }

    private suspend fun queuedRun(entry: LcncRunQueueEntry): QueuedRun {
        val receipt = queuedMetadata(entry)
        val cid = ContentId(receipt["programCid"] as String)
        val program = try { LcncProgramConfix.fromJson(receipt["program"] as String, queuedBytes(cid).decodeToString()) }
            catch (failure: CancellationException) { throw failure }
            catch (failure: QueueFailure) { throw failure }
            catch (_: Exception) { throw QueueFailure(400, "invalid_queued_program") }
        @Suppress("UNCHECKED_CAST")
        val request = receipt["queueRequest"] as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val inputs = receipt["inputs"] as Map<String, Any?>
        return QueuedRun(receipt, program, request, inputs, pinnedVersions(program, cid, request),
            caseFrame(program, cid, inputs, request, retained = true))
    }

    suspend fun queuedReceipt(entry: LcncRunQueueEntry): Map<String, Any?> = queuedMetadata(entry)

    /** Only an immutable, locally committed predecessor can authorize reusing retained evidence. */
    suspend fun enqueueRebuild(receiptCid: ContentId): Join<HttpResponse?, LcncRunQueueEntry?> = try {
        fun invalid(): Nothing = throw QueueFailure(400, "invalid_rebuild_receipt")
        val command = committedReceipt(receiptCid)
        @Suppress("UNCHECKED_CAST")
        val receipt = command["lcncRun"] as Map<String, Any?>
        val terminal = when (command["type"]) {
            "complete" -> receipt["status"] == "completed" && receipt["ok"] == true
            "fail" -> receipt["status"] in setOf("failed", "refused")
            "cancel" -> receipt["status"] in setOf("cancelled", "timed_out", "interrupted")
            else -> false
        }
        if (!terminal) throw QueueFailure(409, "receipt_not_terminal")
        val name = (receipt["program"] as? String)?.takeIf { it.isNotBlank() } ?: invalid()
        val programCid = runCatching { ContentId(receipt["programCid"] as? String ?: "") }.getOrNull() ?: invalid()
        val program = try { LcncProgramConfix.fromJson(name, queuedBytes(programCid).decodeToString()) }
            catch (failure: CancellationException) { throw failure }
            catch (failure: QueueFailure) { throw failure }
            catch (_: Exception) { throw QueueFailure(422, "program_version_unreadable") }
        val key = receipt["programKey"]
        if (key != null && key != LcncBlackboard.programKey(name)) invalid()
        val named = key != null
        val inputs = receipt["inputs"] as? Map<*, *> ?: invalid()
        if (inputs.keys.any { it !is String }) invalid()
        val source = if (receipt.containsKey("sourceReceiptCid")) {
            val cid = runCatching { ContentId(receipt["sourceReceiptCid"] as? String ?: "") }.getOrNull() ?: invalid()
            val retained = inputs["evidence"] as? Map<*, *> ?: invalid()
            if (retained.keys != setOf("receiptCid", "runId", "programCid", "returns") ||
                retained["receiptCid"] != cid.value || (retained["runId"] as? String).isNullOrBlank()) invalid()
            runCatching { ContentId(retained["programCid"] as? String ?: "") }.getOrNull() ?: invalid()
            val returns = retained["returns"] as? Map<*, *> ?: invalid()
            if (returns.keys.any { it !is String }) invalid()
            @Suppress("UNCHECKED_CAST")
            cid j (retained as Map<String, Any?>)
        } else null
        val saved = if (receipt.containsKey("queueRequest")) receipt["queueRequest"] as? Map<*, *> ?: invalid()
            else emptyMap<String, Any?>()
        if (saved.keys.any { it !is String }) invalid()
        val budgets = receipt["budgets"] as? Map<*, *> ?: emptyMap<String, Any?>()
        val options = saved.entries.filter { it.key in setOf("timeoutMs", "maxNodes", "subnet", "pinnedVersions", "pulse", "baseReceiptCid", "case") }
            .associate { it.key as String to it.value }.toMutableMap()
        for (field in s_["timeoutMs", "maxNodes"].view) budgets[field]?.let { options[field] = it }
        for (field in s_["pulse", "baseReceiptCid"].view) if (!options.containsKey(field))
            receipt[field]?.let { options[field] = it }
        if (receipt.containsKey("programVersions")) options["pinnedVersions"] = receipt["programVersions"]
        if (receipt.containsKey("queueDeclaredSubnet") &&
            receipt["queueDeclaredSubnet"] != if (named) program.controls.subnet else null) invalid()
        val subnet = options["subnet"] ?: receipt["subnet"] ?: if (named) program.controls.subnet ?: "local" else "local"
        val scope = Subnet.parse(if (named) program.controls.subnet ?: "local" else "local")
        if (subnet !is String || subnet.isBlank() || subnet.length > 256 ||
            subnet.split('.').any { it.isEmpty() || it.any { char -> char.isWhitespace() || char.isISOControl() } }) invalid()
        val requested = Subnet.parse(subnet)
        if (!(scope contains requested) || requested.level - scope.level > 3) throw QueueFailure(404, "no_workgroup")
        options["subnet"] = subnet
        options["expectProgramCid"] = programCid.value
        options["rebuildOf"] = receiptCid.value
        options["rebuildOfRunId"] = receipt["runId"]
        source?.let { options["sourceReceiptCid"] = it.a.value }
        @Suppress("UNCHECKED_CAST")
        enqueueRun(name, program, named, inputs as Map<String, Any?>, options, source, retainedPulseBase = true, retainedCase = true)
    } catch (failure: CancellationException) { throw failure }
      catch (failure: EvidenceFailure) { response(failure.status, mapOf("ok" to false, "error" to failure.code)) j null }
      catch (failure: QueueFailure) { response(failure.status, mapOf("ok" to false, "error" to failure.code)) j null }
      catch (failure: Exception) { response(503, mapOf("ok" to false, "error" to "rebuild_receipt_unavailable")) j null }

    suspend fun enqueue(name: String, program: LcncProgram, named: Boolean, inputs: Map<String, Any?>,
                        request: Map<*, *>): Join<HttpResponse?, LcncRunQueueEntry?> =
        enqueueRun(name, program, named, inputs, request)

    private suspend fun enqueueRun(name: String, program: LcncProgram, named: Boolean, inputs: Map<String, Any?>,
                                   request: Map<*, *>, retainedSource: Join<ContentId, Map<String, Any?>>? = null,
                                   retainedPulseBase: Boolean = false, retainedCase: Boolean = false): Join<HttpResponse?, LcncRunQueueEntry?> {
        var reservation: RunPermit? = null
        try {
            val runId = UUID.randomUUID().toString()
            val jobId = "lcnc/run/$runId"
            val token = request["debugToken"] as? String
            if (request.containsKey("debugToken") && (token == null ||
                    !Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}").matches(token)))
                return response(400, mapOf("error" to "invalid_debug_token")) j null
            val inherited = currentCoroutineContext()[RunPermit]
            val ownedPreparation = currentCoroutineContext()[LcncWorkgroupElement]?.currentPreparation() == true
            admission.withLock {
                if (token != null && debuggers.containsKey(token)) throw QueueFailure(409, "debug_token_active")
                val family = inherited?.let { permits[it] }
                if (family == null && stopped.isCompleted && !ownedPreparation) throw QueueFailure(429, "execution_capacity")
                if (family != null) {
                    reservation = RunPermit(runId, checkNotNull(inherited).runId, family, queued = true, preparation = true)
                    family.references++
                    permits[checkNotNull(reservation)] = family
                    admittedRuns++
                }
            }
            if (inputs.keys.any { it.startsWith(LcncCaseProgram.BINDING_PREFIX) }) throw QueueFailure(400, "reserved_case_input")
            if (request.containsKey("case") && (setOf("pulse", "baseReceiptCid", "sourceReceiptCid", "selectedPulse").any { request.containsKey(it) } || inputs.containsKey("evidence")))
                throw QueueFailure(400, "case_execution_protocol_conflict")
            val sourceCid = retainedSource?.a ?: if (request.containsKey("sourceReceiptCid")) {
                if (inputs.containsKey("evidence")) throw EvidenceFailure(409, "source_evidence_conflict")
                val value = request["sourceReceiptCid"] as? String ?: throw EvidenceFailure(400, "invalid_source_receipt")
                runCatching { ContentId(value) }.getOrElse { throw EvidenceFailure(400, "invalid_source_receipt") }
            } else null
            val evidence = retainedSource?.let { ("lcnc/run/" + it.b["runId"]) j it.b }
                ?: sourceCid?.let { sourceEvidence(it) }
            val effective = if (evidence == null) inputs else inputs + ("evidence" to evidence.b)
            val selectedPulse = pulseAdmission(program, effective, request, retainedBase = retainedPulseBase)
            val retainedRequest = pulseRequest(request, selectedPulse)
            if (evidence != null && ValueBudget().violation(effective) != null)
                throw EvidenceFailure(413, "source_receipt_payload_limit")
            val bytes = LcncProgramConfix.toJson(program).encodeToByteArray()
            val cid = withContext(Dispatchers.IO) { ctx.casStore.put(bytes) }
            if (cid != ContentId.of(bytes)) throw QueueFailure(409, "queued_content_identity_mismatch")
            request["expectProgramCid"]?.toString()?.takeIf { it.isNotBlank() }?.let {
                if (it != cid.value) throw QueueFailure(409, "program_version_drift")
            }
            pinnedVersions(program.copy(name = name), cid, retainedRequest)
            val selectedCase = caseFrame(program, cid, effective, retainedRequest, retainedCase)
            val options = retainedRequest.entries.filter { it.key in setOf("debugToken", "rebuildOf", "rebuildOfRunId", "expectProgramCid", "pinnedVersions", "pulse", "baseReceiptCid") }
                .associate { it.key.toString() to it.value } + mapOf(
                    "timeoutMs" to (((request["timeoutMs"] as? Number)?.toLong() ?: 120000L).coerceAtLeast(1L)),
                    "maxNodes" to (((request["maxNodes"] as? Number)?.toInt() ?: 10000).coerceAtLeast(1)),
                    "subnet" to (request["subnet"] ?: if (named) program.controls.subnet ?: "local" else "local"),
                ) + (selectedCase?.let { mapOf("case" to mapOf("intent" to it.request.intent, "requestCid" to it.cid.value)) } ?: emptyMap())
            val receipt = commit(jobId, "submit", mapOf(
                "runId" to runId, "program" to name, "programKey" to if (named) LcncBlackboard.programKey(name) else null,
                "programCid" to cid.value, "inputs" to effective, "status" to "queued", "queuedAtMs" to ctx.clock(),
                "queueNamed" to named, "queueDeclaredSubnet" to if (named) program.controls.subnet else null,
                "queueRequest" to options, "queueDurability" to store.durability.name,
                "executionFamily" to "lcnc/run/${reservation?.family?.runId ?: runId}", "parentRunId" to reservation?.parentRunId,
                "versionPolicy" to "root-at-queue,subprogram-at-first-use",
                "budgets" to mapOf("timeoutMs" to options["timeoutMs"], "maxNodes" to options["maxNodes"], "maxPayloadChars" to 131072),
            ) + (sourceCid?.let { mapOf("sourceReceiptCid" to it.value) } ?: emptyMap()) +
                (if (selectedPulse != null) mapOf("pulse" to options["pulse"], "baseReceiptCid" to selectedPulse.receiptCid)
                    else emptyMap()), 0L, queue = true)
            val context = currentCoroutineContext().minusKey(Job).minusKey(RunPermit) +
                (reservation ?: kotlin.coroutines.EmptyCoroutineContext)
            return null j (queuedRef(receipt) j context)
        } catch (failure: CancellationException) {
            reservation?.let { releasePermit(it) }
            throw failure
        } catch (failure: EvidenceFailure) {
            reservation?.let { releasePermit(it) }
            return response(failure.status, mapOf("ok" to false, "error" to failure.code)) j null
        } catch (failure: QueueFailure) {
            reservation?.let { releasePermit(it) }
            return response(failure.status, mapOf("ok" to false, "error" to failure.code)) j null
        } catch (failure: Exception) {
            reservation?.let { releasePermit(it) }
            return response(503, mapOf("ok" to false, "error" to "receipt_commit_failed", "detail" to failure.message)) j null
        }
    }

    private suspend fun releasePermit(permit: RunPermit) = withContext(NonCancellable) {
        admission.withLock {
            val family = permits.remove(permit) ?: return@withLock
            if (--family.references == 0) slots.release()
            admittedRuns--
            if (stopped.isCompleted && admittedRuns == 0) drained.complete(Unit)
        }
    }

    suspend fun releaseQueued(entry: LcncRunQueueEntry) {
        val permit = entry.b[RunPermit] ?: return
        if (permit.queued && entry.a.jobId == "lcnc/run/${permit.runId}") releasePermit(permit)
    }

    suspend fun reserveQueued(entry: LcncRunQueueEntry): LcncRunQueueEntry {
        val snapshot = queuedRun(entry)
        val runId = snapshot.receipt["runId"] as String
        val inherited = entry.b[RunPermit]
        val rootId = (snapshot.receipt["executionFamily"] as String).removePrefix("lcnc/run/")
        fun register(family: PermitFamily): LcncRunQueueEntry {
            val permit = RunPermit(runId, snapshot.receipt["parentRunId"] as? String, family, queued = true)
            family.references++
            permits[permit] = family
            admittedRuns++
            return entry.a j (entry.b.minusKey(RunPermit) + permit)
        }
        admission.withLock {
            val retained = inherited?.takeIf { it.queued && it.runId == runId }?.let { permits[it] }
                ?: permits.values.firstOrNull { it.runId == rootId }
            if (retained != null) {
                val reserved = register(retained)
                // Preparation owns the child until its first dispatch takes a separate claim lease.
                if (inherited?.preparation == true && inherited.runId == runId && permits.remove(inherited) != null) {
                    retained.references--
                    admittedRuns--
                }
                return reserved
            }
        }
        var acquired = false
        var registered = false
        try {
            slots.acquire()
            acquired = true
            queuedRun(entry)
            return admission.withLock {
                val existing = permits.values.firstOrNull { it.runId == rootId }
                if (existing != null) { slots.release(); acquired = false }
                register(existing ?: PermitFamily(rootId)).also { registered = true }
            }
        } finally { if (acquired && !registered) slots.release() }
    }

    suspend fun executeQueued(entry: LcncRunQueueEntry, routing: Join<Workgroup, NuidFanoutElement.Claim>): HttpResponse = try {
        val queued = queuedRun(entry)
        val permit = entry.b[RunPermit]
        val reserved = admission.withLock { permit != null && permit.queued && permits.containsKey(permit) &&
            entry.a.jobId == "lcnc/run/${permit.runId}" }
        if (!reserved) response(409, mapOf("ok" to false, "error" to "queue_not_reserved"))
        else withContext(checkNotNull(permit)) {
            executeRun(queued.program.name, queued.program, queued.receipt["queueNamed"] as Boolean,
                queued.inputs, queued.request, routing, queued)
        }.let { result ->
            if (result.status == 409 && (runCatching { reify(result.body) as? Map<*, *> }.getOrNull())?.get("error") == "debug_token_active")
                failQueued(entry, 409, "debug_token_active") else result
        }
    } catch (failure: QueueFailure) {
        if (failure.code.startsWith("pinned_") || failure.code.startsWith("invalid_pinned_"))
            failQueued(entry, failure.status, failure.code)
        else response(failure.status, mapOf("ok" to false, "error" to failure.code))
    }

    suspend fun failQueued(entry: LcncRunQueueEntry, status: Int, error: String, phase: String = "admission",
                           fields: Map<String, Any?> = emptyMap()): HttpResponse = try {
        val queued = queuedMetadata(entry)
        val owner = currentCoroutineContext()[RunPermit]
        admission.withLock {
            if (permits.keys.any { !it.queued && it.runId == queued["runId"] && it !== owner })
                response(409, mapOf("ok" to false, "error" to "queue_execution_active"))
            else {
                val receipt = commit(entry.a.jobId, "fail", queued + fields + mapOf("ok" to false, "status" to "refused",
                    "phase" to phase, "error" to error, "finishedAtMs" to ctx.clock(), "previousReceiptCid" to entry.a.cid.value),
                    1L, "${entry.a.jobId}:refused:${UUID.randomUUID()}", queue = true)
                response(status, receipt)
            }
        }
    } catch (failure: QueueFailure) { response(failure.status, mapOf("ok" to false, "error" to failure.code)) }

    fun queuedRecovery(): Series<LcncRunQueueEntry> = recoveredQueue

    /** Rebuild from CAS pointers reconstructed by the board WAL, never from browser state. */
    suspend fun recover() {
        val queued = mutableListOf<LcncRunQueueEntry>()
        for (row in store.cards().filter { it.owner == "lcnc-runner" && it.jobId.startsWith("lcnc/run/") }) {
            @Suppress("UNCHECKED_CAST")
            val receipt = store.command(row.jobId)?.get("lcncRun") as? Map<String, Any?> ?: continue
            if (receipt["status"] == "queued" && row.revision == 1L) {
                val entry: LcncRunQueueEntry = BoardApply.Committed(row.jobId, row.lastSequence, 1L, "replay", row.commandCid ?: continue) j
                    kotlin.coroutines.EmptyCoroutineContext
                try {
                    queuedRun(entry)
                    queued.add(entry)
                    project(receipt, entry.a)
                } catch (failure: QueueFailure) {
                    if (failure.code.startsWith("pinned_") || failure.code.startsWith("invalid_pinned_")) {
                        failQueued(entry, failure.status, failure.code)
                        continue
                    }
                    // Schema errors retire only their own queue; corrupt or missing CAS remains explicit.
                    if (failure.status != 400) throw failure
                    commit(row.jobId, "cancel", receipt + mapOf("ok" to false, "status" to "interrupted",
                        "error" to failure.code, "previousReceiptCid" to entry.a.cid.value,
                        "finishedAtMs" to ctx.clock()), row.revision)
                }
            } else if (receipt["status"] in listOf("queued", "validating", "running")) {
                commit(row.jobId, "cancel", receipt + mapOf(
                    "ok" to false, "status" to "interrupted", "error" to "runtime_restarted",
                    "previousReceiptCid" to row.commandCid?.value,
                    "finishedAtMs" to ctx.clock(),
                ), row.revision)
            } else {
                val value = project(receipt, BoardApply.Committed(row.jobId, row.lastSequence, row.revision, "replay", row.commandCid ?: continue))
                // The recovered receipt's consumed facts return to the plane with it.
                runCatching { facts?.assertRun(value) }.onFailure { System.err.println("[LcncRunService] consumed facts for ${row.jobId} not restored: ${it.message}") }
            }
        }
        recoveredQueue = queued.toSeries()
    }

    fun cancel(runId: String): Boolean = active[runId]?.let { it.cancel(CancellationException("cancelled by user")); true } ?: false

    suspend fun debug(method: String, path: String, body: String): HttpResponse {
        if (method !in listOf("GET", "POST")) return response(405, mapOf("error" to "method_not_allowed"))
        if (body.length > 4096 || path.length > 4096) return response(413, mapOf("error" to "payload_limit"))
        val request = if (method == "GET") borg.trikeshed.relaxfactory.CouchHttpSurface.parseQuery(path.substringAfter('?', ""))
            else runCatching { reify(body) as? Map<*, *> }.getOrNull()
                ?: return response(400, mapOf("error" to "bad_json"))
        val token = request["token"] as? String ?: return response(400, mapOf("error" to "debug_token_required"))
        val expected = request["runId"] as? String
        if ((method == "POST" || request.containsKey("runId")) &&
            (expected == null || !Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}").matches(expected)))
            return response(400, mapOf("error" to "debug_run_id_required"))
        if (request.containsKey("coordinates") && (method != "POST" || request["action"] != "source-line" ||
                request["coordinates"] !in listOf("generated", "authored")))
            return response(400, mapOf("error" to "invalid_debug_coordinates"))
        val coordinates = if (request["coordinates"] == "authored") VmSourceCoordinates.AUTHORED else VmSourceCoordinates.GENERATED
        val run = debuggers[token]
        if (run == null || (expected != null && run.runId != expected)) {
            // Exact durable identity only: no named-program lookup or latest-run substitution.
            val receipt = if (method == "GET" && expected != null)
                (ctx.blackboard.get("lcnc/run/$expected") as? Map<*, *>)?.filterKeys {
                    it in setOf("runId", "status", "programCid", "receiptCid", "invocationsCid", "finishedAtMs", "error")
                } else null
            return response(if (run == null) 404 else 409, mapOf(
                "error" to if (run == null) "debug_run_not_active" else "debug_run_identity_mismatch",
                "receipt" to receipt,
            ))
        }
        if (method == "GET") {
            val receipt = ctx.blackboard.get("lcnc/run/${run.runId}") as? Map<*, *>
            val snapshot = run.control.snapshot()
            // Preserve the legacy stepper vocabulary; the separate Oroboros surface exposes interventions.
            val commands = LcncDebug.Command.entries.map { it.name.lowercase().replace('_', '-') }.toSet()
            return response(200, snapshot + mapOf("actions" to (snapshot["actions"] as? List<*>)?.filter { it in commands },
                "runId" to run.runId,
                "programCid" to receipt?.get("programCid"), "timeoutIncludesPauses" to true))
        }
        val command = when (request["action"]) {
            "step" -> LcncDebug.Command.STEP
            "continue" -> LcncDebug.Command.CONTINUE
            "source-into" -> LcncDebug.Command.SOURCE_INTO
            "source-over" -> LcncDebug.Command.SOURCE_OVER
            "source-out" -> LcncDebug.Command.SOURCE_OUT
            "source-line" -> LcncDebug.Command.SOURCE_LINE
            else -> return response(400, mapOf("error" to "invalid_debug_command"))
        }
        val ordinal = (request["ordinal"] as? Number)?.toDouble()
        if (ordinal == null || !ordinal.isFinite() || ordinal < 1 || ordinal > Int.MAX_VALUE || ordinal % 1 != 0.0)
            return response(400, mapOf("error" to "invalid_debug_ordinal"))
        val line = (request["line"] as? Number)?.toDouble()
        if (command == LcncDebug.Command.SOURCE_LINE &&
            (line == null || !line.isFinite() || line < 1 || line > Int.MAX_VALUE || line % 1 != 0.0))
            return response(400, mapOf("error" to "invalid_debug_line"))
        val accepted = try { run.control.command(ordinal.toInt(), command, line?.toInt(), coordinates) }
            catch (_: IllegalArgumentException) { return response(400, mapOf("error" to "invalid_debug_line")) }
        return response(if (accepted) 202 else 409, mapOf("ok" to accepted, "runId" to run.runId))
    }

    suspend fun stopAdmission() = withContext(NonCancellable) {
        admission.withLock {
            stopped.complete()
            for (run in oroboros.executions.values) run.control.drain()
            if (admittedRuns == 0) drained.complete(Unit)
        }
    }

    suspend fun drain() = withContext(NonCancellable) {
        stopAdmission()
        drained.await()
    }

    private suspend fun admitRun(runId: String, token: String?, debug: LcncDebug?, routed: Boolean,
                                 queued: Boolean = false): Join<Int, RunPermit?> {
        val inherited = currentCoroutineContext()[RunPermit]
        var acquired = false
        var registered = false
        suspend fun register(family: PermitFamily, parentRunId: String? = null): RunPermit {
            if (stopped.isCompleted) debug?.drain()
            val permit = RunPermit(runId, parentRunId, family)
            family.references++
            permits[permit] = family
            admittedRuns++
            if (token != null) debuggers[token] = DebugRun(runId, checkNotNull(debug))
            registered = true
            return permit
        }
        try {
            return admission.withLock {
                if (queued && permits.keys.any { !it.queued && it.runId == runId }) throw QueueFailure(409, "queue_execution_active")
                if (token != null && debuggers.containsKey(token)) 409 j null
                else {
                    val family = inherited?.let { permits[it] }
                    when {
                        family != null -> 200 j register(family,
                            if (queued && inherited?.queued == true && inherited.runId == runId) inherited.parentRunId else inherited?.runId)
                        queued -> throw QueueFailure(409, "queue_not_reserved")
                        (!routed && stopped.isCompleted) || !slots.tryAcquire() -> 429 j null
                        else -> {
                            acquired = true
                            200 j register(PermitFamily(runId))
                        }
                    }
                }
            }
        } finally {
            if (acquired && !registered) slots.release()
        }
    }

    suspend fun execute(name: String, program: LcncProgram, named: Boolean, inputs: Map<String, Any?>, request: Map<*, *>,
                        routing: Join<Workgroup, NuidFanoutElement.Claim>? = null): HttpResponse =
        executeRun(name, program, named, inputs, request, routing)

    private suspend fun executeRun(name: String, program: LcncProgram, named: Boolean, inputs: Map<String, Any?>, request: Map<*, *>,
                        routing: Join<Workgroup, NuidFanoutElement.Claim>? = null, queued: QueuedRun? = null): HttpResponse {
        if (inputs.keys.any { it.startsWith(LcncCaseProgram.BINDING_PREFIX) })
            return response(400, mapOf("ok" to false, "error" to "reserved_case_input"))
        if (request.containsKey("case") && queued == null)
            return response(409, mapOf("ok" to false, "error" to "case_requires_live_queued_claim"))
        val runId = queued?.receipt?.get("runId") as? String ?: UUID.randomUUID().toString()
        val jobId = "lcnc/run/$runId"
        val token = request["debugToken"] as? String
        if (request.containsKey("debugToken") && (token == null ||
                !Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}").matches(token)))
            return response(400, mapOf("error" to "invalid_debug_token"))
        val debug = LcncDebug(stepping = token != null, allowInterventions = !request.containsKey("case"))
        // Admission covers the interval before the cancellable execution Job is registered.
        val (admitted, permit) = admitRun(runId, token, debug, routing != null, queued != null)
        if (admitted != 200) return response(admitted, mapOf("ok" to false,
            "error" to if (admitted == 409) "debug_token_active" else "execution_capacity"))
        var executionJob: Job? = null
        try {
            admission.withLock {
                oroboros.attach(runId, program, debug)
                if (stopped.isCompleted) debug.drain()
            }
            return withContext(checkNotNull(permit)) { coroutineScope {
                executionJob = currentCoroutineContext().job
                if (queued == null) active[runId] = checkNotNull(executionJob)
                val sourceCid = if (queued != null) {
                    (queued.receipt["sourceReceiptCid"] as? String)?.let { ContentId(it) }
                } else if (request.containsKey("sourceReceiptCid")) {
                    if (inputs.containsKey("evidence")) throw EvidenceFailure(409, "source_evidence_conflict")
                    val value = request["sourceReceiptCid"] as? String ?: throw EvidenceFailure(400, "invalid_source_receipt")
                    runCatching { ContentId(value) }.getOrElse { throw EvidenceFailure(400, "invalid_source_receipt") }
                } else null
                val evidence = sourceCid?.let {
                    if (queued == null) sourceEvidence(it)
                    else {
                        @Suppress("UNCHECKED_CAST")
                        val retained = inputs["evidence"] as? Map<String, Any?> ?: throw QueueFailure(400, "invalid_queued_evidence")
                        val sourceRun = retained["runId"] as? String ?: throw QueueFailure(400, "invalid_queued_evidence")
                        if (sourceRun.isBlank() || retained["receiptCid"] != it.value) throw QueueFailure(400, "invalid_queued_evidence")
                        "lcnc/run/$sourceRun" j retained
                    }
                }
                val effectiveInputs = if (queued != null || evidence == null) inputs else inputs + ("evidence" to evidence.b)
                val selectedPulse = try { pulseAdmission(program, effectiveInputs, request, retainedBase = queued != null) }
                catch (failure: EvidenceFailure) {
                    if (queued == null) throw failure
                    return@coroutineScope failQueued(queuedRef(queued.receipt) j currentCoroutineContext(),
                        failure.status, failure.code)
                }
                val retainedRequest = pulseRequest(request, selectedPulse)
                if (queued == null && evidence != null && ValueBudget().violation(effectiveInputs) != null)
                    throw EvidenceFailure(413, "source_receipt_payload_limit")
                // A run takes as long and walks as many statements as its work needs: a treatise or a
                // dictionary curated whole is hours and tens of thousands of nodes. The caller sets them.
                val timeoutMs = ((request["timeoutMs"] as? Number)?.toLong() ?: 120000L).coerceAtLeast(1L)
                val maxNodes = ((request["maxNodes"] as? Number)?.toInt() ?: 10000).coerceAtLeast(1)
                val versions = linkedMapOf<String, String>()
                val pinned = mutableMapOf<String, LcncProgram>()
                suspend fun freeze(label: String, source: LcncProgram): Pair<LcncProgram, String> = withContext(Dispatchers.IO) {
                    val bytes = LcncProgramConfix.toJson(source).encodeToByteArray()
                    // A frozen program carries its inline literals, a whole book included.
                    val version = ctx.casStore.put(bytes)
                    if (version != ContentId.of(bytes)) throw QueueFailure(409, "program_version_identity_mismatch")
                    LcncProgramConfix.fromJson(label, bytes.decodeToString()) to version.value
                }
                val (frozen, cid) = if (queued == null) freeze(name, program)
                    else program to (queued.receipt["programCid"] as String)
                oroboros.attach(runId, frozen, debug)
                val invocation = routing?.let { LcncWorkgroupKey(it.a j (it.b j ContentId(cid))) }
                if (named) {
                    pinned[name] = frozen; versions[name] = cid
                    oroboros.pin(runId, name, frozen)
                }
                // A rebuild (Forge genesis, Cut S) names the receipt it refreshes, must land on the very
                // program version that receipt recorded, and pins the subprogram versions it recorded.
                val rebuildOf = request["rebuildOf"]?.toString()?.takeIf { it.isNotBlank() }
                val rebuildOfRunId = request["rebuildOfRunId"]?.toString()?.takeIf { it.isNotBlank() }
                request["expectProgramCid"]?.toString()?.takeIf { it.isNotBlank() }?.let { expected ->
                    if (expected != cid) return@coroutineScope response(409, mapOf("ok" to false, "error" to "program_version_drift", "expected" to expected, "actual" to cid))
                }
                (queued?.pins ?: pinnedVersions(frozen, ContentId(cid), retainedRequest)).forEach { (label, version) ->
                    pinned[label] = version.a
                    oroboros.pin(runId, label, version.a)
                    versions[label] = version.b
                }
                var receipt: Map<String, Any?> = (queued?.receipt ?: mapOf(
                    "runId" to runId, "program" to name, "programKey" to if (named) LcncBlackboard.programKey(name) else null,
                    "executionFamily" to "lcnc/run/${checkNotNull(permit).family.runId}", "parentRunId" to permit.parentRunId,
                    "programCid" to cid, "inputs" to effectiveInputs, "startedAtMs" to ctx.clock(),
                    "versionPolicy" to "root-at-admission,subprogram-at-first-use",
                    "budgets" to mapOf("timeoutMs" to timeoutMs, "maxNodes" to maxNodes, "maxPayloadChars" to 131072),
                ) + (sourceCid?.let { mapOf("sourceReceiptCid" to it.value) } ?: emptyMap())) + (routing?.let { mapOf(
                    "workgroup" to it.a.name, "claimId" to it.b.claimId,
                    "subnet" to it.b.nuid.subnet.toString(), "claimedAtSubnet" to it.a.scope.toString(),
                    "claimLineage" to (NuidInvocation(it).lineage α { ancestor ->
                        mapOf("workgroup" to ancestor.a.name, "claimId" to ancestor.b.claimId)
                    }).toList(),
                    "parentWorkgroup" to it.b.parent?.a?.name, "parentClaimId" to it.b.parent?.b?.claimId,
                ) } ?: emptyMap()) +
                    (if (rebuildOf != null) mapOf("rebuildOf" to rebuildOf, "rebuildOfRunId" to rebuildOfRunId) else emptyMap()) +
                    (if (selectedPulse != null) mapOf("pulse" to request["pulse"], "baseReceiptCid" to selectedPulse.receiptCid) else emptyMap())
                var revision = if (queued == null) 0L else 1L
                val invocationLedger = LcncInvocationLedger()
                suspend fun record(op: String, status: String, fields: Map<String, Any?> = emptyMap()): Map<String, Any?> {
                    val value = receipt + fields + mapOf(
                        "status" to status, "programVersions" to versions.toMap(),
                        "previousReceiptCid" to receipt["receiptCid"],
                    )
                    receipt = if (queued == null) commit(jobId, op, value, revision)
                        else commit(jobId, op, value, revision, "$jobId:$status:${UUID.randomUUID()}", queue = true)
                    revision = (receipt["timelineRevision"] as Number).toLong()
                    return receipt
                }
                suspend fun finish(code: Int, op: String, status: String, fields: Map<String, Any?>): HttpResponse {
                    debug.finish(when (status) {
                        "completed" -> "completed"
                        "cancelled", "timed_out", "interrupted" -> "cancelled"
                        else -> "failed"
                    })
                    val observations = invocationLedger.snapshot()
                    val evidenceCid = withContext(Dispatchers.IO) {
                        val identity = ctx.casStore.put(jsonOf(observations + mapOf(
                            "kind" to "lcnc-invocations", "runId" to runId, "programCid" to cid,
                        )).encodeToByteArray())
                        ctx.casStore.sync()
                        identity.value
                    }
                    debug.persistent((receipt["claimedAtSubnet"] as? String).orEmpty(), "invocations",
                        emptyMap(), mapOf("runId" to runId, "invocationsCid" to evidenceCid,
                            "durability" to ctx.casStore.durability.name.lowercase()),
                        durable = ctx.casStore.durability == StorageDurability.DURABLE)
                    val debugCid = withContext(Dispatchers.IO) {
                        ctx.casStore.put(oroborosEvidence(runId, cid, debug.snapshot())).value
                    }
                    return response(code, record(op, status, fields + mapOf(
                        "finishedAtMs" to ctx.clock(), "invocationsCid" to evidenceCid, "oroborosCid" to debugCid,
                        "invocationsAttempted" to observations["attempted"], "invocationsDropped" to observations["dropped"],
                    )))
                }

                if (queued == null) record("submit", "validating")
                val selectedCase = queued?.case
                suspend fun owned(case: CouncilCaseRegistry.Case?): HttpResponse {
                    suspend fun caseFields(): Map<String, Any?> {
                        if (case == null || selectedCase == null) return emptyMap()
                        val fallback = mapOf("intent" to selectedCase.request.intent, "caseId" to selectedCase.request.caseId)
                        return try {
                            if (case.snapshotCid() == null) mapOf("case" to (fallback + ("phase" to "uninitialized")))
                            else mapOf("case" to case.lcncCaseMetadata(ctx.casStore, selectedCase.request))
                        } catch (_: Exception) {
                            mapOf("case" to (fallback + mapOf("phase" to "unavailable", "error" to "case_metadata_unavailable")))
                        }
                    }
                    try {
                        val contracts = vocabulary()
                        val violations = LcncTypeCheck.check(frozen, contracts, strict = false).map { it.toMap() }
                        if (violations.isNotEmpty()) return finish(400, "fail", "refused", mapOf(
                            "ok" to false, "phase" to "validation", "error" to "type_check_failed", "violations" to violations.take(128),
                        ))
                        record("start", "running", if (queued == null) emptyMap() else mapOf("startedAtMs" to ctx.clock()))
                        if (queued != null) active[runId] = checkNotNull(executionJob)
                        val owner = if (case == null) null else caseRun(runId, cid, queued, routing)
                        if (case != null && selectedCase != null && selectedCase.request !is LcncCaseRequest.Transition) {
                            val result = when (selectedCase.request) {
                                is LcncCaseRequest.Initialize -> case.initializeLcncCase(ctx.casStore, selectedCase, frozen, checkNotNull(owner))
                                is LcncCaseRequest.Read -> case.readLcncCase(ctx.casStore, selectedCase)
                                is LcncCaseRequest.Transition -> error("Unexpected transition dispatch")
                            }
                            return finish(200, "complete", "completed", result.output + mapOf("ok" to true,
                                "case" to result.metadata, "returns" to emptyMap<String, Any?>(), "outputs" to emptyMap<String, Any?>()))
                        }
                        val walker = LcncRunner(selectedPulse?.runners ?: ctx.lcncRunners).apply {
                            pulse = selectedPulse?.selection
                            maxNodeExecutions = maxNodes
                            trail = borg.trikeshed.lcnc.LcncTrail.live
                            ledger = LcncConsumedLedger().also { ledger ->
                                if (evidence != null) ledger.consumed("lcnc-run", evidence.a, checkNotNull(sourceCid).value)
                                if (selectedPulse != null) ledger.consumed("lcnc-run", "lcnc/run/" + selectedPulse.runId, selectedPulse.receiptCid)
                            }
                            invocations = invocationLedger
                            subprogramLoader = { label ->
                                pinned[label] ?: ctx.programLoader(label)?.let { source ->
                                    val (body, version) = freeze(label, source)
                                    val problems = LcncTypeCheck.check(body, contracts, strict = false)
                                    require(problems.isEmpty()) { "subprogram type_check_failed: $label" }
                                    pinned[label] = body; versions[label] = version
                                    oroboros.pin(runId, label, body)
                                    body
                                }
                            }
                        }
                        // One existing walker and assembly closure for ordinary runs and admitted case stages.
                        suspend fun walk(args: Map<String, Any?>, trustedRecord: (suspend (String) -> String)?): LcncRunner.ScopeResult = withTimeout(timeoutMs) {
                            val terminal = if (case == null) kotlin.coroutines.EmptyCoroutineContext
                                else TribunalRecordKey(trustedRecord ?: { throw LcncCaseException(LcncCaseFailure.UNSUPPORTED, "case_terminal_boundary_not_implemented") })
                            val hostContext = ctx.scope.coroutineContext.minusKey(Job) +
                                ctx.muxContext.minusKey(Job) + currentCoroutineContext() + debug +
                                (invocation ?: kotlin.coroutines.EmptyCoroutineContext) + terminal
                            val binding = ctx.ccekBinding
                            if (binding == null) withContext(hostContext) { walker.runProcedure(frozen, args) }
                            else LcncCcekAssembly(binding, walker).launch(name, frozen, args, context = hostContext).result.await()
                        }
                        suspend fun output(args: Map<String, Any?>, trustedRecord: (suspend (String) -> String)?): LcncCaseResult {
                            val result = walk(args, trustedRecord)
                            val promptVersions = PromptNodes.promptVersionsOf(listOf(frozen) + pinned.values, result.nodeOutputs) +
                                (walker.ledger?.promptVersions() ?: emptyMap())
                            val output = mapOf("returns" to result.returns, "outputs" to result.nodeOutputs,
                                "bindings" to result.bindings, "bindingsTruncated" to result.bindingsTruncated,
                                "promptVersions" to promptVersions,
                                "consumed" to result.consumed, "consumedTruncated" to result.consumedTruncated,
                                "inputFingerprint" to result.inputFingerprint)
                            val projection = borg.trikeshed.lcnc.LcncOutputReceipt.retain(ctx.casStore, output)
                            return LcncCaseResult(projection + rootState(cid, result.retained), invocationLedger.snapshot())
                        }
                        val result = if (case != null && selectedCase != null) {
                            val completed = case.transitionLcncCase(ctx.casStore, selectedCase, frozen, checkNotNull(owner), ::output)
                            completed.output + ("case" to completed.metadata)
                        } else output(effectiveInputs, null).output
                        val done = finish(200, "complete", "completed", result + ("ok" to true))
                        runCatching { facts?.assertRun(receipt) }.onFailure { System.err.println("[LcncRunService] consumed facts for $jobId not asserted: ${it.message}") }
                        if (rebuildOfRunId != null) {
                            runCatching { facts?.retractRun(rebuildOfRunId) }
                            ctx.blackboard.remove(borg.trikeshed.lcnc.LcncStaleMarker.key(rebuildOfRunId))
                        }
                        return done
                    } catch (e: TimeoutCancellationException) {
                        return finish(504, "cancel", "timed_out", caseFields() + mapOf("ok" to false, "error" to "time_limit"))
                    } catch (e: CancellationException) {
                        try {
                            withContext(NonCancellable) {
                                withTimeout(5000) { finish(499, "cancel", "cancelled", caseFields() + mapOf("ok" to false, "error" to (e.message ?: "cancelled"))) }
                            }
                        } catch (receiptFailure: Throwable) {
                            if (receiptFailure !== e) e.addSuppressed(receiptFailure)
                        }
                        throw e
                    } catch (e: LcncCaseException) {
                        return finish(caseStatus(e.failure), "fail", "refused", caseFields() + mapOf(
                            "ok" to false, "phase" to "case_admission", "error" to e.message))
                    } catch (e: EvidenceFailure) {
                        return finish(e.status, "fail", "failed", caseFields() + mapOf("ok" to false, "phase" to "reporting", "error" to e.code))
                    } catch (e: QueueFailure) {
                        return if (queued != null && revision == 1L && e.code.startsWith("run_commit_refused:"))
                            failQueued(queuedRef(queued.receipt) j currentCoroutineContext(), e.status, e.code)
                        else response(e.status, mapOf("ok" to false, "error" to e.code))
                    } catch (e: Exception) {
                        return finish(400, "fail", "failed", caseFields() + mapOf("ok" to false, "phase" to "execution", "error" to (e.message ?: "execution_failed").take(2048)))
                    }
                }
                if (selectedCase == null) owned(null)
                else try {
                    caseRun(runId, cid, queued, routing) // Refuse forged public metadata before even opening a case owner.
                    ctx.councilCases.withCase(selectedCase.request.caseId, create = selectedCase.request is LcncCaseRequest.Initialize) { owned(it) }
                } catch (e: CouncilCaseRegistry.Missing) {
                    finish(404, "fail", "refused", mapOf("ok" to false, "phase" to "case_admission", "error" to "case_not_found"))
                } catch (e: CouncilCaseRegistry.Capacity) {
                    finish(429, "fail", "refused", mapOf("ok" to false, "phase" to "case_admission", "error" to "case_capacity"))
                } catch (e: LcncCaseException) {
                    finish(caseStatus(e.failure), "fail", "refused", mapOf("ok" to false, "phase" to "case_admission", "error" to e.message))
                }

            } }
        } catch (e: QueueFailure) {
            return response(e.status, mapOf("ok" to false, "error" to e.code))
        } catch (e: EvidenceFailure) {
            return response(e.status, mapOf("ok" to false, "error" to e.code))
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            @Suppress("UNCHECKED_CAST")
            val receipt = ctx.blackboard.get(jobId) as? Map<String, Any?>
            return response(499, receipt ?: mapOf("ok" to false, "runId" to runId, "error" to "cancelled"))
        } catch (e: Exception) {
            return response(503, mapOf("ok" to false, "runId" to runId, "error" to "receipt_commit_failed", "detail" to (e.message ?: "unavailable").take(2048)))
        } finally {
            withContext(NonCancellable) {
                admission.withLock {
                    debug.close()
                    oroboros.release(runId)
                    if (token != null) debuggers.remove(token, DebugRun(runId, checkNotNull(debug)))
                    executionJob?.let { active.remove(runId, it) }
                    val family = checkNotNull(permits.remove(checkNotNull(permit)))
                    if (--family.references == 0) slots.release()
                    admittedRuns--
                    if (stopped.isCompleted && admittedRuns == 0) drained.complete(Unit)
                }
            }
        }
    }
}
