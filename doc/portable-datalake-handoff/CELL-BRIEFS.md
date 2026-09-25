# Separate-session cell briefs — portable datalake swarm

Written 2026-09-24 on `cell/lcnc` after seeding the cell branches. Each section
below is the opening brief for one agent session. Feed it verbatim as the
session's first message, after pointing the session at the repo.

## Session setup (every cell)

1. Clone `https://github.com/jnorthrup/TrikeShed`, check out your `cell/<name>`
   branch. It exists on origin, cut from `codex/portable-datalake`.
2. Load `doc/portable-datalake-handoff/SKILL.md` as binding doctrine, then read
   `doc/portable-datalake-handoff/gaps-ledger.md` (acceptance contract) and
   `doc/portable-datalake-handoff/HANDOFF.md` (cell ownership table).
3. Rebase on `codex/portable-datalake` before the first edit; rebase at least
   daily afterwards.
4. Gate: `./gradlew compileKotlinJvm compileTestKotlinJvm` must pass before any
   push. Commit messages must canonically describe the diff.

## Clobbering rules — the binding part

These exist because cells have laid tiles in exclusion of each other; agent
commits have rained unrelated changes ("meteorites") on a busy multi-session
branch.

- **Path exclusivity is absolute.** Touch only the paths your cell owns (table
  below). A commit containing another cell's paths blocks merge, no matter how
  correct the change is. If a fix outside your paths is required, stop and
  record it in your session notes for that cell's agent — do not edit.
- **Shared files — coordinate, never edit silently:** `build.gradle.kts`
  (faction wiring goes through `board`+`portable` pairing),
  `BlackboardNamespaces.kt` (append-only rows via PR), `ModelMux`/`KeyMux`
  (Order 13: propose, never touch). If your change cannot land without one of
  these, your brief says who pairs; otherwise stop and report.
- **Rebase before assuming tree state.** Never `push --force` your cell branch.
  Merge conflicts with the integration branch resolve by rebasing, not by
  reverting others' commits.
- **Never wholesale-revert on test failure.** Tests are not currency; read
  intent, revert only the offense, keep the legitimate half.
- **No medium decisions inside feature commits.** Any UI commit is reviewed as
  a media decision (the b9531f327 lesson: a card grid + modal smuggled into a
  curation gate commit was reverted in full). The blackboard is a spatial
  medium, never a CSS grid.
- **No authored JS, no scripts, no `.jules/*.md`.** Browser JS is webpack
  output of Kotlin/JS only. Meteorite files arriving in any diff get excised.
- **`docs/` Sync hazard:** `generateForgePages` deletes whatever its preserve
  list does not name. Adding anything under `docs/` requires naming it in that
  task in the same commit — but only `board`/`lcnc`/`portable` agents will ever
  legitimately touch this; others stay out of `docs/` entirely.

## cell/board — priority 1

Owns: `src/jsBlackboard/`, `src/commonMain/kotlin/borg/trikeshed/landscape/`,
`src/commonMain/kotlin/borg/trikeshed/blackboard/`, `harness.html`,
`harness.css`.

First blocker is real and yours: the `blackboard` browser app does not exist.
`kotlinJsBrowserApps` in `build.gradle.kts` is `forge,documents,spacegraph` and
`src/jsBlackboard/` is empty. Faction wiring in `build.gradle.kts` is shared —
you pair with `portable` on that one file (add the app name and source set,
nothing else). Deliver: spatial medium (camera pan/zoom over `#world`),
feed-driven territory lamps (`BlackboardFeed` + `LandscapeActivity`),
provenance diagram from `ProvenanceTrace` (Bytes↔Production, back/forward).
Gap-ledger rows: No-code front door (visible-sheets half); Managed curation.

## cell/lcnc — priority 1 (this session already lives here)

Owns: `src/jsSpacegraph/`, `src/commonMain/kotlin/narchy/`, `panels.html`,
LCNC editor sources.

Deliver the no-code front door: intake → useful views → chosen workflow, no
code edited. Gap-ledger rows: No-code front door; Company deployment (access
model). Note: `cell/lcnc` already carries the script.js→Kotlin port and the
ForgeBrowserGraph fix; rebase onto the updated `codex/portable-datalake`
(fast-forwarded to b964a649e) rather than re-porting.

## cell/curation — priority 2

Owns: `src/commonMain/kotlin/borg/trikeshed/narsese/`,
`src/commonMain/kotlin/borg/trikeshed/provenance/`, `DocumentCurationWire`.

The curator is integrated behind reactor-owned ModelMux (426017b16) and
ProvenanceTrace landed (3587de335). Your lane: non-hallucination acceptance —
every proposal opens to receipts, bytes, and productions. Gap-ledger rows:
Managed curation; Ingest fidelity. Note: the curation adapter still needs a
supplying host with installed managed libraries — that host is `graalvm`'s
delivery, coordinate through the integration branch, do not implement it
yourself.

## cell/graalvm — priority 2

Owns: `src/jvmMain/kotlin/borg/trikeshed/graal/subvm/`, camel runtime bindings.

CoreNLP + camel as resident sub-VM polyglot guests in-JVM on GraalVM CE
25.3.4.1 (locked in `build.gradle.kts`). You are the curation adapter's
supplying host: installed managed libraries, real caller path. Gap-ledger
rows: Managed curation (supplying host); Portable boot (runtime needs).

## cell/portable — priority 3

Owns: packaging, launcher, manifests, `utils/subvm` release tooling.

The daemon today is Gradle-only (`runOroborosDaemon` JavaExec, AOT staging
under `build/staging/`). Gap: clean-machine install without engineering
checkout or Gradle; offline task definition; backup/restore. You also pair
with `board` on the `build.gradle.kts` faction wiring (the blackboard app
addition). Gap-ledger rows: Portable boot; Offline use; Company deployment;
Performance report.

## cell/durable — priority 3

Owns: `src/jvmMain/kotlin/borg/trikeshed/couch/`, CAS/durable heads.

Integrate the Stage-1 durable Couch/CAS (CouchCommitStore lineage) into the
daemon: heads, revisions, deletions, checkpoints recover on restart. Ledger
finding: `CouchStoreFactory.casBacked` is a memory projection around durable
blobs — retained blobs alone do not establish recovery. Acceptance is
stop/restart/restore tests against the actual backing stores. Gap-ledger
rows: Durable state; Peer environment replication.

## Standing named gap (nobody's lane yet)

The k3s runner image and invocation in `k3s-cells.yaml` are
`REPLACE-WITH-*` placeholders — the swarm runner choice is the operator's, not
a cell's. Until replaced, the Jobs are a contract sketch, not a deployable.
