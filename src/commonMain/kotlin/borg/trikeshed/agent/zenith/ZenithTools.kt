package borg.trikeshed.agent.zenith

import borg.trikeshed.agent.modelmux.AssertionPhase
import borg.trikeshed.agent.modelmux.BashWork
import borg.trikeshed.agent.modelmux.MissionEvent
import borg.trikeshed.agent.modelmux.MissionJournal
import borg.trikeshed.agent.modelmux.MissionTask
import borg.trikeshed.agent.modelmux.bashMissionPlan
import borg.trikeshed.agent.modelmux.executeBashWork
import borg.trikeshed.agent.modelmux.executeMission
import borg.trikeshed.agent.modelmux.missionAssertionState
import borg.trikeshed.agent.modelmux.missionAssertions
import borg.trikeshed.agent.modelmux.missionEvents
import borg.trikeshed.agent.modelmux.planMission
import borg.trikeshed.context.nuid.*
import borg.trikeshed.lib.*
import borg.trikeshed.mcp.McpTool
import borg.trikeshed.mcp.McpToolResult
import borg.trikeshed.mcp.ToolFailure
import borg.trikeshed.userspace.nio.channels.spi.ProcessOperations
import borg.trikeshed.userspace.nio.channels.spi.ProcessResult
import borg.trikeshed.userspace.nio.file.spi.FileOperations
import borg.trikeshed.userspace.nio.spi.NioSupervisor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * What this port of Zenith actually is. It is reported to clients verbatim so nobody has to guess: this is the
 * dependency-gated mission subset with durable receipts, not the upstream coordinator.
 */
object ZenithStatus {
    val implemented = listOf(
        "plan admission: strict bounded JSON, unique ids, dependency/cycle validation, work/validate/gate contract checks",
        "dependency-ordered execution of Bash tasks through the NUID workgroup dispatcher, awaiting each result",
        "acceptance: a task counts only when its result passes (exit status 0 for Bash)",
        "payload-free receipts (ADMITTED/COMPLETED/FAILED/REJECTED) and an optional durable single-run journal",
        "assertion state projected from receipts (PENDING/VALIDATED/PASSED/FAILED) and read-only journal inspection",
    )
    val missing = listOf(
        "gates as runtime evidence checks over the latest validator attempts (a gate here is an ordinary task)",
        "resumable attempts, retry, handoff and persisted attention items (a failure stops the run)",
        "plan patching with sealed history and rollback, and the explicit terminal review step",
        "model tasks over this interface (the plan format accepts them; running them is not wired here)",
        "A2A peer control and concentric work organization with published mailboxes/forums",
    )
}

/**
 * Zenith's mission machinery as MCP tools.
 *
 * `zenith_mission_run` executes caller-supplied Bash with the permissions of this process, so it is registered only
 * when [allowExec] is true; without it the tool does not exist. Journals live in one configured directory and are
 * addressed by bare file name, never by path.
 */
class ZenithTools(
    private val allowExec: Boolean = false,
    private val journalDir: String? = null,
) {
    fun tools(): List<McpTool> = buildList {
        add(statusTool())
        add(validateTool())
        if (journalDir != null) add(inspectTool())
        if (allowExec) add(runTool())
    }

    private fun obj(vararg properties: Pair<String, Map<String, Any?>>, required: List<String> = emptyList()): Map<String, Any?> =
        mapOf("type" to "object", "properties" to properties.toMap(), "required" to required, "additionalProperties" to false)

    private val planArg = mapOf("type" to "string", "minLength" to 2, "maxLength" to 1_048_576)
    private val journalArg = mapOf("type" to "string", "minLength" to 1, "maxLength" to 64)

    private fun journalPath(name: String): String {
        val dir = journalDir ?: throw ToolFailure("journals are not configured on this server")
        if (!JOURNAL_NAME.matches(name)) throw ToolFailure("journal must be a bare file name of letters, digits, '.', '_' or '-'")
        return "$dir/$name"
    }

    private fun kindOf(task: MissionTask): String = if (task.payload is BashWork) "bash" else "model"

    private fun describe(task: MissionTask): Map<String, Any?> = mapOf(
        "id" to task.id,
        "kind" to kindOf(task),
        "role" to task.role.name.lowercase(),
        "dependsOn" to task.dependencies.view.toList(),
        "targets" to task.targets.view.toList(),
    )

    private fun eventMap(e: MissionEvent): Map<String, Any?> = mapOf(
        "task" to e.task, "phase" to e.phase.name, "claimId" to e.claimId, "worker" to e.worker,
        "role" to e.role.name.lowercase(), "targets" to e.targets.view.toList(),
    )

    private fun assertionReport(declared: List<String>, events: List<MissionEvent>): Pair<Map<String, String>, String?> {
        val states = missionAssertionState(events.toSeries()).view.associate { it.a to it.b }
        val report = linkedMapOf<String, String>()
        for (id in declared) report[id] = (states[id] ?: AssertionPhase.PENDING).name
        // Also anything receipts targeted that the plan did not declare, so an inspection of a bare journal still shows it.
        for ((id, phase) in states) if (id !in report) report[id] = phase.name
        val basis = when {
            report.isEmpty() -> null
            report.values.any { it == AssertionPhase.FAILED.name } -> "FAILED"
            report.values.all { it == AssertionPhase.PASSED.name } -> "PASSED"
            else -> "INCOMPLETE"
        }
        return report to basis
    }

    // ── zenith_status ─────────────────────────────────────────────────

    private fun statusTool() = McpTool(
        name = "zenith_status",
        description = "What this Zenith port does and does not do, and which of its tools this server enables. Read this before " +
            "relying on any Zenith behaviour: the port is the dependency-gated mission subset, not the upstream coordinator.",
        inputSchema = obj(),
    ) {
        McpToolResult(
            mapOf(
                "port" to "borg.trikeshed.agent.zenith (mission subset)",
                "implemented" to ZenithStatus.implemented,
                "missing" to ZenithStatus.missing,
                "execution" to if (allowExec) "enabled: zenith_mission_run executes Bash with this server's permissions" else "disabled: zenith_mission_run is not registered",
                "journals" to if (journalDir != null) "enabled" else "disabled",
            ),
        )
    }

    // ── zenith_plan_validate ──────────────────────────────────────────

    private fun validateTool() = McpTool(
        name = "zenith_plan_validate",
        description = "Admit a mission plan without running it: strict JSON, unique ids, dependencies, cycles and the work/validate/gate " +
            "contract, using the same checks execution applies. Returns the dispatch order, or the first reason it is refused.",
        inputSchema = obj("plan" to planArg, required = listOf("plan")),
    ) { args ->
        val source = args["plan"] as String
        try {
            val bash = nuid(Capability.Process("bash"), Nonce.RandomBytes(), Subnet.core)
            val model = nuid(Capability.Process("model-completion"), Nonce.RandomBytes(), Subnet.core)
            val assertions = missionAssertions(source)
            val tasks = bashMissionPlan(source, bash, model)
            val order = planMission(tasks, assertions)
            val blockers = buildList {
                if (!allowExec) add("zenith_mission_run is not enabled on this server")
                if (order.any { kindOf(it) == "model" }) add("model tasks cannot be run through this interface yet")
            }
            McpToolResult(
                mapOf(
                    "valid" to true,
                    "order" to order.map { it.id },
                    "tasks" to order.map { describe(it) },
                    "assertions" to assertions.view.toList(),
                    "runnableHere" to blockers.isEmpty(),
                    "blockers" to blockers,
                ),
            )
        } catch (refused: Exception) {
            if (refused is CancellationException) throw refused
            McpToolResult(mapOf("valid" to false, "error" to (refused.message ?: refused::class.simpleName ?: "refused").take(200)))
        }
    }

    // ── zenith_mission_inspect ────────────────────────────────────────

    private fun inspectTool() = McpTool(
        name = "zenith_mission_inspect",
        description = "Read-only reconstruction of a finished or interrupted mission from its journal: the receipts and the assertion " +
            "state they imply. This is inspection, not resumption and not authentication; a journal is only as trustworthy as its storage.",
        inputSchema = obj("journal" to journalArg, required = listOf("journal")),
    ) { args ->
        val path = journalPath(args["journal"] as String)
        val nio = NioSupervisor()
        try {
            nio.open()
            val files = checkNotNull(nio.service<FileOperations>()) { "no filesystem service" }
            if (!files.exists(path)) throw ToolFailure("no such journal")
            val events = missionEvents(files.readString(path)).view.toList()
            val (report, basis) = assertionReport(emptyList(), events)
            McpToolResult(mapOf("events" to events.map { eventMap(it) }, "assertions" to report, "assertionOutcome" to basis))
        } catch (failure: ToolFailure) {
            throw failure
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Exception) {
            throw ToolFailure("journal cannot be inspected: ${failure::class.simpleName}")
        } finally {
            withContext(NonCancellable) { nio.drain(); nio.close() }
        }
    }

    // ── zenith_mission_run ────────────────────────────────────────────

    private fun runTool() = McpTool(
        name = "zenith_mission_run",
        description = "EXECUTES the plan's Bash tasks in dependency order with this server's permissions, capturing each task's output. " +
            "A task passes only on exit status 0. The first failure stops the run. Children get no ambient credentials. Optionally " +
            "writes a durable receipt journal (refuses to overwrite an existing one).",
        inputSchema = obj(
            "plan" to planArg,
            "journal" to journalArg,
            "timeoutMs" to mapOf("type" to "integer", "minimum" to 100, "maximum" to 300_000),
            "maxOutputChars" to mapOf("type" to "integer", "minimum" to 0, "maximum" to 1_048_576),
            required = listOf("plan"),
        ),
        readOnly = false,
    ) { args -> run(args) }

    private suspend fun run(args: Map<String, Any?>): McpToolResult {
        val source = args["plan"] as String
        val journalName = args["journal"] as String?
        val journalAt = journalName?.let { journalPath(it) }
        val timeoutMs = (args["timeoutMs"] as Number?)?.toLong() ?: DEFAULT_TIMEOUT_MS
        val maxOutput = (args["maxOutputChars"] as Number?)?.toInt() ?: DEFAULT_OUTPUT_CHARS

        val nio = NioSupervisor()
        val events = ArrayList<MissionEvent>()
        val captured = LinkedHashMap<String, ProcessResult>()
        var journal: MissionJournal? = null
        // The mission's children hang off their own job. withTimeout cancels only the awaiting block, so without this a timeout
        // would wait for the children to finish by themselves, and a hung task would hold this call (and a server slot) forever.
        val missionJob = Job(currentCoroutineContext()[Job])
        val fanout = NuidFanoutElement(parentJob = missionJob)
        var outcome = "COMPLETED"
        var failure: String? = null
        try {
            nio.open()
            val processes = checkNotNull(nio.service<ProcessOperations>()) { "no process service" }
            if (journalAt != null) journal = MissionJournal(checkNotNull(nio.service<FileOperations>()) { "no filesystem service" }, journalAt)
            fanout.open()
            val capability = Capability.Process("bash")
            fanout.register(Workgroup("bash", Subnet.core, TraitSpace { s_[capability] })) { claim ->
                withContext(processes) { executeBashWork(claim.payload as BashWork) }
            }
            val address = nuid(capability, Nonce.RandomBytes(), Subnet.core)
            val assertions = missionAssertions(source)
            // No model address: a plan containing model tasks is refused here, with a fixed message, before any effect.
            val tasks = bashMissionPlan(source, address, null)
            val recording = tasks.view.map { t ->
                t.copy(accept = { value ->
                    (value as? ProcessResult)?.let { captured[t.id] = it }
                    t.accept(value)
                })
            }.toSeries()
            try {
                withTimeout(timeoutMs) {
                    executeMission(fanout, recording, observe = { e -> events.add(e); journal?.record(e) }, assertions = assertions)
                }
            } catch (late: TimeoutCancellationException) {
                outcome = "TIMED_OUT"
                failure = "mission exceeded ${timeoutMs}ms"
                missionJob.cancel(CancellationException(failure))   // stop the children now
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (stopped: Exception) {
                outcome = "FAILED"
                failure = (stopped.message ?: stopped::class.simpleName ?: "failed").take(200)
                missionJob.cancel(CancellationException("mission stopped at its first failure"))   // the first failure stops the run
            }
            val (report, basis) = assertionReport(assertions.view.toList(), events)
            val last = events.lastOrNull { it.phase.name == "FAILED" || it.phase.name == "REJECTED" }
            return McpToolResult(
                buildMap {
                    put("outcome", outcome)
                    failure?.let { put("reason", it) }
                    last?.let { put("failedTask", it.task) }
                    put("events", events.map { eventMap(it) })
                    put("assertions", report)
                    put("assertionOutcome", basis)
                    put("outputs", captured.mapValues { (_, r) -> output(r, maxOutput) })
                    journalName?.let { put("journal", it) }
                },
                isError = outcome != "COMPLETED",
            )
        } catch (refused: IllegalArgumentException) {
            throw ToolFailure("plan refused: ${refused.message?.take(200)}")
        } catch (refused: IllegalStateException) {
            if (refused is CancellationException) throw refused
            throw ToolFailure("plan refused: ${refused.message?.take(200)}")
        } finally {
            withContext(NonCancellable) {
                try { fanout.drain(); fanout.close() }
                finally {
                    missionJob.cancel()
                    try { journal?.close() }
                    finally { nio.drain(); nio.close() }
                }
            }
        }
    }

    private fun output(result: ProcessResult, limit: Int): Map<String, Any?> {
        val out = result.stdout.decodeToString()
        val err = result.stderr.decodeToString()
        return mapOf(
            "exitCode" to result.exitCode,
            "stdout" to out.take(limit),
            "stderr" to err.take(limit),
            "truncated" to (out.length > limit || err.length > limit),
        )
    }

    private companion object {
        val JOURNAL_NAME = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")
        const val DEFAULT_TIMEOUT_MS = 60_000L
        const val DEFAULT_OUTPUT_CHARS = 65_536
    }
}
