# Userspace NIO Contract

Userspace NIO is uring-centric. `FunctionalUringFacade` in commonMain owns the
submission/completion contract for native and emulated execution. Kernel
`io_uring` is an execution backend; emulation is required when a kernel
operation, setup permission, runtime binding or host is unavailable. Runtime
language alone must not choose a less capable backend.

## Submission and ownership

- `UringSubmission` → `UringCompletion` is the canonical boundary. `userData`
  identifies an outstanding request; completions need not arrive in submission
  order. Negative `res` is Linux errno; zero is EOF for a nonempty READ; positive
  transfer results advance the borrowed buffer by exactly that many bytes.
- `FunctionalUringFacade.create(scope, ...)` is the typed context-key factory.
  Its SupervisorJob belongs to the supplied scope. A bounded channel carries
  batches of at most `entries` SQEs; one batch executes at a time. Caller-owned
  buffers remain borrowed through acknowledgement or effect settlement.
- Cancelling a queued caller skips its work. Cancelling an executing caller
  waits for the actual effects to settle before releasing buffers; it does not
  undo writes. `drain()` closes admission, joins accepted queued/active work,
  then closes backend-owned resources. A kernel transport failure that cannot
  establish a terminal CQE cannot safely promise a cancellation deadline.
- The synchronous constructor and channel methods retain exclusive-caller
  compatibility. Their prepared SQ and undrained CQ share the configured bound.
  Unscoped suspending calls borrow their caller's structured scope per batch;
  persistent application compositions use the key factory.
- Supported operations are `UringOp` masks (`capabilities`); usable native
  operations are a separate `nativeCapabilities` mask. Unsupported operations
  and unsupported SQE flags receive `-EOPNOTSUPP`. Linked/fixed/multishot requests,
  kernel cancellation SQEs and general FIFO ordering are not promised.
- Portable file operations are NOP, OPENAT with an encoded path, READ/WRITE,
  FSYNC, FTRUNCATE and CLOSE. OPENAT uses AT_FDCWD and the common Linux flags
  accepted by the adapter. Sliced ByteBuffers retain backing-array offsets;
  partial IO and read-only destination validation are explicit.
- FileChannel open now submits OPENAT to the same selected backend as transfer,
  sync and close. Its synchronous NIO compatibility surface translates only
  EOF to `-1` and negative errno to IOException. File locks and mapping remain
  explicitly unsupported. Pipe.open provides bounded nonblocking memory IO
  through the same SQE/CQE facade; selection and blocking mode are unsupported.
- Fill/drain is a general mutable protocol, independent of storage: chunked
  series, primitive buffers and counters can implement it. The pipe uses a
  bounded primitive byte region; this does not make every fill/drain a ring.

## Native discovery and hooks

Linux Kotlin/Native uses a per-backend ring, real setup and register probes.
JVM and hosted Node discover ABI-compatible callable native modules, attempt
setup and operation discovery, and require a matching kernel NOP completion.
`UringProbeReport` distinguishes host/runtime/module/ABI/permission/operation
failures. `NioCapabilityReport` snapshots the canonical selector and closes its
probe instance. A missing module is not evidence that the host kernel lacks
io_uring. Non-Linux POSIX, Node fs and JVM FileChannel primitives execute
explicitly labeled emulated submissions. Restricted JS/Wasm currently support
NOP and deterministic unsupported completions for unavailable host effects.

The JNI bridge source and Node ABI1 executor use the same OS descriptor
ownership for native and per-operation emulation. Native submissions must not
be replayed after uncertain execution, and borrowed memory must not be released
before a terminal completion. Node handles, offsets and request identities use
BigInt. No native Node addon binary is included in this slice.

Userspace eBPF programs run on actual admission/completion. Submit programs may
reject before effects. Completion programs observe the actual CQE; their return
value cannot rewrite an executed operation's byte count, errno, descriptor or
flags. Verified instruction storage is immutable, forward branches are bounded,
and accepted opcodes have interpreter implementations. This is a userspace
subset interpreter, with no kernel BPF execution or measured speedup claim.

## Ownership

- Extend existing `FileOperations`, `ChannelOperations`, `ReactorOperations`,
  `ProcessOperations`, or platform SPI contracts before adding another API.
- Keep platform types and `java.*`, `javax.*`, `jdk.*`, and `sun.*` calls in
  platform implementations. Kotlin platform mappings are not bypasses.
- Keep lifecycle, cancellation, buffer ownership, completion ordering, and
  failure semantics explicit in the portable contract. A method named
  `writeAtomically` must not imply durability for an adapter that lacks it.
- Publish actual capabilities through `NioCapabilityReport`. Unsupported
  operations need an explicit outcome; do not silently weaken guarantees.
- Resolve providers at an operation or element boundary. Keep concrete
  primitive representations in measured hot loops; avoid repeated provider
  lookup, wrapper chains, and generic indexing when they introduce boxing.

## Conformance

Every changed operation needs focused checks appropriate to its behavior:

| Concern | Required evidence |
| --- | --- |
| Buffer access | Offsets, limits, empty input, partial reads/writes, EOF, caller buffer ownership |
| Reactor behavior | No blocking on the event loop; stalled I/O does not starve another element |
| Cancellation | Queued cancellation and active-effect settlement preserve resource ownership; any deadline must reflect the actual backend |
| Errors | Failure identity survives the SPI; unsupported differs from EOF or successful empty output |
| Filesystem | Symlink behavior, missing paths, concurrent changes, and stated atomicity/durability guarantees |
| Completion | Correlation IDs survive batching, arbitrary CQE ordering, partial completion, and cancellation |
| Portability | The same portable contract checks run on each claimed backend; untested backends remain unverified |

An SPI import scan alone cannot prove these properties. JVM bytecode naturally
uses JDK representations for Kotlin strings and collections; do not label
those mappings as application bypasses. Source dependency checks must resolve
aliases and distinguish platform implementations from portable contracts.

## Investigating Slowdowns

Start with a real-world slowdown in an actual workflow. Performance testing
is an on-demand diagnostic, not a standing workload or a requirement for
every edit. Do not keep benchmarks, allocation sampling, or profiling sessions
running after the investigation. Ordinary operational telemetry is separate
from active performance testing.

Reproduce the symptom, form a specific hypothesis, and use the smallest
bounded measurement that can distinguish causes. Verify the fix in the
affected workflow, stop the diagnostic, and return to product work. Focused
correctness checks and the compilation gate still apply.

When comparison is useful, measure the portable call path and concrete backend
with the same workload. Record enough context to reproduce the result:
revision, actual backend, runtime, workload, cache state, and execution limit.
Use repeated runs only when needed to distinguish an improvement from noise.
The metrics below are options for a specific investigation, not a mandatory
dashboard or continuous test battery.

| Metric | Purpose |
| --- | --- |
| Allocated bytes/op and objects/op by allocation site | Locate boxing, path/attribute construction, and wrapper churn |
| Throughput and p50/p95/p99 latency | Detect throughput gains that damage responsiveness |
| Reactor delay and cancellation latency | Expose event-loop blocking and delayed cleanup |
| Syscalls/op, metadata probes/op, bytes copied/op | Identify redundant stat calls, copying, and missed batching |
| Open descriptors, live processes, retained heap after quiescence | Distinguish resource leaks from allocation churn |

Do not equate cumulative allocation with live heap, or high allocation with
megamorphic dispatch. Attribute allocations to sites before changing data
structures. Inspect bytecode or generated code to verify that concrete
`TwInt` placement stays a primitive `Long`; a value class can still box at
generic, nullable, or interface boundaries.

For directory scans, measure metadata probes separately from enumeration.
Returning only type flags must not require callers to allocate or repeatedly
fetch a complete platform attribute object. Coalesce metadata reads where
semantics permit; do not introduce a stale global metadata cache as a shortcut.

JVM allocation counters, JFR, native instrumentation, and `sun.*` facilities
may support diagnostic SPI implementations. Unavailable instrumentation must
produce an unavailable result, not a zero measurement. No diagnostic JDK
objects should escape into portable callers.

## Verification Status

- `jvmMainClasses` remains the compilation gate. Do not substitute the full
  `build` or `jvmTest` task for a focused change.
- Use explicit execution deadlines for focused tests and measurements;
  terminate leftover workers when a deadline expires. A timeout is not a pass.
- `scripts/common-purity.sh` currently contains a placeholder, not a working
  portability check. The documented `commonMainPurity` task is not evidence
  that this SPI boundary is enforced.
- Existing synchronous `FileOperations` and `ChannelOperations` methods do not
  by themselves promise suspension or cancellation. Audit their scheduling
  and cleanup before making that claim.
- These requirements do not certify all current callers or backends. Report
  the operation and backend actually inspected, tested, and measured.

## Repair verification and integration (2026-09-08)

`scripts/verify-uring-conformance.sh` compiles the current common/JVM IO slice
against an explicitly supplied existing support jar. It exercises disposable
file IO, the common FileChannel and Pipe paths, eBPF decisions, request identity,
unsupported features, cancellation, backpressure, drain and descriptor cleanup.
The connected `DocumentInputElement` reads exact bytes and validates their CID
through a `BtrfsUringFileVolume` using the supplied scoped uring channel. A real
file roundtrip covers OPENAT, FTRUNCATE, WRITE, FSYNC, READ and CLOSE; reopening
with `create=false, resize=false` preserves the file and document padding is
excluded. This verifies raw Volume IO, not a valid Btrfs filesystem image.

This is source-overlay evidence. The local `jvmMainClasses --offline` gate
failed with 411 compiler diagnostics from the unfinished CCEK/KeyedService and
domain migration. Diagnostics in the touched HTX/provider files concern their
unchanged TLS element dependency. No removed CCEK facade has been restored.
The gate output is retained at
`/private/tmp/trikeshed-uring-conformance/jvmMainClasses.log`.

Darwin arm64 JVM selection observed emulation with native mask zero. A separate
Kotlin/Native Darwin source overlay exercised the actual POSIX primitives,
sliced/partial buffers, EOF, sync/truncate/close and active cancellation. Linux
kernel execution and Linux-native compilation remain unverified; no new Linux
machine or privileged setup was created. Native compilation is deferred until
an authorized supported environment is available.

Integration dependencies are explicit: the saved checkout's eBPF and JVM
mapping slice was already present at this worktree's a2e1b1fdc base. Only agreed
host/module discovery/report files were transferred read-only from task
01a081d6-1466-7410-a2ff-d84b12b98af8 (worktree 827c). The filesystem owner's
`BtrfsUringFileVolume.kt` was transferred as the minimal consumer dependency;
its compatibility channel cleanup was corrected here. The caller passes the
selected channel's actual report and drains input, volume and channel in that
order. The scoped channel is dedicated to that volume's request identities.
Btrfs format/oracle work and the Linux legacy volume migration remain owned
by task 01a08193-f927-7a92-b506-a5e869e0c845 in the saved checkout. Changes here
and in those independent worktrees are unmerged; no commit, merge or live daemon
rollout is implied by a focused check.
