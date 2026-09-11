# Cursor / ISAM I/O trace sleeve

This core benchmark uses **real production code from the root JVM jar**, real temporary files, and the existing internal channel-factory seam. Friend compilation exposes that seam to the benchmark without widening the production API. There are no mocked completion results in this benchmark.

```sh
./gradlew jvmJar
./gradlew -p bench/columnar/io bench installDist --console=plain
```

Two deterministic layouts run the same workload: row-major and grouping by IOMemento type. Each writes half the rows, appends the remaining half, opens the ISAM cursor, reads and decodes every value, performs a column-name projection and filtered sum, and closes. An independent little-endian encoder checks every physical data-file byte; every decoded cell and the query checksum are verified independently. The 13-byte schema is `id:Int32, flag:Int8, amount:Float64`. Input `r` maps to `r`, `r%4`, and `((17*r+3)%101-50)/64`. The test does not depend on the old Columnar checkout or market-data fixtures.

The actual workload path is `Cursor → IsamDataFile → JvmIsamOperations → userspace FileChannel/UringChannel → FunctionalUringFacade → UserspaceChannelBackend → real filesystem`. ISAM metadata text, existence checks, data-file size and metadata-file size use the same channel factory. Size crosses the facade as STATX; the backend normalizes kernel metadata to a 24-byte result, so this is **not a claim that the raw kernel STATX CQE returns 24**. Independent verification, fixture cleanup and report writing are benchmark-side operations outside the measured workload.

Controls are independently selectable; no test suite gates running this trace sleeve:

| Gradle property | Implemented values |
| --- | --- |
| `mode` | `emulated`, `native`, `auto` |
| `rows` | 2–1,000,000; default 1024 |
| `warmup`, `iterations` | nonnegative warmups, positive measured iterations; defaults 1 and 3 |
| `trace` | `off`, `application`, `backend`, `both`; `true`/`false` remain accepted aliases |
| `traceOps` | comma-separated actual UringOp names, e.g. `READ,WRITE,STATX`; blank means all |
| `tracePhases` | comma-separated `setup,write,append,open,read_decode,query_projection,close`; blank means all |
| `traceEvery` | every Nth matching backend request, positive integer; default 1 |
| `results` | JSON output path, relative to `bench/columnar/io` unless absolute |
| `uringLibrary` | explicit JNI library path, forwarded to `trikeshed.uring.library` |

Example observation placement and sampling:

```sh
./gradlew -p bench/columnar/io bench -Prows=128 -Ptrace=backend \
  -PtraceOps=READ,STATX -PtracePhases=read_decode,query_projection \
  -PtraceEvery=4 -Presults=../results/isam-selected.json
```

Sampling selects requests and retains their paired completion. Request identity is `(channel, request)` because `userData` is scoped to the actual channel. Records include operation, descriptor, offset, requested bytes, normalized result, errno, cancellation result and monotonic timestamp. Buffered traces have a 100,000-event ceiling, preserve admitted request/completion pairs, and report omitted event counts; this also bounds automatic diagnostic replay. `backend_submit` observes admission **after** facade validation. This sleeve does not claim to observe pre-admission rejections. The bounded synchronous ISAM path does not exercise asynchronous cancellation/drain; observed `-ECANCELED` results are counted, without inventing cancellations. Existing eBPF policies are not configured by this sleeve; opcode/phase controls select observation, not permission or execution rewriting.

Application timers include encoding/decoding, facade handling, backend I/O and enabled tracing overhead. Backend timers enclose only the real `submitBatch` call. These intervals are nested and their difference is not pure facade latency. Warmups are verified but excluded from measured samples. The trace is buffered and written after timing; report counters include warmups. On failure the JSON status is `failed`, ranked samples are empty, and the process exits 2. A run with tracing off or filtered performs a separate unfiltered diagnostic replay and writes `.failure.trace.jsonl`; a replay never erases the original failure or contributes timings.

`emulated` explicitly selects existing JVM emulation. `auto` reports actual discovered availability. `native` requires actual native capability bits for OPENAT, READ, WRITE, FSYNC, CLOSE and STATX before the workload begins, and fails closed if unavailable. No backend is relabeled native based only on the host operating system.

The `installDist` output under `build/install/trikeshed-columnar-io` is a portable JDK 25 classpath. Its launcher accepts positional arguments in this order:

```text
mode rows warmup iterations results trace traceOps tracePhases traceEvery
```

For example, a Linux runner can set `JAVA_OPTS=-Dtrikeshed.uring.library=/opt/trikeshed/libtrikeshed_uring.so`. The separate `linux/` tools build and trace the real JNI boundary.

Historical motivation: Columnar commit `0534034f1cce5e3c633e4217354559aa15104447` contains active ISAM round-trip checks in `DatabinanceKlineIsamTest.kt` and `ISAMCursorKtTest.kt`, plus a sharded-column rewrite comparison in `NinetyDegreeTest.kt`. Their useful behaviors motivate this newly authored deterministic workload. No historical timings or licensed source are copied; see [the assimilation inventory](../../../docs/columnar-assimilation.md).
