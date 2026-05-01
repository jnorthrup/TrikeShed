# libs/uring — TODO

## Status: STUB (Key/Element shape exists, no real io_uring)

## Pure Boundary Audit

### Keys (correct)
- `LiburingFacadeElement.Key : AsyncContextKey<LiburingFacadeElement>` — singleton routing identity. Pure.

### Elements (stateful — needs real implementation)
- `LiburingFacadeElement` extends `AsyncContextElement`
  - Currently: no fields, no open/close override. Lifecycle transitions via inherited `open()`/`close()` only.
  - [ ] Add ring fd field
  - [ ] Add submission queue (SQ) and completion queue (CQ) state
  - [ ] `open()`: call `io_uring_setup`, store ring fd, mmap SQ/CQ rings
  - [ ] `drain()`: drain completion queue, reap all pending CQEs
  - [ ] `close()`: call `io_uring_exit`, unmap rings
  - [ ] Lifecycle: CREATED → OPEN (ring setup) → ACTIVE (submit/completions) → DRAINING → CLOSED (ring teardown)

### Statics that should stay static
- `LiburingFacadeProvider` (jvmMain) — SPI loader stub. Correct as a plain class implementing `LiburingFacadeSpi`.

### Test-only specs (not in main sources)
- `UringSqe` data class — SQE shape documentation (opcode, fd, addr, len, off, userData)
- `UringCqe` data class — CQE shape documentation (userData, res, flags)
- [ ] Move these to `commonMain` when real implementation begins

### Root-level duplicate collision
- Root `src/commonMain/.../context/LiburingFacadeSpi.kt` — SPI interface used by this module
- Root `src/commonMain/.../userspace/kernel/spi/LiburingFacadeSpi.kt` — **duplicate** SPI interface in different package
- Root `src/commonMain/.../userspace/context/AsyncContextKey.LiburingKey` — sealed hierarchy key for LiburingElement
- Root `src/commonMain/.../userspace/context/AsyncContextElement` subclass — LiburingElement with `key = AsyncContextKey.LiburingKey`
- [ ] **Reconcile**: decide whether root's sealed `LiburingKey` hierarchy or this module's open `AsyncContextKey` is canonical. Recommendation: this module's open-class Key is the right pattern. Collapse root's sealed hierarchy userspace LiburingElement into this module.

## Integration Steps

1. **quic**: `QuicElement` should look up `LiburingFacadeElement` from coroutine context and use its SPI for zero-copy reads/writes
2. **ngsctp**: same — `SctpElement` should use io_uring for I/O
3. **root Reactor**: root `userspace.nio.Reactor` uses `PlatformBackend` — `LiburingFacadeElement` should provide a `PlatformBackend` adapter
4. **SPI consolidation**: eliminate duplicate `LiburingFacadeSpi` in `userspace.kernel.spi` package. Keep the one in `borg.trikeshed.context`.

## Path to Stable

1. Flesh out `LiburingFacadeElement` with real ring state (ring fd, SQ/CQ ring pointers)
2. Implement `open()`/`close()` with JNI or Kotlin/Native CInterop to `liburing`
3. Implement real `LiburingFacadeProvider.submitRead()`/`submitWrite()`/`poll()` using io_uring syscalls
4. Move `UringSqe`/`UringCqe` from test to main sources
5. Implement `PlatformBackend` adapter so root Reactor can use this module
6. Add lifecycle tests (ring setup → submit → reap → teardown)
7. Add concurrency tests (multiple coroutines submitting SQEs concurrently)
8. Reconcile with root `userspace` sealed hierarchy — delete root copies, point imports here
