# Recorded core benchmark evidence

The JSON reports retain actual platform identity, selected backend, controls, every measured sample, input dimensions, correctness status and request/byte counts. Large trace and syscall logs are stored losslessly as `.gz`; rerunning a benchmark produces ordinary JSONL/log files. Use `gzip -dc <path>.gz` to inspect the preserved trace.

| Report | Execution and result |
| --- | --- |
| `cursor.json` | Memory Cursor pivot/group workload; 65,536 rows;256 checked cells |
| `isam-io-untraced.json` | macOS JVM emulation,1,024 rows,1 warmup/3 measured per layout; 6 verified samples;49,440 requests; zero backend errors |
| `isam-io-trace.json` | macOS full application/backend trace,16 rows;264 request/completion pairs |
| `isam-selected.json` | macOS backend READ/STATX in read_decode/query_projection, every fourth matching request; 129 retained pairs |
| `isam-io-native-host.json` | macOS native mode correctly refuses; zero ranked samples;diagnostic retained |
| `linux-native-trace/isam.json` | Linux arm64 default Docker seccomp denies io_uring_setup with EPERM; zero ranked samples |
| `linux-native-unconfined-trace/isam.json` | Explicit unconfined Linux test container;native JVM/JNI ABI 3;256 rows; 6 verified samples;12,576 requests; zero backend errors; strace enabled |
| `linux-native-unconfined-baseline/isam.json` | Same Linux native workload with application tracing and strace off; 6 verified samples;12,576 requests; zero backend errors |

Each persistent workload covers row-major and type-grouped files, write, append, metadata, file-size STATX, decode, name projection/query and closure. Independent file bytes and all values are checked. Request counts include warmups. Linux native bits are required for every operation used by the workload; no OS-name heuristic substitutes for discovery. Trace intervals include observer overhead and are not comparable to uninstrumented intervals as pure backend speed.

Linux evidence is the actual OrbStack Linux VM kernel 7.0.14, Temurin 25.0.4 arm64 and pinned liburing 2.15. The macOS run proves emulated behavior; it does not prove Linux. No Kotlin/Native Linux execution is claimed. Source/backend adapters and source-fingerprint verification are reported separately.

`verification.json` records the report inventory, JVM artifact hash and paired-trace check. `linux-*/launch.json` records the exact container arguments; `run.log.gz` retains process diagnostics. The Linux runner and all selectable trace controls are documented in `../io/README.md` and `../io/linux/README.md`.
