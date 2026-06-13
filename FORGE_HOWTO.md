# Forge How-To Guide

**Autonomous LLM Workflow Fabric** — Kotlin/JVM library for versioned, auditable, collaborative inference workflows.

---

## Quick Start

```bash
cd /Users/jim/work/TrikeShed

# Run full test suite (serves as complete demo)
./gradlew :libs:forge:test --no-daemon --rerun-tasks

# Build JAR
./gradlew :libs:forge:jar --no-daemon

# Run Forge UI (Compose Desktop)
./gradlew :libs:forge-ui:jvmRun --no-daemon
```

---

## Core Concepts

| Concept | Description |
|---|---|
| **ForgeWorkspace** | Storage/collaboration backend (files, snapshots, prompts, workflows, artifacts, cascades) |
| **ForgeStepRunner** | Executes individual workflow steps (LLM, code, agent, transform, conditional, parallel) |
| **ForgeAgentRunner** | Runs coding agents (Codex, Claude Code, LCNC) via channel of events |
| **Operational Cascade** | Map-Reduce-Rereduce pipeline for analytics on workspace data |

---

## 1. Workspace Operations

```kotlin
val workspace: ForgeWorkspace = ForgeWorkspaceImpl()  // In-memory for testing

// Files
workspace.put(ForgeFile(ForgeFileId("f1"), "notes.md", "# Hello", "text/markdown"))
val file = workspace.get(ForgeFileId("f1"))
val allFiles = workspace.list()
val results = workspace.search("hello")
workspace.delete(ForgeFileId("f1"))

// Snapshots (Git-like)
val snap = workspace.snapshot("initial commit", setOf("v1"))
val history = workspace.history()
workspace.restore(snap.id)
val diff = workspace.diff(fromSnap.id, toSnap.id)
val branch = workspace.branch(baseSnap.id, "feature-branch")
val merged = workspace.merge(sourceSnap.id, targetSnap.id, "merge feature")

// Prompts
workspace.putPrompt(ForgePrompt(ForgePromptId("summarize"), "Summarize", "Summarize: {{text}}", mapOf("text" to "string"), setOf("llm")))
val prompt = workspace.getPrompt(ForgePromptId("summarize"))

// Workflows
workspace.putWorkflow(ForgeWorkflow(...))
val wf = workspace.getWorkflow(ForgeWorkflowId("my-wf"))

// Artifacts
val artifact = workspace.artifact("Report", "desc", files, wfId, execId, true)
val exported = workspace.export(artifact.id, ExportFormat.JSON)
workspace.importArtifact(exported)
```

---

## 2. Define a Workflow

Workflows are typed DAGs of steps:

```kotlin
val workflow = ForgeWorkflow(
    id = ForgeWorkflowId("doc-pipeline"),
    name = "Document Processing",
    steps = listOf(
        // LLM call
        WorkflowStep.LlmCall(
            id = "summarize",
            promptId = ForgePromptId("summarize"),
            inputs = mapOf("text" to "{{input.content}}"),
            model = "gpt-4o-mini",
            parameters = mapOf("temperature" to "0.2")
        ),
        // Code execution
        WorkflowStep.CodeExecution(
            id = "extract",
            language = "python",
            code = """
                import re, json
                text = input.summary
                entities = re.findall(r'[A-Z][a-z]+ [A-Z][a-z]+', text)
                result = json.dumps({"entities": list(set(entities))})
            """.trimIndent(),
            inputs = mapOf("summary" to "{{summarize.output}}"),
            timeoutMs = 5000
        ),
        // Agent invocation (Codex, Claude Code, LCNC)
        WorkflowStep.AgentInvocation(
            id = "enhance",
            agentType = AgentType.CODEX,
            task = "Enrich with external knowledge",
            context = mapOf("data" to "{{extract.output}}"),
            allowedTools = listOf("web_search", "read_file", "run_command")
        ),
        // File transform
        WorkflowStep.FileTransform(
            id = "render",
            inputFileIds = listOf(),
            transform = "render-markdown-report",
            outputPath = "reports/{{input.filename}}.md"
        ),
        // Parallel branches
        WorkflowStep.Parallel(
            id = "parallel",
            branches = listOf(
                listOf(WorkflowStep.AgentInvocation(...)),  // branch 1
                listOf(WorkflowStep.AgentInvocation(...)),  // branch 2
            )
        ),
        // Conditional
        WorkflowStep.Conditional(
            id = "quality-gate",
            condition = "{{extract.output.word_count}} > 100",
            thenBranch = listOf(...),
            elseBranch = listOf(...)
        ),
        // Cascade execution (analytics)
        WorkflowStep.CascadeExecution(
            id = "rollup",
            cascadeId = CascadeId("detected-cascade"),
            inputs = emptyMap()
        )
    ),
    inputSchema = mapOf("content" to "string"),
    outputSchema = mapOf("report" to "markdown")
)

workspace.putWorkflow(workflow)
workspace.snapshot("workflow saved")
```

---

## 3. Execute Workflows

```kotlin
// Streaming execution (Flow<StepProgress>)
val progressFlow = workspace.execute(
    ForgeWorkflowId("doc-pipeline"),
    mapOf("content" to "Long document text..."),
    configs = mapOf(AgentType.CODEX to AgentConfig(...))
)

progressFlow.collect { event ->
    when (event) {
        is StepProgress.StepStarted -> println("Started: ${event.stepId}")
        is StepProgress.StepProgress -> println("Progress: ${event.message}")
        is StepProgress.StepCompleted -> println("Done: ${event.stepId} -> ${event.result}")
        is StepProgress.StepFailed -> println("Failed: ${event.stepId} - ${event.error}")
        is StepProgress.WorkflowCompleted -> println("Workflow done!")
    }
}

// Synchronous execution
val result = workspace.executeSync(
    ForgeWorkflowId("doc-pipeline"),
    mapOf("content" to "Long document text..."),
    configs = mapOf(AgentType.CODEX to AgentConfig(...))
)

if (result.status == ExecutionStatus.SUCCESS) {
    println("Final outputs: ${result.finalOutputs}")
    println("Artifacts: ${result.artifacts}")
}
```

---

## 4. Operational Cascades (Analytics)

Detect Map-Reduce pipelines from JSONL data:

```kotlin
// Store JSONL data
val jsonl = """
    {"infrastructure_id": "infra-1", "machine_id": "m-1", "ts": "2024-01-01T10:00:00", "cpu_mhz": 2400}
    {"infrastructure_id": "infra-1", "machine_id": "m-1", "ts": "2024-01-01T11:00:00", "cpu_mhz": 2500}
    {"infrastructure_id": "infra-1", "machine_id": "m-2", "ts": "2024-01-01T10:00:00", "cpu_mhz": 3200}
""".trimIndent()

workspace.put(ForgeFile(ForgeFileId("readings.jsonl"), "readings.jsonl", jsonl, "application/jsonl"))

// Detect cascade
val request = CascadeDetectionRequest(
    sources = listOf(CascadeSource.FileSource(ForgeFileId("readings.jsonl"), DataFormat.JSON)),
    candidateKeys = listOf("infrastructure_id", "machine_id", "ts"),
    candidateMetrics = listOf("cpu_mhz"),
    maxHierarchyDepth = 4
)

val detection = workspace.detectCascades(request)
val cascade = detection.detectedCascades[0]
workspace.putCascade(cascade)

// Execute cascade
val result = workspace.executeCascadeSync(cascade.id)
// result.output = List<CascadeRow> with condensed aggregates

// Visualize cascade graph
val graph = workspace.getCascadeGraph(cascade.id)
// graph.nodes = [SOURCE, MAP, REDUCE, REREDUCE, SINK]
// graph.edges = connections between stages

// Stream progress
val progress = workspace.executeCascade(cascade.id).toList()
// Contains StageStarted, StageCompleted, CascadeCompleted events
```

---

## 5. Collaboration (Real-Time)

```kotlin
// Join workspace
val user = ForgeUser(ForgeUserId("alice"), "Alice", Color(0xFFFF0000))
workspace.join(user)

// Subscribe to events
workspace.events().collect { event ->
    when (event) {
        is CollaborationEvent.UserJoined -> println("${event.name} joined")
        is CollaborationEvent.UserLeft -> println("${event.userId} left")
        is CollaborationEvent.CursorMoved -> println("Cursor: ${event.position}")
        is CollaborationEvent.FileChanged -> println("File changed: ${event.fileId}")
        is CollaborationEvent.SnapshotCreated -> println("Snapshot: ${event.snapshot.message}")
    }
}

// Emit cursor position
workspace.emit(CollaborationEvent.CursorMoved(user.id, CursorPosition(row = 10, col = 5)))

// Get active users
val users = workspace.users()
```

---

## 6. Agent Runners

Implement `ForgeAgentRunner` for your agent:

```kotlin
class MyCodexRunner : ForgeAgentRunner {
    override val agentType = AgentType.CODEX
    
    override fun runAgent(config: AgentConfig, task: String, context: Map<String, String>, workingDir: String): ReceiveChannel<AgentEvent> {
        val channel = Channel<AgentEvent>()
        // Spawn Codex process, stream events to channel
        return channel
    }
    
    override suspend fun isAvailable(): Boolean = true  // Check Codex CLI exists
}

// Register with step runner
val stepRunner = ForgeStepRunnerImpl()
val agentConfig = AgentConfig(AgentType.CODEX, null, null, "/workspace", emptyMap(), 5, 60000)
val result = stepRunner.runAgentInvocation(
    step = WorkflowStep.AgentInvocation(...),
    resolvedInputs = mapOf("task" to "Create hello world"),
    agentConfig = agentConfig,
    workingDir = "/workspace"
)
```

---

## 7. LCNC Integration (Target Backend)

Forge is designed to plug into **LCNC** (TrikeShed's faceted Confix cursor runtime):

| Forge Interface | LCNC Implementation |
|---|---|
| `ForgeWorkspace` | Over Miniduck/ISAM + websocket sync (faceted cursors) |
| `ForgeStepRunner` | Over LCNC runtime (GraalJS eval, FieldSynapse wireproto) |
| `ForgeAgentRunner` | Add `AgentType.LCNC` → spawn LCNC workflows |
| `events(): Flow<CollaborationEvent>` | Wire LCNC's CCEK SPI bus |

**Integration steps:**
1. Implement `ForgeWorkspace` using `lib_cursor` cursors + `Series` storage
2. Implement `ForgeStepRunner` using LCNC's `Reactor` + `ClassFile` delegates
3. Add `AgentType.LCNC` to `ForgeAgentRunner`
4. Wire `events()` to LCNC's real-time sync channel

---

## 8. Example Workflow File

See `libs/forge/examples/partner-onboarding-workflow.json` for a complete example:

```json
{
  "name": "Document Processing Pipeline",
  "steps": [
    { "id": "step1", "type": "LlmCall", "promptId": "summarize-document", ... },
    { "id": "step2", "type": "CodeExecution", "language": "python", ... },
    { "id": "step3", "type": "AgentInvocation", "agentType": "LCNC", ... },
    { "id": "step4", "type": "FileTransform", "transform": "render-markdown-report", ... },
    { "id": "step5", "type": "Parallel", "branches": [ [...] ] },
    { "id": "step6", "type": "Conditional", "condition": "...", ... },
    { "id": "step7", "type": "FileTransform", "transform": "create-artifact-bundle", ... }
  ]
}
```

---

## 9. Test-Driven Development

All 31 tests are TDD GREEN. Run them to verify behavior:

```bash
./gradlew :libs:forge:test --no-daemon --rerun-tasks
```

Key test classes:
- `ForgeWorkspaceTddTest` — workspace CRUD, snapshots, collaboration
- `ForgeStepRunnerTddTest` — all step types (LLM, code, agent, transform, conditional, parallel)
- `ForgeAgentRunnerTddTest` — agent runner contract
- `ForgeCascadeTddTest` — cascade detection, execution, graph, progress

---

## 10. Project Structure

```
libs/forge/
├── src/main/kotlin/borg/trikeshed/forge/
│   ├── ForgeWorkspace.kt          # Interface
│   ├── ForgeWorkspaceImpl.kt      # In-memory impl
│   ├── ForgeRunner.kt             # StepRunner, AgentRunner interfaces
│   ├── ForgeStepRunnerImpl.kt     # Stub impl
│   ├── ForgeTypes.kt              # Data types
│   ├── CascadeTypes.kt            # Cascade types
│   ├── KanbanTypes.kt             # Kanban integration
│   ├── cursor/                    # HermesKanbanCursor, Fanout
│   ├── swarm/                     # SwarmEntities
│   ├── notion/                    # Notion adapters
│   └── plugin/                    # Gradle plugin
├── src/test/kotlin/...            # 31 TDD tests
├── examples/
│   └── partner-onboarding-workflow.json
├── run.sh                         # Demo runner
└── build.gradle.kts
```

---

## 11. Known Limitations

| Area | Status |
|---|---|
| Standalone CLI | ❌ Not implemented (README examples are speculative) |
| Persistent storage | ❌ In-memory only (ForgeWorkspaceImpl) |
| Real agent runners | ❌ Stubs only (ForgeAgentRunnerStub, ForgeStepRunnerImpl) |
| LCNC backend | ❌ Integration points defined, not implemented |
| Authentication/ACL | ❌ Not implemented |

---

## 12. Next Steps

1. **Implement persistent `ForgeWorkspace`** over Miniduck/ISAM
2. **Build `ForgeStepRunner`** using LCNC runtime (GraalJS + ClassFile)
3. **Add `AgentType.LCNC`** runner
4. **Wire collaboration events** to CCEK SPI bus
5. **Build CLI** (`forge` command) using the workspace API
6. **Add authentication** and multi-tenant support

---

## References

- **README**: `libs/forge/README.md`
- **Test suite**: `libs/forge/src/test/kotlin/...`
- **Example workflow**: `libs/forge/examples/partner-onboarding-workflow.json`
- **UI module**: `libs/forge-ui/` (Compose Desktop/Web)
- **Architecture diagram**: `README.md` (Mermaid in Overview section)