# OrbStack uring workspace

The prepared image is `trikeshed-columnar-io:b163`, SHA256
`c339f6a2e1f53d0a7479f86cbfd8b61bfc6734291a94c85bf9be4ee7fdb5d6c4`.
It is Ubuntu 26.04.1 ARM64 with Temurin JDK 25.0.4 and liburing under
`/opt/liburing`. The local snapshot `trikeshed-uring-workspace:2026-09-12`
adds SSH, tmux, CMake, Ninja and Node 22.22.1 development headers.

`trikeshed-uring-home` is a named Docker volume mounted at `/root`. It holds
the Git checkout, build outputs, Gradle caches, public SSH authorization and
resume records. Container recreation preserves it. Do not remove the volume.
The original prepared image and the toolchain snapshot remain local Docker
images; no tarball export or source archive is required.

## Resume

From the Mac checkout:

```sh
docker compose -f ops/uring/compose.yaml up -d
ssh trikeshed-uring-bench
```

The SSH alias uses localhost port 22229, the existing `github_rsa` identity,
and agent forwarding. Only the public key is installed in the workspace.
The host key was read directly from the local container and pinned in
`~/.ssh/known_hosts.trikeshed-uring`.

Inside the container:

```sh
cd /root/TrikeShed
git status --short
git log -1 --oneline
git pull --ff-only
export JAVA_HOME=/opt/java/openjdk
export PATH="$JAVA_HOME/bin:$PATH"
tmux new-session -A -s uring
```

Integration branch: `codex/uring-orbstack`. Source and dependencies travel
through Git over SSH. The liburing source is pinned by
`src/linuxMain/resources/META-INF/cinterop/liburing.md`. Do not replace this
checkout with a filesystem copy. Logs and measured results belong in
`/root/uring-results`; the persistent Mac integration worktree is
`/Users/jim/work/TrikeShed-uring-orbstack`. The original Mac checkout remains
`/Users/jim/work/TrikeShed`; concurrent daemon-port edits there are preserved.

The container permits io_uring syscalls with `seccomp=unconfined`. Its SSH
port is bound to loopback. On/off comparisons use
`TRIKESHED_URING_MODE=auto|native|emulated`; the native mode must fail if setup
or execution probing fails. JVM also accepts `-Dtrikeshed.uring.mode=...`.
No global kernel sysctl change is needed.

## Verification and benchmarks

Build the full target before treating a benchmark as facade evidence:

```sh
./gradlew compileKotlinJvm compileKotlinJs --console=plain
./gradlew buildLiburing buildNodeUring buildJvmUring --console=plain
```

The shared benchmark admits NOP, WRITE and READ batches through
`FunctionalUringFacade` under an owning supervisor. It checks CQE counts,
identities, transfer lengths, payload bytes and EOF, then drains. Timings
cover warmed batch execution; file operations use cached pages. FSYNC runs
outside the timed WRITE samples. These are facade measurements, not a disk
durability or maximum kernel throughput claim. Use a fresh fixture path for
each invocation; existing paths are rejected with O_EXCL.

Record the Git commit, runtime version, mode, probe report, batch size,
admitted/settled counts and the JSON output for each run. Native-required
results must report nonzero native capabilities; emulated results must report
zero. Never label a silent fallback as a native benchmark.

Kotlin/Native's compiler in the configured distribution does not run on a
Linux ARM64 host. The ARM64 executable must be built on a supported compiler
host and run here. ARM64 execution must not be replaced with translated
x86_64 execution in comparative timings.

Current verification state is recorded in `/root/uring-results/RESUME.md`.
Unverified compilation or benchmark work remains open; a built C bridge alone
does not establish that the Kotlin facade target passes.

## Recorded target state — 2026-09-12

The README's updated build section identifies SDK Java `25.0.4.1-graal` and
Kotlin `2.4.20`. The checked-in Gradle wrapper is `9.6.1`; the separately
installed `9.7.0` is not the wrapper. The Mac uses that Graal JDK; the supplied
ARM64 Linux image uses Temurin `25.0.4`. Compare modes within a host/runtime
before comparing hosts.

At checkpoint `83eb0bdd5`, Linux `buildLiburing buildNodeUring buildJvmUring`
passes. Full JVM protocol builds fail before test execution on both hosts:
365 diagnostics in the committed, unfinished common `OroborosDaemon` port.
Mac and Linux JS compilation fail with 504 diagnostics: 441 in that daemon,
57 in other common portability work, and 6 in JS file/watcher actuals.
These are compile failures, not protocol failures or passing tests. No source
exclusion was added to bypass them, and no facade benchmark has run.

| Surface | Implementation / required evidence |
|---|---|
| TCP and Unix sockets | JVM SQEs execute through scoped POSIX FFM; ring tests cover fd identity, readiness, cancellation and drain. Execution blocked by compilation. |
| TLS 1.2 and 1.3 | JVM SSLEngine codec; ring transport tests cover fragmented records, bidirectional payloads, certificate/hostname rejection and close-notify receipt. Execution blocked by compilation. |
| TLS on JS and Kotlin/Native | Current provider is `StubTlsCodecBackend`; unsupported, not a pass. |
| HTTP/1.1 / HTX / Couch | Real transport paths plus a keep-alive fragmented-header regression; execution blocked by compilation. Some fixture servers still use raw JDK sockets. |
| WebSocket | Handshake/frame tests exist; parser coverage does not establish a live TLS transport. |
| HTTP/2 | Detection/taxonomy/ALPN coverage; no complete live HTTP/2 transport demonstrated. |
| HTTP/3 / QUIC | Current session is a simulation; no real QUIC/TLS/QPACK transport demonstrated. |
| SCTP | In-memory state-machine coverage; kernel wire transport unsupported. |
| libp2p / IPNS | Real uring/TLS/yamux/DHT dial path; most tests use mocks. Public DHT mutation is not part of local transport validation. |
| UDP multicast | Existing raw JDK adapter remains outside the ring replacement. |

JVM protocol command, after the common port compiles:

```sh
TRIKESHED_URING_MODE=emulated ./gradlew jvmTest -PfocusedTransportSlice=true \
  --tests 'borg.trikeshed.userspace.*' --tests 'borg.trikeshed.reactor.*' \
  --tests 'borg.trikeshed.htx.*' --tests 'borg.trikeshed.ws.*' \
  --tests 'borg.trikeshed.sctp.*' --tests 'borg.trikeshed.http3.*' \
  --tests 'borg.trikeshed.ipns.*' --console=plain
```

On Linux, repeat with `TRIKESHED_URING_MODE=native` and
`JAVA_TOOL_OPTIONS=-Dtrikeshed.uring.library=/root/TrikeShed/build/native/jvm/libtrikeshed_uring.so`.
Run `auto` separately and inspect the probe report. JVM native file opcodes
use JNI; socket/poll effects still use POSIX FFM and are excluded from
`nativeCapabilities`. Native-required mode on the Mac must reject the host.

Node bridge ABI verification (independent of Kotlin compilation):

```sh
TRIKESHED_URING_MODULE="$PWD/build/native/node/trikeshed_uring.node" \
  node --test src/jsTest/node/uring_node.test.cjs
```

Shared JVM benchmark, after the full target builds:

```sh
TRIKESHED_URING_MODE=emulated ./gradlew uringBenchmarkJvm --console=plain \
  -PuringBenchmarkArgs='/root/uring-results/emulated-new.bin entries=64 batch=8 bytes=4096 warmup=250 iterations=1000'
```

Repeat native with a distinct new file. Repeat each mode several times in
alternating order; keep raw JSON and runtime/probe metadata. The JNI and Node
bridges currently execute one kernel request per bridge call, so batch-size
results include that crossing cost. Do not infer native kernel batching from
the common batch admission size.
