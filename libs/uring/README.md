# libs/uring

io_uring facade AsyncContextElement and SPI loader.

## What It Is

A Kotlin Multiplatform library providing:
- **LiburingFacadeElement**: an `AsyncContextElement` shell that serves as a typed coroutine-context entry for io_uring-backed async I/O. Currently has no fields — the ring fd, submission/completion queue state, and lifecycle behavior are all unimplemented.
- **LiburingFacadeProvider** (JVM only): implements `LiburingFacadeSpi` with stub methods (`submitRead` returns 0, `submitWrite` returns 0, `poll` returns empty list). Discoverable via `java.util.ServiceLoader`.

The module is a **stub** — no real io_uring system calls are made. It exists to establish the Key/Element/SPI boundary so that transport modules (quic, ngsctp) can declare a dependency on the context key and receive a real implementation later.

## Source Layout

```
src/
  commonMain/kotlin/borg/trikeshed/uring/
    LiburingFacadeElement.kt   # AsyncContextElement shell with Key companion
  jvmMain/kotlin/borg/trikeshed/uring/
    LiburingFacadeProvider.kt  # LiburingFacadeSpi stub (ServiceLoader-discoverable)
  jvmTest/kotlin/borg/trikeshed/uring/
    LiburingFacadeProviderTest.kt  # SPI loader discovery, element lifecycle transitions
    LiburingLinuxImplTest.kt      # RED-phase specs: ring buffer mapping, SQE/CQE shapes, fanout
```

## Key / Element / Reactor Status

| Artifact | Role | Status |
|---|---|---|
| `LiburingFacadeElement.Key` (`companion object`) | **AsyncContextKey** | Correct — singleton routing identity |
| `LiburingFacadeElement` | **AsyncContextElement** | Shell only — no ring state, no open/close override |
| `LiburingFacadeProvider` | `LiburingFacadeSpi` impl | Stub — returns 0/empty |
| `UringSqe` / `UringCqe` (test-only) | PDU shape specs | RED-phase documentation, not in main sources |

No `ReactorSupervisor` integration yet. Element inherits `SupervisorJob` from `AsyncContextElement.supervisor` but does nothing with it.

## Dependencies

- **Root project** `borg.trikeshed.context`: `AsyncContextElement`, `AsyncContextKey`, `ElementState`, `LiburingFacadeSpi`
- **Root project** `borg.trikeshed.context` (jvmMain): `loadLiburingFacadeSpi()` via `NioSpiLoader`
- **kotlinx.coroutines**: `SupervisorJob`

## Key Collision with Root Userspace Hierarchy

The root project defines a **sealed** `AsyncContextKey` in `borg.trikeshed.userspace.context` with a nested `LiburingKey` object and a corresponding `LiburingElement`. This module's `LiburingFacadeElement.Key` is an `open class AsyncContextKey<LiburingFacadeElement>` — the standard (non-sealed) pattern.

Resolution: keep this module's open-class Key. The root sealed hierarchy serves a different purpose (NIO backend selection). These should coexist or the sealed hierarchy should be collapsed into this module's type.

## SPI Registration

`LiburingFacadeProvider` is registered via Java SPI:
- `LiburingFacadeSpi` interface defined in root `src/commonMain/.../context/LiburingFacadeSpi.kt`
- Duplicate `LiburingFacadeSpi` also exists in root `src/commonMain/.../userspace/kernel/spi/LiburingFacadeSpi.kt` (different package)
- Provider registration presumably in `META-INF/services/borg.trikeshed.context.LiburingFacadeSpi`
