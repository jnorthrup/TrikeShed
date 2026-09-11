# Linux JVM/native ISAM evidence

This runner executes the same core Cursor/ISAM application distribution as the macOS run. It uses a Linux arm64 JDK 25 container, compiles the repository JNI source against pinned liburing 2.15, and loads that library explicitly. Linux here is the OrbStack VM kernel, not the macOS host and not a Kotlin/Native executable.

```sh
./gradlew jvmJar --console=plain
./gradlew -p bench/columnar/io installDist --console=plain
python3 bench/columnar/io/linux/run.py --mode native --rows 256 --warmup 1 --iterations 3 --trace off --output bench/columnar/results/linux-baseline
```

Docker's default seccomp policy denied `io_uring_setup` with EPERM in the recorded environment. That failure remains in the [setup-denial report](../../results/linux-native-trace/isam.json). The explicit `--unconfined` option removes only this test container's seccomp filter; it does not change the host configuration. The native workload succeeded when that option was selected:

```sh
python3 bench/columnar/io/linux/run.py --unconfined --mode native --trace both --syscalls --output bench/columnar/results/linux-native-unconfined-trace
python3 bench/columnar/io/linux/run.py --unconfined --mode native --trace off --output bench/columnar/results/linux-native-unconfined-baseline
```

`--trace application|backend|both|off`, `--trace-ops READ,STATX`, `--trace-phases read_decode`, and `--trace-every 8` select the actual observation path. Request/completion pairs retain their identity when sampled. `--syscalls` separately captures io_uring_setup/register/enter with strace and materially affects timing; use a distinct trace-off/no-strace run as the baseline. `--mode emulated` explicitly selects the ordinary JVM backend inside the same facade; `native` refuses to run unless every required operation has a native capability bit, including STATX. `auto` reports what discovery actually selects.

The image pins the Linux arm64 Temurin manifest and liburing commit `d41bf9220ec39277ff235379e9089d9e0fd6c2a5`. JNI ABI 3 adds descriptor STATX; older JNI binaries are rejected during discovery. Each Linux run is bounded and its disposable container is removed on completion or timeout. Files and JSON evidence remain in the chosen output directory. Image building requires network access for dependencies; benchmark data I/O runs through the common facade. No global package installation occurs.

The trace pairs identify channel and userData, operation, offset, requested length and normalized completion. Native STATX returns kernel CQE0; the JNI adapter serializes the existing24-byte metadata representation and reports24 to the facade. Independent byte-file verification and report generation belong to the benchmark harness. Application/backend intervals are nested, not additive, and their difference is not pure facade overhead. Synchronous ISAM does not exercise cancellation admission; no cancellation success is claimed.
