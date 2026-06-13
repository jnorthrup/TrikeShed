# Forge

**Autonomous LLM Workflow Fabric** — Kotlin/JVM library for versioned, auditable, collaborative inference workflows.

## Overview

Forge provides a **workflow algebra** on top of a pluggable storage/collaboration backend. It handles:

- **Files** — Markdown, JSON, PDF, etc. with search & streaming
- **Snapshots** — Git-like VCS (create, diff, branch, merge, restore)
- **Prompts** — Templated, parameterized, versioned
- **Workflows** — Typed DAG: LLM calls, code execution, agent invocation, file transforms, conditionals, parallel branches
- **Execution** — Sync + streaming `Flow<StepProgress>` with full audit trail
- **Artifacts** — Export/import (JSON, ZIP) for sharing
- **Collaboration** — Real-time cursors, presence, event streams
- **Agents** — Pluggable `ForgeAgentRunner` for Codex, Claude Code, LCNC, etc.

## Architecture

```
┌─────────────────────┐
│   Forge (Algebra)   │  ← this library
├─────────────────────┤
│ ForgeWorkspace      │  ← storage/collaboration interface
│ ForgeStepRunner     │  ← step execution interface
│ ForgeAgentRunner    │  ← agent runtime interface
└─────────┬───────────┘
          │
          ▼ pluggable backend
┌─────────────────────┐
│        LCNC         │  (faceted Confix cursors, Miniduck/ISAM, websocket sync)
│  • Faceted cursors  │
│  • Wire reification │
│  • Real-time sync   │
└─────────────────────┘
```

## Quick Start

### Build
```bash
./gradlew :libs:forge:build --no-daemon
```

### Run Tests
```bash
cd libs/forge
./run.sh
```

### CLI Usage
```bash
# Initialize workspace
java -jar forge.jar init

# Files
forge file put --path notes.md --content "# Hello" --mime text/markdown
forge file list
forge file search "hello"

# Snapshots (Git-like)
forge snapshot create "initial commit"
forge snapshot list
forge snapshot restore <id>
forge snapshot diff <from> <to>
forge snapshot branch <base-id> feature-branch
forge snapshot merge <source> <target> "merge message"

# Prompts
forge prompt save --name summarize --template "Summarize: {{text}}" --param text:string:"Text to summarize"

# Workflows
forge workflow save --name my-workflow --steps @workflow.json
forge run my-workflow '{"input": "data"}'

# Artifacts
forge artifact create --name "Report" --description "..." --files <file-id-1>,<file-id-2>
forge export <artifact-id> JSON

# Collaboration
forge collab join --name "Alice" --color "#ff0000"
forge collab users
forge collab events  # subscribe to real-time events
```

## Workflow Definition

Workflows are JSON arrays of typed steps:

```json
[
  {
    "id": "step1",
    "type": "LlmCall",
    "promptId": "summarize",
    "inputs": { "text": "{{input}}" },
    "model": "gpt-4o-mini"
  },
  {
    "id": "step2",
    "type": "CodeExecution",
    "language": "kotlin",
    "code": "result = input.uppercase()",
    "inputs": { "input": "{{step1.output}}" }
  },
  {
    "id": "step3",
    "type": "AgentInvocation",
    "agentType": "LCNC",
    "task": "Process result",
    "context": { "data": "{{step2.output}}" },
    "allowedTools": ["read_file", "run_command"]
  },
  {
    "id": "step4",
    "type": "Conditional",
    "condition": "{{step2.output.length}} > 100",
    "thenBranch": [...],
    "elseBranch": [...]
  },
  {
    "id": "step5",
    "type": "Parallel",
    "branches": [[...], [...], [...]]
  }
]
```

## Integration Points (for LCNC)

| Interface | Purpose |
|-----------|---------|
| `ForgeWorkspace` | Implement over Miniduck/ISAM + websocket sync |
| `ForgeStepRunner` | Implement step execution via LCNC runtime |
| `ForgeAgentRunner` | Add `AgentType.LCNC` → spawn LCNC workflows |
| `events(): Flow<CollaborationEvent>` | Wire LCNC's event bus |

## Examples

- `examples/partner-onboarding-workflow.json` — Partner KYC, credit, contract, provisioning

## Running Tests

```bash
./gradlew :libs:forge:test --no-daemon
```

31 tests covering all interfaces (GREEN).