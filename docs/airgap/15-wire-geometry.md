# R3e: wire geometry and exact query rows

R3e takes a synchronous snapshot of wire endpoints before attaching new SVG
paths. It preserves the existing curve projection, observed-transfer owner
and editing behavior. The same D3M increment corrects structural map-entry
hashing exposed by new native Horn proof-quad inspection. Both changes retain
their failed verification attempts and the reason for each correction.

The [verification packet](evidence/r3e-20261105/verification.json) separates
unprofiled comparisons, diagnostic profiles, preliminary runtime failures and
final extracted-runtime checks. The [D3M record](../d3m/2026-11-05-airgap-r3e.md)
identifies every frozen branch and integrated tip. The prior
[R3d runbook](14-receipt-rendering.md) owns the exact-comparison reuse and
batched node-overlap mechanics retained by this increment.

## Geometry snapshot and attachment

`Panels.redrawWires` used to read two port rectangles, create and attach a
path, then repeat that sequence for the next wire. Shared ports were measured
again for each wire. The R3d diagnostic profile attributed 35.112 ms of sampled
inclusive time to this redraw and 18.799 ms to port-center lookup. These
nested values identify repeated work; they must not be added together or
substituted for the final measurements below.

The redraw now caches each distinct node/direction/bare-port center for that
call, including an absent center. It materializes an ordered collection of
endpoint twins, then reads viewport width and height once. The materialization
is intentional: these are measurements of mutable DOM geometry. A lazy
projection evaluated while paths were attached would repeat the original
read/write interleaving. This snapshot exists only during the synchronous
redraw and conveys no validity across an edit or asynchronous callback.

The curve helper has a measured-width/height overload. The original overload
still reads its supplied viewport for temporary drag wires and existing
callers. Curve shape, camera transformation and viewport clipping remain the
existing algorithm. Paths, mismatch/refusal titles, typed cable labels and
existing page-owned pointer handlers are built on one document fragment,
which is attached after the loop. Wire-box growth still uses actual endpoints;
verdict positioning and observed-spark layout still run afterward.

There is no additional executor, observation stream, frame scheduler or
persistent geometry owner. Pairwise node overlap remains quadratic, and
whole-canvas serialization remains in the validity check. The existing
page-owned listener registrations are also retained; long-session allocation
and listener retention need separate measurement before any lifetime claim.
This bounded change does not establish dense recursive or 10k-node capacity.

The first extracted runtime failed the retained-document preset load. An
infix `j` call on the `dynamic` endpoint was compiled as a JavaScript method
lookup, producing “is not a function” before the wires could be displayed.
The source compiled successfully, demonstrating why compiled JS is not a
substitute for executing the browser path. The correction explicitly calls
the existing `Twin<dynamic>` factory. The failed recording, screenshot,
process records and candidate archives remain preliminary evidence. No
fixture, load assertion or wire behavior was removed to make the check pass.

## Exact row equality and hash-based lookup

The incoming Horn projection adds bounded, request-local RDF quads over a
supplied checked first proof and its complete selected source context. Two
fixed eight-pattern inspection programs query variable bindings and ordered
premise uses through the existing quad engine. Typed source identity, premise
ordering, revision/context identity, whole-row coverage and duplicate/missing
row refusal remain part of the contract.

Initial integration could not compile because of missing collection brackets
in production and fixture code, plus a filtered Series used at a stdlib
association boundary without `.view`. After these syntax/boundary corrections,
two new tests still refused valid query rows. Their actual SPARQL answers
matched the expected data. The fault was underneath the query contract.

JSON reification returns `LinearArrayMap`. Its entries were anonymous objects
with identity equality and hashing. `AbstractMap` could compare a nested parsed
row equal to an ordinary map while its entry-derived hash differed. Recreating
the entry objects could change the hash again. A hash index built with the
ordinary row could therefore fail to retrieve the equal parsed row. Exact
coverage checks correctly refused the apparent mismatch.

The entries now implement the standard `Map.Entry` contract: keys and values
compare structurally; the entry hash is key hash XOR value hash, with zero for
a null value. The underlying query assertions remain unchanged. This is a
common collection correction, not an alternate ordering algorithm or a special
case for one Horn fixture. It does not modify the older, separate `ArrayMap`
implementation.

A diagnostic uses the compiled certificate fixture, actual proof projection,
actual SPARQL lowering/answering and actual JSON reifier. Before the fix both
rows compare equal in both directions but fail lookup in a genuine `HashMap`.
Afterward the binding row hashes are both 1604915285 and the premise row hashes
are both -2050393759; both lookups return the stored value. Earlier diagnostic
iterations are retained, including a singleton `Map.of` lookup that was not a
hash-index test and therefore did not demonstrate this failure.

Common regressions cover nested values, reordered JSON fields, null values,
repeatable hashes, entry/set equality and hash-index lookup in both directions.
A twelve-field object also exercises the map's indexed path beyond its linear
threshold of eight. JVM execution proves those checks on that target;
cross-target compilation alone does not prove their runtime behavior elsewhere.

These quads describe supplied proof/selection context. They do not by
themselves establish native execution, source entailment, authenticated review
or permission to publish a production. Conditional external-solver checks and
authored certificate fixtures retain their distinct standing. The integration
also preserves source-pinned corpus replay evidence and removes the misleading
missing-runtime-owner warning from static note nodes. Other node types retain
their readiness checks.

## Measurement protocol and limits

The comparison uses the immutable released R3d installation and the final R3e
archive extracted over a fresh verified R4f base. Both run the same driver,
65-node graph, 64 wires and 65 actual native scalar invocations. Each repetition
starts a fresh JVM, browser context and writable store. The three pairs run
in A/B, B/A, A/B order. Builds, archive compression and profiling are excluded
from that sequence. The host has a four-CPU quota and a 16 GiB memory limit.
The browser, bubblewrap and host kernel are external test inputs.

The three-second frame window begins with execution paused before the source
node, then includes Continue and receipt handling. Raw `requestAnimationFrame`
intervals, sample counts, percentiles and maxima are retained. They are not
isolated frame preparation times, portable guarantees or dense-graph results.
This driver also exercises keyboard controls during the window, so its numbers
must be compared with its own matched baseline, not substituted into the older
R3d driver comparison.

After the controller receives the real receipt body, trusted CDP Space events
resume and pause the existing display button. The application handler is not
replaced. Four keyboard events and both state transitions must be observed.
Chromium Event Timing entries are matched by event name and timestamp to those
keyboard events; raw entries from later pointer interactions are retained
separately. The browser quantizes duration and omits entries below its reporting
threshold, whose minimum is 16 ms. An absent entry is unreported, not zero.

The raw `handlerToFrame` diagnostic subtracts the event handler clock from the
`requestAnimationFrame` frame timestamp. That timestamp can precede the handler
within an already-running frame, so negative values are retained. This is not
an elapsed wait or paint latency and is excluded from the timing table. A future
callback-wait probe must also record the clock inside the callback. Controller
round-trip time includes CDP transport and controller scheduling. Dispatch
after receipt delivery does not measure physical-input latency or an input
already queued during an earlier receipt stall. The duration, processing and
frame clocks remain separately labeled; no single number substitutes for all
three boundaries.

After the timing window, the graph is fitted, zoomed about the measured source
port, and resized. Every path with both endpoints visible must meet actual port
centers within one screen pixel. Clipped pairs are counted separately. A real
pointer hit deletes one exposed wire, leaves all 65 nodes and invalidates the
old observed trace without another Run submission. The retained execution
still describes its original 64-wire graph; a later canvas edit does not
rewrite that evidence.

Two early harness pilots demanded an eligible pair after zooming around the
viewport center, which had moved the shared source offscreen. The harness now
anchors zoom on the measured port and records exclusions. No product behavior
was changed for that invalid assumption. Those pilots and a third passing
baseline pilot ran during compilation and have no performance acceptance
standing.

## Matched observations

| Runtime / repetition | Samples | p95 (ms) | Maximum (ms) | CDP round-trip (ms) | Reported keyboard durations (ms) |
|---|---:|---:|---:|---:|---|
| R3d / 01 | 160 | 33.4 | 116.7 | 27.1 | 72, 88, 64, 72 |
| R3d / 02 | 159 | 33.4 | 149.9 | 35.4 | 88, 96, 72, 80 |
| R3d / 03 | 159 | 33.4 | 116.7 | 22.2 | 64, 72, 72, 72 |
| R3e / 01 | 160 | 33.4 | 116.7 | 30.5 | 96, 104, 88, 88 |
| R3e / 02 | 162 | 33.3 | 133.4 | 15.8 | 80, 72, 56, 88 |
| R3e / 03 | 167 | 33.3 | 116.6 | 40.8 | 80, 88, 56, 56 |

All four keyboard entries were reported in each run. The matched maximum
frame intervals are 116.7–149.9 ms for R3d and 116.6–133.4 ms for R3e;
keyboard durations and controller round-trips overlap as well. These small
repetitions do not establish a consistent latency improvement. The change
removes repeated geometry reads and separates SVG attachment, while sustained
frame-rate/control acceptance remains open.

Every final run verifies all 65 native values, zero overlapping node pairs,
64 visible endpoint pairs after fit, 27 after zoom and 17 after resize. The
remaining 37/47 pairs are explicitly clipped from those latter checks. The
largest measured endpoint error is below 0.00012 screen pixels, against a
one-pixel tolerance. This checks visible endpoints; it does not prove every
point on each clipped curve. Each final ledger retains 29 of 194 observations
and reports 165 owner omissions. Live displayed selections are checked against
actual received events and can vary with polling; they are never reconstructed
from successful outputs.

## Separate diagnostic profiles

One additional baseline/final pair uses Chromium CPU sampling and a browser
trace, decoded through each exact packaged source map. Sampled inclusive time
in `programOutputs` is 93.613 ms for R3d and 79.314 ms for R3e. Nested wire
redraw is 34.458 ms and 21.821 ms respectively; the existing overlap pass is
27.903 ms and 28.515 ms. Inclusive values overlap and must not be summed.
Timeline Layout totals are 39.332 ms and 31.856 ms, while Paint totals increase
from 58.133 ms to 73.386 ms. Those trace categories also overlap.

These profiles include sampling/recording overhead and use the retained
profiling driver, not the keyboard-comparison driver. They locate remaining
work and are not independent performance acceptance trials. Both real native
runs and their retained evidence pass the same independent audit. The raw CPU
samples, compressed timeline and source-map decoder remain in the diagnostic
packet. The mixed timing changes reinforce the limited conclusion from the
unprofiled comparison: the mechanism is changed, while broad responsiveness
improvement remains unproven.

## Follow-on implementation mechanics

WP12/WP13/WP24 still need a measured scene budget across larger and recursively
nested graphs. The next useful experiment freezes the same execution receipt,
varies visible node/wire cardinality and records the largest frame gap,
interaction timing, DOM growth and listener retention over repeated edits.
It must include clipped paths, missing ports, nested scopes and camera changes;
a zero-wire or entirely offscreen scene cannot stand in for a populated view.
A persistent geometry cache would need explicit invalidation on every geometry
mutation before it could replace this per-call snapshot.

WP09/WP11 retain a separate obligation to distinguish stopping display motion
from stopping admission and draining execution. Human-paced animation must
continue to resolve to actual observed transfers and explicitly reported
omissions. A successful final value never permits inventing missing sparks.
WP15/WP07 retain the distinction between projected proof context and actual
native execution. WP19/WP20 still require guarded production promotion,
revocation and effect ownership; neither a quad row nor a cache hit grants
those authorities.

The wider LCNC cardinality wizards, legal panel simulation, fiction/news packs,
Rete consolidation and optional local statistical engines remain governed by
the exhaustive blueprint work packages. This release narrows two concrete
mechanical gaps without changing their planning cost assumptions or claiming
that the whole demonstrator is complete.

## Verification, artifacts and reproduction

The final source commit is `5fdd0472c115922287ba46a4977cb1134aa9017a`.
Offline application installation, Spacegraph production compilation and the
selected JVM suite pass: **316 passed, seven skipped, zero failures** across
59 suites. The seven conditional checks require an unavailable external native
Horn executable. Their names are retained in `skipped-tests.json`; the native
Sulong scalar/Confix browser path executes separately and is not skipped.
No source or test exclusions were introduced.

A final D3M branch adds malformed-row assertions and a query-local copy
workaround for the map-hash defect. Reconciliation
`cf544ffd37f291287ea7ec09aa1a538db5ce7893` keeps every new assertion and uses the
already corrected common map contract without copying or normalizing rows.
Five focused map/Horn tests pass with the added extra-field, non-string and
duplicate-key cases. Production source and build inputs remain byte-identical
to the packaged source; only the test file changes. The retained
`strict-row-validation.patch.txt` applies after the final source archive and
reconstructs the exact integrated `src/` subtree. These five checks are a
focused rerun, not five additional unique tests in the 316-test suite.


JS main, Documents, Wasm-JS, Wasm-WASI and Linux x64 compilation pass at
`d1e5d413259e191a9aa6cff15998f6df2b50aa38`. The only runtime source difference
at the final commit is the Spacegraph endpoint factory correction, which the
final production build compiles. All target-specific/common source and build
inputs used by the earlier target check are unchanged. The source-continuity
record lists the intervening documentation and exact one-file difference.
Later publication documentation and evidence do not redefine those tested
source identities.

The final extracted runtime passes **35 browser checks**: seven in each of
three geometry runs, ten in the document scenario, and four in the retained
reference ownership scenario. Three frame measurements and three control
measurements are separately recorded; they are not six additional pass claims.
The document run retains the 111,243-byte PDF and exact UTF-16 passage
`[18477,18654)`, checks eight observed transfers, and reopens the same receipt
in a second JVM with source assets, PDF tools, miner and LLVM owners unavailable.
The ownership scenario uses two real runs and four actual CAS reads; editing
the definition during a delayed read refuses display, and restoration permits
inspection. Every accepted process drains and exits zero.

Independent audits verify CAS path hashes, WAL sequence/CRC32C, programme and
receipt identity, real scalar values, observed wire endpoints/counts/times,
unchanged original document records, and cleanup. Each process runs with a
read-only packaged filesystem and separate writable state in a loopback-only
network namespace. Actual external connection attempts return `ENETUNREACH`.
These observations do not establish physical power-loss durability or vendor
source-build closure.

The final browser bundle SHA-256 is `622a0d3f5d54d54e6b30c031426e2f22d7af9b019290d38611f7b87b2ff02de9`.

The final runtime overlay is `airgap-document-r3e-final-runtime-update-2026-11-05.tar.gz`
(36,928,779 bytes), SHA-256
`b96bf9ef74224f625d49767d1e723d5de2c1bc4748ae8d266a8a39c24e6dd006`. It requires the immutable R4f runtime
archive named and hashed in the packet. Two assemblies from the same pinned
bytes are identical. The actual overlay is extracted over a fresh verified
base, then its complete membership, modes and hashes are verified before
and after the runtime checks. Preliminary candidate archives are retained
for diagnosis; use this final overlay for the release.

The source overlay is `airgap-document-r3e-final-sources-update-2026-11-05.tar.gz`
(37,468,185 bytes), SHA-256
`bcef7f2000025f3e2a807bb0536ea84c19d6e48180d2e55fc8dc0568d3cfac75`. Apply the R4e source base, R4f update,
R3c final update and R3d final update first, then this full-index binary patch.
A temporary Git index verifies the exact target tree. Raw evidence whitespace
is intentionally unchanged. The cumulative blueprint PDF and incremental
evidence ZIP are separate from these archives and retain their own manifests.

Reproduction scripts are retained as inert `.py.txt`, `.cjs.txt` and
`.java.txt` files. Adapt the recorded absolute tool/root paths to a matching
Linux x86-64 host; restore script extensions in a scratch directory. Use
`package-final.py verify` for complete inventory/mode/hash verification, not
only a subset checksum. `run.py <case> geometry` executes the matched driver;
`browser` executes the document/reopen scenario; `ownership` exercises the
delayed real read. `audit.py <case> fanout|document` and
`ownership-audit.py <case> ownership` inspect independent retained evidence.
`compare.py` records exact command/order/driver hashes, while `profiles.py`
labels its separate diagnostic overhead. No harness fabricates a successful
server response or native output.

See the existing R4f/R3d runbooks for the complete namespace and filesystem
launch commands. This release runs in the cloud checkout. Jim's local machine
has not been synchronized or modified by these commands.
