# Bounded document demonstration

This package composes the production project miner, durable Couch stores,
Kanban module, LCNC runners and Panels UI. Its packaged test entry point adds
fixture admission and local lifecycle controls. It is not the full Oroboros
daemon or a general Nix evaluator. No model inference is required.

`./gradlew airgapDocumentZip` builds the application layer. This includes the
production JVM jar, the production Spacegraph bundle, the small entry jar,
23 resolved dependency jars for Kotlin/coroutines and the GraalVM LLVM runtime,
two pinned LLVM bitcode modules, and the official
12-page *Coinbase, Inc. v. Suski* opinion. Test framework jars and unrelated test
classes are not included. The PDF's acquisition metadata is in `assets/`.

The application layer requires GraalVM Community 25.3.4.1, Linux x86-64 and
Poppler 25.03.0 for the verified extraction identity. A sealed runtime must
also carry the exact JDK, executable/library closure, licenses and source
provenance, and be checked from an empty filesystem namespace. The Gradle
archive alone does not demonstrate that closure.

The sealed Linux filesystem must include glibc's `/usr/lib/locale/C.utf8`
data and start with `LANG=C.UTF-8`. The packaged entry refuses a non-UTF-8
JVM `native.encoding` before opening state. JDK `file.encoding=UTF-8` alone
does not protect command-line values or filesystem paths: without locale
data, the OS argument decoder can already have replaced Unicode before
`main` receives it. Preserve the locale package identity and corresponding
glibc source alongside the runtime library inventory.

For an installation at `/opt/trikeshed` and writable state at `/state`, set
`JAVA_HOME` to the packaged JDK and `TRIKESHED_PDF_ROUTE=layer`. In `run` mode,
set `TRIKESHED_LLVM_SCALAR_PROFILE=/state/runtime/add-mlir.bc.json` and
`TRIKESHED_LLVM_CONFIX_PROFILE=/state/runtime/confix.bc.json`. The entry point
creates those profiles from the pinned module identities, then opens them
through the production runtime owner. Start with:

```
/opt/trikeshed/bin/airgap-document /state /opt/trikeshed 18764 run
```

`run` requires fresh state. `/panels` serves the production UI;
`GET /__proof/state` supplies fixture admission metadata for the retained
document graph. Open `/panels?load=airgap-document` to load the five-node,
eight-wire demonstration through the existing preset-link flow. It leaves
the saved scratch canvas unchanged and waits for an explicit Run or Debug
action. Debug lets the operator step through the nodes and inspect observed
wire transfers before the native selection completes.

`POST /__proof/finish` exports a state report and drains the
owned modules, listener, native seats and stores. These local test controls
have no authentication; run only in an isolated network namespace with a
trusted local browser. The application's airgap switch prohibits model
egress; OS network isolation supplies the broader no-egress boundary.

After that JVM has exited, unset both LLVM profile variables and start a
second JVM with the same arguments except `inspect`. This mode remounts the
durable project commit chains, checks all six saved document revisions and
their bytes, and reopens the LCNC receipt store. It has no miner or LLVM
runners. The original installation assets and bitcode can be hidden for this
second process. A browser can inspect and replay the recorded result without
submitting a new run. The same named-preset link loads the recovered document
definition for matching-canvas receipt inspection. Fresh-process evidence must identify both boots and
verify the exact receipt, invocation ledger, source bytes and quotation.

To execute again against the existing verified documents, use `recover`
instead of `inspect`, and set both LLVM profile variables as in `run` mode.
Recovery remounts and verifies the six retained document revisions without
constructing a miner or extracting the PDF. It checks and registers the
pinned native modules. The production run owner marks previously started,
unfinished runs `interrupted` with `runtime_restarted`; a missing terminal
invocation ledger remains unavailable. In this demonstrator's direct-run
flow, reopening does not restart that interrupted execution. Choose Run or
Debug explicitly to create a separate new run; keep the interrupted receipt
as evidence. The general queued-work recovery policy remains owned by
Kanban and is separate from this direct-run scenario.

The JVM uses a 768 MiB maximum heap and reports two processors. These settings
are not an OS memory limit or CPU quota. A native invocation can still run
until its bounded computation returns; there is no hard native cancellation.
The retained PDF has a text layer. OCR, arbitrary PDFs, model weights and the
full daemon's optional services are outside this package's verified scope.
The application dependency profile omits the full daemon's desktop Compose,
Kotlin compiler, Python, Ruby and JavaScript-language distributions. The
production Spacegraph browser bundle is still included. These omissions are
a packaging boundary, not a restriction on the rest of the repository.
