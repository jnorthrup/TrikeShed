package borg.trikeshed.forge

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.flowOf

/**
 * Shared test stubs for Forge TDD tests.
 */
internal class ForgeWorkspaceStub : ForgeWorkspace {
    override suspend fun put(file: ForgeFile): ForgeFile = throw AssertionError("RED: put not implemented")
    override suspend fun get(id: ForgeFileId): ForgeFile? = throw AssertionError("RED: get not implemented")
    override suspend fun delete(id: ForgeFileId): Boolean = throw AssertionError("RED: delete not implemented")
    override suspend fun list(): Map<ForgeFileId, ForgeFile> = throw AssertionError("RED: list not implemented")
    override suspend fun search(query: String): List<ForgeFile> = throw AssertionError("RED: search not implemented")
    override fun stream(id: ForgeFileId): ReceiveChannel<String>? = throw AssertionError("RED: stream not implemented")

    override suspend fun snapshot(message: String, tags: Set<String>): ForgeSnapshot = throw AssertionError("RED: snapshot not implemented")
    override suspend fun getSnapshot(id: ForgeSnapshotId): ForgeSnapshot? = throw AssertionError("RED: getSnapshot not implemented")
    override suspend fun history(): List<ForgeSnapshot> = throw AssertionError("RED: history not implemented")
    override suspend fun restore(id: ForgeSnapshotId): ForgeSnapshot = throw AssertionError("RED: restore not implemented")
    override suspend fun diff(from: ForgeSnapshotId, to: ForgeSnapshotId): ForgeDiff = throw AssertionError("RED: diff not implemented")
    override suspend fun branch(base: ForgeSnapshotId, name: String): ForgeSnapshot = throw AssertionError("RED: branch not implemented")
    override suspend fun merge(source: ForgeSnapshotId, target: ForgeSnapshotId, message: String): ForgeSnapshot = throw AssertionError("RED: merge not implemented")

    override suspend fun putPrompt(prompt: ForgePrompt): ForgePrompt = throw AssertionError("RED: putPrompt not implemented")
    override suspend fun getPrompt(id: ForgePromptId): ForgePrompt? = throw AssertionError("RED: getPrompt not implemented")
    override suspend fun listPrompts(): List<ForgePrompt> = throw AssertionError("RED: listPrompts not implemented")
    override suspend fun searchPrompts(query: String): List<ForgePrompt> = throw AssertionError("RED: searchPrompts not implemented")
    override suspend fun deletePrompt(id: ForgePromptId): Boolean = throw AssertionError("RED: deletePrompt not implemented")

    override suspend fun putWorkflow(workflow: ForgeWorkflow): ForgeWorkflow = throw AssertionError("RED: putWorkflow not implemented")
    override suspend fun getWorkflow(id: ForgeWorkflowId): ForgeWorkflow? = throw AssertionError("RED: getWorkflow not implemented")
    override suspend fun listWorkflows(): List<ForgeWorkflow> = throw AssertionError("RED: listWorkflows not implemented")
    override suspend fun searchWorkflows(query: String): List<ForgeWorkflow> = throw AssertionError("RED: searchWorkflows not implemented")
    override suspend fun deleteWorkflow(id: ForgeWorkflowId): Boolean = throw AssertionError("RED: deleteWorkflow not implemented")

    override fun execute(workflowId: ForgeWorkflowId, inputs: Map<String, String>, configs: Map<AgentType, AgentConfig>, snapshotId: ForgeSnapshotId?): kotlinx.coroutines.flow.Flow<StepProgress> = flowOf()
    override suspend fun executeSync(workflowId: ForgeWorkflowId, inputs: Map<String, String>, configs: Map<AgentType, AgentConfig>, snapshotId: ForgeSnapshotId?): ForgeExecutionResult = throw AssertionError("RED: executeSync not implemented")
    override suspend fun cancel(executionId: ForgeExecutionId): Boolean = throw AssertionError("RED: cancel not implemented")
    override suspend fun executions(workflowId: ForgeWorkflowId?): List<ForgeExecutionResult> = throw AssertionError("RED: executions not implemented")

    override fun events(): kotlinx.coroutines.flow.Flow<CollaborationEvent> = flowOf()
    override suspend fun emit(event: CollaborationEvent) = throw AssertionError("RED: emit not implemented")
    override suspend fun users(): List<ForgeUser> = throw AssertionError("RED: users not implemented")
    override suspend fun join(user: ForgeUser) = throw AssertionError("RED: join not implemented")
    override suspend fun leave(userId: ForgeUserId) = throw AssertionError("RED: leave not implemented")

    override suspend fun artifact(name: String, description: String, files: List<ForgeFile>, workflowId: ForgeWorkflowId?, executionId: ForgeExecutionId?, isPublic: Boolean): ForgeArtifact = throw AssertionError("RED: artifact not implemented")
    override suspend fun getArtifact(id: ForgeArtifactId): ForgeArtifact? = throw AssertionError("RED: getArtifact not implemented")
    override suspend fun listArtifacts(publicOnly: Boolean): List<ForgeArtifact> = throw AssertionError("RED: listArtifacts not implemented")
    override suspend fun export(id: ForgeArtifactId, format: ExportFormat): ForgeExportBundle = throw AssertionError("RED: export not implemented")
    override suspend fun importArtifact(bundle: ForgeExportBundle): ForgeArtifact = throw AssertionError("RED: importArtifact not implemented")

    // Cascade operations
    override suspend fun putCascade(cascade: OperationalCascade): OperationalCascade = throw AssertionError("RED: putCascade not implemented")
    override suspend fun getCascade(id: CascadeId): OperationalCascade? = throw AssertionError("RED: getCascade not implemented")
    override suspend fun listCascades(): List<OperationalCascade> = throw AssertionError("RED: listCascades not implemented")
    override suspend fun deleteCascade(id: CascadeId): Boolean = throw AssertionError("RED: deleteCascade not implemented")
    override suspend fun detectCascades(request: CascadeDetectionRequest): CascadeDetectionResult = throw AssertionError("RED: detectCascades not implemented")
    override fun executeCascade(cascadeId: CascadeId, snapshotId: ForgeSnapshotId?): kotlinx.coroutines.flow.Flow<CascadeProgress> = flowOf()
    override suspend fun executeCascadeSync(cascadeId: CascadeId, snapshotId: ForgeSnapshotId?): CascadeExecutionResult = throw AssertionError("RED: executeCascadeSync not implemented")
    override suspend fun getCascadeGraph(cascadeId: CascadeId): CascadeGraph? = throw AssertionError("RED: getCascadeGraph not implemented")
}

internal class ForgeStepRunnerStub : ForgeStepRunner {
    override suspend fun runLlmCall(step: WorkflowStep.LlmCall, inputs: Map<String, String>, modelConfig: Map<String, String>): StepResult = throw AssertionError("RED: runLlmCall not implemented")
    override suspend fun runCodeExecution(step: WorkflowStep.CodeExecution, inputs: Map<String, String>, workingDir: String): StepResult = throw AssertionError("RED: runCodeExecution not implemented")
    override suspend fun runAgentInvocation(step: WorkflowStep.AgentInvocation, inputs: Map<String, String>, config: AgentConfig, workingDir: String): StepResult = throw AssertionError("RED: runAgentInvocation not implemented")
    override suspend fun runFileTransform(step: WorkflowStep.FileTransform, workspace: ForgeWorkspace): StepResult = throw AssertionError("RED: runFileTransform not implemented")
    override suspend fun runConditional(step: WorkflowStep.Conditional, inputs: Map<String, String>): Boolean = throw AssertionError("RED: runConditional not implemented")
    override suspend fun runParallel(step: WorkflowStep.Parallel, inputs: Map<String, String>, runBranch: suspend (List<WorkflowStep>, Map<String, String>) -> List<StepResult>): List<StepResult> = throw AssertionError("RED: runParallel not implemented")
    override suspend fun runCascadeExecution(step: WorkflowStep.CascadeExecution, inputs: Map<String, String>, workspace: ForgeWorkspace): StepResult = throw AssertionError("RED: runCascadeExecution not implemented")
}

internal class ForgeAgentRunnerStub : ForgeAgentRunner {
    override fun runAgent(config: AgentConfig, task: String, context: Map<String, String>, workingDir: String): ReceiveChannel<AgentEvent> = Channel(10)
    override suspend fun isAvailable(): Boolean = true
    override val agentType: AgentType = AgentType.CODEX
}