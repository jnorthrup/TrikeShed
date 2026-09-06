# Userspace NIO Contract

Userspace NIO is the portable product boundary. JVM, native, and browser
backends implement that boundary; application code, including Oroboros, does
not reach around it. Backend implementation freedom is what allows platform
tuning without making the product depend on a particular JDK or kernel.

This document records the SPI requirements from the current optimization
work. The referenced `ENDGAME.md` has not been located in this checkout;
alignment with that document remains unverified.

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
| Cancellation | A cancelled operation releases descriptors, processes, waiters, and queued work within a stated deadline |
| Errors | Failure identity survives the SPI; unsupported differs from EOF or successful empty output |
| Filesystem | Symlink behavior, missing paths, concurrent changes, and stated atomicity/durability guarantees |
| Completion | Correlation IDs and per-channel ordering survive batching, partial completion, and cancellation |
| Portability | The same portable contract checks run on each claimed backend; untested backends remain unverified |

An SPI import scan alone cannot prove these properties. JVM bytecode naturally
uses JDK representations for Kotlin strings and collections; do not label
those mappings as application bypasses. Source dependency checks must resolve
aliases and distinguish platform implementations from portable contracts.

## Performance Evidence

Measure the portable call path and the concrete backend separately, using
identical workloads. Record revision, actual backend, runtime/compiler flags,
hardware/filesystem, payload sizes, concurrency, cache state, warmup, and a
hard wall-clock deadline. Compare repeated runs before claiming a change.

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
