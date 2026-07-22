package borg.trikeshed.flywheel

import borg.trikeshed.jules.JulesCause
import borg.trikeshed.utils.kanban.QueueEntry
import java.time.Instant

/** Single work item ready for dispatch. */
data class WorkItem(
    val workId: String,
    val tier: String,           // epic/feature/task/test/chore
    val title: String,
    val spec: String,           // detailed spec for the agent
    val parent: String? = null, // parent workId for hierarchies
    val score: Double = 0.5,    // 0.0-1.0 priority within tier
    val deps: List<String> = emptyList(), // workIds that must land first
    val tags: List<String> = emptyList(),
    val createdAt: Long = Instant.now().toEpochMilli()
)

/** Agent that can execute work. */
sealed interface Agent {
    val id: String
    val capability: String      // e.g. "codex", "opencode", "jules", "claude"
    fun dispatch(work: WorkItem, context: String): DispatchResult
    fun tend(sessionId: String): TendResult
    fun harvest(sessionId: String): HarvestResult
}

/** Result of dispatching work to an agent. */
sealed class DispatchResult {
    data class Ok(val sessionId: String, val metadata: Map<String, String> = emptyMap()) : DispatchResult()
    data class Failed(val reason: String) : DispatchResult()
}

/** Result of tending an active session. */
sealed class TendResult {
    data class InProgress(val progress: String, val metadata: Map<String, String> = emptyMap()) : TendResult()
    data class AwaitingFeedback(val inquiry: String, val metadata: Map<String, String> = emptyMap()) : TendResult()
    data class Completed(val summary: String) : TendResult()
    data class Failed(val reason: String) : TendResult()
}

/** Result of harvesting a completed session. */
sealed class HarvestResult {
    data class Patch(val unifiedDiff: String, val baseCommit: String, val metadata: Map<String, String> = emptyMap()) : HarvestResult()
    data class NoPatch(val reason: String) : HarvestResult()
    data class Failed(val reason: String) : HarvestResult()
}

/** Kanban reducer policy — decides what to dispatch, hold, requeue. */
interface KanbanReducer {
    /** Given current queue + live sessions, produce a dispatch plan. */
    fun reduce(queue: List<QueueEntry>, live: Map<String, LiveSession>): DispatchPlan
}

/** Live agent session being tended. */
data class LiveSession(
    val sessionId: String,
    val workId: String,
    val agentId: String,
    val capability: String,
    val state: SessionState,
    val dispatchedAt: Long,
    val lastPollAt: Long,
    val attempt: Int,
    val patches: List<String> = emptyList(),
    val metadata: Map<String, String> = emptyMap()
)

enum class SessionState {
    DISPATCHED, IN_PROGRESS, AWAITING_FEEDBACK, COMPLETED, FAILED, HARVESTED
}

/** Plan output from kanban reducer. */
data class DispatchPlan(
    val toDispatch: List<DispatchDecision>,
    val toHold: List<HoldDecision>,
    val toRequeue: List<RequeueDecision>
)

data class DispatchDecision(
    val workId: String,
    val agentId: String,
    val reason: String = ""
)

data class HoldDecision(
    val workId: String,
    val reason: String
)

data class RequeueDecision(
    val workId: String,
    val reason: String,
    val newSpec: String? = null,
    val newScore: Double? = null
)

/** Flywheel configuration. */
data class FlywheelConfig(
    val maxLive: Int = 15,
    val pollIntervalMs: Long = 20_000,
    val tendTimeoutMs: Long = 300_000,
    val workPoolPath: String = "doc/todo.md",
    val trajectoryJsonPath: String = System.getProperty("user.home") + "/.local/forge/trajectory.json",
    val statePath: String = System.getProperty("user.home") + "/.local/forge/flywheel.state.json",
    val agents: Map<String, AgentConfig> = emptyMap()
)

data class AgentConfig(
    val type: String,              // "codex", "opencode", "jules", "claude"
    val command: String,           // CLI command template with {workId} {spec} {context}
    val maxConcurrent: Int = 3,
    val env: Map<String, String> = emptyMap()
)

/** Persisted flywheel state. */
data class FlywheelState(
    val queue: List<WorkItem> = emptyList(),
    val live: Map<String, LiveSession> = emptyMap(),
    val landed: Int = 0,
    val outcomes: List<Outcome> = emptyList(),
    val harvested: Set<String> = emptySet(),
    val lastCycleAt: Long = 0L
)

data class Outcome(
    val workId: String,
    val title: String,
    val ok: Boolean,
    val reason: String,
    val at: Long = Instant.now().toEpochMilli()
)