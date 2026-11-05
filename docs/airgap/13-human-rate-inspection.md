# R3c: human-paced transfer inspection

R3c returns to the visible LCNC workflow after the R4 storage increments. An
operator can hold wire motion, inspect a pinned input-consumption record and
change display speed without moving a spark backwards or prematurely ending
it. The actual execution remains under the existing debugger and userspace
owners. A small display table retains received wire counts after the motion
finishes. A larger, real native graph demonstrates why that table must state
its observation coverage.

This is a bounded continuation of [R3a](04-observed-transfers.md) and the
[document traversal](05-document-traversal.md), running on the R4f sealed
runtime with a browser update. It does not close recursive scope navigation,
large-graph rendering or the whole [visualization blueprint](../lcnc/airgap-demonstrator/07-visualization.md).
The [verification packet](evidence/r3c-20261105/verification.json) and
[D3M record](../d3m/2026-11-05-airgap-r3c.md) retain exact inputs and limits.

## Operator workflow

Open `/panels?load=airgap-document`, review the retained PDF graph, then choose
Debug. Existing node controls hold execution at supported boundaries. The
**Pause display** button instead freezes the presentation of already observed
wire transfers. It sends no execution command. Incoming eligible observations
continue updating the table, even while the animation stays held. **Resume
display** resumes that presentation from its previous position.

Expand **Inspect observed transfers**. Each mapped wire has a count, first and
last observation sequence, and the last measured time from walk start. Select
a wire with a pointer or keyboard to pin its last received observation. The
record includes the actual invocation, path, producer, consumer, wire and
sequence fields supplied by the execution owner. New observations update the
table without replacing the pinned text. Selecting again intentionally pins
the later record. The raw invocation receipt remains the durable authority.

The table has a bounded scroll area with sticky column labels. Its record
area scrolls independently; the display controls remain in the enclosing
activity panel. Hide activity releases that presentation. Neither inspection,
speed changes, hiding, camera movement nor receipt playback submits a new run.

Editing a parameter, connection or programme definition invalidates the old
mapping immediately on save, including when no animation frame is running.
Layout and camera changes retain a matching definition. Playback still
requires the retained programme to match the canvas; this increment does not
turn historical observations into executable state or resume an old native
stack.

## Three distinct clocks and records

The previous renderer computed progress by dividing the entire elapsed display
time by the currently selected duration. Changing from 0.25× to 4× could
instantly consume the remaining trajectory. A real R4f document baseline
reproduced this: two unfinished markers disappeared immediately after the
speed increase. The retained baseline still completed the PDF/Sulong run and
closed normally; this was a presentation defect.

The renderer now accumulates presentation time. Before changing speed or
entering/leaving display pause, it accounts for the elapsed interval using the
previous rate. Each spark starts against that accumulated clock and advances
through the existing 1.2-second presentation duration. Pausing stops the
presentation clock, not the owner clock. Camera redraws schedule a single
frame to place held markers on the current paths without advancing progress.
The existing page owner retains and cancels animation callbacks and listeners.

Measured `elapsedNanos` stays unchanged. It originates at the execution walk
and includes debugger pauses; it is neither animation duration nor an
inter-machine latency. Captured runtime measurements and source coordinates
remain under their existing native and document contracts. No synthetic
instruction progress, percent complete or successful wire delivery is added.

The new `PanelsTransfers` projection is synchronous browser presentation over
the already accepted observations. It does not introduce an executor,
coroutine runtime, native context, journal or alternate control route.
`PanelsTrace` retains the existing ownership ticket, programme comparison,
sequence deduplication and wire-endpoint checks. The existing save and redraw
paths now notify it so idle invalidation and held-marker placement work.

## Coverage is part of the display

At most 256 distinct mapped wires occupy the table. Each row retains exact
received count and sequence endpoints, plus one copied last observation.
It is not an unbounded event journal. Sequence endpoints can contain gaps and
other phases; they do not prove that every intermediate event was received.
The table reports selections it could not retain separately from the existing
animation queue's display omissions and unmapped observations. The existing
animation limits remain 256 pending aggregates and 128 visible markers.
Those are implementation limits; the accepted 65-node case does not exercise
every capacity boundary.

The table and its pin are local page memory, not new durable journal entries.
An earlier live observation can have fallen outside the final ledger's suffix.
Reloading and replaying that receipt can therefore show fewer selections than
the live session did. The document's eight-transfer history happens to fit
completely and reopens unchanged; the 19-transfer live scalar table is not
claimed to survive a reload in full.

Owner omissions come from the bounded owner snapshots. They include phases
as well as consumption observations, and they can include events previously
seen in an earlier snapshot. Consequently they are not a count of lost wire
deliveries in the browser. The table describes observations accepted by its
current owner. An HTTP request may finish after that observer ends; draining
the read does not authorize it to repaint the display.

The larger fixture has one scalar source and 64 scalar branches: 65 actual
Polyglot/Sulong invocations. The source computes 2; branch `i` computes `i + 2`.
All results are verified in the completed retained receipt. The accepted
browser maps **19** consumed transfers, each backed by an actual delivered
observation. Its final ledger retains **29** of **194** observations and
reports **165** omissions. The display does not invent the other branch
transfers merely because their final results are available. Every consumption
observation in the final retained ledger is represented, and duplicate
snapshot delivery does not double its count.

## Verification on the extracted update

The exact updated browser bundle is
`e698fc73adab72a1459e826a558c93f7461c537b00d08aabbf95c26ff4f883cd`.
The source commit is `06da928c627b16db79abc97becaab483874102da`.
All four changed browser source files and the release instructions match the
offline build worktree by SHA-256. Spacegraph production compilation,
webpack, staging and the dedicated web JAR task complete with no source or
test exclusions. JVM, common/native code, engine modules, dependencies and
build configuration are unchanged. No new JVM unit-test or cross-target
compilation is claimed for those unchanged inputs.

The accepted document case passes ten browser checks: continuous speed,
display pause, held-marker camera alignment, execution completion during
display pause, pinned and keyboard inspection, counts after motion ends,
idle-edit invalidation, reduced-motion replay, hiding and retained quotation
verification, including fresh-process inspection. Some checks cover several
related assertions. Exactly one programme is submitted. The second JVM has
no miner or LLVM runners, and the source assets and PDF tools are hidden.
It reopens the same receipt and table without executing again. Both owners
drain and exit zero.

The 65-node fixture passes three additional browser checks for computed
results, observation coverage and bounded display/control separation. Its
separate frame-interval case records measurements rather than declaring a
performance target passed. The owner drains and exits zero. Both accepted
scenarios use a read-only installed runtime, separate writable state and
network namespaces with loopback only; external connection attempts return
`ENETUNREACH`. The browser and Linux host remain verification inputs.

Independent Python audits verify every retained CAS object's pathname/hash,
all three WAL frames including sequence and CRC32C, run/program/receipt
identities, displayed wire endpoints, counts and measured time values. The
document case has 27 CAS objects; the larger case has 26. All six original
document revisions remain unchanged. The document's 111,243 original PDF
bytes and exact UTF-16 quotation range `[18477, 18654)` are checked again;
the native scanner reports zero owned bytes after release.

## Rendering measurements and next work

The final larger case records 112 external browser animation-frame intervals:
p50 **16.7 ms**, p95 **33.4 ms**, maximum **1,016.6 ms**. The earlier isolated
candidate recorded a 983.4 ms maximum. The long gap is material: this release
does not claim sustained game-quality rendering, a met frame budget or a
10,000/50,000-node result. These intervals cover the actual headless-browser
run, including output presentation and UI activity. They do not isolate
`PanelsTrace.draw` preparation time or identify the cause of the stall.
The [reference-host record](evidence/r3c-20261105/reference-host.json) preserves
CPU, memory/cgroup, browser and concurrent-work scope.

The next rendering work should capture a browser performance trace around
receipt-output projection, definition comparisons, rich node expansion,
overlap resolution and SVG redraw. Attribute long tasks before changing them.
Then measure bounded output batches and scope-level detail under the same
real graph, preserving ownership checks at asynchronous boundaries. Extend
the fixture to repeated scopes and larger cardinality only with explicit
owner/display coverage. Whole-run evidence navigation and source support must
remain available while details are culled. These are follow-up gates, not
completed behavior in this release.

## Distribution and retained failures

The runtime update is **2,263,306 bytes**, SHA-256
`41a3acc274685e8afdcfa70ab31f6cb44e64d8e8fe5e163d6dc42a0fdc5e2a1a`.
It requires the immutable R4f runtime, SHA-256
`28fb0c97ecb549881238929e7159fbb5847a0817cca4c9632306ea4e95df5c58`.
Extract that verified base into a fresh installation, then extract
`airgap-document-r3c-runtime-update-2026-11-05.tar.gz` into the same directory.
The update replaces only the Spacegraph JAR, runtime README and two package
manifests. It is not a standalone runtime. Verify SHA-256, complete membership
and modes before using the established isolated launch procedure.

Two independently assembled update archives are byte-identical. The actual
archive is extracted over a verified copy of the R4f root, unlinking replaced
hardlinked files first. All 1,355 inventory files, directory/file modes and
hashes pass. Both accepted browser scenarios use that installed result. The
unchanged R4f root is reverified afterwards.

The corresponding source update is **38,393,268 bytes**, SHA-256
`266f73dda1f07d39a47fb0e4c494b1ab88976d6647e794b3d1da574c94078798`.
Apply it after the R4f source update on the R4e source companion. Its full-index
binary Git patch reproduces tree
`a57ec88df23303981fdba9d87987785223ae692e` exactly in a temporary Git index.
No dependency source changes. Later evidence/publication documentation is
separate from the tested code commit. Full vendor/native rebuild provenance
and power-loss durability remain open.

The evidence keeps the old speed defect; the first working UI before its
table-height refinement; a larger fixture refused for wiring JSON into a
numeric port; the corrected numeric fixture; and a failed audit that treated
all HTTP-completed reads as eligible to repaint. That audit was corrected to
respect the existing observer fence while still requiring every displayed
selection to match real evidence. The first runtime overlay also failed
strict directory-mode verification because staging-directory entries carried
different modes. The accepted overlay contains changed files only and
preserves base directory modes. No integrity check was weakened. Raw logs
and source bytes retain their original whitespace.

D3M initially and before publication freezes 545 refs: 535 remote heads,
nine local branches and `origin/HEAD`. All are contained; no new merge or
conflict is required. No branch is preferred or deleted. Jim's Mac remains
unchanged; this is the cloud Linux checkout and ordinary master publication.
