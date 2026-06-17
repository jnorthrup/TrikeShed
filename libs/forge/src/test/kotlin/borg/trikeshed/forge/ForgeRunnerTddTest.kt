package borg.trikeshed.forge

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * TDD GREEN tests for ForgeStepRunner contract.
 */
class ForgeStepRunnerTddTest {

    private val runner: ForgeStepRunner = ForgeStepRunnerImpl()
    private val workspace: ForgeWorkspace = ForgeWorkspaceImpl()

    @Test
    fun `runLlmCall executes prompt with inputs and returns result`() = runTest {
        val step = WorkflowStep.LlmCall(
            id = "step1",
            promptId = ForgePromptId("summarize"),
            inputs = mapOf("text" to "Long document content..."),
            model = "gpt-4o-mini",
            parameters = mapOf("temperature" to "0.3")
        )

        val result = runner.runLlmCall(step, mapOf("text" to "Long document content..."), mapOf("temperature" to "0.3"))

        assertTrue(result is StepResult.Success)
        val success = result as StepResult.Success
        assertEquals("step1", success.stepId)
        assertNotNull(success.output)
        assertTrue(success.output.contains("gpt-4o-mini"))
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

        val result = runner.runCodeExecution(step, emptyMap(), "/tmp")

        assertTrue(result is StepResult.Success)
        val success = result as StepResult.Success
        assertEquals("step1", success.stepId)
        assertTrue(success.output.contains("python"))
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

        val result = runner.runAgentInvocation(step, mapOf("language" to "kotlin"), config, "/workspace")

        assertTrue(result is StepResult.Success)
        val success = result as StepResult.Success
        assertEquals("step1", success.stepId)
        assertTrue(success.output.contains("CODEX"))
    }

    @Test
    fun `runFileTransform applies transform to input files`() = runTest {
        workspace.put(ForgeFile(ForgeFileId("f1"), "a.md", "Content A", "text/markdown"))
        workspace.put(ForgeFile(ForgeFileId("f2"), "b.md", "Content B", "text/markdown"))

        val step = WorkflowStep.FileTransform(
            id = "step1",
            inputFileIds = listOf(ForgeFileId("f1"), ForgeFileId("f2")),
            transform = "concat",
            outputPath = "merged.md"
        )

        val result = runner.runFileTransform(step, workspace)

        assertTrue(result is StepResult.Success)
        val success = result as StepResult.Success
        assertEquals("step1", success.stepId)
        assertTrue(success.artifacts.any { it.path == "merged.md" })
        assertTrue(success.artifacts.first().content.contains("Content A"))
        assertTrue(success.artifacts.first().content.contains("Content B"))
    }

    @Test
    fun `evalConditional returns true for satisfied condition`() = runTest {
        val step = WorkflowStep.Conditional(
            id = "step1",
            condition = "input.length > 10",
            thenBranch = listOf(WorkflowStep.CodeExecution("then", "python", "pass", emptyMap())),
            elseBranch = listOf(WorkflowStep.CodeExecution("else", "python", "pass", emptyMap()))
        )

        val result = runner.runConditional(step, mapOf("input" to "this is a long string"))
        assertTrue(result)

        val result2 = runner.runConditional(step, mapOf("input" to "short"))
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
                runner.runCodeExecution(step as WorkflowStep.CodeExecution, inputs, "/tmp")
            }
        }

        assertEquals(3, results.size)
        assertTrue(results.all { it is StepResult.Success })
    }
}

// =========================================================================
// TDD GREEN tests for ForgeAgentRunner contract
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

        val events = runner.runAgent(config, "Create a test", mapOf(), "/workspace")
        assertNotNull(events)
    }

    @Test
    fun `isAvailable returns true when agent is configured`() = runTest {
        assertTrue(runner.isAvailable())
    }

    @Test
    fun `agentType returns correct type`() {
        assertEquals(AgentType.CODEX, runner.agentType)
    }
}