# R3: visible document traversal — implementation handoff

Status: planned; source inspection at the R2 combined commit
`f91da5d6e2765debe3c85c0b2bcd081b5e7c90ea`. This is the next release gate,
not a claim that the animation or final browser acceptance already exists.
The [visualization blueprint](../lcnc/airgap-demonstrator/07-visualization.md)
owns the wider design. This handoff identifies concrete seams after R2.

## Smallest useful demonstration

Open a retained source beside its frozen graph. Run the actual Confix scanner,
inspect its copied geometry, feed a measured count into another real engine,
and reopen the exact receipt after owners close. The R2 fixture already
produces seven rows and a scalar result of 107. R3 makes that causal path
visible and interruptible at the boundaries the executor actually supports.
The synthetic Coinbase-shaped JSON is a transport fixture, not the Coinbase
agreement or evidence of legal understanding. A subsequent content fixture
must retain a denatured agreement archive or complex PDF plus extraction
provenance before showing source-clause traversal.

The operator must distinguish live observation, delayed presentation and
historical receipt replay at a glance. A wire spark means a particular output
was consumed by a particular invocation. A node glow means an observed phase.
Neither signifies truth, strict validation, approval or native instruction
completion. A successful Confix invocation with `ok=false` is a completed
call with a scanner fault; render that data result explicitly.

## Existing code to extend

| Responsibility | Existing implementation | Required increment |
|---|---|---|
| Actual input selection | `src/commonMain/kotlin/borg/trikeshed/lcnc/LcncRunner.kt` | Observe selected fed wires after ordinary input resolution; preserve scope and last-fed/many-input semantics |
| Output owner and retained origin | `LcncScopeFrame.kt` in the same directory | Reference the owner already resolved; never recompute a producer from the edited canvas |
| Bounded invocation receipt | `LcncInvocationLedger.kt` | Retain consumption/timing observations within explicit budgets and report omissions |
| Cooperative controls | `LcncDebug.kt` | Extend existing observations; keep commands on the existing owned execution and bounded channel |
| Run submission and final output projection | `src/jsSpacegraph/kotlin/borg/trikeshed/web/spacegraph/PanelsRun.kt` | Bind presentation to admitted program/run; `_lastOut` remains display-only |
| Live control/observation | `PanelsDebug.kt`, `PanelsOroboros.kt` | Project measured phases and consumption into the selected graph/scope |
| Durable receipt inspection | `PanelsReceipts.kt` | Render LLVM-specific observations and replay identities |
| Canvas/flyover | `PanelsBlip.kt` and existing Panels geometry | Add readable measured summaries and coordinate-safe overlays |

Do not introduce another executor, graph runner, native context or UI-driven
node evaluation. All source intake, scanner work, scalar calls and downstream
review remain under existing userspace owners. UI scheduling owns only display.

## First increment: correct LLVM inspection

The current receipt renderer reads `runtime.language`, `tier`, `vmId`, `stats`
and `pointcuts`, then describes observations as captured before release.
R2 emits `backend=graalvm-llvm`, a pinned module CID, ABI, timings and post-free
allocation observations. The generic label is therefore unsuitable.

Dispatch presentation by the retained runtime's actual discriminant. For LLVM,
show module CID, ABI, engine/compiler versions, queue/call/scan durations,
source CID, row/fault result and copied-buffer measurements. Unknown/missing
fields say unavailable; missing is never zero. Preserve raw invocation JSON
and its CAS link. Use `textContent` for retained strings.

For Confix, `liveBytesAfter=0` means the bridge's one owned allocation was freed;
it does not mean the VM or process uses no native memory. `guardsVerified`
means observed sentinels passed. `internalProgress=unobserved` means exactly
that. `pauseBoundary=between-scans` prevents a source-step affordance for LLVM.
The native/JVM YAML disagreement for `v: 1e++5` should be inspectable with both
engine identities and unchanged tags, not smoothed into a single result.

The inspector must keep the incoming D3M lifecycle fixes: immutable receipt
ownership follows its drawer, selected receipt and connected section, while
workspace inspection follows canvas revision. Changing a canvas must not
rewrite a historical receipt's captured-program comparison. Closing a drawer
or selecting another receipt prevents a delayed response from repainting it.

## Second increment: observable transfers

The current debugger keeps at most 128 observations and reports dropped rows;
its field whitelist contains location, phase and status, but no timestamp or
wire-consumption record. The invocation ledger separately bounds each runtime
snapshot. Those limits are evidence constraints, not an excuse to invent
missing activity.

Add a compact observation at the actual input-selection boundary. Proposed
fields, to be reviewed as a versioned extension before implementation:

| Field | Meaning |
|---|---|
| `sequence` | Monotonic observation sequence within this run, independent of nested walk ordinals |
| `runId`, `programCid` | Admitted frozen execution, never the currently edited graph |
| `consumer` | Scope path, invocation ordinal, parent, iteration and node ID |
| `producer` | Resolved output owner plus producing invocation/frame identity |
| `wire` | Frozen from-node/port and to-node/port; preserve authored wire index if endpoints repeat |
| `selection` | Single selected value or position in an explicitly many-input port |
| `elapsedNanos` | Owner's monotonic duration since run admission, not wall-clock chronology across machines |
| `phase` | Observed selection/entry/settlement; no synthetic percentage |
| `evidence` | Existing retained value/source identity when available; otherwise an explicit unavailable reason |

Emit only after the executor has selected the actual input. For single-value
ports, a superseded earlier feeder must not look like a consumed value. For
many-input ports, preserve selected positions. A skipped node or missing
required input must not produce a success spark. Nested rings use the resolved
scope frame; a graph edge drawn between repeated node names is insufficient.
Debug intervention must identify the changed input and its control receipt,
not silently attribute the replacement to the original producer.

Preserve durable observations needed to explain the accepted result. The live
projection may retain a bounded suffix and aggregate counts. Publish first and
last sequence, count, omitted count and coverage for every aggregate. A gap
renders a gap. Reconnection requests the same run; it never resubmits work.
Avoid serializing full documents, secrets or native pointers into telemetry.
Use existing CAS identities and bounded previews.

## Human-rate display and clocks

The renderer uses one page-owned `requestAnimationFrame` loop. It transforms
observed transfers into short visible trajectories along existing wire
geometry. Their display duration is presentation policy, not the measured
engine duration. Label replay and slow playback explicitly. Preserve the
actual scan/call/queue durations in the inspector even when a microsecond
operation stays visible for a readable interval.

Start with configurable limits: at most 128 visible trajectories, a 256-entry
pending display queue and a 4 ms per-frame preparation budget. These are
proposed initial bounds to measure, not achieved performance. Beyond them,
aggregate by scope/wire/time bin with an exact represented count and omission
indicator. Do not grow an unbounded animation backlog to catch up with the
agent rate. Reduced-motion mode uses static edge highlights and counts.

Server monotonic elapsed time orders one owner's work. Browser frame time
controls motion. Wall-clock labels support human navigation only. Do not
subtract clocks from different owners to infer latency. Show transport lag
only when its measurement basis is known. Node fill may indicate observed
queued/running/settled phases; no countdown or percent-complete without a
real bound from that engine.

Pan, zoom and nested-scope changes recompute display coordinates without
changing event identity or re-running work. At wide zoom, aggregate to scope
counts and status; at middle zoom, show node/edge summaries; at close zoom,
show ports and one invocation's provenance. Picking resolves the frozen run,
path and ordinal before opening evidence. Edited graph layout may be used only
where the definition comparison and identity mapping remain explicit.

## Pause, rescaffold and resume

Use the existing debugger/control route. Pause requests wait for a supported
execution boundary. An already admitted native scan finishes and frees its
allocation before that boundary is acknowledged. The UI distinguishes request
pending from pause observed. Stopping observation does not cancel or resume the
run. Failed command delivery has an unknown outcome until a later observation;
never automatically replay a side-effecting control.

A schema, filter, algorithm or cardinality edit creates a new authored program
identity. Freeze it and validate its contracts before admission. Do not mutate
the frozen program of the old run. Existing explicit debugger interventions
retain their own identities and invalidate ordinary reuse assumptions. Resume
of retained work uses existing frame/provenance rules; a visual replay cannot
supply executable state. A scanner fault can branch only through real graph
logic that inspects its fault/`ok` ports.

## Release evidence and order

1. Correct the runtime inspector and retain an exact browser artifact/screenshot
   for R1 and R2 receipts, including absent fields and the YAML disagreement.
2. Add selected-wire observations under the existing execution owner; exercise
   duplicate feeders, many-input order, skipped nodes, nested repeated IDs,
   iteration, cancellation and intervention. Assert identities against actual
   consumed inputs, not renderer expectations.
3. Project those observations into sparks, readable phases and flyovers. Check
   reduced motion, pan/zoom, overload and observation gaps without extra runs.
4. On the exact combined browser artifact, run the R2 graph once; pause at a
   supported boundary, continue, inspect the copied result and reopen its CAS
   receipt after shutdown. Compare visible selections with retained records.
5. Exercise stale/delayed responses, drawer closure, run replacement and
   disconnected observation. Check no repaint into another run and no implicit
   execution from hover, zoom, replay or reconnect.
6. Run the same accepted path with external networking unavailable using staged
   dependencies. This is R3 evidence; sealed clean-machine closure remains R4.
7. D3M-integrate all enumerated branch heads, review intent, verify the combined
   artifact, retain failures and fixes, then publish the next ordinary master.

The incoming English taxonomy UI has a separate final-artifact acceptance gap:
authenticate, declare, prepare, freeze, approve and query, then invalidate source
or credential and test held responses after closure. Retain that gap until its
own browser evidence passes. It must not be hidden by R2's JVM regressions or
by historical copular/Panel captures.
