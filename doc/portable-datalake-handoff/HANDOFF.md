# k3s swarm handoff — portable datalake

Mission: close the fundsneeded gap ledger for the **salesman's-laptop datalake**:
RTS-viewable blackboard and LCNC assemblies at marketable quality, non-hallucinating
ingest + NLP curation plumbed end to end, CoreNLP and camel resident in sub-VM
polyglot on Graal JVM, hermetic and offline-capable.

Read `SKILL.md` first — it is the binding doctrine. Read `gaps-ledger.md` — it is
the acceptance contract. This handoff exists because cells have been **laying
tiles in exclusion of each other** while agent commits rain unrelated changes
("meteorites"). The boundaries below are the fix: each cell owns paths
exclusively, integrates on one branch, and describes its commits canonically.

## Branch and merge protocol

- Integration branch: `codex/portable-datalake` (cut from `codex/curator-mux-gate`).
- Each cell works a `cell/<name>` branch, rebases at least daily, opens one PR
  per coherent change. No cell merges another cell's paths, ever.
- **Compile is law**: `./gradlew compileKotlinJvm compileTestKotlinJvm` must pass.
  No test-regime gate beyond that — preservation and improvement outrank ceremony.
- Every commit message must describe its actual content canonically. A message
  that undersells the diff (the "Refactor codebase structure" failure) blocks merge.
- Meteorite watch: `.jules/*.md` / `.Jules/*.md` arriving in any diff get excised
  in a separate commit before merge.

## Cells and exclusive ownership

| Cell | Owns (exclusive) | Delivers | Gap-ledger rows |
|---|---|---|---|
| `board` | `src/jsBlackboard/`, `src/commonMain/kotlin/borg/trikeshed/landscape/`, `src/commonMain/kotlin/borg/trikeshed/blackboard/`, `harness.html`, `harness.css` | The `blackboard` assembly faction: spatial medium (camera pan/zoom over `#world`), feed-driven territory lamps via `BlackboardFeed`+`LandscapeActivity`, provenance diagram rendered from `ProvenanceTrace` (Bytes↔Production tree, back/forward toggle) | No-code front door; Managed curation (visible sheets half) |
| `lcnc` | `src/jsSpacegraph/`, `src/commonMain/kotlin/narchy/`, `panels.html`, LCNC editor sources | The no-code front door at marketable quality: intake → useful views → chosen workflow, no code edited | No-code front door; Company deployment (access model) |
| `curation` | `src/commonMain/kotlin/borg/trikeshed/narsese/`, `src/commonMain/kotlin/borg/trikeshed/provenance/`, `DocumentCurationWire` | Source → extraction → curation → visible sheets with inspectable support: every proposal opens to receipts, bytes, and productions (`ProvenanceTrace`); non-hallucination is the acceptance | Managed curation; Ingest fidelity |
| `graalvm` | `src/jvmMain/kotlin/borg/trikeshed/graal/subvm/`, camel runtime bindings | CoreNLP + camel as resident sub-VM polyglot guests in-JVM on Graal; the curation adapter's supplying host with installed managed libraries | Managed curation (supplying host); Portable boot (runtime needs) |
| `portable` | packaging, launcher, manifests, `utils/subvm` release tooling | Clean-machine install without engineering checkout or Gradle build; offline task definition; backup/restore | Portable boot; Offline use; Company deployment; Performance report |
| `durable` | `src/jvmMain/kotlin/borg/trikeshed/couch/`, CAS/durable heads | Integrate the Stage-1 durable Couch/CAS (CouchCommitStore lineage) into the daemon: heads, revisions, deletions, checkpoints recover on restart | Durable state; Peer environment replication |

Shared (coordinate, never edit silently): `build.gradle.kts` (faction wiring
changes go through `board`+`portable` pairing), `BlackboardNamespaces.kt`
(append-only rows via PR), `ModelMux`/`KeyMux` (Order 13 — model access is
reactor-owned; propose, don't touch).

## Priority order

1. `board` + `lcnc` — the RTS-viewable blackboard and LCNC assemblies
   (marketable coherence; highest priority).
2. `curation` + `graalvm` — non-hallucinating ingest and NLP curation through
   the real caller with inspectable support.
3. `portable` + `durable` — the salesman can carry it and drop it.

## k3s template

`k3s-cells.yaml` sketches one ConfigMap (doctrine + ledger) and one Job per
cell. Each Job checks out its `cell/<name>` branch in a git worktree, mounts
the ConfigMap at `/doctrine`, and runs the cell's agent with
`/doctrine/SKILL.md` loaded. Replace the agent image/command with your swarm
runner's; the contract that matters is: skill loaded, branch isolated, compile
gate, canonical messages.

## Definition of done (from the ledger)

The salesman imports approved material and completes the job without editing
code; originals are retained; the release runs source → extraction → curation →
visible sheets where every semantic claim back-traces to bytes through real
receipts; the app opens and performs agreed local tasks without connectivity;
stop/restart restores heads, revisions, deletions, checkpoints; a clean machine
installs from a manifest without Gradle.
