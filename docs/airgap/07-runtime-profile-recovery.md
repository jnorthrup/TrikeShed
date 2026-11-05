# R4b: narrower document runtime and recovery after forced JVM exit

R4b narrows the [R4a offline distribution](06-offline-runtime.md), gives the
packaged graph an ordinary Panels preset link, and checks recovery after the
writer JVM receives SIGKILL. It uses the same production extraction, durable
project stores, Kanban receipt persistence and Sulong ownership path. The
scope remains one bounded passage from the retained twelve-page opinion.
This increment ran on cloud Linux x86-64; Jim's Mac was not modified.

## Application dependency boundary

`airgapDocumentLibraries` resolves the Kotlin standard library, coroutines,
atomicfu, serialization core, datetime and the GraalVM Polyglot/LLVM Community
runtime. Their transitive closure contains **23 dependency jars**. Together
with the production JVM, Spacegraph and entry jars, the package contains
**26 jars**, compared with R4a's 110. The 84 removed dependency jars include
desktop Compose, the Kotlin compiler and Python, Ruby and Graal JavaScript
language distributions. The browser's production JavaScript bundle remains
part of the package.

This is a narrower application profile, not a globally minimal JVM image.
The complete pinned GraalVM JDK, Poppler and operating-system closure remain.
The full production JVM jar also contains classes for services outside this
profile; executing those services can require omitted libraries. The tested
entry registers the bounded document composition. It does not establish that
the full daemon works with these dependencies.

The generated launcher still supplies Gradle's ordered classpath. The
packaging manifest covers membership, bytes and permissions, including the
matching Maven POMs, embedded notices and 23 source-jar records. The source
companion retains the unchanged 65 Debian source packages and pinned Graal
archives, plus the combined TrikeShed Git source at the tested cutoff.
The JDK vendor's modified-source marker and complete embedded-native build
closure remain unresolved, as documented in R4a. Repeating archive assembly
from pinned inputs is a packaging check, not a source-build reproduction.

## Open the actual graph

Start the generated launcher as described in the packaged
[README](../../release/airgap-document/README.md), then open:

```
http://127.0.0.1:18764/panels?load=airgap-document
```

The entry exposes the real admitted source reference and passage offset
through the existing `/api/panels/presets` contract. The five-node, eight-wire
canvas passes through the production `?load=` flow. Loading it leaves the
saved scratch canvas unchanged and does not submit a run. The operator
chooses Run or Debug; Debug permits pauses around actual wire consumption
and inspection of measured native work. No alternate editor or simulated
HTTP execution is introduced.

The same link in the inspector loads the recovered document definition, so
its saved receipt matches the canvas. The graph controls remain identical;
changing execution controls would change the definition used by receipt
matching. The inspector has no miner or native runners. This does not mean
its entire HTTP surface is read-only: normal Kanban routes remain present,
and the local lifecycle controls are unauthenticated. Use the documented
isolated network namespace and trusted local browser.

## Forced-exit boundary and durable replay

The controller first observes one completed LCNC run through the real
`/__proof/state` response and retains that response as
`state-before-crash.json`. It identifies the Java descendant of its own
bubblewrap process, checks the executable and entry-point command, and sends
SIGKILL to that Java PID. The writer exits through the monitor with status
137, is reaped, and produces no orderly cleanup record. A missing cleanup
record is expected for this case and is not reported as a successful drain.

Only then does the controller launch a different JVM in `inspect` mode.
The second process remounts the same durable state, verifies all six document
revisions and byte identities, and serves the same receipt and invocation
ledger. Installation assets, bitcode modules and PDF tools are hidden from
this process, as in R4a. The browser verifies the original quotation and
replays the recorded wires without submitting another LCNC run. The
inspector subsequently drains and exits normally.

The kill occurs **after a completed receipt response and before orderly
shutdown**. Native buffers have already been released. This establishes
recovery at that observed point. It does not establish atomicity at every
intermediate write, power-loss durability, a hard native-call deadline or
safe interruption of an in-flight guest computation. A host-captured state
response is evidence of what the writer served, not a writer shutdown export.

Resource snapshots come from the JVM itself. For the writer they are taken
before SIGKILL; for the inspector they precede normal shutdown. They exclude
the browser and do not aggregate Poppler child CPU or memory. The 768 MiB
heap setting and two reported processors remain JVM settings, not OS quotas.

## D3M and compatibility

The [D3M record](../d3m/2026-11-05-airgap-r4b.md) freezes 534 refs and retains
five sequential merges without textual conflicts. Publication then freezes
535 refs and preserves two additional documentation/evidence merges, with
all tested source and build inputs unchanged. Incoming work projects
reviewed proof premises into bounded request-local RDF quads, permits
protected visual queries of those quads, renders checked query results in
Documents, and honors the LCNC `topics?` port before authored defaults.
The retained tests cover these contracts alongside the prior native/document
regressions. Their presence does not make the PDF quotation a legal judgment
or an independently established English entailment.

The browser proof here exercises the packaged document graph and preset
flow. The new reviewed-query Documents UI is compiled and its shared/route
contracts are tested; this release packet does not claim a separate new
browser acceptance run for that UI.

## Reproduction and evidence

The [verification packet](evidence/r4b-20261105/verification.json) records the
combined source, current regression totals, platform builds, archive hashes,
process identities and actual browser cases. Its `accepted` directory retains
the extracted-archive run; `preliminary` keeps the narrower-classpath probe,
the first forced-exit probe and the failed preset attempt. The failed attempt
exposed a Kotlin local-helper access error inside an inline Series projection;
the entry now constructs the wire literal within that projection. The compiler
and target checks were not disabled. The first concurrent target build was
stopped when both Gradle processes and webpack reached the workspace's
16 GiB memory limit; its log and termination record are retained. Target
verification was rerun sequentially after the JVM build. This build-host
limit is separate from the packaged JVM's heap setting.

The `harness` directory retains the controllers as text. Build dependencies
must already be staged for the offline Gradle invocations. Runtime execution
uses the sealed root and an external loopback-only verification host; it does
not mount the build cache or workspace. Extract permissions faithfully:

```
tar -xzpf airgap-document-r4b-linux-x64-2026-11-05.tar.gz --no-same-owner
```

Check the expected manifest digest against the retained release evidence,
then verify membership, file modes and hashes before running. Package hashes
are identities, not publisher signatures. The runtime archive and source
companion are separate from the documents/evidence bundle.

## Accepted R4b checkpoint

Combined source `b61a25cd119bee4b2261bf4bdf9d1b0668098c89` passed **237 selected JVM
regressions**, with no failures or errors. One additional native Horn
endpoint test was conditionally skipped because its external solver profile
was not configured. JVM main and all JVM test
sources compile. JS main, production Documents and Spacegraph, Wasm-JS,
Wasm-WASI and Linux x64 compile with external networking unavailable and
staged build caches.

The freshly extracted runtime passed **11 browser cases**, including inert
preset loading, unchanged scratch storage, actual wire animation, native
measurements, exact source/quotation verification and recovery in a second
JVM after SIGKILL. Four startup refusals and five package-integrity refusals
passed. The independent audit checks all 27 CAS objects, the exact
receipt/program/invocation identities, eight consumed wires and the UTF-16
quotation directly from retained bytes.

| Observed process | Ready after launch | Java peak RSS snapshot | Java CPU snapshot | Exit |
|---|---:|---:|---:|---:|
| run (before SIGKILL) | 3.453 s | 440,092 KiB | 11.62 s | 137 |
| inspect (before normal shutdown) | 2.273 s | 361,456 KiB | 7.68 s | 0 |

The 26 application jars contain 136,638,800 bytes. The sealed root
has 1,339 manifested files containing 997,399,374 bytes, plus
the two manifest files. Two runtime archives assembled from those pinned
files are identical: 460,512,553 bytes, SHA-256
`065ca62ee7d4e75a0564440a70736b22b548ca7699479f516becf9245916fbfa`.
The source companion contains 666,012,421 bytes, SHA-256
`041d58e32c74ad4998c979f9589f7edf4e2b1c55b38a03f78910bb1dacf032e5`.

These are single-run resource observations and packaging identities. They
are not throughput guarantees, OS quotas or proof of arbitrary-write
crash safety. The full vendor/native rebuild audit, wider environments,
dense recursive visualization and broader LCNC authoring remain open.
