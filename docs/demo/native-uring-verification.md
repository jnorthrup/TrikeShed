# Native uring verification — 2026-09-08

Darwin ARM64 executed the production POSIX backend against disposable files and a pipe. It reported `nativeCapabilities == 0` and `emulated: Darwin has no Linux io_uring kernel`.

Observed passes: OPENAT, sliced explicit-length WRITE, partial READ, EOF=0, FSYNC, FTRUNCATE, read-only and malformed buffer rejection, unsupported operation rejection, descriptor close and backend cleanup, bounded queue rejection, and an active pipe READ which retained its borrow and finished after caller cancellation.

The focused executable was built and run with:

```sh
cd /Users/jim/.codex/worktrees/4721/TrikeShed
python3 /private/tmp/trikeshed-native-uring/compile.py
/private/tmp/trikeshed-native-uring/probe.kexe
```

[Compiler invocation](/private/tmp/trikeshed-native-uring/compile.py), [compiler output](/private/tmp/trikeshed-native-uring/compile.log), and [executable assertions](/private/tmp/trikeshed-native-uring/Probe.kt) are local verification artifacts. The compiler was Kotlin/Native 2.4.10 with cached macOS ARM64 coroutines 1.11.0 and atomicfu 0.32.1; the compiler's existing dependency-cache lock required sandbox escalation.

This source overlay compiled the repository's `Buffer`, `ByteBuffer`, `ByteOrder`, `UringOp`, `Liburing`, common `UserspaceIO`, POSIX `UserspaceIO`/`PosixUringIO`/`NativeUringAdapter` declaration, and macOS `NativeUringAdapter`/`Liburing` actuals. Temporary support sources supplied only the small Join/Series/MetaSeries shape, Long BitMasked operators, native byte order, FanoutEvent, and the backend interface signature. The POSIX IO effects and buffer implementation were production code. The overlay did **not** compile the full common facade, application consumers, or whole project. Shared file assertions also have Linux and macOS test wrappers in the repository.

Linux `NativeUringAdapter`, `LinuxLiburingFacade`, and the repaired Linux channel/file consumers remain **uncompiled and unexecuted** here. The repository has no checked-out `liburing` source/library on this host. Existing Colima was stopped and the configured `claw-vm` SSH endpoint timed out; no machine was started or provisioned.

The Linux source now creates a ring per backend, performs setup, register-probe discovery and a no-user-buffer NOP enter/CQ probe, and intersects probed operations with its actual encoders. It encodes NOP, OPENAT, READ, WRITE, SEND, RECV, CLOSE, FSYNC, and FTRUNCATE; other admitted encodings receive explicit unsupported status or use POSIX emulation when supported there. Execution is currently serial per owned ring; the common contract promises no FIFO completion order. A submission is prepared once. After uncertain enter/wait failure, its call and buffer borrow remain outstanding until the matching terminal CQE; no effect is replayed through POSIX. The transport becomes observably disabled for subsequent native work. An unrecoverable inaccessible ring can therefore keep the call outstanding indefinitely.

POSIX emulation uses Linux completion errno values and advances only the successful byte count. Offset `-1` uses the current stream position. Its OPENAT adapter currently accepts `AT_FDCWD`; other directory descriptors fail explicitly with `-EOPNOTSUPP` when no native encoder is selected.

The Linux channel and whole-file helpers now use the common facade, and HTX closes the owned channel handle before its socket. These consumer edits have not executed on Linux. This worktree's separate legacy `LiburingVolume.linux.kt` still uses the older global facade; the filesystem owner's migration in the saved checkout remains an unmerged dependency.

## Node callback execution

Node 26.7.0 on this Mac executed the current common `FunctionalUringFacade` through the selected Node emulated backend against a disposable file. OPENAT, sliced explicit-length WRITE, FSYNC, FTRUNCATE, partial READ, EOF, CLOSE and drain passed through filesystem callbacks. The probe replaced all six synchronous filesystem entry points with failures, then delayed a real read's callback: caller cancellation remained unsettled until that callback released the buffer. The observed report was `HOST_UNSUPPORTED` with `nativeCapabilities == 0`.

```sh
kotlinc-js @/private/tmp/trikeshed-platform-uring/js.args
kotlinc-js @/private/tmp/trikeshed-platform-uring/js-link.args
NODE_PATH=/Users/jim/work/TrikeShed/build/js/node_modules node /private/tmp/trikeshed-platform-uring/node/uring-probe.js
```

[Executable assertions](/private/tmp/trikeshed-platform-uring/NodeAsyncProbe.kt) and the argument files describe a focused current-source dependency closure, not a whole-project build. The closure compiles the production facade, buffer, emulated adapter and detector without a support KLIB; it extracts the unchanged Array/List `toSeries` declarations and excludes the unrelated noncompiling Iterable `α` overload from a temporary copy of `Join.kt`. An earlier synchronous facade roundtrip also passed. The native Node ABI1 module's `execute` remains a synchronous runtime binding; no installed native addon or kernel execution was verified here.

The JVM legacy `JvmChannelHandle` was also compiled from current source with the updated common `ChannelOperations` interface, ahead of the current IO overlay and existing support JAR. Its [disposable-file/localhost fixture](/private/tmp/trikeshed-native-uring/JvmChannelProbe.kt) passed registered-file ranges, partial read, legacy EOF conversion, unsupported CQEs, capacity rejection, drain and borrowed-descriptor ownership, plus actual nonblocking loopback would-block/read/EOF behavior. [Observed output](/private/tmp/trikeshed-native-uring/jvm-channel-probe.log) is retained locally. Socket binding required an accepted sandbox escalation; the fixture contacted only `127.0.0.1`. Queued read/write no longer schedules daemon workers; the pre-existing DNS/connect scheduling provider remains unchanged.
