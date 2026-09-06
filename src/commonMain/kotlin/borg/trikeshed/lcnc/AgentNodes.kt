package borg.trikeshed.lcnc

/**
 * THE CODING-AGENT LANE (Forge genesis, Cut A; the post's bullet 4): Forge launches a
 * general-purpose coding agent, not only a chat model. The pure half lives here: the
 * roster row, the run request a lego or the claim worker builds, the result and its
 * receipt shape, and the two legos over an [AgentRuns] seam. The jvmMain half spawns
 * the CLI in a scratch clone, bounds it, and records its diff and transcript in CAS.
 */
data class AgentCliInfo(
    val id: String,
    val path: String,
    val version: String,
    val enabled: Boolean,
    val why: String = "",
) {
    fun toMap(): Map<String, Any?> = linkedMapOf("id" to id, "path" to path, "version" to version, "enabled" to enabled, "why" to why)
}

data class AgentRunRequest(
    val agent: String,
    val brief: String,
    val repo: String = "",
    val model: String = "",
    val budgetSeconds: Int = AgentNodes.DEFAULT_BUDGET_SECONDS,
    val maxBytes: Int = AgentNodes.DEFAULT_MAX_BYTES,
    val retain: Boolean = false,
    val runId: String = "",
    val jobId: String = "",
)

data class AgentRunResult(
    val runId: String,
    val jobId: String = "",
    val agent: String,
    val cli: String = "",
    val version: String = "",
    val model: String = "",
    val repo: String = "",
    val base: String = "",
    val scratch: String = "",
    val retained: Boolean = false,
    val startedAtMs: Long = 0L,
    val finishedAtMs: Long = 0L,
    val budgetSeconds: Int = AgentNodes.DEFAULT_BUDGET_SECONDS,
    val exit: Int = -1,
    val killed: Boolean = false,
    val bytes: Long = 0L,
    val kept: Long = 0L,
    val truncated: Boolean = false,
    val transcriptCid: String = "",
    val patchCid: String = "",
    val patchBytes: Long = 0L,
    val filesChanged: Int = 0,
    val summary: String = "",
    val error: String = "",
) {
    /** Done as asked: the CLI exited 0 inside its budget and nothing on our side failed. */
    val ok: Boolean get() = error.isEmpty() && !killed && exit == 0

    /** The `agent/run/<runId>` receipt, every field named so a person and the plane read the same thing. */
    fun receipt(): Map<String, Any?> = linkedMapOf(
        "runId" to runId, "jobId" to jobId, "agent" to agent, "cli" to cli, "version" to version, "model" to model,
        "repo" to repo, "base" to base, "scratch" to scratch, "retained" to retained,
        "startedAtMs" to startedAtMs, "finishedAtMs" to finishedAtMs, "budgetSeconds" to budgetSeconds,
        "exit" to exit, "ok" to ok, "killed" to killed, "bytes" to bytes, "kept" to kept, "truncated" to truncated,
        "transcriptCid" to transcriptCid, "patchCid" to patchCid, "patchBytes" to patchBytes, "filesChanged" to filesChanged,
        "summary" to summary.take(AgentNodes.SUMMARY_CHARS), "error" to error,
    )
}

/** The seam: who is installed here, and one bounded run. */
interface AgentRuns {
    suspend fun roster(): List<AgentCliInfo>
    suspend fun run(request: AgentRunRequest): AgentRunResult
}

/** Map-backed seam for tests and module rigs: a scripted result per request, no process. */
class InMemoryAgentRuns(
    private val rosterRows: List<AgentCliInfo>,
    private val script: (AgentRunRequest) -> AgentRunResult,
) : AgentRuns {
    val requests = ArrayList<AgentRunRequest>()
    override suspend fun roster(): List<AgentCliInfo> = rosterRows
    override suspend fun run(request: AgentRunRequest): AgentRunResult {
        requests.add(request)
        return script(request)
    }
}

object AgentNodes {
    const val RUN = "agent.run"
    const val LIST = "agent.list"
    const val RECEIPT_PREFIX = "agent/run/"
    const val LANGUAGE = "agent-run"
    const val DEFAULT_BUDGET_SECONDS = 600
    /** The reaper strikes a RUNNING card at fifteen minutes; a run must be over before that. */
    const val MAX_BUDGET_SECONDS = 840
    const val DEFAULT_MAX_BYTES = 4_194_304
    const val SUMMARY_CHARS = 4096

    /** The plane id the brief cites for a MUST met by a file change: the receipt on the blackboard. */
    fun evidenceId(runId: String): String = "blackboard/$RECEIPT_PREFIX$runId"

    fun servedTypes(): Set<String> = setOf(RUN, LIST)

    fun clampBudget(seconds: Int?): Int = (seconds ?: DEFAULT_BUDGET_SECONDS).coerceIn(1, MAX_BUDGET_SECONDS)

    /** A wired input wins over the param of the same name (the SubVmLegos rule); blanks fall through. */
    fun requestOf(node: LcncNode, inputs: Map<String, Any?>, runId: String, jobId: String = ""): AgentRunRequest {
        fun pick(name: String): String {
            val wired = inputs[name] ?: inputs["$name?"]
            val s = wired?.toString()?.takeIf { it.isNotBlank() } ?: node.params[name]?.takeIf { it.isNotBlank() }
            return s.orEmpty()
        }
        return AgentRunRequest(
            agent = pick("agent").trim().lowercase(),
            brief = pick("brief"),
            repo = pick("repo"),
            model = pick("model"),
            budgetSeconds = clampBudget(pick("budgetSeconds").toIntOrNull()),
            maxBytes = pick("maxBytes").toIntOrNull()?.coerceIn(4096, 64 * 1024 * 1024) ?: DEFAULT_MAX_BYTES,
            retain = pick("retain").equals("true", ignoreCase = true),
            runId = runId,
            jobId = jobId,
        )
    }

    fun registry(runs: AgentRuns, mintRunId: () -> String): Map<String, LcncNodeRunner> = mapOf(
        LIST to LcncNodeRunner { _, _ ->
            val rows = runs.roster()
            mapOf("agents" to rows.map { it.toMap() }, "count" to rows.size)
        },
        RUN to LcncNodeRunner { node, inputs ->
            val request = requestOf(node, inputs, mintRunId())
            require(request.agent.isNotEmpty()) { "agent.run: no agent named (wire agent? or set the agent param)" }
            require(request.brief.isNotEmpty()) { "agent.run: no brief wired or in params" }
            val result = runs.run(request)
            mapOf(
                "summary" to result.summary, "transcriptCid" to result.transcriptCid, "patchCid" to result.patchCid,
                "exit" to result.exit, "ok" to result.ok, "error" to result.error, "runId" to result.runId, "truncated" to result.truncated,
            )
        },
    )
}
