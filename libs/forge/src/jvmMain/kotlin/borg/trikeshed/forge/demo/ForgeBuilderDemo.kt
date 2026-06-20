package borg.trikeshed.forge.demo

import borg.trikeshed.forge.*
import kotlinx.coroutines.runBlocking

/**
 * Forge Builder Demo - demonstrates construction patterns for Forge objects
 */
fun main() = runBlocking {
    println("🔨 Forge Builder Demo Starting...\n")
    
    // Builder 1: Kanban Board Construction
    println("📋 1. Kanban Board")
    val col1 = KanbanColumn(KanbanColumnId.generate(), "Backlog", 0)
    val col2 = KanbanColumn(KanbanColumnId.generate(), "In Progress", 1)
    val col3 = KanbanColumn(KanbanColumnId.generate(), "Review", 2)
    val col4 = KanbanColumn(KanbanColumnId.generate(), "Done", 3)
    
    val card1 = KanbanCard(KanbanCardId.generate(), "Design API", columnId = col1.id, priority = CardPriority.HIGH)
    val card2 = KanbanCard(KanbanCardId.generate(), "Setup database", columnId = col1.id, priority = CardPriority.MEDIUM)
    val card3 = KanbanCard(KanbanCardId.generate(), "Implement auth", columnId = col2.id, priority = CardPriority.HIGH)
    
    val board = KanbanBoard(
        id = KanbanBoardId.generate(),
        name = "Project Board",
        columns = listOf(col1, col2, col3, col4),
        cards = listOf(card1, card2, card3)
    )
    println("   ✓ Built board: ${board.name} with ${board.columns.size} columns, ${board.cards.size} cards")
    
    // Builder 2: Workflow Construction
    println("\n⚙️ 2. Workflow")
    val promptId = ForgePromptId.generate()
    val workflow = ForgeWorkflow(
        id = ForgeWorkflowId.generate(),
        name = "Data Processing Pipeline",
        steps = listOf(
            WorkflowStep.LlmCall("summarize", promptId, mapOf("text" to "input data"), "gpt-4"),
            WorkflowStep.LlmCall("translate", promptId, mapOf("text" to "{{summary}}"), "claude"),
            WorkflowStep.LlmCall("format", promptId, mapOf("text" to "{{translation}}"), "gpt-4")
        ),
        inputSchema = mapOf("rawText" to "string"),
        outputSchema = mapOf("finalText" to "string")
    )
    println("   ✓ Built workflow: ${workflow.name} with ${workflow.steps.size} steps")
    
    // Builder 3: File Construction
    println("\n📁 3. File")
    val file = ForgeFile(
        id = ForgeFileId.generate(),
        path = "docs/spec.md",
        content = "# Specification\n\nAuto-generated spec.",
        mimeType = "text/markdown"
    )
    println("   ✓ Built file: ${file.path} (${file.content.length} chars)")
    
    // Builder 4: Prompt Construction
    println("\n💬 4. Prompt")
    val prompt = ForgePrompt(
        id = ForgePromptId.generate(),
        name = "Summarizer",
        template = "Summarize: {{text}}",
        parameters = mapOf(
            "text" to PromptParameter("text", "string", "Text to summarize", true, "100"),
            "maxLength" to PromptParameter("maxLength", "int", "Max summary length", false, "100")
        )
    )
    println("   ✓ Built prompt: ${prompt.name} with ${prompt.parameters.size} parameters")
    
    // Show board as Mermaid
    println("\n📊 Board as Mermaid:")
    println(board.toMermaid().take(500))
    
    println("\n✅ Forge Builder Demo Complete!")
    println("   All construction patterns demonstrated successfully.")
}