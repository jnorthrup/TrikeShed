# io_uring synchronization benchmark — 2026-10-17

All 171 timed JVM forks completed with validated payloads/completions; all samples are retained. Real 8- and 64-SQE kernel submissions were verified. Batch64 improved tested cached READ/WRITE throughput over batch1; batch8 varied, including a slower READ case with four owners on one CPU. The paired comparisons do not establish a consistent NONE or MONITOR advantage for ISAM append throughput or reactor p95 latency on this host. ISAM currently uses singleton SQE submissions even when appending 64 rows per forced call.

Measurements used an Apple M3 Pro (Mac15,7), Ubuntu 24.04 ARM64 under OrbStack, kernel `7.2.9-orbstack-00391-g2b298d05fbd4`, GraalVM JDK 25.0.4, Kotlin 2.4.21 and liburing 2.15. The disposable container had guest affinity restricted to CPU 0 or CPUs 0–3, no CPU quota, an overlayfs writable layer, no host mounts and no network during timing. Affinity constrains guest CPUs; the physical host and VM are shared. These are cached file and guest CPU measurements, not physical SSD limits.

An owner is a separate serial executor with its own database, ring and FIFO. NONE state is never concurrently accessed by several owners. MONITOR is uncontended here; this measures policy overhead under valid ownership, not contended shared-database behavior. NONE removes optional owner-state synchronization while lower backend, descriptor and kernel-wait locks remain. Native ring baselines have no NONE/MONITOR policy.

Absolute figures below are medians across JVM forks. Throughput is total owner work divided by the global phase wall time, including coordination. CPU is cgroup CPU time divided by that wall time: **100% means one occupied guest CPU; four CPUs allow 400%**. CPU time per operation is the resource cost; a lower CPU percentage alone may reflect slower I/O. JVM GC/JIT/dispatch threads and benchmark instrumentation are included.

The mode ratios are medians of per-repetition NONE/MONITOR ratios, with exploratory 95% percentile bootstrap intervals from 20,000 whole-pair resamples. They need not equal the ratio of the two absolute medians. No outliers were discarded. Seven pairs and VM variability limit precision; full fork values and ranges are in `summary.json`.

ISAM used eight Long columns, one uncompressed 64-byte group, no mmap and no application row cache. Each owner appended 32,768 rows in 512 calls of 64 rows, with one data FSYNC per call. A seed row, two warmup runs of 8,192 rows, and full reader verification were outside measurement. Reads decoded and checksummed all eight columns over a warm OS page cache: four scans of 32,769 rows and 65,536 deterministic point reads per owner. This is ISAM throughput; the JVM Couch FileOperations path uses java.nio and is not measured as native database I/O.

| Topology | NONE rows/s | MONITOR rows/s | Paired rate ratio [95% CI] | CPU % NONE / MONITOR | CPU µs/row NONE / MONITOR |
| --- | --- | --- | --- | --- | --- |
| 1 CPU / 1 owner | 12,482 | 11,743 | 0.959 [0.879, 1.161] | 94.5 / 92.0 | 71.8 / 81.3 |
| 1 CPU / 4 owners | 35,118 | 22,383 | 1.109 [0.836, 1.396] | 99.6 / 99.5 | 28.3 / 44.4 |
| 4 CPUs / 4 owners | 34,872 | 41,689 | 0.924 [0.615, 1.215] | 171.5 / 202.4 | 45.1 / 46.0 |

Every append-throughput interval includes 1. The append CPU-cost ratios also include 1: 0.986 [0.718, 1.172], 0.901 [0.715, 1.198], and 0.954 [0.891, 1.074], respectively. The results support retaining both options; they do not establish a universal winner.

Scan and point-read phases do not exercise the optional ISAM writer monitor. Their mode differences are controls for fork/runtime variability, not evidence of read-lock savings.

| Topology | Read control | NONE rows/s | MONITOR rows/s | CPU % NONE / MONITOR |
| --- | --- | --- | --- | --- |
| 1 CPU / 1 owner | scan | 145,680 | 191,529 | 100.0 / 100.0 |
| 1 CPU / 1 owner | point_read | 130,038 | 150,887 | 100.0 / 99.9 |
| 1 CPU / 4 owners | scan | 256,685 | 151,216 | 100.0 / 100.0 |
| 1 CPU / 4 owners | point_read | 169,262 | 160,552 | 100.0 / 100.0 |
| 4 CPUs / 4 owners | scan | 453,018 | 457,014 | 340.5 / 325.5 |
| 4 CPUs / 4 owners | point_read | 265,734 | 316,844 | 232.6 / 270.9 |

The batch baseline used native NOP, positioned 4 KiB WRITE and READ SQEs with distinct buffers and offsets within each batch. Cached blocks were reused. WRITE forced once after its measured phase, so these are not per-batch durable write rates. Each configuration had two separate warmup rounds plus 256 warmup batches, followed by 16,384 measured SQEs for batch 1/8 or 32,768 for batch 64, per owner. Rates and CPU costs normalize those differing operation counts. Five fresh JVM forks were retained per configuration.

The table reports global wall throughput, CPU use/cost and the median owner batch p95. The batch p95 covers admission through terminal return for the whole batch; it is not a per-SQE or pooled-owner percentile. Preparation and payload validation are additionally included in wall throughput.

| Topology | SQEs/batch | READ / WRITE ops/s | CPU % READ / WRITE | CPU µs/SQE READ / WRITE | Owner batch p95 µs READ / WRITE |
| --- | --- | --- | --- | --- | --- |
| 1 CPU / 1 owner | 1 | 14,695 / 7,870 | 100.0 / 100.0 | 68.04 / 126.40 | 91.2 / 306.5 |
| 1 CPU / 1 owner | 8 | 32,383 / 23,955 | 100.4 / 100.0 | 31.05 / 41.72 | 660.3 / 1453.1 |
| 1 CPU / 1 owner | 64 | 102,240 / 74,685 | 99.7 / 100.0 | 9.75 / 13.39 | 3634.9 / 4136.7 |
| 1 CPU / 4 owners | 1 | 32,100 / 11,756 | 100.0 / 99.9 | 31.21 / 85.00 | 362.7 / 1207.7 |
| 1 CPU / 4 owners | 8 | 13,708 / 68,307 | 100.0 / 100.0 | 72.93 / 14.64 | 6547.9 / 1449.0 |
| 1 CPU / 4 owners | 64 | 108,520 / 122,171 | 100.0 / 100.0 | 9.21 / 8.18 | 5696.5 / 5657.7 |
| 4 CPUs / 4 owners | 1 | 91,249 / 55,600 | 342.3 / 351.0 | 38.52 / 63.64 | 97.8 / 150.4 |
| 4 CPUs / 4 owners | 8 | 185,304 / 255,156 | 333.7 / 354.3 | 17.36 / 14.01 | 246.8 / 263.9 |
| 4 CPUs / 4 owners | 64 | 390,268 / 478,974 | 370.7 / 370.5 | 9.15 / 7.71 | 1119.0 / 1009.0 |

Paired batch64/batch1 gains use matching repetition numbers. CPU ratios below 1 mean less measured CPU time per SQE. These are mode-independent baseline results.

| Topology | Opcode | Throughput ratio [95% CI] | CPU/SQE ratio [95% CI] |
| --- | --- | --- | --- |
| 1 CPU / 1 owner | NOP | 5.825 [4.953, 7.547] | 0.169 [0.132, 0.205] |
| 1 CPU / 1 owner | READ | 5.903 [5.400, 8.149] | 0.170 [0.122, 0.185] |
| 1 CPU / 1 owner | WRITE | 9.363 [6.021, 11.944] | 0.107 [0.084, 0.167] |
| 1 CPU / 4 owners | NOP | 5.866 [4.103, 6.926] | 0.171 [0.144, 0.242] |
| 1 CPU / 4 owners | READ | 3.205 [2.904, 4.022] | 0.312 [0.249, 0.347] |
| 1 CPU / 4 owners | WRITE | 11.291 [5.715, 12.778] | 0.089 [0.078, 0.175] |
| 4 CPUs / 4 owners | NOP | 6.633 [5.781, 10.402] | 0.153 [0.092, 0.172] |
| 4 CPUs / 4 owners | READ | 6.308 [3.956, 7.603] | 0.188 [0.168, 0.277] |
| 4 CPUs / 4 owners | WRITE | 8.072 [6.798, 8.832] | 0.129 [0.121, 0.185] |

The native completion baseline blocked on a FIFO READ CQE through the common facade. Each owner performed 256 warmup and 2,048 measured events; five forks were retained. The producer idled for 500 µs before timestamping its native WRITE request. Latency runs from that timestamp to consumer completion observation, including writer admission/JNI, I/O and coroutine delivery. The fixture idle is excluded. This is not kernel CQE-to-scheduler latency. Quantiles pool owner events within each fork, then take the median fork quantile.

| Topology | p50 µs | p95 µs | p99 µs | Events/s | CPU % | CPU µs/event |
| --- | --- | --- | --- | --- | --- | --- |
| 1 CPU / 1 owner | 82.2 | 477.3 | 2576.5 | 687 | 74.0 | 993.3 |
| 1 CPU / 4 owners | 40.3 | 585.9 | 2773.0 | 4,353 | 100.0 | 229.7 |
| 4 CPUs / 4 owners | 108.2 | 368.7 | 806.7 | 2,012 | 104.2 | 509.3 |

The reactor comparison used the same event count, pacing and latency definition, with seven paired forks. Its watch is **POSIX-emulated POLL_ADD under a native-selected ring**; the current JNI encoder has no native POLL_ADD. The producer WRITE and subsequent payload READ are native. This distinguishes reactor policy behavior from the native READ completion baseline above.

| Topology | Policy | p50 µs | p95 µs | p99 µs | Events/s | CPU % | CPU µs/event |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 1 CPU / 1 owner | NONE | 168.7 | 473.8 | 905.0 | 562 | 63.4 | 1162.4 |
| 1 CPU / 1 owner | MONITOR | 134.4 | 439.4 | 1350.0 | 566 | 56.2 | 992.3 |
| 1 CPU / 4 owners | NONE | 28.5 | 283.8 | 1029.5 | 4,707 | 99.8 | 212.4 |
| 1 CPU / 4 owners | MONITOR | 37.3 | 997.8 | 3860.5 | 3,388 | 99.9 | 295.1 |
| 4 CPUs / 4 owners | NONE | 118.8 | 480.3 | 933.7 | 1,801 | 116.0 | 617.2 |
| 4 CPUs / 4 owners | MONITOR | 123.0 | 389.6 | 698.5 | 1,808 | 113.3 | 626.5 |

| Topology | NONE/MONITOR p95 [95% CI] | Event-rate ratio [95% CI] | CPU/event ratio [95% CI] |
| --- | --- | --- | --- |
| 1 CPU / 1 owner | 1.047 [0.904, 1.101] | 1.054 [0.968, 1.108] | 1.042 [1.009, 1.114] |
| 1 CPU / 4 owners | 0.551 [0.482, 1.196] | 1.136 [0.990, 1.267] | 0.878 [0.791, 1.006] |
| 4 CPUs / 4 owners | 1.105 [0.735, 1.985] | 0.997 [0.985, 1.021] | 0.985 [0.799, 1.221] |

Every reactor p95 interval includes 1. The 1 CPU / 1 owner NONE case used slightly more CPU per event in this sample; that is a resource-use result, not a latency advantage. Higher event rates with four owners on one CPU show overlap between independent waiters; four CPU affinity does not by itself guarantee a faster paced wake workload.

A separate low-rate measurement performed four cycles per owner, each preceded by 250 ms idle, with one warmup cycle and three forks. It measures low-duty wait/write/read CPU, not zero-event idle; its fixed order and short warmup make the figures descriptive. Low-rate CPU remains nonzero, so blocking wait support alone does not establish zero idle overhead.

| Topology | Native READ CPU % | Reactor NONE CPU % | Reactor MONITOR CPU % |
| --- | --- | --- | --- |
| 1 CPU / 1 owner | 3.17 | 3.72 | 2.85 |
| 1 CPU / 4 owners | 8.75 | 7.73 | 8.31 |
| 4 CPUs / 4 owners | 8.91 | 7.63 | 9.93 |

Separate syscall profiles supplied kernel evidence without tracing the timed matrix. Every requested submission count matched the successful `io_uring_enter` return count. The batch8/64 profiles each contained 24 full data batches plus five singleton control/probe submissions; batch1 contained 29 singleton submissions. Both database profiles contained 469 singleton submissions, maximum batch 1. In the full database measurements each owner appended with 32,768 WRITE and 512 FSYNC requests, within 37,888 total requests, all backend admission batches of size 1.

The native wake profile recorded 19 timed `poll` calls, nine longer than 5 ms and a maximum of 22.034 ms with a 20 ms producer idle. Reactor profiles also recorded blocking polls (maxima 25.610 ms NONE, 25.589 ms MONITOR). The benchmark gate observes entry into readiness waiting; it does not prove that the OS parked before every timed write. These profiles establish real batching and blocking-wait capability without claiming a kernel timestamp for each latency sample.

The implementation used existing common userspace NIO/uring compositions with bounded channels, owning SupervisorJobs and drain/join. Work was assigned as 25 concrete tasks across four owners: database fixture/measurement (7), ring/reactor fixture/measurement (7), Linux/runtime/provenance (7), and JVM integration/matrix/report (4). No production tuning or synchronization default changed for these measurements.

Relevant current production sources and all four benchmark sources were freshly compiled as one Kotlin module over a frozen cached full JVM class/dependency snapshot, using matching compiler plugins; JNI ABI 6 was built from the current source with `-O3 -pthread -Wall -Wextra -Werror`. This was not a clean full repository build. Source SHA-256 values and runtime tree/library fingerprints are recorded; all 23 recorded source hashes matched after execution. Cached classes were insulated from concurrent workspace builds. All 171 native-required runs validated required native opcodes, payloads and completion identities, and all admitted work was joined before fixture cleanup. DB receipts retain checksums/counters rather than raw payloads or explicit native masks; the matching source enforces native capability and full-cell checks before publishing each receipt. Separate profiles substantiate kernel execution, rather than tracing every timed fork. Only the owned disposable benchmark container was removed; existing containers were preserved.

Reproduction requires Linux, a cgroup v2 CPU controller, a usable native binding and a new disposable fixture directory: the driver deletes its fixture tree on exit. The regular build entry points are `./gradlew :jvmMainClasses :stageDaemonLib :buildJvmUring`; this task used the recorded fresh-source/cached-class build described above. After building, use the following JVM settings and class entry point, supplying the built JVM/runtime classpath and absolute native-library path. In a container set its cpuset before starting each fresh JVM; `taskset` below is the equivalent guest process-affinity setting. Cgroup CPU accounting requires no other work in that container/cgroup.

```bash
LANG=C.UTF-8 LC_ALL=C.UTF-8 taskset -c 0 java \
  --enable-native-access=ALL-UNNAMED \
  -Xms512m -Xmx512m -XX:+UseG1GC \
  -XX:ParallelGCThreads=1 -XX:ConcGCThreads=1 -XX:CICompilerCount=2 \
  -Dkotlinx.coroutines.scheduler.core.pool.size=4 \
  -Dkotlinx.coroutines.io.parallelism=8 \
  -Dtrikeshed.uring.mode=native \
  -Dtrikeshed.uring.library=/absolute/path/libtrikeshed_uring.so \
  -cp '/absolute/path/jvm-classes:/absolute/path/runtime-jars/*' \
  borg.trikeshed.userspace.benchmark.JvmSynchronizationBenchmark \
  workload=db mode=NONE workers=1 rows=32768 batch_rows=64 scans=4 \
  point_reads=65536 warmup_rounds=2 warmup_rows=8192
```

For each topology use `(cpuset, workers) = (0, 1), (0, 4), (0-3, 4)`. Repeat DB and `workload=reactor_wake` with both `mode=NONE` and `mode=MONITOR`, seven paired fresh JVMs, randomizing topology/mode order with seed 20261017. Wake arguments are `iterations=2048 warmup=256 producer_idle_ns=500000`. Use the same arguments for `workload=native_wake` with five forks; its policy is reported as NOT_APPLICABLE. For `workload=batch`, run `batch=1 iterations=16384`, `batch=8 iterations=2048` and `batch=64 iterations=512`, each with `warmup=256 warmup_rounds=2` and five forks, randomizing batch order per topology/repetition. Low-rate wake cases use `iterations=4 warmup=1 producer_idle_ns=250000000`, three forks per topology/path/policy. The driver emits one JSON receipt to stdout and rejects unavailable required native opcodes. Repeat syscall profiles separately using `strace -f -T -e trace=io_uring_enter,io_uring_setup,io_uring_register,poll,ppoll`.


- [summary.cbor](summary.cbor): Absolute metrics, individual fork values, paired ratios and bootstrap intervals.
- [samples.csv](samples.csv): 345 normalized phase records from 171 JVM forks.
- [kernel-profiles.cbor](kernel-profiles.cbor): Submission-count/return histograms and blocking-poll evidence.
- [syscall-profiles.txt.gz](syscall-profiles.txt.gz): Eight raw separate syscall traces.
- [metadata.cbor](metadata.cbor): Machine/runtime, exact compiler/JVM invocation, source/binary hashes and cleanup receipt.
