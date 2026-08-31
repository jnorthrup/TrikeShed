# TrikeShed Guides

Navigate the daemon surfaces. Each guide covers one surface: purpose, routes, walkthrough, and honest status markers.

> **Start here:** [Daemon Launch Guide](guide-daemon-launch.md) — boot the daemon from a clean checkout or forge home. Every other guide requires a running daemon.

## Surface Guides

| Guide | Surface | What You Can Do |
|-------|---------|-----------------|
| [Graal Console](guide-graal-console.md) | `/graal` | RTS commander view — vitals, terrain map, DAG cross-links, decompile, AOT cache, SSE events |
| [Kanban Board](guide-kanban-board.md) | `/api/board` | Read the WAL-backed board, issue invoke commands, import plan docs |
| [MCP Kanban](guide-mcp-kanban.md) | `/api/mcp` | Point Claude Code or any MCP client at the board — two tools, four resources, one durable store |
| [NARS Beliefs](guide-nars-beliefs.md) | `/api/beliefs` | Drive the NARS curation loop — belief bag, review induction, decay tick, teach/query |
| [Couch Surface](guide-couch-surface.md) | `/{db}/*` | Couch 1.6-style document CRUD, `_changes` feed, `_replicate` pull/push |
| [Guest Worlds](guide-guest-worlds.md) | `/api/vm` + capsule | Spawn GraalPy sub-VMs, seed VFS directories, run pytest in the sandbox |
| [Drop a Corpus](guide-drop-a-corpus.md) | `/api/graal/ingest` | Bulk ingest any file — Tika/OCR extraction, CAS storage, plan-shape gate |
| [Metered VMs](guide-metered-vms.md) | `/api/vm` | Spawn/drive/revoke metered VMs, SSE events, terminal page |
| [Panels / LCNC](guide-panels-lcnc.md) *(probationary)* | `/panels` | Open the concentric canvas, run LCNC programs, server-persisted constructions |

## Architecture Pages

| Page | What It Covers |
|------|----------------|
| [WikiSkill Hermes Trainer](wikiskill-hermes-trainer.md) | Fixed `1A` CommonResources corpus for grounded CoreNLP → NARS causal links, persistent wiki patterns, an atomic candidate, and a disjoint validation target |
| [Marketability + MCP Kanban Audit](marketability-kanban-mcp-audit.md) | Current corpus reconciliation, proposed marketability backlog, and minimum MCP exposure of the LCNC Kanban asset over its one durable store |
| [Escape Velocity](escape-velocity.md) | The independence story — git-CAS self-hosting, pijul gateway, substrate inventory, wave-2 targets |
| [CCEK Covenant](ccek-covenant.md) | The anti-rolloff anchor — CCEK definition, negative space, rings-are-blocks, machine status |
