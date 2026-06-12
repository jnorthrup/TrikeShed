# TrikeShed

**A compositional, blackboard-based fabric for autonomous LLM workflows with real-time human–agent collaboration.**

TrikeShed is a systems substrate for building reliable, observable, multi-step workflows that combine LLMs, coding agents, and human collaborators on shared state.

Workflows are expressed as **algebraic compositions** rather than ad-hoc chains or simple DAGs. State is managed through a **blackboard architecture** with cursor-based real-time synchronization, immutable snapshots, and strong artifact provenance. The design draws from classical AI blackboard systems, modern effectful composition, and collaborative knowledge tools — the spiritual successor to “GNU Autotools × Notion” for agentic work.

## Forge: The Visual Front-End

The included Forge interface renders the complete working surface in real time:

- **Workspace metrics** (files, snapshots, prompts, workflows, executions, cursor rows, collab events, DB rows)
- **Preload algebra chain** visualization: `Join<A,B>` → `Series<T>` → `Cursor` → `ConfixDoc` → `Blackboard` → `CCEK`
- Live multi-user collaboration (cursor presence, simultaneous edits)
- Execution provenance with stable IDs and full trace
- Cascade outputs with quantitative scores (α / β / γ)
- Telemetry analysis demo pipeline
- Notion-backed model layer
- Explicit extensibility surface (“Forge Hinges”)

The interface is deliberately instrumented so every layer — from algebraic composition down to individual agent steps — remains visible and actionable.

## Core Architectural Concepts

### Compositional Algebra
Workflows are constructed from a small, typed set of operators that compose cleanly:
