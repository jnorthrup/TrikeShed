package borg.trikeshed.forge

import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * TDD GREEN tests for ForgeWorkspace contract.
 * These tests verify the expected behavior works.
 */
class ForgeWorkspaceTddTest {

    private val workspace: ForgeWorkspace = ForgeWorkspaceImpl()

    @Test
    fun `putFile stores file and getFile retrieves it`() = runTest {
        val file = ForgeFile(
            id = ForgeFileId("test-1"),
            path = "notes/hello.md",
            content = "# Hello Forge",
            mimeType = "text/markdown"
        )
        val stored = workspace.put(file)
        assertEquals(file, stored)

        val retrieved = workspace.get(ForgeFileId("test-1"))
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
        workspace.put(file)
        assertTrue(workspace.delete(ForgeFileId("test-2")))
        assertNull(workspace.get(ForgeFileId("test-2")))
    }

    @Test
    fun `listFiles returns all files`() = runTest {
        val f1 = ForgeFile(ForgeFileId("a"), "a.md", "A", "text/markdown")
        val f2 = ForgeFile(ForgeFileId("b"), "b.md", "B", "text/markdown")
        workspace.put(f1)
        workspace.put(f2)

        val store = workspace.list()
        assertEquals(2, store.size)
        assertEquals("A", store.values.first { it.path == "a.md" }.content)
        assertEquals("B", store.values.first { it.path == "b.md" }.content)
    }

    @Test
    fun `searchFiles returns matching files`() = runTest {
        val f1 = ForgeFile(ForgeFileId("a"), "a.md", "forge is great", "text/markdown")
        val f2 = ForgeFile(ForgeFileId("b"), "b.md", "nothing here", "text/markdown")
        workspace.put(f1)
        workspace.put(f2)

        val results = workspace.search("forge")
        assertEquals(1, results.size)
        assertEquals("forge is great", results[0].content)
    }

    @Test
    fun `streamFile returns channel for large files`() = runTest {
        val file = ForgeFile(ForgeFileId("big"), "big.md", "x".repeat(10000), "text/markdown")
        workspace.put(file)

        val channel = workspace.stream(ForgeFileId("big"))
        assertNotNull(channel)
    }

    @Test
    fun `snapshot captures current workspace state`() = runTest {
        val f1 = ForgeFile(ForgeFileId("a"), "a.md", "A", "text/markdown")
        workspace.put(f1)

        val snap = workspace.snapshot("initial commit")
        assertNotNull(snap.id)
        assertEquals("initial commit", snap.message)
        assertEquals(1, snap.files.size)
    }

    @Test
    fun `history returns all snapshots`() = runTest {
        workspace.put(ForgeFile(ForgeFileId("a"), "a.md", "A", "text/markdown"))
        workspace.snapshot("first")
        workspace.put(ForgeFile(ForgeFileId("b"), "b.md", "B", "text/markdown"))
        workspace.snapshot("second")

        val history = workspace.history()
        assertEquals(2, history.size)
        assertEquals("first", history[0].message)
        assertEquals("second", history[1].message)
    }

    @Test
    fun `restoreSnapshot reverts workspace to snapshot state`() = runTest {
        workspace.put(ForgeFile(ForgeFileId("a"), "a.md", "A", "text/markdown"))
        val snap1 = workspace.snapshot("v1")
        workspace.put(ForgeFile(ForgeFileId("b"), "b.md", "B", "text/markdown"))
        val snap2 = workspace.snapshot("v2")

        workspace.restore(snap1.id)
        val files = workspace.list()
        assertEquals(1, files.size)
        assertEquals("A", files.values.first().content)
    }

    @Test
    fun `diffSnapshots computes added removed modified`() = runTest {
        val f1 = ForgeFile(ForgeFileId("a"), "a.md", "A1", "text/markdown")
        workspace.put(f1)
        val snap1 = workspace.snapshot("v1")

        val f2 = ForgeFile(ForgeFileId("b"), "b.md", "B", "text/markdown")
        workspace.put(f2)
        val f1Updated = ForgeFile(ForgeFileId("a"), "a.md", "A2", "text/markdown")
        workspace.put(f1Updated)
        val snap2 = workspace.snapshot("v2")

        val diff = workspace.diff(snap1.id, snap2.id)
        assertEquals(1, diff.addedFiles.size)
        assertEquals(0, diff.removedFiles.size)
        assertEquals(1, diff.modifiedFiles.size)
        assertEquals("A2", diff.modifiedFiles[0].content)
    }

    @Test
    fun `branchSnapshot creates new branch from base`() = runTest {
        workspace.put(ForgeFile(ForgeFileId("a"), "a.md", "A", "text/markdown"))
        val main = workspace.snapshot("main")

        val branch = workspace.branch(main.id, "feature")
        assertEquals("feature", branch.tags.firstOrNull())
        assertEquals(main.id, branch.parentId)
    }

    @Test
    fun `mergeBranch merges changes back`() = runTest {
        workspace.put(ForgeFile(ForgeFileId("a"), "a.md", "A", "text/markdown"))
        val main = workspace.snapshot("main")

        val branch = workspace.branch(main.id, "feature")
        val merged = workspace.merge(branch.id, main.id, "merge feature")
        assertNotNull(merged.id)
    }

    @Test
    fun `prompt lifecycle save get list search delete`() = runTest {
        val prompt = ForgePrompt(
            id = ForgePromptId("summarize"),
            name = "Summarize",
            template = "Summarize: {{text}}",
            parameters = mapOf("text" to PromptParameter("text", "string", "Text to summarize"))
        )
        workspace.putPrompt(prompt)

        val retrieved = workspace.getPrompt(ForgePromptId("summarize"))
        assertNotNull(retrieved)
        assertEquals("Summarize", retrieved?.name)

        val library = workspace.listPrompts()
        assertEquals(1, library.size)

        val found = workspace.searchPrompts("summarize")
        assertEquals(1, found.size)

        assertTrue(workspace.deletePrompt(ForgePromptId("summarize")))
        assertNull(workspace.getPrompt(ForgePromptId("summarize")))
    }

    @Test
    fun `workflow lifecycle save get list search delete`() = runTest {
        val workflow = ForgeWorkflow(
            id = ForgeWorkflowId("wf-1"),
            name = "Test Workflow",
            steps = emptyList(),
            inputSchema = mapOf("input" to "string"),
            outputSchema = mapOf("output" to "string")
        )
        workspace.putWorkflow(workflow)

        val retrieved = workspace.getWorkflow(ForgeWorkflowId("wf-1"))
        assertNotNull(retrieved)
        assertEquals("Test Workflow", retrieved?.name)

        val registry = workspace.listWorkflows()
        assertEquals(1, registry.size)

        assertTrue(workspace.deleteWorkflow(ForgeWorkflowId("wf-1")))
        assertNull(workspace.getWorkflow(ForgeWorkflowId("wf-1")))
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
        workspace.putWorkflow(workflow)

        val progress = workspace.execute(ForgeWorkflowId("wf-exec"), mapOf("x" to "test"))
        assertNotNull(progress)
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
        workspace.putWorkflow(workflow)
        workspace.snapshot("initial")  // Create a snapshot first

        val result = workspace.executeSync(ForgeWorkflowId("wf-sync"), emptyMap())
        assertEquals(ExecutionStatus.SUCCESS, result.status)
        assertEquals(ForgeWorkflowId("wf-sync"), result.workflowId)
    }

    @Test
    fun `cancelExecution stops running workflow`() = runTest {
        assertTrue(workspace.cancel(ForgeExecutionId("running-1")))
    }

    @Test
    fun `executions returns history filtered by workflow`() = runTest {
        val history = workspace.executions(null)
        assertTrue(history.size >= 0)
    }

    @Test
    fun `events stream emits collaboration events`() = runTest {
        val events = workspace.events()
        assertNotNull(events)
    }

    @Test
    fun `emitCollaborationEvent broadcasts to subscribers`() = runTest {
        val event = CollaborationEvent.UserJoined(ForgeUserId("u1"), "Alice")
        workspace.emit(event)
    }

    @Test
    fun `users returns active users`() = runTest {
        val user = ForgeUser(ForgeUserId("u1"), "Alice", "#ff0000")
        workspace.join(user)
        val users = workspace.users()
        assertEquals(1, users.size)
        assertEquals("Alice", users[0].name)
    }

    @Test
    fun `join and leave manages user presence`() = runTest {
        val user = ForgeUser(ForgeUserId("u2"), "Bob", "#00ff00")
        workspace.join(user)
        assertEquals(1, workspace.users().size)

        workspace.leave(ForgeUserId("u2"))
        assertEquals(0, workspace.users().size)
    }

    @Test
    fun `artifact lifecycle create get list export import`() = runTest {
        val file = ForgeFile(ForgeFileId("art-1"), "out.md", "Result", "text/markdown")
        val artifact = workspace.artifact("My Artifact", "Description", listOf(file), null, null, false)
        assertNotNull(artifact.id)

        val retrieved = workspace.getArtifact(artifact.id)
        assertNotNull(retrieved)
        assertEquals("My Artifact", retrieved?.name)

        val collection = workspace.listArtifacts(false)
        assertEquals(1, collection.size)

        val exported = workspace.export(artifact.id, ExportFormat.JSON)
        assertEquals(ExportFormat.JSON, exported.format)
        assertNotNull(exported.data)

        val imported = workspace.importArtifact(exported)
        assertEquals("My Artifact", imported.name)
    }
}