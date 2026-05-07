# NIO/Uring Boundary Todo

## Summary
- Establish a **commonMain Uring API first**, then implement platform actuals behind it.
- Treat JVM Streams, Readers, Java 1 `InputStream`s, NIO Channels, POSIX handles, mmap buffers, and Linux uring as minimally divergent forms of the same Uring-backed `Series` / `ByteRegion` access model.
- Keep `/Users/jim/work/mp-superproject/mp/acapulco/src/main/java/org/bereft/HistoryService.kt` as the reference bottom-end consumer in every adjacency until the dataframe path is delivered.
- Keep MiniDuck leakage pushed down into `libs/`; do not let MiniDuck concepts climb into Acapulco or the NIO facade.

## Todo List
1. **Define the commonMain Uring facade**
    - Create the canonical common API around Uring-shaped submit/completion, handle identity, offset reads/writes, mmap/registered buffers, and `ByteRegion`.
    - Make NIO channels/buffers adapt to this facade, rather than making uring an afterthought under Java-style NIO.
    - Preserve compatibility with full Linux uring while allowing POSIX/JVM fallback actuals.

2. **Deprecate old `userspace.*` IO boundaries**
    - Keep existing `userspace.*` wrappers only as compatibility aliases/adapters.
    - Move real IO behavior into the new NIO/Uring implementation layer when each old usage appears.
    - Avoid broad rewrites; migrate call sites as they touch the boundary.

3. **Implement platform actuals on demand**
    - Linux actual: real uring-compatible handles, offsets, registered/mmap buffers, submit/completion path.
    - POSIX actual: fd/mmap/read/write analogs matching the same handle and `ByteRegion` semantics.
    - JVM actual: unify `InputStream`, `Reader`, legacy streams, `FileChannel`, `SeekableByteChannel`, and NIO channels through the same Uring facade semantics, even when implemented eagerly underneath.

4. **Bind reactor and coroutine lifecycle**
    - Use reactor ownership from `SupervisorJob` starting points.
    - Keep lifecycle explicit: open, active, drain, close.
    - Route completions through structured concurrency, not callback soup.
    - Ensure suspend APIs are facade consumers, not hidden ambient IO.

5. **Rework ISAM access path**
    - Depart from Acapulco’s direct `FileChannel.open(...); ISAMCursor(...)` pattern.
    - Introduce Zstd block API offset access to reach ISAM v1 root cursors.
    - Keep root cursor exposure algebraic: `Cursor = Series<RowVec>`, with offsets and blocks as IO-backed lazy projections.
    - Do not flatten into MiniDuck/DataFrame concepts at this layer.

6. **Use HistoryService as the reference consumer**
    - Map its current flow: CSV fixup, ISAM write, ISAM reopen, `AssetModel.push`.
    - Replace direct Java NIO/ISAM handle leaks with the new TrikeShed NIO/Uring cursor path once the facade exists.
    - Preserve current Acapulco behavior until the dataframe delivery path is ready.

## Test / Verification Plan
- Verify `ByteRegion` offset read/write parity across JVM fallback, POSIX, and Linux uring actuals.
- Verify mmap/registered-buffer read paths expose the same cursor rows as eager channel reads.
- Verify reactor lifecycle: open -> active -> drain -> close, with no hidden global mutable IO state.
- Verify ISAM v1 root cursor access through Zstd block offsets.
- Verify HistoryService-equivalent flow can write, reopen, and push cursor data without direct `FileChannel` leaks.
- Keep tests focused; strict red-first TDD is suspended, but each migrated boundary still needs a small behavior pin.

## Assumptions
- CommonMain Uring API is the semantic authority; NIO is the compatibility facade over it.
- Mild eager behavior in JVM/POSIX fallback implementations is acceptable if the API preserves uring-compatible handles, offsets, and buffers.
- MiniDuck remains contained under `libs/` and is not part of the Acapulco-facing API.
- Tensor lowering remains an optimization target only, not the IO or cursor model.
