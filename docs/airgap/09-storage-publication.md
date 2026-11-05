# R4d: directory publication and uncertain receipt commits

R4d addresses an observed JVM storage gap and exercises the point between
writing a completed receipt's board-log frame and acknowledging its flush.
It keeps the [R4c document graph](08-interrupted-execution.md), pinned LLVM
modules and explicit native-buffer ownership. The additional behavior is in
the existing filesystem SPI; there is no replacement executor or journal.

## The directory gap and correction

The original packaged traversal created 89 CAS shard directories. Its syscall
trace contained no successful parent-directory sync between any of those
creations and the first descendant CAS-file rename. File content and the leaf
directory were synced, but newly created ancestor names had no corresponding
barrier. This is an observed publication-order gap, not an observed loss of
89 objects. The traversal itself completed successfully.

The POSIX filesystem adapter already syncs a newly created directory and its
parent. `JvmFileOperations.mkdirs` now does the same, from the nearest existing
directory outward. It handles a competing directory creation without replacing
the entry and refuses an ancestor that is a file. Exclusive `createDirectory`
syncs its parent after successful creation and still refuses an existing leaf.
`writeAtomically` uses this directory-creation path before opening its temporary
file; file force, atomic replacement and final parent force remain intact.

The effect stays at the existing JVM userspace filesystem boundary. Additional
sync calls are deliberate durability work, not a claimed performance saving.
Compatibility checks exercise an already-open reader across atomic replacement,
missing ancestors, an occupied file ancestor, exclusive creation and an existing
symlink to a directory. They do not substitute for the traced sync ordering.

A syscall audit reconstructs unfinished/resumed calls, finds each successful
CAS-directory creation, and requires a successful sync of its parent before
the first descendant file publication. The accepted trace and the original
missing-barrier trace remain independently inspectable. Successful kernel
ring setup is also observed; metadata operations and the directory barriers
remain distinct from READ/WRITE SQEs. Ring setup alone does not establish
which kernel operation performed every transfer.

This correction covers new directories created through this SPI. It does not
retroactively establish the history of an already-existing directory hierarchy,
repair incomplete source admission, or prove power-loss survival on every
filesystem and storage device.

## Acknowledgment is a separate boundary

The production board owner writes a CAS command, appends its pointer as a
CRC32C-protected WAL frame, syncs the CAS, then flushes the WAL and its parent.
Only after that barrier does it publish the projection and complete command
replies. An append/flush failure poisons that owner; a new owner must reopen
and inspect the retained log.

The UI submits one explicit Run request. In this packaged profile, its actual
WAL begins with `queued`, then `running`; queue admission and dispatch remain
owned by Kanban. “Explicit run” describes the user gesture, not the absence of
an internal queue. The following faults occur after the native scan has
returned and before the last graph node is released from the debugger. They
do not exercise recovery of work that is still waiting in the queue.

The controller attaches `strace` to its own Java process, checks attachment
of all observed threads, and arms a path-filtered fault on
`/state/runtime/.kanban/board.wal`. A separate two-file control verifies that
this path filter restricts fault injection: an unrelated file's fsync succeeds
while the selected file receives EIO. The actual owned JVM PID, tracer command,
thread attachment, syscall trace, browser request and pre/post-fault WAL bytes
are retained. No successful write return is fabricated.

| Fault | Observed request outcome | Live owner | Reopened evidence |
|---|---|---|---|
| EIO on final WAL fsync | HTTP 503, `receipt_commit_failed`, no receipt | Last published board row stays running; failed owner cannot acknowledge the new frame | Same run is completed when the new owner reads the complete frame |
| SIGKILL at final WAL fsync | Transport response unavailable; outcome unknown | Actual JVM exits 137, with no writer cleanup | Same run is completed when the new owner reads the complete frame |

The browser sends no automatic retry in either case. The new inspector has no
miner or LLVM runners, hides installation assets and bitcode, and reopens the
same six source revisions. It reads exactly one run with the original run and
program identities. The verified quotation and invocation ledger come from
that retained completed receipt; no second execution supplies the answer.

These are deliberately different from R4c's pre-finish interruptions, which
recover as interrupted because no completed frame was appended. Here the
completed frame is readable after the process boundary even though the first
owner did not return a receipt. This demonstrates why a lost/failed response
cannot establish either success or cancellation and why retrying an effect
without inspecting its identity can duplicate work.

The host kernel survives both experiments. Its readable pages may retain a
write whose earlier fsync failed or never returned. The inspector's startup
performs its own recovery and flush. None of these facts establishes that the
same frame would survive power loss. The SIGKILL check is at a selected syscall
boundary; it is not an arbitrary partial-byte write or hard native cancellation.

## Independent receipt and WAL audit

After the owners exit, the audit checks every CAS object's path hash. It decodes
both saved WAL byte streams, verifies magic, version, increasing sequence,
length and CRC32C, then follows each pointer to the exact CAS command. The
pre-fault log contains `queued` and `running`; the post-fault log adds one
`completed` frame for the same job. Reopening must leave those WAL bytes
unchanged. The completed receipt must reference the exact prior running
receipt and retain the same program identity.

The audit also checks all six source revisions, the original 111,243-byte PDF,
the exact UTF-16 quotation range 18477–18654 on physical page 10, eight consumed
wires, native guard verification and `liveBytesAfter=0`. An unreferenced CAS
object is not promoted into a receipt merely because its hash is valid.

In the EIO case, the writer propagates its failed board-owner cleanup as a
nonzero exit. Its final cleanup export is absent; the evidence does not claim
that every writer cleanup completed successfully. The native call's recorded
zero live-byte result precedes this publication fault. The fresh inspector's
normal cleanup is separately verified. SIGKILL has its own explicit no-cleanup
classification and is not conflated with the EIO shutdown error.

## Refusals and retained attempts

An additional startup control injects EIO into the CAS-root directory fsync.
The error must propagate from `JvmFileOperations.mkdirs` before HTTP admission,
source-admission publication or board-WAL creation. The partial state remains
as failure evidence. Existing fresh/existing-state, module-identity and retained
source-identity refusals remain checked, as do package membership, mode and
hash refusals.

The first storage-fault harness incorrectly required a zero exit after EIO;
the production owner correctly propagated its durability failure. Its cleanup
handler then timed out while stopping the waiting browser, so that attempt
has no successful controller report or recovered-inspector claim. The next
harness records the expected nonzero writer exit and retains shutdown errors.
Both successful preliminary storage scenarios are kept with their exact tools.
The first extracted-archive EIO check also raced an asynchronous response-recording
callback after the page had already received HTTP 503. Its harness now awaits
the exact run response directly. That failed archive attempt remains retained;
no application behavior or receipt assertion was weakened.

The first independent audit expected `validating` as the first WAL status;
the actual profile retains `queued`. A second audit reached the quotation and
needed integer indices for JSON coordinates represented as integral floating
values. Both failed audit programs and logs remain. Final assertions still
require the exact run chain, frame CRCs and known quotation boundaries.

## Scope and reproduction

The [D3M record](../d3m/2026-11-05-airgap-r4d.md) binds this correction to the
combined source. The JVM application and selected tests are rebuilt. Other
targets reuse R4c's successful compile evidence only after checking that their
source sets, build files and contracts are byte-identical; no fresh non-JVM
compile is claimed.

Use the packaged launch and isolation instructions in the
[release README](../../release/airgap-document/README.md) and
[offline-runtime runbook](06-offline-runtime.md). The retained storage harness
adds external `strace` fault control on the verification host; it is not an
application dependency or a new product control. Only trace owned processes.
Preserve archive modes when extracting. Verification-host kernel, browser,
namespace tooling and tracer remain external inputs.

Vendor JDK modifications, embedded native-resource source-build closure,
arbitrary-write/power-loss recovery, broader deployment environments and the
full recursive LCNC visualization remain open. The complete JDK remains in the
package; this increment does not claim a smaller runtime or benchmark gains.
