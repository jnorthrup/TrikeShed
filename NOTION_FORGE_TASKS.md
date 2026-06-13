# NotionForge — Notion as Forge Task Targets

## Key Clarification

**Notion is NOT a separate system. Notion is a FORGE task target.**

```
┌─────────────────────────────────────────────────────────────┐
│                    FORGE TASKS                              │
│                                                              │
│  ┌─────────────────────────────────────────────────────┐  │
│  │  NotionForge Task                                    │  │
│  │  ───────────────────                                │  │
│  │  Input:  (from upstream tasks / direct)             │  │
│  │  Action: Write to Notion API                        │  │
│  │  Output: Notion page/block ID                        │  │
│  └─────────────────────────────────────────────────────┘  │
│                                                              │
│  Forge Task Types:                                          │
│  • LLMCall      → Generate content                         │
│  • CodeExec    → Process data                             │
│  • AgentInvoke → Delegate work                            │
│  • NotionForge → Write to Notion  ◄── Notion as target  │
│  • Cascade      → Map/reduce pipeline                      │
└─────────────────────────────────────────────────────────────┘
```

## NotionForge = Forge Task

```kotlin
/**
 * NotionForge is a Forge workflow step that writes to Notion.
 * It's NOT a separate Notion system — it's a Forge task type.
 */
sealed class WorkflowStep {
    // ... existing types ...
    
    /** Write to Notion — just another Forge step type */
    data class NotionForge(
        val id: String,
        val action: NotionAction,
        val inputs: Map<String, String>,
    ) : WorkflowStep
}

/**
 * Notion actions — what the Forge task does to Notion.
 */
sealed class NotionAction {
    /** Create a new page */
    data class CreatePage(
        val parentId: String?,  // null = root
        val title: String,
        val content: String,     // Markdown
    ) : NotionAction()
    
    /** Create a database (table) */
    data class CreateDatabase(
        val parentPageId: String,
        val name: String,
        val schema: DatabaseSchema,
    ) : NotionAction()
    
    /** Append blocks to a page */
    data class AppendBlocks(
        val pageId: String,
        val blocks: List<Block>,
    ) : NotionAction()
    
    /** Update page properties */
    data class UpdatePage(
        val pageId: String,
        val properties: Map<String, String>,
    ) : NotionAction()
    
    /** Query a database */
    data class QueryDatabase(
        val databaseId: String,
        val filter: String?,
    ) : NotionAction()
}
```

## Example: Swarm → NotionForge

```kotlin
// A swarm that writes results to Notion
val workflow = Workflow(
    id = "wf_swarm_output",
    name = "Swarm → Notion",
    steps = listOf(
        // Step 1: Run the swarm (existing Forge cascade)
        WorkflowStep.CascadeExecution(
            id = "run_swarm",
            cascadeId = CascadeId("cascade_analysis"),
        ),
        
        // Step 2: Write results to Notion (NotionForge task)
        WorkflowStep.NotionForge(
            id = "write_report",
            action = NotionAction.CreatePage(
                parentId = null,
                title = "Analysis Report - ${Date.now()}",
                content = """
                    # Swarm Analysis Results
                    
                    ## Summary
                    {{run_swarm.summary}}
                    
                    ## Findings
                    {{run_swarm.findings}}
                    
                    ## Recommendations
                    {{run_swarm.recommendations}}
                """.trimIndent()
            ),
            inputs = mapOf(
                "run_swarm.summary" to "{{steps.run_swarm.output.summary}}",
                "run_swarm.findings" to "{{steps.run_swarm.output.findings}}",
            )
        ),
    )
)
```

## NotionForge in the Graph

```
┌─────────────────────────────────────────────────────────────┐
│                    SWARM GRAPH                              │
├─────────────────────────────────────────────────────────────┤
│                                                              │
│   root (done)                                              │
│     ├─ worker_1                                            │
│     ├─ worker_2                                            │
│     └─ verifier ──▶ synthesizer ──▶ NotionForge          │
│                           (Forge task)  ▼                   │
│                                ┌──────────────┐            │
│                                │  Notion API │            │
│                                │  Write Page │            │
│                                └──────────────┘            │
└─────────────────────────────────────────────────────────────┘
```

## Unified Data Model (Corrected)

| System | Entity | Actually |
|--------|--------|----------|
| Kanban | Task | Hermes kanban.db task |
| Kanban | Swarm | Hermes kanban swarm (graph of tasks) |
| Forge | Workflow | Forge workflow definition |
| Forge | Cascade | Forge map/reduce pipeline |
| **Notion** | **Page** | **Forge artifact (output of NotionForge task)** |
| **Notion** | **Block** | **Forge artifact (output of NotionForge task)** |
| **Notion** | **Database** | **Forge artifact (output of NotionForge task)** |

**Notion IS a Forge target, not a source.**

```
Hermes Kanban ──▶ Forge Workflow ──▶ NotionForge ──▶ Notion API
     │                │                    │
     │                │                    └── writes pages/blocks
     │                └── executes cascade
     └── task graph
```

## Implementation

```kotlin
/**
 * NotionForgeExecutor — executes NotionForge steps.
 */
class NotionForgeExecutor(
    private val notioClient: NotionClient,
) {
    suspend fun execute(step: WorkflowStep.NotionForge, inputs: Map<String, String>): StepResult {
        val resolved = resolveTemplates(step.action, inputs)
        
        return when (val action = resolved) {
            is NotionAction.CreatePage -> {
                val pageId = notioClient.createPage(action.parentId, action.title, action.content)
                StepResult.Success(
                    stepId = step.id,
                    output = """{"pageId": "$pageId"}""",
                    artifacts = listOf(
                        ForgeArtifact(
                            id = ForgeArtifactId.generate(),
                            name = action.title,
                            description = "Notion page",
                            files = emptyList(),
                            workflowId = null,
                            executionId = null,
                        )
                    )
                )
            }
            
            is NotionAction.CreateDatabase -> { /* ... */ }
            is NotionAction.AppendBlocks -> { /* ... */ }
            is NotionAction.UpdatePage -> { /* ... */ }
            is NotionAction.QueryDatabase -> { /* ... */ }
        }
    }
    
    private fun resolveTemplates(action: NotionAction, inputs: Map<String, String>): NotionAction {
        // Replace {{variable}} patterns with input values
    }
}
```

## CLI Usage

```bash
# Create a Forge workflow that writes to Notion
forge workflow save --name report \
  --steps @workflow-with-notion.json

# Run it
forge run report '{"topic": "analysis"}'

# The NotionForge step writes output to Notion automatically
```

## Summary

| Old (Wrong) | New (Correct) |
|-------------|----------------|
| "Notion integration" | NotionForge task type |
| "Notion database" | Forge artifact |
| "Notion pages" | Forge output |
| "Sync from Notion" | Read artifact from Notion API |
| "Write to Notion" | NotionForge step |

**Notion = Forge task target. NotionForge = the step type.**