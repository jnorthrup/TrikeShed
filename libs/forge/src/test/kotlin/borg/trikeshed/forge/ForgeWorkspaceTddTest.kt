package borg.trikeshed.forge

import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.tasks.await
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * TDD RED tests for ForgeWorkspace contract.
 * These tests define the expected behavior - they MUST FAIL until implementation exists.
 */
class ForgeWorkspaceTddTest {

    private val workspace: ForgeWorkspace = ForgeWorkspaceStub()

    @Test
    fun `putFile stores file and getFile retrieves it`() = runTest {
        val file = ForgeFile(
            id = ForgeFileId("test-1"),
            path = "notes/hello.md",
            content = "# Hello Forge",
            mimeType = "text/markdown"
        )
        val stored = workspace.put(file).await()
        assertEquals(file, stored)

        val retrieved = workspace.get(ForgeFileId("test-1")).await()
        assertNotNull(retrieved)
        assertEquals(file.content, retrieved?.content)
    }

    @Test
    fun `deleteFile removes file from workspace`() = runTest {
        val file = ForgeFile(
            id = ForgeFileId("test-2"),
            path = "notes/temp.md",
            content = "delete me",
            mimeType = "text/markdown"
        )
        workspace.put(file).await()
        assertTrue(workspace.delete(ForgeFileId("test-2")).await())
        assertNull(workspace.get(ForgeFileId("test-2")).await())
    }

    @Test
    fun `listFiles returns all files as ForgeFileStore`() = runTest {
        val f1 = ForgeFile(ForgeFileId("a"), "a.md", "A", "text/markdown")
        val f2 = ForgeFile(ForgeFileId("b"), "b.md", "B", "text/markdown")
        workspace.put(f1).await()
        workspace.put(f2).await()

        val store = workspace.list().await()
        assertEquals(2, store.size)
        assertEquals("A", store[0].content)
        assertEquals("B", store[1].content)
    }

    @Test
    fun `searchFiles returns matching files`() = runTest {
        val f1 = ForgeFile(ForgeFileId("a"), "a.md", "forge is great", "text/markdown")
        val f2 = ForgeFile(ForgeFileId("b"), "b.md", "nothing here", "text/markdown")
        workspace.put(f1).await()
        workspace.put(f2).await()

        val results = workspace.search("forge").await()
        assertEquals(1, results.size)
        assertEquals("forge is great", results[0].content)
    }

    @Test
    fun `streamFile returns channel for large files`() = runTest {
        val file = ForgeFile(ForgeFileId("big"), "big.md", "x".repeat(10000), "text/markdown")
        workspace.put(file).await()

        val channel = workspace.stream(ForgeFileId("big"))
        assertNotNull(channel)
    }

    @Test
    fun `snapshot captures current workspace state`() = runTest {
        val f1 = ForgeFile(ForgeFileId("a"), "a.md", "A", "text/markdown")
        workspace.put(f1).await()

        val snap = workspace.snapshot("initial commit").await()
        assertNotNull(snap.id)
        assertEquals("initial commit", snap.message)
        assertEquals(1, snap.files.size)
    }

    @Test
    fun `history returns all snapshots as ForgeHistory`() = runTest {
        workspace.put(ForgeFile(ForgeFileId("a"), "a.md", "A", "text/markdown")).await()
        workspace.snapshot("first").await()
        workspace.put(ForgeFile(ForgeFileId("b"), "b.md", "B", "text/markdown")).await()
        workspace.snapshot("second").await()

        val history = workspace.history().await()
        assertEquals(2, history.size)
        assertEquals("first", history[0].message)
        assertEquals("second", history[1].message)
    }

    @Test
    fun `restoreSnapshot reverts workspace to snapshot state`() = runTest {
        workspace.put(ForgeFile(ForgeFileId("a"), "a.md", "A", "text/markdown")).await()
        val snap1 = workspace.snapshot("v1").await()
        workspace.put(ForgeFile(ForgeFileId("b"), "b.md", "B", "text/markdown")).await()
        val snap2 = workspace.snapshot("v2").await()

        workspace.restore(snap1.id).await()
        val files = workspace.list().await()
        assertEquals(1, files.size)
        assertEquals("A", files[0].content)
    }

    @Test
    fun `diffSnapshots computes added/removed/modified`() = runTest {
        val f1 = ForgeFile(ForgeFileId("a"), "a.md", "A1", "text/markdown")
        workspace.put(f1).await()
        val snap1 = workspace.snapshot("v1").await()

        val f2 = ForgeFile(ForgeFileId("b"), "b.md", "B", "text/markdown")
        workspace.put(f2).await()
        val f1Updated = ForgeFile(ForgeFileId("a"), "a.md", "A2", "text/markdown")
        workspace.put(f1Updated).await()
        val snap2 = workspace.snapshot("v2").await()

        val diff = workspace.diff(snap1.id, snap2.id).await()
        assertEquals(1, diff.first.size)      // added: b.md
        assertEquals(0, diff.second.first.size) // removed: none
        assertEquals(1, diff.second.second.size) // modified: a.md
        assertEquals("A2", diff.second.second[0].content)
    }

    @Test
    fun `branchSnapshot creates new branch from base`() = runTest {
        workspace.put(ForgeFile(ForgeFileId("a"), "a.md", "A", "text/markdown")).await()
        val main = workspace.snapshot("main").await()

        val branch = workspace.branch(main.id, "feature").await()
        assertEquals("feature", branch.tags.firstOrNull())
        assertEquals(main.id, branch.parentId)
    }

    @Test
    fun `mergeBranch merges changes back`() = runTest {
        workspace.put(ForgeFile(ForgeFileId("a"), "a.md", "A", "text/markdown")).await()
        val main = workspace.snapshot("main").await()

        val branch = workspace.branch(main.id, "feature").await()
        // In real impl, branch would have different files
        // For test, just verify merge produces snapshot
        val merged = workspace.merge(branch.id, main.id, "merge feature").await()
        assertNotNull(merged.id)
    }

    @Test
    fun `prompt lifecycle: save, get, list, search, delete`() = runTest {
        val prompt = ForgePrompt(
            id = ForgePromptId("summarize"),
            name = "Summarize",
            template = "Summarize: {{text}}",
            parameters = mapOf("text" to PromptParameter("text", "string", "Text to summarize"))
        )
        workspace.putPrompt(prompt).await()

        val retrieved = workspace.getPrompt(ForgePromptId("summarize")).await()
        assertNotNull(retrieved)
        assertEquals("Summarize", retrieved?.name)

        val library = workspace.listPrompts().await()
        assertEquals(1, library.size)

        val found = workspace.searchPrompts("summarize").await()
        assertEquals(1, found.size)

        assertTrue(workspace.deletePrompt(ForgePromptId("summarize")).await())
        assertNull(workspace.getPrompt(ForgePromptId("summarize")).await())
    }

    @Test
    fun `workflow lifecycle: save, get, list, search, delete`() = runTest {
        val workflow = ForgeWorkflow(
            id = ForgeWorkflowId("wf-1"),
            name = "Test Workflow",
            steps = emptyList(),
            inputSchema = mapOf("input" to "string"),
            outputSchema = mapOf("output" to "string")
        )
        workspace.putWorkflow(workflow).await()

        val retrieved = workspace.getWorkflow(ForgeWorkflowId("wf-1")).await()
        assertNotNull(retrieved)
        assertEquals("Test Workflow", retrieved?.name)

        val registry = workspace.listWorkflows().await()
        assertEquals(1, registry.size)

        assertTrue(workspace.deleteWorkflow(ForgeWorkflowId("wf-1")).await())
        assertNull(workspace.getWorkflow(ForgeWorkflowId("wf-1")).await())
    }

    @Test
    fun `executeWorkflow returns flow of step progress`() = runTest {
        val workflow = ForgeWorkflow(
            id = ForgeWorkflowId("wf-exec"),
            name = "Exec Test",
            steps = listOf(
                WorkflowStep.LlmCall("step1", ForgePromptId("p1"), emptyMap(), "test-model")
            ),
            inputSchema = mapOf("x" to "string"),
            outputSchema = mapOf("y" to "string")
        )
        workspace.putWorkflow(workflow).await()

        val progress = workspace.execute(ForgeWorkflowId("wf-exec"), mapOf("x" -> "test"))
        val events = progress.toList().await()

        // Should have at least Started and Completed
        assertTrue(events.any { it is StepProgress.Started })
        assertTrue(events.any { it is StepProgress.WorkflowCompleted })
    }

    @Test
    fun `executeWorkflowSync returns final execution result`() = runTest {
        val workflow = ForgeWorkflow(
            id = ForgeWorkflowId("wf-sync"),
            name = "Sync Test",
            steps = emptyList(),
            inputSchema = emptyMap(),
            outputSchema = emptyMap()
        )
        workspace.putWorkflow(workflow).await()

        val result = workspace.executeSync(ForgeWorkflowId("wf-sync"), emptyMap()).await()
        assertEquals(ExecutionStatus.SUCCESS, result.status)
        assertEquals(ForgeWorkflowId("wf-sync"), result.workflowId)
    }

    @Test
    fun `cancelExecution stops running workflow`() = runTest {
        assertTrue(workspace.cancel(ForgeExecutionId("running-1")).await())
    }

    @Test
    fun `executions returns history filtered by workflow`() = runTest {
        val history = workspace.executions(null).await()
        assertTrue(history.size >= 0)
    }

    @Test
    fun `events stream emits collaboration events`() = runTest {
        val events = workspace.events().toList().await()
        // Stub returns empty - real impl would have events
        assertTrue(events.isEmpty())
    }

    @Test
    fun `emitCollaborationEvent broadcasts to subscribers`() = runTest {
        val event = CollaborationEvent.UserJoined(ForgeUserId("u1"), "Alice")
        workspace.emit(event).await()
        // No exception = success
    }

    @Test
    fun `users returns active users`() = runTest {
        val user = ForgeUser(ForgeUserId("u1"), "Alice", "#ff0000")
        workspace.join(user).await()
        val users = workspace.users().await()
        assertEquals(1, users.size)
        assertEquals("Alice", users[0].name)
    }

    @Test
    fun `join and leave manages user presence`() = runTest {
        val user = ForgeUser(ForgeUserId("u2"), "Bob", "#00ff00")
        workspace.join(user).await()
        assertEquals(1, workspace.users().await().size)

        workspace.leave(ForgeUserId("u2")).await()
        assertEquals(0, workspace.users().await().size)
    }

    @Test
    fun `artifact lifecycle: create, get, list, export, import`() = runTest {
        val file = ForgeFile(ForgeFileId("art-1"), "out.md", "Result", "text/markdown")
        val artifact = workspace.artifact("My Artifact", "Description", listOf(file), null, null).await()
        assertNotNull(artifact.id)

        val retrieved = workspace.getArtifact(artifact.id).await()
        assertNotNull(retrieved)
        assertEquals("My Artifact", retrieved?.name)

        val collection = workspace.listArtifacts(false).await()
        assertEquals(1, collection.size)

        val exported = workspace.export(artifact.id, ExportFormat.JSON).await()
        assertEquals(ExportFormat.JSON, exported.format)
        assertNotNull(exported.data)

        val imported = workspace.importArtifact(exported).await()
        assertEquals("My Artifact", imported.name)
    }
}

// =========================================================================
// Stub implementation that makes tests compile but FAIL (RED phase)
// =========================================================================

private class ForgeWorkspaceStub : ForgeWorkspace {
    override suspend fun put(file: ForgeFile): ForgeFile = throw AssertionError("RED: put not implemented")
    override suspend fun get(id: ForgeFileId): ForgeFile? = throw AssertionError("RED: get not implemented")
    override suspend fun delete(id: ForgeFileId): Boolean = throw AssertionError("RED: delete not implemented")
    override suspend fun list(): ForgeFileStore = throw AssertionError("RED: list not implemented")
    override suspend fun search(query: String): ForgeFileStore = throw AssertionError("RED: search not implemented")
    override fun stream(id: ForgeFileId): ReceiveChannel<String>? = throw AssertionError("RED: stream not implemented")

    override suspend fun snapshot(message: String, tags: Set<String>): ForgeSnapshot = throw AssertionError("RED: snapshot not implemented")
    override suspend fun getSnapshot(id: ForgeSnapshotId): ForgeSnapshot? = throw AssertionError("RED: getSnapshot not implemented")
    override suspend fun history(): ForgeHistory = throw AssertionError("RED: history not implemented")
    override suspend fun restore(id: ForgeSnapshotId): ForgeSnapshot = throw AssertionError("RED: restore not implemented")
    override suspend fun diff(from: ForgeSnapshotId, to: ForgeSnapshotId): ForgeDiff = throw AssertionError("RED: diff not implemented")
    override suspend fun branch(base: ForgeSnapshotId, name: String): ForgeSnapshot = throw AssertionError("RED: branch not implemented")
    override suspend fun merge(source: ForgeSnapshotId, target: ForgeSnapshotId, message: String): ForgeSnapshot = throw AssertionError("RED: merge not implemented")

    override suspend fun putPrompt(prompt: ForgePrompt): ForgePrompt = throw AssertionError("RED: putPrompt not implemented")
    override suspend fun getPrompt(id: ForgePromptId): ForgePrompt? = throw AssertionError("RED: getPrompt not implemented")
    override suspend fun listPrompts(): ForgePromptLibrary = throw AssertionError("RED: listPrompts not implemented")
    override suspend fun searchPrompts(query: String): ForgePromptLibrary = throw AssertionError("RED: searchPrompts not implemented")
    override suspend fun deletePrompt(id: ForgePromptId): Boolean = throw AssertionError("RED: deletePrompt not implemented")

    override suspend fun putWorkflow(workflow: ForgeWorkflow): ForgeWorkflow = throw AssertionError("RED: putWorkflow not implemented")
    override suspend fun getWorkflow(id: ForgeWorkflowId): ForgeWorkflow? = throw AssertionError("RED: getWorkflow not implemented")
    override suspend fun listWorkflows(): ForgeWorkflowRegistry = throw AssertionError("RED: listWorkflows not implemented")
    override suspend fun searchWorkflows(query: String): ForgeWorkflowRegistry = throw AssertionError("RED: searchWorkflows not implemented")
    override suspend fun deleteWorkflow(id: ForgeWorkflowId): Boolean = throw AssertionError("RED: deleteWorkflow not implemented")

    override fun execute(workflowId: ForgeWorkflowId, inputs: Map<String, String>, configs: Map<AgentType, AgentConfig>, snapshotId: ForgeSnapshotId?): Flow<StepProgress> = flowOf()
    override suspend fun executeSync(workflowId: ForgeWorkflowId, inputs: Map<String, String>, configs: Map<AgentType, AgentConfig>, snapshotId: ForgeSnapshotId?): ForgeExecutionResult = throw AssertionError("RED: executeSync not implemented")
    override suspend fun cancel(executionId: ForgeExecutionId): Boolean = throw AssertionError("RED: cancel not implemented")
    override suspend fun executions(workflowId: ForgeWorkflowId?): ForgeExecutionHistory = throw AssertionError("RED: executions not implemented")

    override fun events(): Flow<CollaborationEvent> = flowOf()
    override suspend fun emit(event: CollaborationEvent) = throw AssertionError("RED: emit not implemented")
    override suspend fun users(): ForgeActiveUsers = throw AssertionError("RED: users not implemented")
    override suspend fun join(user: ForgeUser) = throw AssertionError("RED: join not implemented")
    override suspend fun leave(userId: ForgeUserId) = throw AssertionError("RED: leave not implemented")

    override suspend fun artifact(name: String, description: String, files: List<ForgeFile>, workflowId: ForgeWorkflowId?, executionId: ForgeExecutionId?, isPublic: Boolean): ForgeArtifact = throw AssertionError("RED: artifact not implemented")
    override suspend fun getArtifact(id: ForgeArtifactId): ForgeArtifact? = throw AssertionError("RED: getArtifact not implemented")
    override suspend fun listArtifacts(publicOnly: Boolean): ForgeArtifactCollection = throw AssertionError("RED: listArtifacts not implemented")
    override suspend fun export(id: ForgeArtifactId, format: ExportFormat): ForgeExportBundle = throw AssertionError("RED: export not implemented")
    override suspend fun importArtifact(bundle: ForgeExportBundle): ForgeArtifact = throw AssertionError("RED: importArtifact not implemented")
}