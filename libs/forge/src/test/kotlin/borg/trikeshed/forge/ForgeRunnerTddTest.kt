package borg.trikeshed.forge

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.tasks.await
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * TDD RED tests for ForgeStepRunner contract.
 */
class ForgeStepRunnerTddTest {

    private val runner: ForgeStepRunner = ForgeStepRunnerStub()
    private val workspace: ForgeWorkspace = ForgeWorkspaceStub()

    @Test
    fun `runLlmCall executes prompt with inputs and returns result`() = runTest {
        val step = WorkflowStep.LlmCall(
            id = "step1",
            promptId = ForgePromptId("summarize"),
            inputs = mapOf("text" to "Long document content..."),
            model = "gpt-4o-mini",
            parameters = mapOf("temperature" to "0.3")
        )

        val result = runner.runLlmCall(step, mapOf("text" -> "Long document content..."), mapOf("temperature" -> "0.3")).await()

        assertTrue(result is StepResult.Success)
        val success = result as StepResult.Success
        assertEquals("step1", success.stepId)
        assertNotNull(success.output)
    }

    @Test
    fun `runCodeExecution executes code and returns output`() = runTest {
        val step = WorkflowStep.CodeExecution(
            id = "step1",
            language = "python",
            code = "print('hello')\nresult = 2 + 2",
            inputs = emptyMap(),
            timeoutMs = 5000
        )

        val result = runner.runCode(step, emptyMap(), "/tmp").await()

        assertTrue(result is StepResult.Success)
        val success = result as StepResult.Success
        assertEquals("step1", success.stepId)
        assertTrue(success.output.contains("4"))
    }

    @Test
    fun `runAgentInvocation invokes coding agent with task`() = runTest {
        val step = WorkflowStep.AgentInvocation(
            id = "step1",
            agentType = AgentType.CODEX,
            task = "Create a hello world function",
            context = mapOf("language" to "kotlin"),
            allowedTools = listOf("read_file", "write_file", "run_command")
        )
        val config = AgentConfig(
            type = AgentType.CODEX,
            endpoint = null,
            apiKey = null,
            workingDirectory = "/workspace",
            environment = emptyMap(),
            maxTurns = 5,
            timeoutMs = 60000
        )

        val result = runner.runAgent(step, mapOf("language" -> "kotlin"), config, "/workspace").await()

        assertTrue(result is StepResult.Success)
        val success = result as StepResult.Success
        assertEquals("step1", success.stepId)
        assertNotNull(success.output)
    }

    @Test
    fun `runFileTransform applies transform to input files`() = runTest {
        val step = WorkflowStep.FileTransform(
            id = "step1",
            inputFileIds = listOf(ForgeFileId("f1"), ForgeFileId("f2")),
            transform = "concat",
            outputPath = "merged.md"
        )

        val result = runner.runTransform(step, workspace).await()

        assertTrue(result is StepResult.Success)
        val success = result as StepResult.Success
        assertEquals("step1", success.stepId)
        assertTrue(success.artifacts.any { it.path == "merged.md" })
    }

    @Test
    fun `evalConditional returns true for satisfied condition`() = runTest {
        val step = WorkflowStep.Conditional(
            id = "step1",
            condition = "input.length > 10",
            thenBranch = listOf(WorkflowStep.CodeExecution("then", "python", "pass", emptyMap())),
            elseBranch = listOf(WorkflowStep.CodeExecution("else", "python", "pass", emptyMap()))
        )

        val result = runner.evalConditional(step, mapOf("input" -> "this is a long string")).await()
        assertTrue(result)

        val result2 = runner.evalConditional(step, mapOf("input" -> "short")).await()
        assertFalse(result2)
    }

    @Test
    fun `runParallel executes branches concurrently and collects results`() = runTest {
        val step = WorkflowStep.Parallel(
            id = "step1",
            branches = listOf(
                listOf(WorkflowStep.CodeExecution("b1", "python", "result = 1", emptyMap())),
                listOf(WorkflowStep.CodeExecution("b2", "python", "result = 2", emptyMap())),
                listOf(WorkflowStep.CodeExecution("b3", "python", "result = 3", emptyMap()))
            )
        )

        val results = runner.runParallel(step, emptyMap()) { branchSteps, inputs ->
            branchSteps.map { step ->
                runner.runCode(step as WorkflowStep.CodeExecution, inputs, "/tmp").await()
            }
        }.await()

        assertEquals(3, results.size)
        assertTrue(results.all { it is StepResult.Success })
    }
}

// =========================================================================
// TDD RED tests for ForgeAgentRunner contract
// =========================================================================

class ForgeAgentRunnerTddTest {

    private val runner: ForgeAgentRunner = ForgeAgentRunnerStub()

    @Test
    fun `runAgent returns channel of events`() = runTest {
        val config = AgentConfig(
            type = AgentType.CODEX,
            endpoint = "http://localhost:8080",
            apiKey = "test-key",
            workingDirectory = "/workspace",
            environment = emptyMap()
        )

        val events = runner.run(config, "Create a test", mapOf(), "/workspace")
        assertNotNull(events)

        // Collect a few events
        val collected = mutableListOf<AgentEvent>()
        for (i in 0..2) {
            val event = events.receiveOrNull()
            if (event != null) collected.add(event)
        }
        assertTrue(collected.isNotEmpty())
    }

    @Test
    fun `isAvailable returns true when agent is configured`() = runTest {
        assertTrue(runner.isAvailable().await())
    }

    @Test
    fun `agentType returns correct type`() {
        assertEquals(AgentType.CODEX, runner.agentType)
    }
}

// =========================================================================
// Stub implementations that make tests compile but FAIL (RED phase)
// =========================================================================

private class ForgeStepRunnerStub : ForgeStepRunner {
    override suspend fun runLlmCall(step: WorkflowStep.LlmCall, inputs: Map<String, String>, modelConfig: Map<String, String>): StepResult = throw AssertionError("RED: runLlmCall not implemented")
    override suspend fun runCode(step: WorkflowStep.CodeExecution, inputs: Map<String, String>, workingDir: String): StepResult = throw AssertionError("RED: runCode not implemented")
    override suspend fun runAgent(step: WorkflowStep.AgentInvocation, inputs: Map<String, String>, config: AgentConfig, workingDir: String): StepResult = throw AssertionError("RED: runAgent not implemented")
    override suspend fun runTransform(step: WorkflowStep.FileTransform, workspace: ForgeWorkspace): StepResult = throw AssertionError("RED: runTransform not implemented")
    override suspend fun evalConditional(step: WorkflowStep.Conditional, inputs: Map<String, String>): Boolean = throw AssertionError("RED: evalConditional not implemented")
    override suspend fun runParallel(step: WorkflowStep.Parallel, inputs: Map<String, String>, runBranch: (List<WorkflowStep>, Map<String, String>) -> List<StepResult>): List<StepResult> = throw AssertionError("RED: runParallel not implemented")
}

private class ForgeAgentRunnerStub : ForgeAgentRunner {
    override fun run(config: AgentConfig, task: String, context: Map<String, String>, workingDir: String): kotlinx.coroutines.channels.ReceiveChannel<AgentEvent> = Channel(0)
    override suspend fun isAvailable(): Boolean = throw AssertionError("RED: isAvailable not implemented")
    override val agentType: AgentType = AgentType.CODEX
}

private class ForgeWorkspaceStub : ForgeWorkspace {
    override suspend fun put(file: ForgeFile): ForgeFile = throw AssertionError()
    override suspend fun get(id: ForgeFileId): ForgeFile? = throw AssertionError()
    override suspend fun delete(id: ForgeFileId): Boolean = throw AssertionError()
    override suspend fun list(): ForgeFileStore = throw AssertionError()
    override suspend fun search(query: String): ForgeFileStore = throw AssertionError()
    override fun stream(id: ForgeFileId): ReceiveChannel<String>? = throw AssertionError()
    override suspend fun snapshot(message: String, tags: Set<String>): ForgeSnapshot = throw AssertionError()
    override suspend fun getSnapshot(id: ForgeSnapshotId): ForgeSnapshot? = throw AssertionError()
    override suspend fun history(): ForgeHistory = throw AssertionError()
    override suspend fun restore(id: ForgeSnapshotId): ForgeSnapshot = throw AssertionError()
    override suspend fun diff(from: ForgeSnapshotId, to: ForgeSnapshotId): ForgeDiff = throw AssertionError()
    override suspend fun branch(base: ForgeSnapshotId, name: String): ForgeSnapshot = throw AssertionError()
    override suspend fun merge(source: ForgeSnapshotId, target: ForgeSnapshotId, message: String): ForgeSnapshot = throw AssertionError()
    override suspend fun putPrompt(prompt: ForgePrompt): ForgePrompt = throw AssertionError()
    override suspend fun getPrompt(id: ForgePromptId): ForgePrompt? = throw AssertionError()
    override suspend fun listPrompts(): ForgePromptLibrary = throw AssertionError()
    override suspend fun searchPrompts(query: String): ForgePromptLibrary = throw AssertionError()
    override suspend fun deletePrompt(id: ForgePromptId): Boolean = throw AssertionError()
    override suspend fun putWorkflow(workflow: ForgeWorkflow): ForgeWorkflow = throw AssertionError()
    override suspend fun getWorkflow(id: ForgeWorkflowId): ForgeWorkflow? = throw AssertionError()
    override suspend fun listWorkflows(): ForgeWorkflowRegistry = throw AssertionError()
    override suspend fun searchWorkflows(query: String): ForgeWorkflowRegistry = throw AssertionError()
    override suspend fun deleteWorkflow(id: ForgeWorkflowId): Boolean = throw AssertionError()
    override fun execute(workflowId: ForgeWorkflowId, inputs: Map<String, String>, configs: Map<AgentType, AgentConfig>, snapshotId: ForgeSnapshotId?): Flow<StepProgress> = flowOf()
    override suspend fun executeSync(workflowId: ForgeWorkflowId, inputs: Map<String, String>, configs: Map<AgentType, AgentConfig>, snapshotId: ForgeSnapshotId?): ForgeExecutionResult = throw AssertionError()
    override suspend fun cancel(executionId: ForgeExecutionId): Boolean = throw AssertionError()
    override suspend fun executions(workflowId: ForgeWorkflowId?): ForgeExecutionHistory = throw AssertionError()
    override fun events(): Flow<CollaborationEvent> = flowOf()
    override suspend fun emit(event: CollaborationEvent) = throw AssertionError()
    override suspend fun users(): ForgeActiveUsers = throw AssertionError()
    override suspend fun join(user: ForgeUser) = throw AssertionError()
    override suspend fun leave(userId: ForgeUserId) = throw AssertionError()
    override suspend fun artifact(name: String, description: String, files: List<ForgeFile>, workflowId: ForgeWorkflowId?, executionId: ForgeExecutionId?, isPublic: Boolean): ForgeArtifact = throw AssertionError()
    override suspend fun getArtifact(id: ForgeArtifactId): ForgeArtifact? = throw AssertionError()
    override suspend fun listArtifacts(publicOnly: Boolean): ForgeArtifactCollection = throw AssertionError()
    override suspend fun export(id: ForgeArtifactId, format: ExportFormat): ForgeExportBundle = throw AssertionError()
    override suspend fun importArtifact(bundle: ForgeExportBundle): ForgeArtifact = throw AssertionError()
}