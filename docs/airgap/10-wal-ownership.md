# R4e: exclusive WAL ownership and retained-store recovery

R4e closes a demonstrated ownership gap in the existing board write-ahead log.
A filesystem lease is now acquired before opening, scanning or truncating the
WAL. The lease remains owned through append, flush, replay and close. This
extends the existing filesystem SPI and board owner; it adds no executor,
file format, lifecycle hierarchy or automatic retry.

## Reproduced loss at the WAL boundary

The published R4d implementation allowed two direct `BoardFileWal` instances
in one JVM to open the same directory. Both started from the same retained
sequence. The first appended and flushed sequence 2. The second also appended
and flushed sequence 2 at its previously observed offset, overwriting the
first owner's second record. Both flush calls returned. The resulting
83-byte file still had valid CRC32C frames: sequence 1 contained
`retained-first-frame`, and sequence 2 contained `other-owner-flushed`.

The retained baseline includes the exact Java caller, compiled classes,
result and WAL bytes. Its SHA-256 is
`e0adda6b6c3bf3a22479a07ccf194c95c41dece773a306da800dd2e1f3ed6965`.
This is a direct production-WAL reproduction. It does not establish duplicate
writer admission through the full application or loss of an acknowledged
HTTP receipt: the complete packaged application already has a separate
Couch store lease. The fix makes the WAL's own public filesystem constructor
safe against cooperating competing owners, including callers outside that
application composition.

Four current filesystem tests were run before and after the fix. Before it,
active-owner exclusion and directory-alias exclusion failed; the two existing
constructor/refusal cleanup behaviors passed. After it all four pass. The
focused red/green records retain that distinction rather than claiming four
newly reproduced failures.

## Lease and cleanup mechanics

`BoardFileWal.open` resolves the directory through `FileOperations`, retains
the existing directory publication barriers, then calls
`acquireExclusiveLease` on `board.wal.lock`. Only after acquisition may it
open `board.wal` or run recovery. A refused contender therefore cannot
truncate an active owner's incomplete tail. The stable sidecar is a separate
inode from the WAL, avoiding a POSIX record lock being dropped by closing a
WAL descriptor. Keep this sidecar in place; deleting or replacing a live
lock file creates a different ownership identity.

The existing JVM lease adapter combines a process-local ownership registry
with the operating system's nonblocking file lock. Canonical paths and file
identity checks reject same-process aliases before opening and closing a
second descriptor for a held lock inode. The WAL receives the acquired
lease with its channel and parent-sync action through the existing `Join`
algebra. If channel opening fails, the lease is closed. If initial recovery
fails, both channel and lease cleanup are attempted. Normal close is
idempotent; a channel-close exception remains primary and a lease-close
exception is retained as suppressed. The change does not swallow failures.

The board's existing serialized command consumer still owns append ordering.
A filesystem lease does not make concurrent calls on one WAL instance
thread-safe, prevent writes from a process ignoring the lease, or protect
against an operator replacing the sidecar. The internal injected-channel
constructor remains available to its existing tests; the production path
acquires its lease through the filesystem SPI.

On Linux, the existing native adapter uses an open-file-description lock;
its helper documents conflict with ordinary fcntl locks used by JVM FileLock.
This release compiles the Linux native target but measures ownership in
Linux JVM processes only. It does not claim a native-process, macOS or remote
filesystem locking proof. An unsupported adapter refuses lease acquisition;
no unlocked fallback was added.

## Ownership across process death

The accepted extracted-archive scenario starts with a completed R4d store.
A new R4e inspector opens its one receipt and six document revisions with
source-installation assets, LLVM modules and PDF tools hidden. The browser
loads the inert document preset, shows recorded outputs and verifies the
selected quotation against retained text. It submits no Run request.

A second isolated JVM invokes the actual packaged `BoardFileWal` constructor
against the same directory. It exits 42 only after the expected lease refusal;
the WAL digest remains unchanged. The external probe adds one small Java
caller to the packaged classpath, and its directory is checked to contain
only that caller's class. It supplies no substitute WAL implementation.

The controller then kills its owned first Java process with SIGKILL, confirms
exit 137 and that the process has been reaped, and observes no cleanup report.
A fresh R4e inspector opens the same store successfully. A new contender is
again refused. The browser verifies the same receipt, revisions and exact
quotation. The inspector then closes normally with exit 0 and a complete
cleanup record; a final direct-WAL probe opens and closes successfully with
exit 0. All three probes preserve the WAL bytes. The kernel's release on
process death and the application's normal-close release are separate checks.

An independent audit verifies all 27 CAS objects, the three CRC32C frames,
queued/running/completed receipt chain, source identities, eight historical
consumed wires, and UTF-16 quotation range 18477–18654. The original PDF is
111,243 bytes. The native outputs in this particular scenario are historical
R4d outputs; their retained identities are verified, not relabeled as new
execution. Separate accepted R4e archive runs execute the native graph again.

## Recovery and refusal boundary

Five additional cases construct faults in copies of a completed retained
store. These are explicit file edits while no owner is running, not claims
that the kernel produced the same partial write during power loss.

| Retained state | Required and observed result |
|---|---|
| Final frame cut after nine header bytes | Keep the first two complete frames; recover the running job as interrupted |
| Final frame cut after its header and 30 payload bytes | Same bounded tail recovery and interrupted receipt |
| Complete final frame with altered CRC | Exit before HTTP admission; preserve runtime file bytes |
| Final WAL pointer names a missing CAS payload | Exit before admission; preserve the fault state |
| Payload bytes changed under an unchanged CAS address | CAS digest guard refuses before admission; preserve the fault state |

For both short tails, the original first 278 WAL bytes remain unchanged.
Recovery writes a new valid sequence-3 interrupted receipt with
`runtime_restarted`, the same run/program identity and the previous running
receipt CID. It does not fabricate outputs or an invocation ledger. The old
completed command still exists as an unreferenced CAS object and is not
promoted merely because its bytes are valid. Both inspectors close normally.
The existing reader already distinguished short tails from complete corrupt
frames; this release adds packaged evidence while placing that recovery
inside the new ownership boundary.

The prior final-WAL-fsync EIO and SIGKILL scenarios are repeated on the new
archive. They retain an unacknowledged completed frame that a new inspector
can read under the same run identity, without automatic retry. A separate
pre-finish interruption still yields an interrupted receipt and requires an
explicit new run. The directory syscall audit requires every observed new
CAS shard's parent barrier before descendant publication. These checks do
not establish arbitrary-write or power-loss safety.

## D3M and evidence standing

The [D3M record](../d3m/2026-11-05-airgap-r4e.md) retains the chronological
integration, including both textual conflicts and every base/left/right
stage. Both conflicts concern independently implemented versions of the same
Confix input correction: preserve encoded strings and serialize structured
wire values as JSON. The final expression keeps that shared behavior, native
source-identity checks and incoming Unicode/quote/array/null geometry tests.

The publication cutoff also adds source-linked English clause observations,
strict query-result checks, corpus links to the inert query canvas, palette
aware canvas fitting, and retained English/NAL workflow evidence. The combined
source is rebuilt with the incoming unit and HTTP wire tests plus Documents,
Spacegraph, JS, Wasm and Linux target checks. The document graph's browser
scenarios do not certify every new English UI interaction; the incoming
workflow evidence keeps its own source identity and scope.

The first ownership controller passed its browser assertions but called the
integer `response.status` as a function during shutdown. Its failed attempt
has no fabricated successful process report. The corrected controller records
the actual shutdown response or transport error, then requires normal exit
and cleanup. A recovery harness initially expected the later WAL-layer digest
message; the production CAS guard correctly refused altered bytes earlier.
The corrected assertion names that actual guard while preserving the nonzero
exit, no-admission and unchanged-byte requirements. Both failed attempts and
pre-publication-source proofs remain separately retained. Visual review also
found that expanded output cards exceeded the first capture viewport. The
final capture uses the existing Fit action, checks visible passage bounds,
and includes a separate readable passage image. The earlier behaviorally
passing capture remains retained; no product code changed for this adjustment.

## Reproduction and limits

Use the [packaged launch instructions](../../release/airgap-document/README.md)
and [offline-runtime runbook](06-offline-runtime.md), substituting this release's
archive and source commit. Extract with modes preserved. Verify the externally
recorded archive hash, then package membership, modes and `SHA256SUMS` before
launching. Never recover a WAL by deleting a held lease file.

The exact owner, browser, recovery, trace and audit programs are retained as
text under `evidence/r4e-20261105/harness/`, with per-attempt copies where a
program changed. These are external verification tools, not product controls.
They run owned processes in a namespace with only loopback networking and a
read-only extracted runtime. The host kernel, bwrap, browser, strace and
controller remain external inputs. Inspect mode omits the miner and LLVM;
it is not a claim that every ordinary HTTP endpoint becomes read-only.

Process CPU time and high-water memory below are snapshots under shared build
load, not latency or throughput benchmarks. The full JDK remains packaged.
Native allocation still requires explicit release; there is no unified native
and JVM garbage-collection or zero-copy claim. Vendor JDK patch closure,
embedded native-resource rebuild provenance, broader deployment environments,
full Nix evaluation and dense recursive LCNC visualization remain open.

## Accepted R4e checkpoint

Tested source: `2c6e55e25dbe8b38355f045a46cc6a91c378787b`. The selected JVM run reports **287 passing
checks**, one conditional external-native-solver skip, and no failures or
errors (288 cases). Fresh affected-target compilation passes. The extracted
archive passes **21 browser cases**, seven established startup refusals,
three retained-store corruption refusals, two short-tail recoveries, and five
package-integrity refusals plus a restored control. The syscall audit records
91 new CAS shard directories and zero missing
parent barriers. D3M retains 544 publication-cutoff refs through
3 merges, including 2 conflicts with retained stages.

| Artifact | Bytes | SHA-256 |
|---|---:|---|
| `airgap-document-r4e-linux-x64-2026-11-05.tar.gz` | 460613917 | `c95a4bb318f2f89eb88a2bb2179604483d531e30b30e2c534dd84e6a06c8f254` |
| `airgap-document-r4e-sources-2026-11-05.tar.gz` | 753041363 | `a5498b6d731182b7678795c737904637c641d42ed55d699c4beae1ad3df0bb61` |

Two runtime archives assembled from the pinned bytes are identical. This is
packaging reproducibility, not a complete source rebuild. The source companion
contains the tested Git archive and dependency source inventories; it does
not imply that all vendor modifications have been reconstructed.

| Scenario/process | Startup seconds | JVM high-water KiB | JVM CPU seconds | Exit |
|---|---:|---:|---:|---:|
| eio/run | 3.647 | 452728 | 10.560 | 1 |
| eio/inspect | 2.888 | 365356 | 7.720 | 0 |
| kill/run | 3.652 | 446800 | 10.620 | 137 |
| kill/inspect | 2.483 | 369204 | 8.120 | 0 |
| interruption/run | 4.888 | 412248 | 10.920 | 137 |
| interruption/recover | 3.887 | 432940 | 10.870 | 0 |
| interruption/inspect | 2.457 | 365676 | 7.690 | 0 |
| owner/first-inspector | 3.417 | 360056 | 7.820 | 137 |
| owner/second-inspector | 4.572 | 363608 | 7.930 | 0 |

The [machine-readable verification](evidence/r4e-20261105/verification.json)
binds these observations to exact sources, archives and raw evidence. Work
was performed in the cloud Linux checkout; Jim’s Mac was not changed.
