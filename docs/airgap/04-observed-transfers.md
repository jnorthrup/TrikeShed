# R3a: observed transfers and native inspection

R3a adds visible consumption to the actual LCNC walk. The browser stretches
observed transfers into readable wire motion, exposes recorded LLVM timings
and allocation observations, and replays immutable receipts without executing
their nodes. It is one completed increment toward R3's document demonstrator;
source-clause traversal of a retained PDF or denatured agreement remains open.

The release evidence is recorded under
[`evidence/r3a-20261104`](evidence/r3a-20261104/verification.json). That manifest
is authoritative for the tested source, exact compiled artifacts, assertions,
network boundary and final verification status. The wider requirements remain
in the [R3 handoff](03-visible-traversal.md) and
[visualization blueprint](../lcnc/airgap-demonstrator/07-visualization.md).

## What the display means

The source fixture is a 52-byte synthetic JSON object:

```json
{"agreement":"Coinbase","revision":42,"active":true}
```

The real pinned upstream Confix scanner produces seven rows. Its `rowCount`
feeds a scalar LLVM addition with 100, and that result feeds another addition
with 1. The result is **108**. This proves the execution and observation path;
it does not model a Coinbase agreement, legal interpretation or a Nix evaluator.

To reconstruct this canvas, configure both pinned operator profiles using the
[Confix runbook](02-confix.md#build-and-configure) and the
[scalar runbook](../sulong-bitcode/README.md). Build the production Spacegraph
application with `./gradlew stageKotlinJs -PkotlinJsApps=spacegraph` and serve it
with the configured production module/runtime. In Panels:

1. Add `vm.llvm.confix` as `source`, select JSON, and enter the object above.
2. Add `vm.llvm.scalar` as `summary` with right parameter `100`, then another
   as `confirm` with right parameter `1`. Their left parameter can start at `0`.
3. Wire `source.rowCount` to `summary.left?`, then `summary.value` to
   `confirm.left?`.
4. Choose **Debug nodes**, then step the first two nodes. Inspect the pause
   before `confirm`; its selected left input is `107`.
5. Continue. Open **Evidence → Node invocations**, inspect the source runtime,
   and choose **Replay recorded wire activity**. Replay submits no run.

The accepted packet contains the exact frozen programme, not just these
construction instructions. Its retained fixture and browser driver document
the automated reproduction, including local toolchain paths that must be
rebased on another machine. They are evidence harnesses, not a sealed installer.

The existing debugger pauses before each node. After stepping the source and
first scalar node, a spark traverses the authored source-to-summary wire while
execution remains paused before the final node. The consumption has already
happened. Its visible motion is delayed presentation, not an in-flight native
buffer, native instruction pointer or countdown. Continue uses the existing
debug control channel and the same admitted execution.

The inspector shows the pinned module CID and ABI, compiler/engine versions,
queue/call/load/scan durations where recorded, source identity, copied row count,
scanner fault, guard result and bridge allocation counters. Missing values say
unavailable. Scalar kernels do not acquire fictional allocation measurements.
For Confix, `liveBytesAfter=0` describes the bridge allocation after release;
it says nothing about the total native heap, JVM heap or process RSS.

`internalProgress=unobserved`, `pauseBoundary=between-scans` or `between-calls`,
`hardCancellation=false`, and `nativeMemoryIsolation=false` remain explicit.
Scanner invocation completion and scanner data success are separate facts.
Neither a spark nor a green settlement asserts strict validation or truth.
The flyover uses short measurement rows that fit its bounded callout; the
Evidence drawer carries full module identity, load timing and qualification.

## Observation contract: `lcnc-observations/1`

`LcncRunner` records selection where it already resolves the real inputs.
`LcncScopeFrame` supplies the already-resolved output owner. The existing
`LcncInvocationLedger` owns the sequence, monotonic clock, retained stream and
omission counts. `LcncDebug` projects that stream into its current snapshot;
`OroborosEvidence` retains it with the durable invocation evidence. There is
no second runner and no browser-created execution event.

| Field | Meaning and boundary |
|---|---|
| Envelope `runId`, `programCid` | Admitted frozen execution; callers verify the receipt/debug envelope before display |
| `sequence` | Increasing per-walk observation identity across nested frames; JS accepts only exact safe integers |
| `elapsedNanos` | Monotonic elapsed time from **walk start**, including user pauses; not HTTP admission or a cross-machine timestamp |
| `nodeId`, `type`, `invocationOrdinal`, `path`, `parent`, `iteration` | Actual consumer invocation location |
| `frameId`, `documentName`, `documentCid` | Execution-local frame and frozen owning document; repeated frames remain distinct |
| `phase=consumed`, `status=delivered` | Selected input delivery observed at the existing consumer boundary |
| `producer` | Resolved frame/document/output owner, with executed or retained origin |
| `wire` | Authored document-local index and all four endpoint components |
| `selection` | Port, cardinality, position/count and representation of the resolved selection |
| `evidence` | Explicit `value_identity_not_recorded`; the new stream does not invent a value CID or serialize payload previews |

Single inputs report only the actual last-fed wire. MANY inputs preserve
authored selected positions. `representation=scalar` means a direct feeder,
whose value can itself be a list or map; `list` means the runner assembled
multiple feeders. Unfed, skipped and guarded consumers produce no successful
wire delivery. Nested frame IDs and document CIDs prevent same-named nodes
from becoming interchangeable.

Explicit debug input replacements carry `origin=intervention` and the
acknowledged `interventionSequence`, with no fabricated wire/producer. A
`scope.in` publication of an intervened binding retains its producer invocation
plus binding name/frame and intervention sequence. A warm pulse's retained
producer omits a fresh producing `invocationOrdinal`: it records
`reuseInvocationOrdinal` and `productionObserved=false`. A retained settlement
is evidence of reuse, not evidence that the source reran.

The source CID continues to belong to the scanner result. Runtime identity
belongs to the invocation. These existing authorities remain distinct from
the transfer stream; seeing a port selected does not establish its content's
truth, completeness or legal meaning.

## Bounds and loss accounting

The observation stream keeps a bounded suffix, normally 128 rows. Each row
uses the existing bounded-value machinery, with a 256-node/4,096-character
row budget. Live and final envelopes impose additional bounds: a 32,768-character live
snapshot and a 65,536-character final ledger, with 4,096-node limits. These constrain
evidence size; they are not a guarantee that 128 arbitrary rows always fit.

`observationSummary` records clock origin, attempted count, retained count,
omitted count, first/last retained sequence, last observed sequence and
complete/partial coverage. Final evidence trimming recomputes those fields.
`observationsDropped` includes all stream losses; `observationsOmitted` is a
diagnostic for extra envelope trimming and must **not** be added again.
The browser's “owner omissions” count covers omitted phase observations as
well as transfers. It cannot be interpreted as an exact number of lost wires.

The renderer permits at most 128 visible markers and 256 pending wire groups.
Queued observations on the same mapped wire aggregate into a represented
count. Further display losses and unmapped observations are separate counts.
This bounds the display queue; it does not preserve an unlimited causal trace.
There is no achieved 4 ms frame-preparation budget or measured FPS claim yet.
The larger blueprint's time bins, scope aggregates and dense-graph benchmark
remain work to do.

## Display ownership and identity

`PanelsTrace` belongs to `Panels`; `PanelsPage` owns its event handlers and
animation-frame handle. One frame loop follows the existing SVG wire geometry.
The authored wire index and endpoint tuple must both match. The frozen
definition must still match the canvas before queued activity is projected.
Graph replacement or a changed definition invalidates that projection. Layout
and camera changes use the current geometry while preserving execution identity.

Motion lasts 1.2 seconds at 1×. The 0.25×/1×/4× selector changes display time
only. Reduced-motion mode uses static midpoint markers. Native elapsed times
remain in the inspector and the observation retains its walk-start timestamp.
No browser clock is subtracted from the server's monotonic clock.

This increment maps the root frozen document and its inline scopes. Named
subprogram documents remain in the ledger but are counted as unmapped by this
root-canvas projection. Recursive scope navigation and multilevel RTS
aggregation are not complete. A root wire animation must never stand in for
an observation from a different frozen subprogram.

Unavailable or mismatched observation identity stops queued/visible activity
and clears live phase glow. Legitimate completion may finish already-recorded
motion with a recorded/completed label. Hiding activity only closes display
ownership; it is not an execution cancel or resume operation.

## Immutable replay and reopening

The Evidence drawer retains the existing drawer/selection ownership checks.
“Replay recorded wire activity” reads the invocation stream and frozen program,
checks its definition against the canvas, then starts display-only playback.
The banner says **Recorded playback · no execution**. Historical receipts
without schema 1 still render their native observations but do not gain a
fabricated replay stream.

The reproduction fixture uses the production userspace HTTP listener,
`KanbanModule`, CAS/Couch stores and public `OroborosLcncWiring.vmLegos` bootstrap.
It loads the actual production JVM artifact and compiled Panels application.
The external harness provides setup, inputs, browser actions and assertions;
it does not replace production classes or return mock execution responses.

After the run, the harness drains the module and native owners, releases the
host's route claims, and opens a fresh module context without LLVM runners.
The retained receipt and ledger are read through the ordinary content route.
Browser playback from those records remains inspection. The fixture is a
module/listener composition, not proof of the entire daemon's distribution.
Its asserted/inferred blip-plane route is absent and reports not found; the
recorded LLVM flyover remains available from actual completed output. The
proof does not claim that separate plane service.

The execution proof runs inside a Linux user/network namespace with loopback
only and no external route. Dependencies and browser binaries were staged
before that boundary. This establishes offline execution of this path; sealed
dependency closure and clean-machine installation remain R4.

## Remaining R3 acceptance

1. Retain a complex PDF or denatured HTML agreement with acquisition identity,
   extraction offsets, licensing/provenance and source-to-clause coordinates.
2. Show source clauses beside the graph and preserve their identity through
   scanner, evidence, review and retained production tiers.
3. Navigate repeated/named scopes and aggregate dense graphs at RTS zoom
   levels without confusing display sampling with owner omissions.
4. Measure human-rate overload, frame preparation and sustained rendering on
   declared hardware; exercise transport gaps and delayed observation responses
   on the exact final browser artifact.
5. Exercise graph/cardinality/engine edits as new frozen programmes, controlled
   intervention receipts, and reuse invalidation with the visual workflow.
6. Connect domain-specific legal or fictional panels and the NARS/rete feedback
   gates only through their actual contracts and existing evidence authorities.

The incoming English review and Panels lifecycle branches include their own
exact-artifact evidence. D3M retains those records with their original hashes;
R3a's LLVM browser run does not silently recertify those independent workflows.

## Accepted release evidence

The common/JVM source `e6d480cb4` passed **71 selected regressions**, with no
failures, errors or skips, under OS-denied external networking. All JVM main
and test sources compiled. JS main, Documents and Spacegraph production
applications, Wasm-JS, Wasm-WASI and Linux x64 compiled using staged caches.
The final browser source `2c94dc559` changes only the two native hover files
after those checks; its production bundle was rebuilt and the complete
browser proof rerun. No JVM/shared/build configuration changed.

The final Chromium run passed **11 cases**: live movement, exact retained
transfers, native invocation inspection, explicit replay, reduced motion,
actual pan/zoom alignment, legible native flyover, edited-definition
invalidation, owner reopening without LLVM, browser inspection/playback after
reopening, and legacy R2 inspection without invented replay. Exactly one
program execution was submitted. All module/native owners drained and the
listener joined. Six exact CAS objects, screenshots, two screen recordings,
fixture sources, runtime hashes and network observations are retained.

[Live wire screenshot](evidence/r3a-20261104/accepted/live-consumed-wire.png) ·
[Native flyover](evidence/r3a-20261104/accepted/native-flyover.png) ·
[Exact manifest](evidence/r3a-20261104/verification.json) ·
[D3M integration](../../../../../../tmp/d3m/2026-11-04-airgap-r3a.md).

The final D3M inventory contains 503 remote branches, eight local branches
and origin/HEAD. All 512 refs are ancestors of the release. No textual merge
conflicts occurred; both preliminary harness failures and the earlier
successful capture remain separately identified in the evidence packet.
