# Host discovery and io_uring module selection

`PlatformHost.descriptor` records runtime observations. OS and architecture aliases
normalize to `macos`/`linux`/`windows` and `arm64`/`x86_64`/`x86`; unrecognized
values remain visible, and unavailable values remain null. Architecture describes
the running process ABI. It does not independently identify a physical CPU when
the process runs under translation.

Node reads `process.platform`, `process.arch`, runtime/module versions and
`os.cpus()` model strings through structured runtime APIs. CPU vendor is unknown.
JVM reads system properties, retaining Java/VM vendor separately from CPU vendor.
Standard JVM properties do not supply CPU branding or a JNI ABI version; those
fields are null. JVM specification version has its own field.

Neither CPU branding nor an OS/kernel version establishes kernel syscall access.

| Host/runtime | Detection | Module/native behavior |
| --- | --- | --- |
| Darwin arm64, JVM | Real OS/process architecture; optional CPU fields null | `HOST_UNSUPPORTED`; shared emulated uring backend |
| Darwin arm64, Node CommonJS/ESM | Real OS/process architecture, CPU model, Node module ABI and Node-API version | `HOST_UNSUPPORTED`; shared emulated uring backend |
| Linux arm64/x86_64, JVM | System properties | Discover compatible ELF JNI binding; require protocol match, setup, operation probe and completed NOP |
| Linux arm64/x86_64, Node | Node runtime APIs | Resolve callable addon, validate metadata/Node-API/protocol; require setup, operation probe and completed NOP |
| Restricted Node | Retain independently readable fields | Missing loader permission is explicit; no native inference from `process.platform` |
| Browser/worker | Browser runtime identity; no host OS/ABI inference from a process polyfill | Restricted native module access; shared backend reports supported emulated operations |
| Hosted JS VM | Hosted runtime identity where Graal/Polyglot/Java globals identify it | No shipped callable bridge; no host syscall claim |
| Other/unknown architecture | Preserve observed architecture or null | `ARCH_UNSUPPORTED`/`HOST_UNKNOWN`, not a zero-capability probe result |

The canonical `openUserspaceChannelBackend(entries)` selector owns the choice.
`discoverJvmUringBackend` and `discoverNodeUringBackend` return a structured report
and, only after verification, an opened `UserspaceChannelBackend`. The selector
uses that candidate or its emulated uring backend and retains `probeReport`.
`currentNioCapabilityReport()` snapshots this same selector and closes its
temporary backend. JVM/JS `platformNioProviders()` register this report instead
of constructing an independent hardcoded report. Application IO remains on the common submission/completion
contract; no alternate application file API is introduced here.

## Module contracts

JVM checks `-Dtrikeshed.uring.library=/absolute/path/libtrikeshed_uring.so`, or
searches `java.library.path` for the mapped `trikeshed_uring` library name. It
checks ELF magic, class, byte order and machine before `System.load`, then calls
the repository JNI binding's `abiVersion()` (protocol 1). The runtime loader
checks JNI/linker compatibility, including missing dependencies. CPU architecture
matching alone cannot establish libc or JNI linkage compatibility. Ring setup
returns either an owned handle or negative errno; the existing binding performs
`io_uring_get_probe_ring` and supplies actual kernel-supported operation bits.

The JNI source and Linux-only build script are dependencies owned by the shared
IO task. `bin/build-jvm-uring.sh` uses an existing Linux JDK and system liburing;
it installs nothing. The former ServiceLoader declaration points at a provider
that is absent from this checkout. Discovery therefore uses the actual repository
JNI binding instead of treating that declaration or the old native stub as proof.

Node resolves `TRIKESHED_URING_MODULE`, or the optional package `@trikeshed/uring`,
using Node's module loader in CommonJS and ESM. Resolution is separate from loading,
so an absent package differs from a present package whose dependency/load fails.
No Node native addon is bundled by this change. The callable ABI 1 contract is:

| Export | Result/arguments |
| --- | --- |
| `abiVersion()` | Integer 1 |
| `platform()`, `architecture()` | Compiled module OS and CPU architecture |
| `napiVersion()` | Minimum required Node-API version; compared with runtime capability |
| `open(entries)` | Positive BigInt handle, or negative BigInt errno |
| `supports(handle, opcode)` | Boolean from the real ring operation probe |
| `execute(handle, opcode, fd, bytes, start, length, offset, userData)` | Integer completion result; byte view or null; offsets and tokens are BigInt |
| `close(handle)` | Release ring and module resources |

The shared IO adapter owns buffers/descriptors and any POSIX operation emulation
inside a native module. Native and emulated operations must share that module's
descriptor namespace. The module must implement the common file operations
NOP, OPENAT, READ, WRITE, FSYNC, FTRUNCATE and CLOSE, even when some require
emulation. Unsupported operations must return their errno instead of fabricated
success. Optional `size(fd)` returns a BigInt for descriptor metadata.
ABI 1's native `execute` call is synchronous and must retain buffers until its
operation settles. This interface does not establish a nonblocking Node addon
worker; no such addon is present here.

## Probe evidence

The report retains phase (`DISCOVERY`, `LOAD`, `SETUP`, `OPERATIONS`, `EXECUTION`),
module identifier, detail, errno and a nullable native operation mask.

- `HOST_UNSUPPORTED`, `HOST_UNKNOWN`, `RUNTIME_RESTRICTED`: no usable host bridge.
- `MODULE_ABSENT`, `MODULE_LOAD_FAILED`: absent candidate versus failed access/load.
- `ARCH_UNSUPPORTED`, `ARCH_MISMATCH`, `ABI_MISMATCH`: compatibility failures.
- `SETUP_DENIED`: EPERM/EACCES from setup/probe/execution, retaining the phase.
- `KERNEL_UNSUPPORTED`: ENOSYS/EOPNOTSUPP; no kernel-version heuristic.
- `PROBE_FAILED`: malformed results or other failures; EINVAL is not automatically
  labeled an unsupported kernel.
- `OPERATIONS_UNSUPPORTED`: callable probe lacks the required kernel NOP.
- `AVAILABLE_LIMITED`: verified native operations plus required emulation.
- `AVAILABLE`: all operations implemented by this module were kernel-supported
  at probe time. This does not claim support for all Linux io_uring operations.

The native mask remains null until measured. Zero denotes an actual empty probe
result. A positive candidate must also complete a kernel NOP with the expected
completion token. Permission can still change after a successful launch probe;
subsequent IO failures remain operation completions. Rejected candidates are
closed, and cleanup failures are retained alongside the primary diagnostic.

## Verification and deferred execution

Focused runtime checks compile current host/discovery/selection sources on
Darwin arm64. They are source-slice checks, not a complete repository build.
`scripts/verify-uring-host.sh` compiles the JVM slice and records the support JAR
used only for unchanged dependencies. Set `TRIKESHED_SUPPORT_JAR` to that existing
build when it is outside this worktree. `scripts/verify-uring-host-node.sh`
compiles a standalone Node slice in CommonJS and ESM. Its two explicit algebra
projections retain the current Join implementation except an unused broken
Iterable overload, and the exact existing Array/List `toSeries` declarations.
Host detection, module probing, the canonical facade and execution adapters are
compiled unchanged. Both scripts record sources, dependency hashes and logs;
neither reports a whole-source-set or native Linux pass.

The Darwin verification snapshot is JVM 25.0.4.1, `Mac OS X`/`aarch64`, 12
processors; Node 26.7.0, `darwin`/`arm64`, Apple M3 Pro, module ABI 147 and
Node-API 10. Both report `HOST_UNSUPPORTED` with native probe mask null and select
`uring_emulated` with selected native mask zero. JVM checks pass 19 tests; Node
checks pass 23 tests in each module format. Actual disposable file round trips
use the common facade for OPENAT, WRITE, READ, FSYNC and CLOSE; Linux-positive
module behavior is supplied only by labeled fixtures.

The full JVM build is blocked by stale references to removed CCEK/KeyedService
types in unrelated callers; no replacement facade is supplied to clear them.

Linux architecture, ELF/ABI mismatch, absent module, setup denial, unsupported
operations, limited/full probe and malformed completion cases are explicitly
simulated fixtures. They do not establish a native Linux pass. No compatible
module or authorized Linux runtime was available here. Linux JNI compilation,
real setup/permission/operation execution, and a concrete Node addon build remain
deferred until an existing supported Linux development environment is available.
No VM/cloud provisioning, privileged installation or Linux target setup occurred.

Ownership: host/discovery task `01a081d6-1466-7410-a2ff-d84b12b98af8` in worktree
`827c`; common IO/executors task `01a081d0-5cf8-7393-8859-1dd544b6af1e` in
`4721`. Only agreed dependency files were imported read-only between these trees.
No commits, merges or pushes are part of this work.
