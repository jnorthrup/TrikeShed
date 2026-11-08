# R3d: bounded receipt rendering

R3d reduces the long browser stall observed when a 65-node native run returns
its receipt. It reuses graph comparisons only while their serialized input
is identical, and batches node measurements before writing overlap positions.
The same retained results, source ownership checks and observed-transfer
records remain authoritative. This increment advances the human-rate
visualization gate; sustained frame-rate acceptance remains open.

The [verification packet](evidence/r3d-20261105/verification.json) separates
diagnostic profiles, preliminary candidate runs, final extracted-runtime
checks and frame measurements. The [D3M record](../../../../../../tmp/d3m/2026-11-05-airgap-r3d.md)
retains the incoming branch inventories and one equivalent-fix conflict.
The previous [R3c workflow](13-human-rate-inspection.md) describes display
pause, pinned observations and receipt reopening.

## Where the time went

A Chromium CPU profile and performance trace were captured around the real
receipt return, with the graph held before its first native node. Continuing
execution invokes the scalar module 65 times; no result or HTTP response is
fabricated. The browser's packaged source map ties sampled JavaScript frames
back to Kotlin sources. The profiles include recording overhead and are
diagnostic observations, not portable timing guarantees.

In the old renderer, `programOutputs` accounted for 1,100.536 ms of sampled
inclusive CPU time. Its `current()` ownership/definition check accounted for
818.011 ms. That check repeatedly serialized, parsed and reified the same
whole graph, then compared its definition for each projected node and later
callback. The semantic comparison's sampled time is nested within that total;
inclusive values must not be added together. Repeated rectangle reads also
accounted for 222.553 ms of sampled self time. The trace showed layout work
interleaved with position writes.

The two-file candidate profile measured 92.535 ms of sampled inclusive time
in `programOutputs`. Its frame observer still recorded a 150 ms interval.
Both profiles, source-map decoder, full CPU samples and compressed browser
trace are retained. These numbers identify removed repeated work; the final
runtime comparison below assesses observable frame gaps without the profiler.

## Exact comparison reuse

`Panels.programOutputs` retains a local serialized snapshot, panel name and
semantic comparison result for one projection call. Every `current()` call
still checks its existing owner callback, page closure and graph object
identity. It then serializes the current canvas. If the serialized bytes or
name differ, it reifies and compares the new definition before accepting a
result. Otherwise it reuses that exact comparison result.

This is local memoization of a pure comparison. It introduces no global
cache, authority token, execution owner or persistent state. An edit need not
have reached `save()` to change the serialization. Returning to the original
definition allows a fresh matching read. Camera/layout changes may cause a
new comparison; the existing semantic comparator still ignores presentation
fields and compares executable definition fields. Stale work is never
authorized solely by a cached boolean.

The retained browser ownership scenario specifically exercises this boundary.
Two real `json.value` runs produce a retained programme reference. Showing
recorded outputs reserves the existing presentation owner without starting a
trace definition, so trace edit invalidation cannot substitute for this
comparison check. The first real CAS read succeeds, and a camera/layout change
still permits the second. Playwright holds the third real GET request while
the operator edits an ordinary JSON parameter, then forwards the unchanged
request to the real server. Its response hashes to the expected retained CID,
but the changed definition refuses to display it. Restoring the original
parameter permits the fourth read. There are exactly two Run submissions;
the four inspections execute no programme.

The whole canvas is still serialized on every check. The change removes
repeated reification/comparison on equal snapshots, but does not establish
constant-time validation or eliminate all graph-size-dependent work. A future
definition revision counter must account for every mutation path, including
unsaved edits and asynchronous callbacks, before it can replace this check.

## Layout reads before writes

The existing top-level overlap pass keeps authored x-order and its bound of
three passes. Each pass measures width and height once per eligible node,
before writing any new position. Pairwise overlap decisions update local
rectangle coordinates. Changed x positions are then applied together.

Each later pass remeasures dimensions. This matters because the stylesheet
gives nodes a minimum width with automatic sizing: moving a node can change
its available width. Reusing dimensions across all passes would silently
change that behavior. Child-ring layout, wire routing and the existing page
lifecycle remain under their current owners.

The final 65-node fixture checks actual browser rectangles after receipt
projection and reports zero overlapping pairs, with a 0.5-pixel tolerance.
That demonstrates this fixture's result, not a general packing guarantee.
The pairwise algorithm remains quadratic and the three-pass limit can leave
overlap in other arrangements. No dense recursive-layout claim follows from
this bounded measurement.

## Comparable frame measurements

| Runtime / repetition | Samples | p50 (ms) | p95 (ms) | Maximum (ms) |
|---|---:|---:|---:|---:|
| R3c / 01 | 110 | 16.7 | 33.3 | 1049.9 |
| R3c / 02 | 111 | 16.7 | 33.4 | 1000.0 |
| R3c / 03 | 112 | 16.7 | 33.4 | 983.3 |
| R3d / 01 | 166 | 16.7 | 16.8 | 150.0 |
| R3d / 02 | 163 | 16.7 | 33.3 | 150.1 |
| R3d / 03 | 168 | 16.7 | 16.8 | 116.7 |

These are external `requestAnimationFrame` intervals over a three-second
window spanning execution continuation and receipt rendering. Each run uses
a fresh JVM, browser context and writable store, the same 65-node programme,
one Run submission, and the same browser driver. Baseline and final runs are
ordered A/B, B/A, A/B. No Gradle build, archive compression or CPU profiler
runs during this sequence. The host has a four-CPU quota and a 16 GiB memory
limit; Chromium, the host kernel and bubblewrap are external test inputs.

The baseline is the immutable R3c runtime update. The final runtime also
contains the D3M corpus-search integration; the diagnostic candidate above
isolates the two browser-file changes before that integration. The comparison
is a small repeated workload observation, not a statistically general result.
It does not measure isolated frame preparation, input-to-paint latency,
10,000-node capacity or a 50,000-node graph. Three percentiles can conceal
individual long stalls, so the maximum and raw interval arrays are retained.

All 65 native return values are checked in each retained receipt: the source
is 2 and branch `i` is `i + 2`. The final invocation ledger retains 29 of 194
observations and reports 165 owner omissions. Mapped live selections vary
with timing and are recorded per run. Each displayed selection must map to an
actual received consumption event; successful final values do not authorize
invented wire motion. Owner omissions include phases and previously observed
events, so they are not a count of lost browser wire deliveries.

## Verification and integration scope

The tested source is `81438b1290c417318b1ad9c2d51c73aaa07564f5`. Offline JVM
main/test compilation, application installation and the selected regression
set pass: **310 passed, six skipped, zero failures**. The skips require an
external native Horn executable that is unavailable in this environment.
No source or test exclusions are introduced. The first combined attempt's
two failing corpus fixtures and their complete test results remain retained.
Their correction orders producers before consumers and composes the real
display runner; every search assertion remains.

JS main, Documents, Wasm-JS, Wasm-WASI and Linux x64 compilation also pass
at that commit, in a loopback-only namespace. Spacegraph production compilation
is included in the application build. The final browser bundle is
`ed364df88b37a6308b0083bfdbcf2a1b1d49d29b7e3df0c564f2f6979249ec63`. Final D3M integration `36cc2535d` has the exact same Git tree
as the tested source; later release documentation is separate.

The final extracted update passes **26 browser checks**, with three frame
measurements separately recorded. It is checked with three native fanout runs, the
ten-check retained-document browser scenario, and the four-check delayed-read
ownership scenario. The document path retains the 111,243-byte PDF and exact
UTF-16 passage `[18477,18654)`. A second JVM reopens
the same receipt and quotation with source assets, PDF tools, miner and LLVM
owners unavailable. Every process drains normally and exits zero.

Independent Python audits verify CAS pathname hashes, WAL sequence and
CRC32C, programme/run/receipt identities, native results, observed wire
endpoints, displayed counts and times, unchanged original document records,
and cleanup. The new ownership scenario has six WAL frames for its two
completed runs; each ordinary fanout/document case has three. Runtime roots
are read-only, state is separate, and network namespaces have loopback only.
Actual external connection attempts return `ENETUNREACH`.

D3M retains the new local corpus lexical-search contract and offered preset.
Its regression exercises retained inspection CIDs, explicit current/snapshot
mode, exact reported-lemma terms, a bounded result limit, policy-consistent
statistics/search and refusal of stale or malformed inputs. Scores remain
lexical relevance; they confer no evidence confidence, review approval or
Rete production authority. This source integration is not an end-to-end
English parser or corpus-search demonstration in the sealed document runtime.
Incoming NARS data-capsule and native Horn replay evidence retains its own
source, host, authority and verification scope.

## Install and reproduce

| Artifact | Bytes | SHA-256 |
|---|---:|---|
| `airgap-document-r3d-runtime-update-2026-11-05.tar.gz` | 36,881,833 | `e115e47d71f3e7f32c8a5a9c22da7fddbffa0f816f816aeabcef3c7d5f345077` |
| `airgap-document-r3d-final-sources-update-2026-11-05.tar.gz` | 12,680,655 | `a57105c0fbf9fea0f2d9b3a0bfbc566eeeaf531df75d52b7e1ef543c566ce1c6` |


The runtime update overlays the exact R4f runtime in a fresh installation.
It requires no earlier R3c runtime overlay. Verify the base archive hash,
extract the base, then extract this update into the same parent directory.
Verify `SHA256SUMS` and the complete file/directory membership and modes in
`package-identity.json` using the retained verifier. Keep writable state
outside the installation; apply the existing read-only mount, UTF-8 locale,
loopback-only namespace and launch recipe. Rebuild provenance and pinned-byte
archive repeatability are separate claims.

The source update applies after the R4e source companion, R4f source update
and R3c final source update. It is a full-index binary Git patch from
`792a14c3b982956df3a139661797e171cba9b963` to the tested R3d tree. A temporary
Git index verifies the resulting tree identity. Later publication evidence
and equivalent D3M ancestry appear separately in this repository. The initial
pre-fixture-fix source archive remains labelled preliminary in the packet.

The runtime archive was independently assembled twice with equal hashes.
The actual delivered archive was extracted over a verified base before the
accepted browser checks. The packet contains driver and verifier sources,
commands, dependency/base hashes, videos, screenshots, raw logs, profiles,
failed attempts and source snapshots. Earlier release evidence remains in
the previous cumulative bundle; the new delivery bundle states its
incremental scope explicitly.

## Next implementation boundary

The remaining frame gap is material for the intended RTS-style inspector.
Next work should profile the residual synchronous projection, rectangle
comparisons, redraws and serialization under a named workload before choosing
a wider change. Retain p95, maximum and raw intervals; add control-to-paint
measurement, because a good median can coexist with an unresponsive control.

If projection is split across frames, each batch must retain the current
programme/owner check and be cancelled through the existing page owner.
Superseded results may not repaint or feed execution. A partially projected
receipt must state that state, and wire geometry must reflect the displayed
positions. Large graphs require measured culling/aggregation and selected
scope navigation; faster projection of 65 top-level nodes does not supply
those mechanisms. The [visualization blueprint](../lcnc/airgap-demonstrator/07-visualization.md)
continues to own those acceptance targets.

This release does not close machine power-loss recovery, arbitrary native
write faults, full vendor/native source-build provenance, general Nix
evaluation or unified JVM/native garbage collection. Native buffers remain
explicitly owned and bounded; there is no new zero-copy claim.
