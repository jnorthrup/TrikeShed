package borg.trikeshed.forge.demo

import borg.trikeshed.forge.*
import kotlinx.coroutines.runBlocking

/**
 * Forge Demo - demonstrates core workspace capabilities
 */
fun main() = runBlocking {
    println("🔨 Forge Demo Starting...\n")
    
    val workspace = ForgeWorkspaceImpl()
    
    // 1. File Management
    println("📁 1. File Management")
    val file = ForgeFile(
        id = ForgeFileId.generate(),
        path = "notes/hello.md",
        content = "# Hello Forge\n\nThis is a demo file.",
        mimeType = "text/markdown"
    )
    workspace.put(file)
    println("   ✓ Created file: ${file.path}")
    
    val retrieved = workspace.get(file.id)
    println("   ✓ Retrieved file: ${retrieved?.content?.substring(0, 20)}...")
    
    val files = workspace.list()
    println("   ✓ Listed ${files.size} file(s)")
    
    val searchResults = workspace.search("Hello")
    println("   ✓ Search 'Hello': ${searchResults.size} result(s)")
    
    // 2. Snapshots / Version Control
    println("\n📸 2. Snapshots / Version Control")
    val snap1 = workspace.snapshot("Initial commit")
    println("   ✓ Snapshot: ${snap1.id.value} - ${snap1.message}")
    
    val file2 = ForgeFile(
        id = ForgeFileId.generate(),
        path = "notes/todo.md",
        content = "- [ ] Task 1\n- [ ] Task 2",
        mimeType = "text/markdown"
    )
    workspace.put(file2)
    
    val snap2 = workspace.snapshot("Added todo list")
    println("   ✓ Snapshot: ${snap2.id.value} - ${snap2.message}")
    
    val diff = workspace.diff(snap1.id, snap2.id)
    println("   ✓ Diff: ${diff.addedFiles.size} added, ${diff.modifiedFiles.size} modified, ${diff.removedFiles.size} removed")
    
    // 3. Prompts
    println("\n💬 3. Prompt Management")
    val prompt = ForgePrompt(
        id = ForgePromptId.generate(),
        name = "Summarize",
        template = "Summarize this text: {{text}}",
        parameters = mapOf("text" to PromptParameter("text", "string", "Text to summarize"))
    )
    workspace.putPrompt(prompt)
    println("   ✓ Created prompt: ${prompt.name}")
    
    val prompts = workspace.listPrompts()
    println("   ✓ Listed ${prompts.size} prompt(s)")
    
    // 4. Workflows
    println("\n⚙️ 4. Workflow Execution")
    val workflow = ForgeWorkflow(
        id = ForgeWorkflowId.generate(),
        name = "Demo Workflow",
        steps = listOf(
            WorkflowStep.LlmCall("summarize", prompt.id, mapOf("text" to "Forge is a multiplatform workspace"), "demo-model")
        ),
        inputSchema = mapOf("topic" to "string"),
        outputSchema = mapOf("summary" to "string")
    )
    workspace.putWorkflow(workflow)
    println("   ✓ Created workflow: ${workflow.name}")
    
    val result = workspace.executeSync(workflow.id, mapOf("topic" to "demo"))
    println("   ✓ Executed workflow: ${result.status} (${result.stepResults.size} steps)")
    println("   ✓ Execution ID: ${result.executionId.value}")
    
    // 5. Kanban Board
    println("\n📋 5. Kanban Board")
    val board = KanbanBoard(
        id = KanbanBoardId.generate(),
        name = "Demo Board",
        columns = listOf(
            KanbanColumn(KanbanColumnId.generate(), "Todo", 0),
            KanbanColumn(KanbanColumnId.generate(), "In Progress", 1),
            KanbanColumn(KanbanColumnId.generate(), "Done", 2)
        ),
        cards = listOf(
            KanbanCard(
                id = KanbanCardId.generate(),
                title = "Implement feature",
                columnId = KanbanColumnId.generate(),
                priority = CardPriority.HIGH
            ),
            KanbanCard(
                id = KanbanCardId.generate(),
                title = "Write tests",
                columnId = KanbanColumnId.generate(),
                priority = CardPriority.MEDIUM
            )
        )
    )
    println("   ✓ Created board: ${board.name}")
    println("   ✓ Columns: ${board.columns.joinToString { it.name }}")
    println("   ✓ Cards: ${board.cards.joinToString { it.title }}")
    
    val mermaid = board.toMermaid()
    println("   ✓ Mermaid diagram generated (${mermaid.lines().size} lines)")
    
    // 6. Cascade Detection
    println("\n📊 6. Cascade Detection (Map/Reduce)")
    val cascadeRequest = CascadeDetectionRequest(
        sources = listOf(CascadeSource.InlineData("""{"machine": "A", "metric": 10.5}""", DataFormat.JSON)),
        candidateKeys = listOf("machine"),
        candidateMetrics = listOf("metric"),
        maxHierarchyDepth = 2
    )
    val detection = workspace.detectCascades(cascadeRequest)
    println("   ✓ Detected ${detection.detectedCascades.size} cascade(s)")
    println("   ✓ Key hierarchies: ${detection.inferredKeyHierarchies}")
    println("   ✓ Metrics: ${detection.inferredMetrics}")
    println("   ✓ Confidence: ${detection.confidence}")
    
    // 7. Patch Bay
    println("\n🔌 7. Patch Bay / Cable Routing")
    val patchBay = PatchBay(
        id = PatchBayId.generate(),
        name = "Demo Patch Bay",
        modules = emptyMap(),
        cables = emptyList()
    )
    workspace.putPatchBay(patchBay)
    println("   ✓ Created patch bay: ${patchBay.name}")
    
    // 8. Artifacts
    println("\n📦 8. Artifacts / Export")
    val artifact = workspace.artifact(
        name = "Demo Result",
        description = "Exported demo output",
        files = listOf(file),
        workflowId = workflow.id,
        result.executionId,
        false
    )
    println("   ✓ Created artifact: ${artifact.name}")
    
    val exported = workspace.export(artifact.id, ExportFormat.JSON)
    println("   ✓ Exported artifact (${exported.format}): ${exported.data.size} bytes")
    
    println("\n✅ Forge Demo Complete!")
    println("   All core capabilities demonstrated successfully.")
}