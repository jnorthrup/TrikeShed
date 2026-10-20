package borg.trikeshed.kanban.module

import borg.trikeshed.job.ContentId
import borg.trikeshed.lcnc.*
import borg.trikeshed.litebike.JvmKanbanServer.HttpResponse
import borg.trikeshed.module.ModuleContext
import borg.trikeshed.parse.*
import borg.trikeshed.relaxfactory.CouchHttpSurface
import borg.trikeshed.vm.VmSourceCoordinates
import kotlinx.coroutines.CancellationException
import java.util.concurrent.ConcurrentHashMap

/** Oroboros observes the local execution owner; concentric network admission remains with workgroups. */
internal class LcncOroboros(val ctx: ModuleContext) {
    data class Execution(val program: LcncProgram, val control: LcncDebug,
                         val programs: ConcurrentHashMap<String, LcncProgram> = ConcurrentHashMap())
    val executions = ConcurrentHashMap<String, Execution>()

    fun attach(runId: String, program: LcncProgram, control: LcncDebug) {
        executions[runId] = Execution(program, control)
    }

    fun release(runId: String) { executions.remove(runId) }

    fun pin(runId: String, name: String, program: LcncProgram) {
        executions[runId]?.programs?.put(name, program)
    }

    private fun response(status: Int, value: Map<String, Any?>) = HttpResponse(status, jsonOf(value))

    private fun summary(receipt: Map<*, *>): Map<String, Any?> =
        receipt.entries.filter { it.key in setOf("runId", "program", "programCid", "status", "subnet",
            "claimedAtSubnet", "workgroup", "parentRunId", "startedAtMs", "finishedAtMs", "receiptCid",
            "oroborosCid", "sequence", "error") }.associate { it.key as String to it.value }

    private suspend fun content(cid: Any?, limit: Int): Any? {
        if (cid == null) return null
        val identity = ContentId(cid as String)
        val bytes = ctx.casStore.get(identity) ?: error("debug_evidence_unavailable")
        check(ContentId.of(bytes) == identity) { "debug_evidence_identity_mismatch" }
        require(bytes.size <= limit) { "debug_evidence_payload_limit" }
        return reify(bytes.decodeToString())
    }

    suspend fun route(method: String, path: String, body: String): HttpResponse {
        if (method !in setOf("GET", "POST")) return response(405, mapOf("error" to "method_not_allowed"))
        if (path.length > 4096 || body.length > 16384) return response(413, mapOf("error" to "payload_limit"))
        return try {
            val request = if (method == "GET") CouchHttpSurface.parseQuery(path.substringAfter('?', ""))
                else reify(body) as? Map<*, *> ?: return response(400, mapOf("error" to "bad_json"))
            val runId = request["runId"] as? String
            if (method == "GET" && !request.containsKey("runId")) {
                val rows = ctx.blackboard.snapshot().values.entries.asSequence()
                    .filter { it.key.startsWith("lcnc/run/") }.mapNotNull { it.value as? Map<*, *> }
                    .sortedByDescending { (it["sequence"] as? Number)?.toLong() ?: 0L }
                    .take(32).map(::summary).toMutableList()
                for ((id, execution) in executions) {
                    val index = rows.indexOfFirst { it["runId"] == id }
                    val live = mapOf("runId" to id, "state" to execution.control.snapshot()["state"])
                    if (index >= 0) rows[index] = rows[index] + live
                    else rows.add(live + mapOf("program" to execution.program.name, "status" to "admitted"))
                }
                response(200, mapOf("owner" to "local", "executions" to rows.take(64),
                    "truncated" to (rows.size > 64), "recentLimit" to 32,
                    "coverage" to "LCNC executions owned by this host; remote hosts retain their own debugger"))
            } else {
                require(runId != null && Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}").matches(runId)) {
                    "invalid_run_id"
                }
                val execution = executions[runId]
                val receipt = ctx.blackboard.get("lcnc/run/$runId") as? Map<*, *>
                if (method == "GET") {
                    if (execution == null && receipt == null) return response(404, mapOf("error" to "run_not_found"))
                    val evidence = if (execution == null) content(receipt?.get("oroborosCid"), 4_194_304) as? Map<*, *> else null
                    if (evidence != null) check(evidence["runId"] == runId &&
                        evidence["programCid"] == receipt?.get("programCid")) { "debug_evidence_run_mismatch" }
                    val program = execution?.program?.let { reify(LcncProgramConfix.toJson(it)) }
                        ?: content(receipt?.get("programCid"), 1_048_576)
                    val programs = linkedMapOf<String, Any?>()
                    var remaining = 2_097_152
                    var omitted = 0
                    val versions = receipt?.get("programVersions") as? Map<*, *> ?: emptyMap<Any, Any>()
                    val names = (execution?.programs?.keys ?: versions.keys.filterIsInstance<String>()).toSet()
                    for (name in names) {
                        if (programs.size >= 32 || remaining <= 0) { omitted++; continue }
                        val source = execution?.programs?.get(name)
                        val bytes = source?.let { LcncProgramConfix.toJson(it).encodeToByteArray() } ?: run {
                            val cid = versions[name] as? String ?: continue
                            val identity = ContentId(cid)
                            val value = ctx.casStore.get(identity) ?: continue
                            check(ContentId.of(value) == identity) { "program_identity_mismatch" }
                            value
                        }
                        if (bytes.size > remaining) { omitted++; continue }
                        remaining -= bytes.size
                        programs[name] = reify(bytes.decodeToString())
                    }
                    response(200, mapOf("runId" to runId, "owner" to "local", "program" to program,
                        "programs" to programs, "programsTruncated" to omitted,
                        "receipt" to receipt, "debug" to (execution?.control?.snapshot() ?: evidence?.get("debug")),
                        "timeoutIncludesPauses" to true,
                        "coverage" to (evidence?.get("coverage") ?: "node boundaries and explicitly entered supported guest source"),
                        "observation" to if (execution != null) "live" else if (evidence != null) "retained" else "unavailable"))
                } else {
                    if (execution == null) return response(409, mapOf("error" to "run_not_active"))
                    control(runId, execution.control, request)
                }
            }
        } catch (failure: CancellationException) { throw failure }
        catch (failure: IllegalArgumentException) { response(400, mapOf("error" to (failure.message ?: "invalid_debug_request"))) }
        catch (failure: Exception) { response(503, mapOf("error" to (failure.message ?: "debug_unavailable"))) }
    }

    private suspend fun control(runId: String, control: LcncDebug, request: Map<*, *>): HttpResponse {
        fun ordinal(): Int {
            val value = (request["ordinal"] as? Number)?.toDouble()
            require(value != null && value.isFinite() && value in 1.0..Int.MAX_VALUE.toDouble() && value % 1 == 0.0) {
                "invalid_debug_ordinal"
            }
            return value.toInt()
        }
        fun strings(field: String): List<String> {
            val value = request[field] as? List<*> ?: throw IllegalArgumentException("invalid_$field")
            require(value.all { it is String }) { "invalid_$field" }
            @Suppress("UNCHECKED_CAST") return value as List<String>
        }
        fun fields(field: String): Map<String, Any?> {
            if (!request.containsKey(field)) return emptyMap()
            val value = request[field] as? Map<*, *> ?: throw IllegalArgumentException("invalid_$field")
            require(value.keys.all { it is String }) { "invalid_$field" }
            @Suppress("UNCHECKED_CAST") return value as Map<String, Any?>
        }
        val action = request["action"] as? String ?: throw IllegalArgumentException("debug_action_required")
        val accepted = when (action) {
            "pause" -> control.pause()
            "cancel" -> control.cancel()
            "select" -> {
                val automatic = when (request["automatic"]) {
                    null, "none" -> LcncDebug.Automatic.NONE
                    "failure" -> LcncDebug.Automatic.FAILURE
                    "skip" -> LcncDebug.Automatic.SKIP
                    else -> throw IllegalArgumentException("invalid_automatic_pointcut")
                }
                control.select(if (request.containsKey("nodeIds")) strings("nodeIds").toSet() else emptySet(),
                    if (request["scope"] == null) null else strings("scope"), automatic)
            }
            "intervene" -> control.intervene(ordinal(), fields("inputs"), fields("bindings"))
            "monitor-attach", "monitor-detach" -> {
                require(request["scope"] == null || request["scope"] is String) { "invalid_monitor_scope" }
                require(request["pointcut"] == null || request["pointcut"] is String) { "invalid_monitor_pointcut" }
                require(request["spiking"] == null || request["spiking"] is Boolean) { "invalid_monitor_mode" }
                control.monitor(action == "monitor-attach", request["scope"] as? String,
                    request["pointcut"] as? String, request["spiking"] == true)
            }
            else -> {
                val command = LcncDebug.Command.entries.firstOrNull { it.name.lowercase().replace('_', '-') == action }
                    ?: throw IllegalArgumentException("invalid_debug_command")
                val line = (request["line"] as? Number)?.toDouble()
                if (command == LcncDebug.Command.SOURCE_LINE) require(line != null && line.isFinite() &&
                    line in 1.0..Int.MAX_VALUE.toDouble() && line % 1 == 0.0) { "invalid_debug_line" }
                require(request["coordinates"] in setOf(null, "generated", "authored")) { "invalid_debug_coordinates" }
                control.command(ordinal(), command, line?.toInt(),
                    if (request["coordinates"] == "authored") VmSourceCoordinates.AUTHORED else VmSourceCoordinates.GENERATED)
            }
        }
        return response(if (accepted) 202 else 409,
            mapOf("ok" to accepted, "runId" to runId, "debug" to control.snapshot()))
    }
}
