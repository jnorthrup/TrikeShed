# Track: Userspace Kernel Emulation Port

**Track ID:** `userspace-port_20260314`
**Branch:** `master`
**Status:** 🔄 Open

---

## Purpose

Import architectural patterns from `../userspace` Rust library for Kotlin structured concurrency, kernel bypass, and transport primitives while adapting to Kotlin's existing coroutine infrastructure.

---

## Source Evidence

- `/Users/jim/work/userspace/Cargo.toml` — Rust library definition with features for concurrency, kernel, network, syscall-net, tensor
- `/Users/jim/work/userspace/CLAUDE.md` — Userspace kernel emulation library documentation
- `/Users/jim/work/userspace/src/concurrency/` — Rust structured concurrency (job.rs, scope.rs, dispatcher.rs, launch.rs, deferred.rs, cancel.rs)
- `/Users/jim/work/userspace/src/kernel/io_uring.rs` — io_uring implementation
- `/Users/jim/work/userspace/src/kernel/ebpf.rs` — eBPF VM and JIT
- `/Users/jim/work/userspace/src/kernel/syscall.rs` — Unified syscall interface
- `/Users/jim/work/userspace/src/network/adapters.rs` — Protocol adapters (HTTP, QUIC, SSH)
- `/Users/jim/work/userspace/conductor/TRACKS.md` — Completed userspace tracks (posix_sockets, knox_proxy, kernel syscall interface)

---

## Invariants

- Kotlin coroutines already provide structured concurrency; port patterns as guidance, not direct translation
- Kernel bypass (io_uring, eBPF) requires Kotlin/Native Linux target
- Network adapter patterns must integrate with existing TrikeShed protocol routing
- All ports must follow TDD: failing Kotlin tests before implementation
- TrikeShed's CCEK and transport arrangement remain authoritative

---

## Current State

- TrikeShed has completed relaxfactory-literbike-arrangement track (arrange-01 through arrange-05)
- TrikeShed has bounded HTTP/JSON parsing tests awaiting implementation
- No Kotlin/Native target infrastructure exists in TrikeShed yet
- userspace has completed migration tracks (posix_sockets, knox_proxy, syscall interface)

---

## Slice Schema

### userspace-01 — Architectural Survey
**Status:** [x] closed
**Owner:** slave
**Corpus:** `/Users/jim/work/userspace/`

**Deliverables:**
- catalog Rust bounded behaviors with Kotlin-relevance classification ✅
- identify which patterns require Kotlin/Native vs Kotlin/JVM ✅
- document structure-concurrency pattern extraction strategy ✅
- create no code changes; Architecture documentation only ✅

**Verification:** inspect catalog and documentation ✅

**Delivered:**
- Created `survey.md` with comprehensive bounded behavior catalog
- Classified 26 bounded behaviors across 6 modules: Concurrency, Kernel, Network, Tensor, DSEL, Database
- Triage verdicts:
  - **Architecture Guidance Only (No Port):** Job, Scope, Dispatcher, Deferred, Launch, Cancellation, Channels — Kotlin coroutines already provide superior implementations
  - **Kotlin/Native Port Required:** io_uring, eBPF (future), syscalls, SIMD memcpy — requires kernel bypass
  - **Pattern Port:** NetworkAdapter trait, Protocol detection — aligns with TrikeShed protocol routing
  - **Out of Scope:** Tensor ops, MLIR, Database — separate concerns
- Recommended slice order: **SKIP userspace-02** (Kotlin has coroutines), prioritize **userspace-03** (Kotlin/Native setup)

---

### userspace-02 — Structured Concurrency Pattern Guide
**Status:** [SKIP]
**Owner:** N/A
**Corpus:** N/A

**Rationale:**
- Kotlin coroutines already provide superior implementations of Job, CoroutineScope, CoroutineContext, Dispatcher, Deferred, launch/async/runBlocking
- Rust structured concurrency patterns are valuable as architectural reference only
- No code changes or documentation needed beyond survey findings

**Replacement for this slice:**
- A comparison guide is embedded in `survey.md` showing how Rust patterns map to Kotlin coroutines
- The survey already documents that Kotlin's native coroutine infrastructure makes this slice unnecessary

---

### userspace-03 — Kotlin/Native Target Setup
**Status:** [ ] pending
**Owner:** master
**Corpus:** `build.gradle.kts`, `src/nativeMain/`

**Deliverables:**
- Add Kotlin/Native Linux target to TrikeShed if not present
- Configure nativeMain source set for syscall bindings
- Verify compilation with native target

**Verification:** `./gradlew compileKotlinLinux` (or appropriate native target)

---

### userspace-04 — Syscall Interface Design
**Status:** [ ] pending
**Owner:** slave
**Corpus:** `/Users/jim/work/userspace/src/kernel/syscall.rs`, `src/nativeMain/kotlin/borg/trikeshed/syscall/`

**Deliverables:**
- Design Kotlin/Native syscall interface
- Create failing tests for syscall operations
- Document mapping from Rust syscall to Kotlin/Native

**Verification:** tests compile, fail at runtime

---

### userspace-05 — io_uring Bindings Prototype
**Status:** [ ] pending
**Owner:** slave
**Corpus:** `/Users/jim/work/userspace/src/kernel/io_uring.rs`, `src/nativeMain/kotlin/borg/trikeshed/io_uring/`

**Deliverables:**
- Kotlin/Native bindings for io_uring operations
- Failing tests for submission queue, completion queue
- Document zero-copy I/O contract

**Verification:** tests compile, fail at runtime (requires kernel support)

---

## Next Slice

- **SKIP userspace-02** (Kotlin coroutines already superior to Rust structured concurrency patterns)
- **userspace-03: Kotlin/Native Target Setup** (REQUIRED for kernel bypass features)

---

## Evidence Log

- 2026-03-14: Track created for userspace porting
- 2026-03-14: Analyzed userspace Rust structure: concurrency, kernel (io_uring, eBPF), network, syscall
- 2026-03-14: Determined Kotlin/Native required for kernel bypass features
- 2026-03-14: Identified structured concurrency patterns as architectural guidance (not direct port)
- 2026-03-14: Noted userspace completed posix_sockets, knox_proxy, syscall interface migrations
- 2026-03-14: userspace-01 closed — Survey cataloged 26 bounded behaviors across 6 modules; classified into Architecture Guidance, Kotlin/Native Port, Pattern Port, Out of Scope; recommended skipping userspace-02