package borg.trikeshed.forge

import kotlinx.coroutines.channels.ReceiveChannel

/**
 * Low-level runner for individual workflow steps.
 * Implementations provide execution for different step types.
 */
interface ForgeStepRunner {

    /**
     * Execute an LLM call step.
     */
    suspend fun runLlmCall(
        step: WorkflowStep.LlmCall,
        resolvedInputs: Map<String, String>,
        modelConfig: Map<String, String>,
    ): StepResult

    /**
     * Execute a code execution step.
     */
    suspend fun runCodeExecution(
        step: WorkflowStep.CodeExecution,
        resolvedInputs: Map<String, String>,
        workingDir: String,
    ): StepResult

    /**
     * Execute an agent invocation step.
     */
    suspend fun runAgentInvocation(
        step: WorkflowStep.AgentInvocation,
        resolvedInputs: Map<String, String>,
        agentConfig: AgentConfig,
        workingDir: String,
    ): StepResult

    /**
     * Execute a file transform step.
     */
    suspend fun runFileTransform(
        step: WorkflowStep.FileTransform,
        workspace: ForgeWorkspace,
    ): StepResult

    /**
     * Evaluate a conditional step.
     */
    suspend fun runConditional(
        step: WorkflowStep.Conditional,
        resolvedInputs: Map<String, String>,
    ): Boolean

    /**
     * Execute parallel branches.
     */
    suspend fun runParallel(
        step: WorkflowStep.Parallel,
        resolvedInputs: Map<String, String>,
        runBranch: suspend (List<WorkflowStep>, Map<String, String>) -> List<StepResult>,
    ): List<StepResult>
}

/**
 * Agent runner for coding agents (Codex, Claude Code, etc.).
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
 * Events emitted by agent runners.
 */
sealed interface AgentEvent {
    data class Output(val text: String) : AgentEvent
    data class ToolCall(val tool: String, val args: Map<String, String>) : AgentEvent
    data class ToolResult(val tool: String, val result: String) : AgentEvent
    data class Error(val message: String) : AgentEvent
    data class Completed(val finalOutput: String) : AgentEvent
    data class Progress(val message: String) : AgentEvent
}