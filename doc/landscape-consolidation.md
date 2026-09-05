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

## Sticky Editing and Recursive Scale

### Shared Parent Handle

Fit, FD, Shake and container dragging resolve the same view-owned handle:
program identity plus an optional local container id. Main headers, scope
D-tabs and node headers select it; a leaf selects its enclosing parent. The
toolbar grip retains the selected path at satellite scale. Camera pan, zoom-out
and board fit do not change it. Explicit main selection resets it to that main.

Fit frames the selected container. FD places its direct children and resizes
only its ancestry, retaining descendant layouts and the recursive 560 by 360
interior bounds. Dragging the grip or container header moves that assembly in
its enclosing coordinate space; leaf dragging uses the same parent's space.
Dragging a main moves its mount, not its document-local coordinates or draft.
Cancelled drags restore their start positions. Empty landscape dragging is
still camera navigation, not an implicit document operation.

Meta + left-drag (Command on macOS) anywhere in the landscape moves the
already-selected parent, including when a node, port or control covers its
handle. Capture precedes descendant handlers and does not select the object
under the pointer. It stops camera momentum, reuses the existing parent drag,
and suppresses the trailing pointer click/double-click even if Meta is released
first. Ordinary drags and keyboard control activation retain their behavior;
pointer cancellation restores the starting placement.

Shake sends the whole program with the selected parent id. The shared Kotlin
matcher keeps ancestry and existing inbound bindings, but proposes new cables
only between descendants of that parent. External sources remain explicit
patches. Both HTTP surfaces parse the same options and refuse nonexistent or
non-container targets. The response confirms the parent; a server that ignores
scope selection cannot silently broaden a client mutation. A changed selection
invalidates pending results, even within the same main.

Handles resolve by identity after remounts. Removing the selected scope reports
a fallback to its main. Main ownership, local command targeting, navigation
history and server execution authority remain distinct responsibilities.
Draft/undo remounts retain authored child placement rather than rerunning the
default concentric arrangement; reconnect retains a surviving selection.

Verification on 2026-09-05: the JVM gate and 37 targeted JVM tests passed;
19 browser-helper tests passed. Live checks demonstrated nested selection,
scope-confirmed no-op Shake, FD changing only two selected children, board
fit retaining selection, container drag and undo retaining prior FD placement,
and main drag without a draft. IntelliJ's current `LcncTreeShake.kt` inspection
reported one weak warning and one style suggestion; repository-wide warnings
remain outside this increment's clean-build claim.

### Scope Inputs and Connection Diagnostics

Scope sockets are projected from direct child `scope.in` and `scope.out`
declarations, alongside the existing envelope ports. Captions are not sockets:
named ports must have actual drag targets and cable endpoints. Editing a
declaration refreshes its enclosing ring's sockets. An omitted `scope.in`
default remains absent when the editor opens; an explicitly empty default
remains an empty binding.

`preset-scope` now constructs an argument map using a scope's composed
`returns`, connects it to both nested `args?` inputs, and demonstrates a
named `text` input. It remains runnable with no registered leaf runners.
The root `text` binding defaults to `hello`; an invocation's `text` overrides
that default and reaches the final result.

The harness reuses the existing server Shake verdict renderer with namespaced
node identities. A no-op does not remount nodes, create a draft, or disable
Run. Results arriving after an edit or main-selection change are discarded.
The Connections report exposes defaults, frame bindings, optional inputs and
unresolved cable gaps. Rows focus the actual node; the source action opens the
existing compatible-mate picker. Choosing a source authors a draft only.
Neither a successful pairing nor a gap-free cable check authorizes execution.

Optional inputs remain untouched by default. A connected argument map also
prevents Shake from guessing a named wire that would override that map. Map
key presence remains a runtime check, not a conclusion inferred from geometry.

Verification on 2026-09-05: the JVM build gate, 34 targeted JVM tests and 16
browser-helper tests passed. An isolated, dispatch-disabled preview completed
the demo with `result: hello`; a no-op preserved Run availability, nested
socket focus worked, and the source picker created a reversible unpublished
patch. At 390 by 844, the report's client and scroll widths both measured
358 pixels. Existing editor warnings are not a full-project clean bill.

### Editing Ownership

The selected main remains the mutation owner during zoom-out, panning, board
fit and terrain navigation. Layout and pairing commands use that owner, not
whichever panel happens to occupy the most screen area. Explicit program or
scope focus transfers ownership. Wheel zoom-in transfers it only when the
pointer is inside another main covering at least 55 percent of the viewport.

Readable nodes in the active main retain their interactive DOM representation
when zoomed back out; another main taking ownership releases that detail latch.
Other programs remain inspectable spatial context, with inert node controls.
This changes editing focus only, never server execution authority.

Every scope fits its intrinsic child layout into a maximum 560 by 360 local
interior. These scales multiply down the actual scope tree. Descendants and
internal links remain miniature spatial structure until the pixel cutoff;
zoom reveals the same nodes, not a replacement overview document. The existing
non-harness ring camera keeps its behavior. Program frames use content bounds
without the former 1550 by 720 floor, and mount-origin normalization does not
rewrite document-local coordinates. Object and blackboard regions start beyond
the complete program region, not just the first program.

Browser checks retained all 360 node identities, kept the active panel's seven
nodes visible after zoom-out to roughly 20-pixel leaf widths, and released
them on main handoff. Five scopes in the large preset were checked for child
containment after stabilizing intrinsic widths during layout. These checks
supplement the helper tests for sticky ownership, recursive scale and bounds.
The follow-up verification passed 12 JavaScript helper tests, the JVM build
gate and nine page/route tests. A 390 by 844 mobile check also exercised scope
focus and miniature containment.

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

### Topology Masks And Runtime Evidence

The legend also has an independent activity highlight mask: Operational,
Waiting, Completed, Blocked, Inert and Unknown. Unchecked activity states are
de-emphasized, not removed, so scopes, cables, node identity and the selected
mutable parent remain navigable. Counts cover mounted program assemblies,
their nodes and non-LCNC board facts; blob-category counts remain separate.
The selected-program button and per-node lamps open the evidence behind a
status, including version identity and the recorded receipt when available.

Program activity is projected from version-matched durable run receipts.
An active older invocation is not hidden by a newer completed invocation.
Disconnected or deadline-expired runs become Unknown, not indefinitely green.
Unpublished grafts and versions without matching receipts are also Unknown;
a receipt for another version is retained as explicitly historical evidence.
Completed means a recorded invocation or event, not ongoing activity, model
quota exhaustion, or proof that an assembly cannot run again.

Node status is deliberately narrower than program status: a matching output
proves a result (including an empty output), a named validation violation
identifies a blocked node, and an armed browser timer/subscription proves only
that local source is operational. A running program does not prove each child
is currently executing. The daemon does not yet publish per-node execution
phases, so unresolved Hermes/mux child phases remain Unknown. Inspection-only
assemblies are explicitly Inert. Status freshness is refreshed locally once
per second without polling another server or invoking a model.

NARS and kanban records sharing an explicit angular atom or job identifier
receive navigable reference links, including cross-territory arcs. Reference
projection is bounded to 16 records per identifier and 128 links. Matching
words alone cannot create these links. Existing term associations remain a
separate relationship; neither kind grants causal support or execution authority.

The shared Graal landscape exposes independent Git, Classes, Binaries, Other
blobs, Heap blocks, Actual heap and Allocations checkboxes. Store classification
uses paths and known extensions, not content inspection; opaque blobs remain
Other. Masks affect drawing, hit testing and reference overlays without rebuilding
geometry, changing object identities, or selecting a mutable program. The local
view preference persists separately from program documents. The palette's
`graal.heap` executable node remains independently available.

Runtime rows reuse the terrain renderer and open their measurement provenance,
not Couch document or content routes. Actual heap means the supplied live class
histogram. Allocations are sampled JFR attribution, and Heap blocks are measured
GC pool occupancy, not physical object addresses. These projections are different
measurements of the same process and must not be summed as a total footprint.
No object-reference graph or physical heap dump is claimed by these views.

The legend reports unavailable sources explicitly. The daemon's histogram
self-attach opt-in remains unchanged: it has a recorded JVM-freeze risk, and
the UI does not enable it. Heap data loads once and refreshes on explicit demand,
with one refresh in flight, ten-second request deadlines, a 1 MiB response limit,
256 rows per class lane and 64 pool rows. Failed refreshes remove that source's
old rows rather than presenting them as current. Store-only and runtime-only
availability are independent. Runtime timestamp fields are snapshot times, not
object allocation times or a claim of continuous live inspection.

Accept each checkpoint on evidence, not on a route being renamed or a feature
being described. Review legacy Graal operational parity and the remaining
budget/dispatch boundaries before declaring the whole consolidation complete.

### Node Chrome And Corner Resize

Scope headers identify the actual node at each depth. Dashed binding/yield paths
project the frame boundary: named inputs to direct scope.in children, and direct
scope.out children to named outputs and the collected returns map. A connected
args map gets a distinct dotted candidate path; it does not prove that a key is
present or override a named input. Guards are not bindings. These projections
are capped at 512 paths for the selected program, never authored as cables, and
do not assert successful execution. Hidden interiors suppress their paths.
Port labels expose returns field names; leaf captions distinguish defaults,
inherited bindings, and the owning return map. In preset-scope, the root text
default is `hello`; the runner's existing test also supplies `patched` and
expects that value to return through the same nested scopes. No return value
is hardcoded by the new display projection.

The header chevron collapses or expands content; the separated X deletes the
node and its cables. Both are native keyboard-activatable buttons. Collapse
updates the document's collapsed flag and hides only that node's own interior.
The bottom-right bracket is exclusively a resize grip; the activity lamp sits
beside it, not over it. Pointer capture keeps a drag attached to that grip.

Corner motion divides by camera zoom and all enclosing ring scales. Scope
contents fit inside the resized frame without changing child coordinates or
connections. Frames are bounded to 160-1600 by 80-1200 local units (ordinary
nodes retain their content minimums). Escape, pointer cancellation, lost capture,
or a changed parent cancels the gesture. Successful resizing recomputes ancestor
bounds without arranging siblings. Frame sizes are session-local presentation
preferences, retained across board remounts in a bounded 1024-entry cache; they
do not alter program identity, publish a draft, or persist across a page reload.

### Socket Drag Edge Scrolling

Socket drags pan the existing camera inside a 56-screen-pixel edge band,
shrinking to a quarter of the canvas dimension on small viewports. Speed ramps
quadratically to 320 screen pixels per second, including a shared diagonal cap.
Elapsed frame time is capped at 50 ms so a delayed frame cannot jump the camera.
The dashed endpoint stays under the pointer through camera motion and redraws;
the drop uses the resulting world coordinate without changing authored nodes.

Center positions, outside positions and overlaid controls pause edge scrolling.
Release, Escape, cancellation, lost focus, hidden pages, removed sources and
changed parent selection end the gesture without momentum. Only the initiating
pointer can complete it. Outside drops do not create a node or open a chooser.
Creation choosers stay within the window with scrollable options. Edge scrolling
does not change compatibility checks, scope rules or Shake coverage.

### Invocation Bindings And Coroutine Identity

The invocation editor supplies typed JSON values through the existing run
request's `inputs` object. Omitted and explicitly null bindings stay distinct;
these session-local values do not edit or publish the program. Root `scope.in`
declarations supply the initial rows; additional names can feed inherited scope
bindings. Invalid JSON blocks submission. The server rejects a non-object
invocation envelope.

The runtime still uses Kotlin's `CoroutineContext` and the singleton
`LcncScopeFrame.Key`: installing another Element replaces that Key for the
child's dynamic extent, and coroutine unwinding restores the enclosing Element.
The frame's explicit parent pointer supplies lexical binding lookup; titles do
not supply coroutine Key identity. Resolution checks the installed Element and
records the actual binding owner, source, value and node path. These bounded
records accompany the existing version-attributed durable run receipt, not a
second execution history. Declaration paths remain declarations, not evidence.

`ccek.incarnate` construction precedence is named wired input, then `args`
object field, then stored parameter, then default. Its argument rows report the
validated effective values and overridden defaults. Invalid settings fail before
construction. A repeated service title reuses its existing instance only for
identical construction settings; incompatible settings fail explicitly instead
of reporting a configuration that was never applied. This service-handle
registry is separate from Kotlin coroutine context identity.

Runtime tests exercise real CCEK construction and signalling, same-Key
replacement/restoration, malformed scope inputs, and HTTP-to-durable-receipt
attribution. The invocation table rejects evidence from another program version
and older revisions of the same run. It reports unbound values distinctly from
resolved nulls; it does not claim an unwired argument caused an output.
