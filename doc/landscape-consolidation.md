# Landscape Consolidation

## Authority Contracts

The common camera is a projection contract, not a universal object type.
Workspace objects, programs, execution scopes, beliefs and receipts coexist
while keeping their owners. Collapse changes visibility only: a vanishing
point neither deletes its scope nor grants execution authority.

| Concern | Authority and reusable boundary |
| --- | --- |
| Blackboard state | `ConfixBlackboard.snapshot()` atomically joins values, provenance and revision. `revisions` is a conflated wakeup; `replay(after)` supplies ordered changes or a reset. |
| Legacy notifications | `lastWrite`, deprecated `state`, and deprecated `changes` are partial Confix views, not full snapshots. Do not reconstruct authoritative state from them. |
| Work commitment | `BoardStoreElement` owns intake and the existing CAS/WAL. Working reductions remain private until batch flush; projections, rows, acknowledgments and fanout publish only afterward. |
| Failed commitment | Append/flush failure closes intake and fails outstanding replies. Readers retain the last published rows. The private reducer cannot be reused: recover through a fresh element. An uncertain write may appear on restart; a failed acknowledgment is not proof of absence. |
| Immutable attribution | Replay retains the WAL's recorded CID, never a CID recomputed from parsed JSON. `programCid` identifies frozen program bytes; `receiptCid` identifies the raw work command carrying the receipt. |
| Receipt lineage | Raw `lcncRun` payloads carry `previousReceiptCid`, not inherited current `receiptCid`, `sequence` or `timelineRevision`. Those current coordinates are derived from the actual commit. Older payloads remain readable. |
| Program closure | `versionPolicy` is `root-at-admission,subprogram-at-first-use`. Named children are pinned on first acquisition and recorded in `programVersions`. This is not an admission-time snapshot of every dependency or the runtime environment. |
| Navigation | `/harness`, `/panels`, `/panels.html`, `/blackboard` and `/graal` share the harness. Camera bookmarks and scoped identities are view state; unpublished drafts remain caller-owned. |
| Inspection | `GET /api/lcnc/content?cid=...` reads and verifies the execution store's exact JSON bytes. Optional `key` locates a published, unexecuted program only when its canonical bytes match the requested CID. `view=sheet` reuses Confix sheet projection and the existing concentric renderer. No inspection triggers execution. |

Storage roles remain independent: hot lookup, immutable CAS identity, canonical
Confix structure and derived indexed access. Neither transport revision nor
wall-clock time proves an execution dependency. Containment, reference,
tentative association, evidential support and execution dependency must remain
distinguishable; term matches and spatial proximity are not evidence.

## Resource and Recovery Contracts

- Blackboard replay retains 256 changes. An expired/future cursor or changed
  server epoch causes explicit snapshot recovery. Null values and deletions
  remain distinct.
- Sheet budgets cover the family, not each child independently: sheets, rows,
  text, depth and elapsed traversal time are capped. Continuations are tied to
  the snapshot revision; changed state returns a conflict instead of silently
  mixing pages.
- LCNC content inspection caps source bytes at 1 MiB, applies the shared value
  budget, and limits sheet rows to 512. Browser reads also cap received bytes
  without relying on Content-Length. Attachment previews have smaller limits.
- A new or closed inspection aborts previous reads and invalidates pending
  sheet hydration. Late responses cannot replace another object's view.
- Runtime reasoning keeps elapsed, work and payload budgets separate. These
  are cooperative bounds, not proof of hard-real-time behavior. Program
  preparation/indexing and arbitrary runner internals still need independent
  budget coverage.

## Acceptance Ledger

| Checkpoint | Evidence | Remaining obligation |
| --- | --- | --- |
| Integrity | `BoardStoreElementTest`: grouped flush, invisible unflushed state, failed intake, restart ordering and original command identity. `LcncRunProgramRouteTest`: restart receipts and immutable content/lineage. | Crash behavior beyond the existing WAL contract is not newly certified. |
| Projection | `BlackboardRecoveryTest`, `BlackboardWireRepairTest`, `BlackboardSheetRouteTest`, `ProjectionBudgetTest`, `JsonArrayShapeTest`. | Large projections must continue to satisfy combined limits, including fallback values. |
| Navigation | `src/jvmTest/js/landscape.test.cjs`: identity bookmarks, reversible closure collapse, bounded streams and stale inspection isolation. `BlackboardPageTest`: served route aliases. Live browser checks below cover the core journey. | Legacy operational capability parity still requires explicit review; route parity alone does not prove feature parity. |
| Runtime | `LcncRunProgramRouteTest`: stored/inline scopes, server validation, work limit, timeout and cancellation receipts. | Single-node compatibility dispatch is not yet on the scoped-run receipt contract. |

The build gate remains `./gradlew jvmMainClasses --console=plain`; targeted
tests supplement it. Run the browser-helper checks with
`node --test src/jvmTest/js/landscape.test.cjs`.

Verification on 2026-09-05: the build gate and 58 targeted JVM tests passed;
all six browser-helper tests passed. These are selected acceptance checks,
not a claim that the full repository test suite passed.

An isolated preview with autonomous dispatch disabled demonstrated:

- A stored nested-scope preset completed and exposed its immutable program
  version, receipt and predecessor through the shared sheet inspector.
- A second preset's exact published version was inspected without execution.
- A repository terrain object opened in the same inspector. At a 390 by 844
  viewport, the dialog's client and scroll widths were both 356 pixels;
  tabular content retains its own bounded scrolling region.
- Scope zoom retained all 360 mounted node instances; previous-view navigation
  restored the broader scene. This is identity-preservation evidence, not a
  guarantee of all legacy editing behavior.
- Disabling native viewport scrolling removed a second camera offset; the
  fitted program remained inside the desktop viewport with zero native scroll.
- Browser reconnect and a preview-server restart retained the completed run
  and its original receipt CID. Interrupted-run predecessor recovery is also
  covered separately by a restart fixture for validating and running phases.

A subsequent preview refresh exposed a separate lifecycle gap: TERM released
the listener but did not terminate the process. A thread dump showed the main
coroutine waiting and file-watch workers blocked in `WatchService.take()`;
this does not establish the complete cause. The isolated, dispatch-disabled
preview was forcibly stopped and restarted. Clean full-daemon shutdown remains
an acceptance obligation; receipt recovery must not be mistaken for that proof.

## Shared Completion Review

Keep concurrent work moving through three shared checkpoints: integrity,
object-to-scope-to-execution-to-receipt convergence, then recovery. Each
workstream supplies its owner, authoritative state, dependencies, next bounded
increment and evidence. Passing independent increments need not wait for
every exploratory domain. A daily checkpoint is a proposed coordination
cadence, not an automatic scheduler or a promised completion date.

Exploration defaults to two dependency hops and two minutes per question.
Expiry returns a finding, uncertainty and checkpoint consequence; renewal
requires decision-relevant evidence. Preserve accepted-work capacity and age
deferred work rather than letting exploration starve completion.

VM/exokernel observation, harness filesystem/database virtualization, SUMO
correspondence and userspace absorption remain distinct exploration frontiers.
Require measured privilege, recovery and dispatch evidence before absorption;
review AGPL compatibility separately from technical isolation. Skills and
workspace decoration may guide attention without becoming hidden authorities.

## Supervised Developer Review

Accept each checkpoint on evidence, not on a route being renamed or a feature
being described. Review legacy Graal operational parity and the remaining
budget/dispatch boundaries before declaring the whole consolidation complete.
