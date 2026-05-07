# TrikeShed NIO/Uring Todo

## Direction

Begin sequentially with the uring facade. The goal is a commonMain Uring API that generalizes the useful sequencing model underneath legacy JDK IO shapes without inheriting the overbuilt JDK IO taxonomy as the semantic center.

Flat IO is not a fallback, failover, or alternate design path for this work. JVM streams, readers, Java 1 input streams, NIO channels, POSIX file descriptors, mmap buffers, and Linux io_uring all adapt into the uring-sequenced abstraction through explicit handles, offsets, and `ByteRegion`/`Series` views.

MiniDuck remains pushed down into `libs/`. It must not leak upward into Acapulco-facing APIs, the commonMain NIO/Uring facade, or root cursor abstractions.

## Sequential Work

1. Start in the uring facade.
   - Treat `libs/uring` and `io_uring_interop` as the first concrete boundary.
   - Define the commonMain API around uring-shaped handles, submission, completion, offsets, registered/mmap buffers, and lifecycle.
   - Keep the API compatible with Linux io_uring rather than flattening it into blocking file reads.

2. Cinch down the commonMain NIO/Uring bond.
   - Make NIO-shaped buffers and channels adapters over the uring sequence model.
   - Keep `ByteRegion` as the buffer finesse point for JVM, POSIX, mmap, and Linux uring actuals.
   - Avoid endorsing generated NIO stubs as the design authority; they are surface compatibility, not the core.

3. Deprecate old `userspace.*` IO behavior at the boundary.
   - Leave compatibility names only where needed to keep existing code compiling.
   - Move real IO behavior into the NIO/Uring implementation layer as each old usage is encountered.
   - Do not introduce hidden ambient IO state or mutable global routing.

4. Implement actuals on demand.
   - Linux actuals use real uring-compatible handles, offsets, mmap/registered buffers, and submit/completion flow.
   - POSIX actuals expose fd and mmap analogs with the same handle and `ByteRegion` semantics.
   - JVM actuals unify streams, readers, input streams, `FileChannel`, `SeekableByteChannel`, and NIO channels into the same uring-sequenced API, even when an operation is mildly eager underneath.

5. Harness the reactor from supervisor starting points.
   - Reactor ownership starts at explicit `SupervisorJob`/scope boundaries.
   - Lifecycle is forward-only: created, open, active, draining, closed.
   - Suspend APIs consume the uring facade; completions route through structured concurrency rather than callback soup.

6. Depart from Acapulco direct file-channel ISAM access.
   - Use Zstd block API offset access to reach ISAM v1 root cursors.
   - Keep root cursors algebraic: `Cursor = Series<RowVec>`.
   - Preserve lazy block and row projections; do not flatten into table/dataframe assumptions at this layer.

7. Keep `HistoryService.kt` as the bottom-end reference consumer.
   - Reference path: `/Users/jim/work/mp-superproject/mp/acapulco/src/main/java/org/bereft/HistoryService.kt`.
   - Use it to validate every adjacent IO/cursor boundary until dataframe delivery exists.
   - Replace direct Java `FileChannel` and leaked handle patterns only after the uring facade provides the needed cursor path.

## Verification Targets

- Uring facade lifecycle opens, submits, completes, drains, and closes without flat IO fallback.
- `ByteRegion` offset read/write parity holds across Linux uring, POSIX, and JVM actuals.
- mmap and registered-buffer paths expose the same lazy cursor rows as eager JVM adapters.
- Reactor fanout remains structured under supervisor ownership.
- ISAM v1 root cursors are reachable through Zstd block offsets.
- Acapulco `HistoryService.kt` behavior can be mapped onto the new facade without MiniDuck leaks.

## Guardrails

- Do not make tensor the primary IO meaning.
- Do not start with SQL, dataframe, or parser-first APIs.
- Do not eagerly flatten child rows, blocks, parse trees, or blobs.
- Do not route around uring with direct flat-file fallbacks.
- Do not duplicate canonical algebra into local shims when the root TrikeShed types already exist.
