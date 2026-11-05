# R3f: wire lifetime and retained outputs

R3f closes wire editing when the existing page owner closes and displays bounded
retained output maps on a matching canvas. A real 257-node native run exposed
two presentation boundaries: the receipt may retain its entire output map by
reference, and the frozen program may exceed the JSON inspection route's work
limit. The final package handles both through bounded, identity-verified reads.
Execution, retained-state ownership and observation budgets remain unchanged.

The [verification packet](evidence/r3f-20261105/verification.json) records the
exact package, source, native values, CAS/WAL audits and failed attempts. The
[D3M record](../d3m/2026-11-05-airgap-r3f.md) identifies the integrated heads.
This increment extends the [R3e geometry contract](15-wire-geometry.md) and
corrects its earlier description of direct wire listeners as page-owned.

## What the lifetime experiment establishes

At R3e, `on(path, "pointerdown", ...)` resolves to the top-level `PatchJs.on`.
It does not register that path with `PanelsPage.on`. A forced-GC diagnostic
therefore did not support the suspected accumulation of page-owned callbacks.
After zero, 25 and 100 redraws, the baseline has 11,134 DOM nodes and 4,815
JavaScript listeners in each sample. Used heap grows from 11,165,024 to
11,292,160 bytes. Those three samples do not attribute that small increase to
a leak, nor establish absence of leaks in a long session.

The actual defect is different. After awaiting `window.closePanels()`, a real
pointer click on a visible wire still deletes it and changes the serialized
canvas and local saved scratch: 63 wires become 62. This is retained as an
OBSERVED baseline failure of closure, not a passing shutdown check.

The correction registers one `pointerdown` handler on the existing SVG under
`p.page.on`. It finds the current `path[data-wire-index]`, verifies that the
path belongs to the SVG and that its index is in the current wire array, then
uses the existing splice/redraw/save behavior. Redraw creates paths without
per-path callbacks. Temporary drag paths and labels have no indexed wire
target. There is no new owner, executor, channel or timer.

In the final package, the same samples have 11,134 DOM nodes and 4,753 listeners.
The SVG has one pointer handler while open and none after awaited close; a
sampled wire path has none. Used heap is 11,261,888, 11,356,776 and 11,431,112
bytes. The actual click after closure leaves all 63 wires and both serialized
and saved definitions unchanged. These measurements prove the exercised wire
boundary. Other controls retain their own handlers; this is not complete UI
shutdown acceptance or a general memory-leak claim.

## Why 257 outputs were invisible

`LcncOutputReceipt` preserves the output in CAS when its ordinary value budget
is exceeded. A small result can remain an inline node map. A larger result
can carry a reference with `retainedCid`, `contentType`, `bytes`, `preview` and
`previewOnly`, with the receipt's `outputPreview` flag set. The original
119,931-byte pilot map contained all 257 actual outputs, including source
value 2 and branch values 2 through 257. The native run completed successfully.

The browser treated the reference envelope as an inline node map. Looking up
actual node IDs returned nothing. The receipt inspector counted the envelope's
five metadata fields as five nodes. The first larger harness also assumed
inline outputs and failed at its first `.value` lookup. These are separate
facts: execution retained all results, presentation failed to resolve them,
and the first driver did not understand the retained representation.

`programOutputs` now recognizes the aggregate reference only in the receipt's
preview form. Before reading it, it requires a complete lower-case SHA-256 CID,
JSON content type and a finite positive integral byte count at most 1 MiB.
`PatchRead.bytes` enforces the existing stream bound, including when the
server omits or misstates Content-Length. The returned byte count must equal
the declared count. Web Crypto computes SHA-256; fatal UTF-8 decoding and JSON
parsing follow. The decoded output map must be an object, not an array or null.
The decoded row values are preserved; no normalization makes a value match.

Only after that read and validation does the caller recheck the current page,
trace owner, canvas object and semantic program definition. A changed owner or
program causes an explicit refusal before the new output snapshots are
installed. The existing per-node presentation still checks ownership during
its asynchronous work. Display snapshots do not become execution inputs;
the daemon's retained frame remains authoritative for any later pulse.

`retainedJson` supplies the same 1 MiB and SHA-256 boundary for frozen programs
used by explicit recorded-output, recorded-activity and review-task handoffs.
Their callers retain the frozen-program comparison and current view/owner
checks. This avoids the `/api/lcnc/content` route's separate `ValueBudget`
traversal refusal for the 257-node frozen program. It does not raise that
route's limit, remove receipt truncation or increase execution budgets.
A larger JSON object is refused by the bounded display read; it is not silently
partially displayed as complete. Chunked output paging remains future work.

The receipt view says that aggregate node outputs are retained separately and
explains the bounded canvas read. Retained prompt/subprogram version envelopes
also get a retained-reference entry instead of fake version links named after
metadata fields. Explicit generic JSON/Confix inspection still has its own
bounded route and can refuse a large projection. Reopening recorded outputs
never invokes Run automatically.

## A real delayed read, edit and restoration

The accepted ownership case runs 257 native scalar calls once. Automatic
projection displays all actual values. The inspector identifies the retained
map correctly and does not read it merely because its receipt is selected.
The explicit Show action reads and verifies the frozen program, then starts a
second request for the output map. The test holds that real request before
forwarding it to the real server. While held, an ordinary source parameter
changes from 1 to 9.

The original request then proceeds unchanged. Its HTTP 200 bytes match both
CID and declared length, but the changed canvas causes “Program or output
owner changed during retained outputs read.” The post-edit snapshots remain
unchanged by that delayed response. Restoring the parameter and choosing Show
again performs a third real map read and displays the original 257 values.
Independent CAS/WAL inspection confirms one run submission, one completed run,
three map reads and unchanged retained documents. No response is fabricated.

The trace reservation in this explicit inspection has no executing definition.
Consequently, this case exercises the semantic canvas comparison, rather than
relying on an active execution trace becoming invalid. A program-name-only
edit was an invalid early test assumption: `sameDefinition` deliberately
ignores that label, so showing unchanged semantic results was correct. The
failed case remains evidence; the accepted case changes an execution parameter.

## Same runtime at two graph sizes

Six fresh JVM/browser/store runs use the exact extracted release archive.
The order is 65/257, 257/65, 65/257. Both fixtures have one native scalar source
with one outgoing wire to each native scalar branch. The 65-node fixture has
64 wires; the 257-node fixture has 256. Every computed value is checked against
retained execution and the actual canvas snapshot and rendered result. The
larger fixture resolves its output map through an actual independently checked
HTTP read. The browser's own read must also finish and project all values.

The frame window starts before Continue and ends 500 ms after both complete
canvas projection and the post-receipt keyboard controls have settled. Its
length varies by run. `requestAnimationFrame` intervals therefore describe
this execution-to-projection window, not isolated preparation work or sustained
steady-state frame rate. There is no Gradle build, archive compression or
profiling during the six trials. The host has a four-CPU quota and 16 GiB limit.

| Nodes / repetition | Intervals | p95 (ms) | Maximum (ms) | Reported keyboard durations (ms) |
|---|---:|---:|---:|---|
| 65 / 01 | 67 | 33.4 | 116.7 | 80, 72, 64, 56 |
| 65 / 02 | 63 | 50.0 | 116.7 | 56, 104, 72, 88 |
| 65 / 03 | 53 | 50.0 | 133.4 | 80, 112, 120, 104 |
| 257 / 01 | 72 | 50.1 | 383.3 | 96, 88, 112, 112 |
| 257 / 02 | 50 | 83.4 | 383.2 | 104, 96, 352, 248 |
| 257 / 03 | 64 | 66.7 | 350.1 | 440, 232, 144, 160 |

The 65-node maxima are 116.7–133.4 ms; the 257-node maxima are 350.1–383.3 ms.
The larger graph is materially less responsive in these trials. This compares
cardinalities under one runtime, not R3e versus R3f speed. It does not establish
10k/50k capacity, recursive graph performance or acceptable game-like rendering.

Trusted CDP Space events exercise the existing Resume/Pause display control
after the real receipt body reaches the controller. Chromium Event Timing
reports thresholded, quantized durations. The largest reported keyboard
duration is 440 ms in a 257-node trial; the adverse result remains in the table.
The probe now also records `performance.now()` inside the rAF callback, making
`handlerToCallback` an elapsed callback wait. The older `handlerToFrame`
subtraction remains raw diagnostic data and can be negative because the frame
timestamp precedes the callback. Neither is paint latency. CDP round-trip
includes transport; these inputs were not physical input queued before an
earlier receipt stall. All clocks and raw arrays are retained separately.

All fitted endpoint pairs meet actual port centers within one screen pixel:
64 for the small fixture and 256 for the larger one. Zoom/resize checks count
only pairs with both endpoints visible and report clipped pairs explicitly.
The larger runs have 94–110 eligible pairs after zoom and 46–54 after resize.
Each run checks zero overlapping node pairs, then clicks an actual exposed
wire: exactly one wire disappears, every node remains and the old observation
trace becomes invalid without another execution.

Successful results do not reconstruct missing wire events. Every final ledger
retains 29 observations; owner omissions are 165 at 65 nodes and 741 at 257.
Every displayed selection must match an actual received debug or retained event,
and every final retained selection must be represented. Display markers remain
bounded to 128. The human display clock and omission disclosure remain separate
from native execution time and successful-output counts.

## Failure evidence and exact validation scope

The packet keeps the original aggregate-map pilot, the frozen-program
`work_limit` refusal, an incomplete harness interruption and a later invalid
name-change expectation. It also keeps a scale driver that had already passed
all value/layout/geometry checks but still expected 63 wires after deleting
one from a 256-wire graph. The correction expects 255; it does not remove the
actual pointer action or weaken the single-deletion requirement. An incomplete
attempt reported driver exit zero but lacked its restoration evidence; the
independent audit refused it. Zero exit alone is not acceptance.

The final selected JVM suite passes 316 tests across 59 suites, with seven
conditional external native-solver skips and no failures. The affected
production Spacegraph bundle is compiled in the same offline build. Common,
Documents, Wasm-JS, Wasm-WASI and Linux-x64 production/build inputs remain those
verified in R3e; their identity continuity is explicit rather than a claim of
new runtime testing on every target.

The final package passes 74 browser checks, with 15 separately recorded
measurements: six scale frame windows, six scale control probes, and the
lifetime case's frame/control/redraw records. Ten document checks include
reopening without LLVM/miner; four earlier retained-reference checks preserve
stale-read refusal; four aggregate-output checks add the larger ownership case;
the wire-lifetime case and six cardinality trials supply the remaining checks.
Every accepted case has a separate after-close CAS/WAL and process audit.

D3M also integrates native KIF proof inspection controls and inherited proof
replay evidence. The new controls bind the approved selection, source witnesses,
revision, credentials and typed query rows; stale or uncertain currency clears
the optional view. They compile in this exact bundle. These R3f browser fixtures
do not exercise their new authenticated native-solver actions. The seven
conditional skips and this UI coverage limit remain explicit. Imported v87
evidence retains its own tested/source identities and is not relabeled as an
R3f runtime check.

## Packaging and reproduction

Tested source is `9111a78b733dc30736d066b7d2e5fc9ed87e882f`. The source overlay
reconstructs its complete tracked tree from the R3e final source archive,
including inherited publication evidence and strict-row assertions. A temporary
Git index verifies the applied tree exactly. Later R3f documentation/evidence
commits have separately checked production-source continuity.

Use the following release archives; earlier R3f, final and integrated candidate
archives remain preliminary and are preserved with their own hashes.

- Runtime: `airgap-document-r3f-release-runtime-update-2026-11-05.tar.gz`, 2,439,287 bytes, SHA-256 `8f52d2c1007efbdc43f10366b45e236799189d72553046df4892ca70c5c57887`.
- Source: `airgap-document-r3f-release-sources-update-2026-11-05.tar.gz`, 41,963,794 bytes, SHA-256 `30600d0ed5f7e9eefa3b9cd355853d645d647a7041a80884c025b9e238369029`.

Runtime extraction order is R4f full Linux-x64 runtime, R3e **final** runtime
overlay, then R3f **release** overlay. Verify each archive against the named
chain in `UPDATE.json`. Extract into a fresh installation, not over running
state. The overlay changes only the web jar and root manifests; JVM jars,
dependencies and native modules remain the exact R3e bytes. Two assemblies
from pinned bytes are identical. The acceptance runs use the actual archive
extracted over the verified base and check complete membership, modes and hashes.
Changed paths are unlinked before extraction when immutable bases use hardlinks.

Source order is R4e base → R4f → R3c final → R3d final → R3e final → R3f release.
Do not separately apply the R3e strict-row supplement; its assertions are already
included in the R3f full-index binary patch. Vendor/native source rebuild
closure remains open. The cumulative blueprint PDF and incremental evidence ZIP
are separate from the runtime/source overlays; earlier evidence stays in the
R3e ZIP and its documented predecessor chain.

The packet contains inert copies of `run.py`, all browser drivers, independent
auditors, package/source assemblers, `accept.py` and `comparison.py`. Restore
those outside the checkout, adapt their host paths, verify the package first,
and preserve the loopback-only namespace, read-only root and separate writable
state. `run.py` starts the actual packaged AirgapDocumentMain and drains/reaps it
normally. The network audit records no route and an unreachable external connect.
Host kernel, Chromium and bubblewrap are external inputs, not bundled guarantees.

## Next mechanics for the blueprint programme

WP12/WP13/WP24 now have an actual aggregate-output boundary and a measured
257-node fixture. The next rendering increment should profile the exact released
bundle before choosing which work to change: repeated whole-canvas serialization,
semantic comparisons, synchronous node rendering and quadratic overlap resolution
are distinct costs. Preserve the same six-run protocol and retained native
outputs; a sampled profile locates work but is not a speedup trial.

A scalable display needs a bounded scene projection with explicit cancellation
at the existing page/trace owner. Any incremental projection must recheck that
owner between batches and at completion, avoid presenting stale partial output
as a completed canvas, and make receipt loading/projection progress visible.
A revision fast path must cover every semantic edit, including edits not yet
saved, while retaining semantic equivalence for layout-only changes. Spatial
indexes can reduce overlap/wire work only with measured invalidation on moves,
resizes, nested-scope changes and rich output growth. Do not add a second graph
executor or animate inferred events to fill observation gaps.

Retained maps beyond 1 MiB need an indexed, identity-bound paging contract before
a wizard can claim arbitrary cardinality. Page membership/order, whole-map CID,
node selection, omission counts, cancellation and restored-definition replay
must be designed together. The present bounded refusal is the truthful boundary.
This applies equally to legal panels, evidence quads, fictional state and optional
model outputs: large retained state and visible scene state have different budgets.

The native KIF controls need their own exact-package authenticated action cases,
including typed substitutions/premises, source focus, a genuine guarded refusal,
credential/goal changes during reads and stale-source currentness clearing.
That work is separate from solver proof correctness and from the R3f scalar
visualization acceptance. It should reuse the existing proof-row contracts and
retained review owner rather than duplicate them in a new coordinator.

The programme's calendar and frontier-model budget remain the conditional estimates
in the cost workbook. These browser timings are not engineering effort, agent
billing or evidence that the whole demonstrator is finished. Recalibrate future
work packages from recorded implementation effort and tariff data, while keeping
CPU runtime measurements separate from development-model costs.
