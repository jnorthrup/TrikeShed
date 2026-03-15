# Arrangement: Userspace Kernel Emulation Port

## Intent

Import architectural patterns and implementation lineage from `../userspace` Rust library for Kotlin/Native and Kotlin/JVM targets, focusing on structured concurrency, kernel bypass, and transport primitives.

## Owner Matrix

- **Structured concurrency patterns**
  - **Lineage source:** `/Users/jim/work/userspace/src/concurrency/` (job.rs, scope.rs, dispatcher.rs, launch.rs, deferred.rs, cancel.rs)
  - **TrikeShed owner:** `src/commonMain/kotlin/borg/trikeshed/concurrency/` or extend existing coroutine context elements
  - **Reason:** Kotlin coroutines already provide structured concurrency; port the Rust patterns as guidance/architecture reference, not direct translation

- **io_uring kernel bypass**
  - **Lineage source:** `/Users/jim/work/userspace/src/kernel/io_uring.rs`
  - **TrikeShed owner:** Kotlin/Native Linux target in future transport module
  - **Reason:** io_uring provides zero-copy async I/O; critical for high-performance transport but requires native bindings

- **eBPF JIT compilation**
  - **Lineage source:** `/Users/jim/work/userspace/src/kernel/ebpf.rs`, `ebpf_mmap.rs`
  - **TrikeShed owner:** Kotlin/Native Linux target for kernel-space execution
  - **Reason:** eBPF allows in-kernel packet filtering/processing; reserved for advanced transport layers

- **Network protocol abstractions**
  - **Lineage source:** `/Users/jim/work/userspace/src/network/adapters.rs`, `protocols.rs`, `channels.rs`
  - **TrikeShed owner:** `src/commonMain/kotlin/borg/trikeshed/net/` (extends existing transport arrangement)
  - **Reason:** HTTP, QUIC, SSH adapters align with universal listener pattern

- **Syscall interface**
  - **Lineage source:** `/Users/jim/work/userspace/src/kernel/syscall.rs`
  - **TrikeShed owner:** Kotlin/Native Linux target syscall interface
  - **Reason:** Unified syscall abstraction for kernel features

## Negative Decisions

- Do **not** port Rust code directly to Kotlin without architectural adaptation
- Do **not** create JVM-only implementations of kernel bypass primitives
- Do **not** introduce Rust FFI bindings without Kotlin/Native target infrastructure
- Do **not** duplicate structured concurrency patterns that Kotlin coroutines already provide
- Do **not** import `betanet` or `literbike` kernel bypass code without userspace consolidation

## Porting Strategy

### Phase 1: Architectural Pattern Extraction
1. Extract structured concurrency design patterns from Rust to Kotlin guidance
2. Document io_uring integration architecture for Kotlin/Native Linux
3. Map network protocol adapter patterns to TrikeShed's protocol detection/handler pattern

### Phase 2: Kotlin/Native Bindings
1. Create Kotlin/Native Linux target infrastructure if not present
2. Design syscall interface for Kotlin/Native
3. Prototype io_uring bindings for Kotlin/Native

### Phase 3: Integration
1. Connect io_uring transport to existing protocol routing
2. Integrate with CCEK typed service mechanism
3. Validate against existing transport tests

## TDD Stance

- Create failing Kotlin tests for each bounded behavior before porting
- Extract design patterns as architecture documentation, not direct code ports
- Preserve userspace Rust tests as reference implementation
- New Kotlin tests must follow TrikeShed test patterns (kotlin.test assertions)

## Immediate Follow-On

1. Survey userspace Rust code structure and bounded behaviors
2. Identify Kotlin-relevant architectural patterns
3. Determine Kotlin/Native target readiness
4. Create first bounded slice for structured concurrency pattern extraction