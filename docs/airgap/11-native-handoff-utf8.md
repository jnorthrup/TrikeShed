# R4f: native WAL handoff and UTF-8 runtime closure

R4f verifies the existing board WAL across Linux JVM and Kotlin/Native
processes and fixes a measured locale omission in the sealed runtime. The
production document graph continues to run MLIR/LLVM bitcode inside the JVM
through GraalVM Polyglot/Sulong. A separate native verification caller exercises
storage ownership and retained receipt replay; it does not replace that engine.

## The defect found by crossing the boundary

The original sealed root set `LANG=C.UTF-8` but omitted glibc's
`/usr/lib/locale/C.utf8` data. The JDK reported `file.encoding=UTF-8`, while
`native.encoding` and `sun.jnu.encoding` were `ANSI_X3.4-1968`. A JVM caller
appended the command-line value `JVM α first\nline two`; the retained WAL
contained two replacement characters in place of α. A Kotlin/Native caller
preserved β in its subsequent value. All frames had valid CRC32C checksums,
and both flush calls returned. The incorrect bytes reached the WAL from the
JVM's argument decoder; this was not a disagreement in WAL framing.

The failing input, process output and exact WAL remain in
[attempt 01](evidence/r4f-20261105/interop-attempt01/). Explicit UTF-8 text
encoding inside the application could not recover characters already lost
before `main`. Setting `file.encoding` again would not fix that boundary.
Earlier retained PDF extraction used explicit text handling and an ASCII
installation path; this observation does not invalidate every earlier
Unicode-content result.

The sealed root now includes all 12 `C.utf8` files, totaling 373,393 bytes,
with their SHA-256 identities and `libc-bin`/glibc package provenance. With
the same JDK and `LANG=C.UTF-8`, both native and filesystem encoding properties
report UTF-8. The actual generated launcher preserves a Unicode state path.
The packaged entry also requires UTF-8 `native.encoding` before constructing
state paths or opening stores, and reports the observed encoding in its
proof state. The guard refuses a bad deployment; the packaged locale supplies
the correction. It does not reconstruct damaged arguments or authenticate
JVM system properties against a hostile launcher.

Three extracted-archive controls hide the locale directory, select `LANG=C`,
or select a nonexistent UTF-8 locale. Each exits unsuccessfully with the
encoding diagnostic before changing any state-file bytes. All explicitly
set `-Dfile.encoding=UTF-8`, demonstrating that this option alone is
insufficient. A fourth process, with the proper locale, reopens six retained
document revisions and the completed receipt from a Greek-named directory,
then drains normally. Its runtime file bytes remain unchanged.

## Shared WAL ownership, measured in both runtimes

The external native caller links the existing full Linux project klib using
Kotlin/Native 2.4.21. It constructs the actual `BoardFileWal` with
`LinuxFileOperations`; no replacement framing, lease, CAS or reducer is linked.
The JVM caller invokes the corresponding production WAL and filesystem
adapter from the packaged jars. Its extra class directory contains only
that caller. Native process namespaces hide the packaged JDK and LLVM modules.
Recorded process maps contain no `libjvm` for native owners.

The native executable initially failed because the JVM-only root lacked
`libutil.so.1`. Its loader closure also requires `libcrypt.so.1`; both are now
present, with exact library, license and source identities. The original
exit-127 attempt remains retained. `libutil` corresponds to the already
retained glibc source package; libcrypt adds the exact libxcrypt 1:4.4.38-1
Debian sources. The verification executable is delivered separately from the
normal application launcher.

First, JVM/native/JVM processes append three values to `seed-α/board.wal`.
An independent Python decoder checks the WAL1 magic, version, monotonically
increasing big-endian sequences, lengths, CRC32C and exact UTF-8 payloads.
The native WAL reader also returns those same records. Both non-ASCII values
and the directory name survive the round trip.

Four copied-store scenarios then hold an owner, construct an incomplete tail,
and attempt admission from the other runtime:

| Owner | End of ownership | Opposite-runtime contenders | Recovery and next append |
|---|---|---|---|
| JVM | Normal close, exit 0 | Direct and symlink paths refused | Original three frames, then sequence 4 |
| JVM | Actual JVM SIGKILL, exit 137 | Both paths refused | Same prefix and sequence 4 |
| Kotlin/Native | Normal close, exit 0 | Both paths refused | Same prefix and sequence 4 |
| Kotlin/Native | Actual native process SIGKILL, exit 137 | Both paths refused | Same prefix and sequence 4 |

Each refused contender exits 42 only for the expected lease refusal and
leaves the tailed WAL digest unchanged. The native owner additionally checks
that a second owner in the same native process is refused. The controller
identifies only its descendants using process ancestry, command name and
exact command line; it signals the actual owned runtime process and verifies
that it was reaped. Normal-close cases print the closed phase; killed cases
do not. A new opposite-runtime owner truncates only the incomplete tail,
replays the original prefix and appends the next valid sequence.

These results establish interoperability between the existing JVM FileLock
adapter and Linux open-file-description locks for this local filesystem.
They do not make one WAL instance thread-safe, protect against a writer
ignoring the lease, or permit deleting/replacing a live `board.wal.lock`.
The existing serialized board consumer still owns append ordering. These
are constructed short tails and process deaths with a surviving kernel,
not arbitrary-write or power-loss experiments. macOS and remote filesystems
remain outside the measured ownership scope.

## Native replay of a real retained receipt

The native caller also opens a copy of the completed R4e runtime through
actual `FileCasStore` and `BoardStoreElement`, including its channel consumer
and owned cleanup. It verifies sequence 3, the same job/run/program and
receipt identities, and completed status. The selected quotation is checked
against retained extracted text at UTF-16 range 18477–18654. All runtime file
bytes remain unchanged after close.

This is a replay of historical output with the JDK hidden. It neither invokes
Sulong nor reruns the document graph. It proves that the native board reducer
and CAS boundary can read this retained JVM-produced receipt; it does not
establish a complete native HTTP demonstrator or every historical schema.

A separate fresh browser run executes the real PDF admission, extraction,
2,028-byte passage batch, 151-row Sulong scan and native geometry selection
from `/state/δοκιμή-α/runtime`. The independent audit checks all 27 CAS
objects, three queued/running/completed WAL frames, exact receipt identity,
111,243-byte original PDF and the exact retained quotation. Normal cleanup
joins the listener, module owner and native root. Syscall tracing observes
all 93 new CAS shard directories with their parent sync before dependent
publication and observes kernel ring setup. This fresh result is recorded
separately from the historical native replay.

## Validation and retained failures

Tested source is `4c00eccd7d2e8ce65ade1b23734c9aa1a018f0ab`. Fresh compilation
of the JVM entry and all JVM test sources succeeds offline. **22 selected
regressions pass, with no skips, failures or errors**: WAL lease ownership,
Confix native ownership/bounds and LCNC invocation, document spans and geometry
selection. The incoming D3M changes are documentation/evidence only. Common,
native, JVM production, browser and build inputs remain unchanged from R4e;
this release does not claim another fresh compile of every unchanged target.
The native probe links the existing full project klib offline, with every
link input's digest retained.

Both exploratory and extracted-archive handoff matrices pass. The extracted
archive also passes three encoding refusals, one valid Unicode-path reopen,
one fresh browser traversal and its independent audits. Seven package
integrity mutations are refused: changed jar, mode or manifest; extra file
or directory; missing locale; and missing native library. A restored control
passes. Package membership, directory/file modes and all hashes are checked
before execution. Two independently assembled archives have identical bytes;
that demonstrates pinned-byte packaging, not a complete source rebuild.

Failed verification attempts remain identifiable. A harness named `locale.py`
shadowed Python's standard module and started no runtime. Another used
`/proc/exe` lookup that failed to identify the nested owner; the corrected
ancestry/name/argv lookup retains successful process maps. The initial syscall
parser expected literal UTF-8 path text, while strace emitted octal escapes;
decoding those escapes preserves the exact path and barrier assertions. The
first quotation audit used integral JSON floats directly as Python slice
indices; the correction requires exact integral values before conversion.
These are harness corrections, separate from the reproduced locale defect.

## Distribution and reproduction

| Artifact | Bytes | SHA-256 |
|---|---:|---|
| `airgap-document-r4f-linux-x64-2026-11-05.tar.gz` | 460778817 | `28fb0c97ecb549881238929e7159fbb5847a0817cca4c9632306ea4e95df5c58` |
| `airgap-document-r4f-sources-update-2026-11-05.tar.gz` | 46539769 | `57f1f9db30f45d5abe27f810898b49ec0034be7dab599d045ebec292c3176d5f` |
| `airgap-r4f-native-verification-2026-11-05.tar.gz` | 5261081 | `0a556481cd93ec4b367f10c89c3169039ffce5a17c1fe232b84b70ef91519098` |

The runtime contains 1,355 inventoried files totaling 998,157,915 bytes,
excluding its two manifest files. The 26-jar application profile remains
23 dependency jars plus the three application/entry/web jars.

The source update **requires the R4e source companion**, SHA-256
`a5498b6d731182b7678795c737904637c641d42ed55d699c4beae1ad3df0bb61`.
It includes a full-index binary Git patch from R4e's tested source to this
tested source, new libxcrypt sources, and updated notices/inventories. Applying
the patch to a temporary Git index reproduces target tree
`ef1b18f7a522f7e8f1abaed0e865437b5b3efd77` exactly. The patch precedes the
later evidence-only publication commit. Retain both source archives together;
the update is not a standalone source distribution. Existing glibc sources
cover the new locale and libutil bytes. Source archives match their retained
DSC hashes and lengths, without a signature-authentication claim.

The separate native verification archive includes the exact executable,
Java caller, native source, build/link inventories, controller, and distributed
Kotlin/Native notices and source zip. That zip is not claimed to close every
compiler dependency. The retained harness paths name the original workspace;
map them to the corresponding artifacts when reproducing. Use copied test
state: the verification caller deliberately exposes WAL append operations.

Follow the [runtime launch instructions](../../release/airgap-document/README.md)
with the packaged locale, preserved modes and isolated networking. The host
kernel, namespace tools, browser, strace and verification controller remain
external inputs. The [verification packet](evidence/r4f-20261105/verification.json)
and [D3M record](../../../../../../tmp/d3m/2026-11-05-airgap-r4f.md) retain exact identities and
scope. Complete vendor JDK patch/native-resource rebuild provenance, broader
deployment environments, general Nix evaluation and dense recursive LCNC
visualization remain open. Raw native allocations still require explicit
release; no unified GC, zero-copy or performance advantage is asserted.
