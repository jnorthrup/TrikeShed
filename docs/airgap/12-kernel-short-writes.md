# R4g: kernel short writes and receipt recovery

R4g measures the existing WAL's response to actual kernel writes that stop
partway through a frame, and to successful continuation of a short write.
It reuses the exact published R4f runtime and source artifacts. No application
change or new binary is needed: the measured append loop and recovery behavior
already satisfy these five scenarios. The new deliverable is the fault
controller, independent audits, recordings and bounded operating contract.

## From constructed tails to measured writes

Earlier releases edited copies of completed WALs to exercise short-tail
recovery, or killed the writer at node and fsync boundaries. Here the
controller never edits the active WAL. Linux writes the observed bytes from
the actual packaged application, through its active `io_uring` backend.

A normal document run reaches the production debugger's pause before
`selection`. The graph has already performed the real PDF traversal and
native Confix scan. Its durable WAL contains two complete frames totaling
278 bytes: queued and running. The controller verifies that running receipt
and attaches strace to its owned JVM and all observed threads. It delays
`io_uring_enter` by 500 ms and fsync entry by 200 ms. These delays provide a
measured intervention interval; their timings are not application benchmarks.

The browser continues the same run. The controller waits for a final,
content-addressed CAS file whose command names this run and has completed
status. It verifies the filename's SHA-256 and reconstructs the expected
139-byte WAL frame from the job identity and completed-command CID. It
ignores temporary atomic-write paths. The completed CAS payload therefore
exists before the WAL experiment, without being treated as an admitted
terminal receipt merely because it exists.

The controller applies `RLIMIT_FSIZE` to that JVM with a soft limit equal
to the existing 278 bytes plus the selected prefix length. It reads back the
actual limit. This is a process-wide kernel limit, not a modified WAL method,
substitute channel, fabricated syscall return value or native shim. When the
WAL reaches the selected size, the controller requires every byte to equal
the previous complete prefix plus the corresponding beginning of the
expected frame. It records that state, then signals the actual JVM with
SIGKILL while its next ring or fsync entry is delayed. It waits for exit 137
and confirms the JVM is reaped, no cleanup report exists and the WAL still
contains exactly the measured bytes.

The first limited experiments also constrained writes to the JVM's already
large redirected log file. SIGXFSZ signals exposed this collateral effect.
Those attempts remain preliminary. Accepted attempts send JVM stdout/stderr
to a pipe, which the external controller drains to its own log. Its process
has no file-size limit. Accepted traces must contain neither SIGXFSZ nor
EFBIG. The resource limit remains process-wide; the evidence establishes the
observed behavior of this fixture, not a general per-file injection facility.

## The byte boundary determines recovery

The final frame is an 18-byte header, a 117-byte job/CAS pointer and a
four-byte CRC32C. Each scenario starts with a fresh graph run and preserves
its own identities. The byte-boundary
[diagram](evidence/r4g-20261105/partial-writes.svg) is generated from the
accepted audit records.

| Intervention | New frame bytes present | Total WAL bytes at intervention | Fresh inspector outcome |
|---|---:|---:|---|
| Kill in header | 9 of 139 | 287 | Interrupted, same run |
| Kill in payload | 48 of 139 | 326 | Interrupted, same run |
| Kill in CRC | 137 of 139 | 415 | Interrupted, same run |
| Kill after complete frame | 139 of 139 | 417 | Completed, same run; response was lost |
| Restore limit after short write | 9 initially, then all 139 | 287, then 417 | Completed, same acknowledged receipt |

For all three incomplete frames, recovery preserves the original first 278
bytes, discards only the short tail and writes a new valid sequence-3
interrupted command. It retains the same run/program identity, names the
previous running receipt and reports `runtime_restarted`. It fabricates
neither outputs nor an invocation ledger. The completed command and its
ledger remain valid CAS objects, but the new WAL does not reference that
completed command. Each resulting store contains 28 verified CAS objects.

The complete-frame control has the same resource-limit and signal machinery.
The whole valid 139-byte frame is present when the process dies, and the
final WAL fsync has not returned successfully. The new inspector recovers
completed status and the exact completed-command CID. Reopening leaves all
417 WAL bytes unchanged. The browser had observed an unavailable response
and unknown outcome, so this is an unacknowledged completion recovered under
the same run identity. Its 27 CAS objects are independently verified.

Both results are necessary. Promoting every complete CAS command would invent
a completion for the short-frame cases. Discarding every unacknowledged result
would lose the complete valid frame. Recovery follows the existing verified
WAL and command reducer, while the user sees the uncertainty of the original
request separately from the later retained state.

## A short write can also finish successfully

The fifth scenario begins with the same nine-byte kernel write. Instead of
killing the process, the controller restores and reads back its original
unlimited file-size setting while the next ring entry is delayed. The
existing `BoardFileWal.writeBytes` loop continues the same append. The final
WAL equals the two original frames plus the entire expected frame, with valid
CRC32C and sequence 3. Trace parsing requires a successful final WAL fsync
after the limit restoration.

The original HTTP request returns 200 and a completed receipt. The browser
submits exactly one graph run; this is completion of a partial I/O operation,
not resubmission of the graph or an execution retry. The writer drains with
exit 0. A second JVM, with miner, LLVM modules and installation assets hidden,
reopens the same receipt. Its CID matches the acknowledged response, and it
also drains normally. All 27 CAS objects and all six document revisions are
verified.

The successful browser capture closes the ordinary debugger/facets overlays,
uses the existing Fit action, verifies the quotation against retained text
and retains both the whole graph and a readable passage image. An earlier
behaviorally successful capture remains retained because its expanded card
was clipped. No product UI changed for the capture.

## What the audits require

All five scenarios execute the document graph afresh and reopen it in a fresh
JVM. Each browser checks the initial running receipt, observed request outcome,
recovered receipt and inspection without another Run submission: **20 browser
cases across five packaged scenarios**. Four writers exit 137; their four
inspectors and both successful-continuation processes exit 0. No forced
termination substitutes for the six required normal exits.

The independent Python audit checks every CAS filename against payload bytes,
every complete WAL frame's magic/version/length/sequence/CRC32C, exact previous
frame preservation, current and historical receipt links, unchanged source
revisions, and process/cleanup records. It verifies the completed CAS command's
177-unit quotation against UTF-16 range 18477–18654 of retained text, the
111,243-byte original PDF, eight consumed-wire observations and zero retained
native allocation. For incomplete frames, those completed-command artifacts
are examined as retained uncommitted evidence, not mislabeled as recovered
outputs.

The syscall parser joins unfinished/resumed calls by thread before checking
whether the final WAL fsync returned. A split strace line cannot evade that
check. The accepted crash cases have no successful final WAL fsync; the
successful continuation must have one after restoration. Delay markers,
actual file-size limits, before/after WAL snapshots, expected frame bytes,
exact run/CAS identities, commands and raw traces are retained.

The controller identifies its own process descendants and checks their command
lines before setting limits or sending signals. Each scenario runs in its own
network namespace with no external route, a read-only runtime and separate
writable state. The inspector has no miner or LLVM runner and submits no new
execution. The controller, browser, strace, Linux kernel and filesystem remain
external test inputs. A surviving page cache is available throughout.

## Standing, failures and distribution

The runtime remains
`airgap-document-r4f-linux-x64-2026-11-05.tar.gz`, 460,778,817 bytes, SHA-256
`28fb0c97ecb549881238929e7159fbb5847a0817cca4c9632306ea4e95df5c58`.
Its tested application source is
`4c00eccd7d2e8ce65ade1b23734c9aa1a018f0ab`; R4f published it at
`2b9e678222d9d0f83ce6423de97800ab14a4d802`. The archive hash and complete root
membership/mode/hash checks pass again. Application, entry, platform and build
inputs are unchanged. R4f's 22 passing JVM regressions retain their original
identity; R4g does not relabel them as a new unit-test run or claim another
cross-target rebuild.

Use the [R4f source update and required R4e source base](11-native-handoff-utf8.md#distribution-and-reproduction).
The separate R4f Kotlin/Native storage probe remains available but is not used
in these JVM/Sulong scenarios. There is no replacement R4g runtime archive.
The [verification packet](evidence/r4g-20261105/verification.json) records the
reused artifacts and new scenario evidence separately.

The first controller inspected a still-temporary CAS file and rejected its
non-digest pathname before any resource limit was applied. It closed the
writer normally and retains the failed attempt. Final-path filtering corrects
that harness error. The four first limited attempts retain their exact bytes
and browser results, but their SIGXFSZ log side effect prevents acceptance
under the stricter audit. Pipe-based capture fixes the observation boundary.
The successful continuation's first capture and its accepted visual follow-up
also remain distinct. Raw source extraction and `/proc` whitespace is retained
byte-for-byte, verified by the evidence manifest rather than reformatted.

[D3M](../../../../../../tmp/d3m/2026-11-05-airgap-r4g.md) freezes 545 refs, all already ancestors
of the published R4f master. No new branch merge or conflict occurs. Evidence
and documentation are committed and ordinarily pushed after ancestry and
manifest checks. Jim's Mac remains unchanged; this is the cloud Linux work.

These measurements cover three incomplete prefixes, one complete frame and
one successful continuation under a controlled kernel file-size limit. They
do not close every arbitrary-write failure, sector tearing, machine power
loss, remote filesystem behavior or physical-media durability. Complete vendor
JDK/native-resource rebuild provenance remains open. The established runtime
and these recorded boundaries support the next LCNC visualization and control
increments without treating those wider claims as already solved.
