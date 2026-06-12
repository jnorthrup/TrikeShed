package borg.trikeshed.forge

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.flowOf
import kotlinx.serialization.json.Json

/**
 * Basic implementation of ForgeStepRunner for testing.
 */
class ForgeStepRunnerImpl : ForgeStepRunner {

    override suspend fun runLlmCall(
        step: WorkflowStep.LlmCall,
        resolvedInputs: Map<String, String>,
        modelConfig: Map<String, String>
    ): StepResult {
        // Mock LLM response
        return StepResult.Success(
            stepId = step.id,
            output = "Mock LLM response for prompt ${step.promptId.value} with model ${step.model}",
            metadata = mapOf("model" to step.model)
        )
    }

    override suspend fun runCodeExecution(
        step: WorkflowStep.CodeExecution,
        resolvedInputs: Map<String, String>,
        workingDir: String
    ): StepResult {
        // Mock code execution - just return the code as output
        return StepResult.Success(
            stepId = step.id,
            output = "Executed in ${step.language}: ${step.code.take(100)}",
            metadata = mapOf("language" to step.language, "timeoutMs" to step.timeoutMs.toString())
        )
    }

    override suspend fun runAgentInvocation(
        step: WorkflowStep.AgentInvocation,
        resolvedInputs: Map<String, String>,
        agentConfig: AgentConfig,
        workingDir: String
    ): StepResult {
        // Mock agent response
        return StepResult.Success(
            stepId = step.id,
            output = "Agent ${step.agentType} completed task: ${step.task}",
            metadata = mapOf("agentType" to step.agentType.name, "workingDir" to workingDir)
        )
    }

    override suspend fun runFileTransform(
        step: WorkflowStep.FileTransform,
        workspace: ForgeWorkspace
    ): StepResult {
        // Mock file transform - create a merged file artifact
        val inputFiles = step.inputFileIds.mapNotNull { workspace.get(it) }
        val mergedContent = inputFiles.joinToString("\n\n---\n\n") { it.content }
        val artifact = ForgeFile(
            id = ForgeFileId.generate(),
            path = step.outputPath,
            content = "Transformed (${step.transform}):\n$mergedContent",
            mimeType = "text/markdown"
        )
        return StepResult.Success(
            stepId = step.id,
            output = "Transformed ${inputFiles.size} files to ${step.outputPath}",
            artifacts = listOf(artifact)
        )
    }

    override suspend fun runConditional(
        step: WorkflowStep.Conditional,
        resolvedInputs: Map<String, String>
    ): Boolean {
        // Simple condition evaluation - check if input.length > N
        return when {
            step.condition.contains(".length >") -> {
                val parts = step.condition.split(">")
                if (parts.size == 2) {
                    val threshold = parts[1].trim().toIntOrNull() ?: 0
                    resolvedInputs.values.firstOrNull()?.length ?: 0 > threshold
                } else false
            }
            step.condition.contains(".length <") -> {
                val parts = step.condition.split("<")
                if (parts.size == 2) {
                    val threshold = parts[1].trim().toIntOrNull() ?: 0
                    resolvedInputs.values.firstOrNull()?.length ?: 0 < threshold
                } else false
            }
            else -> true
        }
    }

    override suspend fun runParallel(
        step: WorkflowStep.Parallel,
        resolvedInputs: Map<String, String>,
        runBranch: suspend (List<WorkflowStep>, Map<String, String>) -> List<StepResult>
    ): List<StepResult> {
        val allResults = mutableListOf<StepResult>()
        for (branch in step.branches) {
            val branchResults = runBranch(branch, resolvedInputs)
            allResults.addAll(branchResults)
        }
        return allResults
    }

    override suspend fun runCascadeExecution(
        step: WorkflowStep.CascadeExecution,
        resolvedInputs: Map<String, String>,
        workspace: ForgeWorkspace
    ): StepResult {
        val result = workspace.executeCascadeSync(step.cascadeId)
        return if (result.status == CascadeExecutionStatus.SUCCESS) {
            StepResult.Success(
                stepId = step.id,
                output = kotlinx.serialization.json.Json.encodeToString(result.output.map { it.value }),
                artifacts = result.output.map { row ->
                    ForgeFile(
                        id = ForgeFileId.generate(),
                        path = "cascade-output/${row.key.joinToString("_")}.json",
                        content = row.value,
                        mimeType = "application/json"
                    )
                },
                metadata = mapOf("cascadeId" to step.cascadeId.value, "rowCount" to result.output.size.toString())
            )
        } else {
            StepResult.Failure(stepId = step.id, error = "Cascade execution failed: ${result.status}")
        }
    }
}