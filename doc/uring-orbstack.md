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
`/root/uring-results`; the local integration worktree is
`/tmp/trikeshed-uring-integration` until merged.

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
