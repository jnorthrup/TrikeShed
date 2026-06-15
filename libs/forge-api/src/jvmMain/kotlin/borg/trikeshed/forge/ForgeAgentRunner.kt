package borg.trikeshed.forge

import kotlinx.coroutines.channels.ReceiveChannel

/**
 * JVM-specific agent runner for coding agents (Codex, Claude Code, etc.).
 * This is in jvmMain because it spawns native processes.
 */
interface ForgeAgentRunner {

    /**
     * Run an agent with a task and context.
     * Returns a channel of output tokens/events.
     */
    fun runAgent(
        config: AgentConfig,
        task: String,
        context: Map<String, String>,
        workingDir: String,
    ): ReceiveChannel<AgentEvent>

    /**
     * Check if this runner is available/configured.
     */
    suspend fun isAvailable(): Boolean

    /**
     * Get supported agent type.
     */
    val agentType: AgentType
}

/**
 * Streaming step result - JVM only (uses ReceiveChannel).
 */
@Serializable
sealed interface StepResult {
    @Serializable
    data class Success(
        val stepId: String,
        val output: String,
        val artifacts: List<ForgeFile> = emptyList(),
        val metadata: Map<String, String> = emptyMap(),
    ) : StepResult

    @Serializable
    data class Failure(
        val stepId: String,
        val error: String,
        val partialOutput: String? = null,
    ) : StepResult

    // JVM-only: streaming result via channel
    data class Streaming(
        val stepId: String,
        val stream: ReceiveChannel<String>,
    ) : StepResult
}