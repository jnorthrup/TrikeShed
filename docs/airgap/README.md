# Airgap release milestones

This directory records progressively larger executable releases of the
[LCNC airgap programme](../lcnc/airgap-demonstrator/README.md). Each release
retains its code identity, reproduction inputs, actual run evidence, limits
and D3M integration record. A planned gate is not a completed release.

| Release | Behavior | Evidence and standing |
|---|---|---|
| R1: owned scalar execution | Standard MLIR arithmetic through a pinned Sulong module, two wired LCNC nodes, durable receipt reopening | [Verified runbook](../sulong-bitcode/README.md); published master `26d5bf3db` |
| R2: owned Confix scanning | Execute the real pinned upstream JSON/CBOR/YAML scanner, copy bounded rows, release native buffers, compare Kotlin results, retain a whole-program LCNC receipt | [Verified runbook](02-confix.md), [exact evidence](evidence/r2-20261104/verification.json), [D3M record](../d3m/2026-11-04-airgap-r2.md) |
| R3a: observed transfers | Actual selected-wire sparks, recorded LLVM flyovers, reduced motion and immutable receipt playback | [Implemented contract and evidence](04-observed-transfers.md); bounded precursor to full R3 |
| R3b: retained document traversal | Real 12-page PDF, production extraction, bounded passage, LLVM geometry selection, verified source/quotation and reopening without LLVM | [Verified increment](05-document-traversal.md), [final combined evidence](evidence/r3b-cutoff-20261105/verification.json), [D3M record](../d3m/2026-11-05-airgap-r3b.md); dense recursive visualization remains open |
| R3c: human-paced inspection | Continuous display clock, display pause, pinned transfer records and measured coverage in a 65-node native graph | [Verified increment](13-human-rate-inspection.md), [13 browser checks and frame measurements](evidence/r3c-20261105/verification.json), [D3M record](../d3m/2026-11-05-airgap-r3c.md); layered update to R4f, large-graph rendering remains open |
| R4a: packaged offline runtime | Generated launcher, sealed server filesystem, real PDF traversal and recovery in a second JVM without source assets or LLVM | [Verified increment](06-offline-runtime.md), [219 regressions and packaged proof](evidence/r4a-20261105/verification.json), [D3M record](../d3m/2026-11-05-airgap-r4a.md) |
| R4b: narrower profile and forced-exit recovery | 23 dependency jars, inert named preset, exact receipt recovery after SIGKILL at a completed receipt | [Verified increment](07-runtime-profile-recovery.md), [237 regressions and extracted-archive proof](evidence/r4b-20261105/verification.json), [D3M record](../d3m/2026-11-05-airgap-r4b.md) |
| R4c: interrupted execution | SIGKILL at three node boundaries, preserved interrupted receipts, explicit new execution and third-JVM inspection | [Verified increment](08-interrupted-execution.md), [247 regressions and three archive scenarios](evidence/r4c-20261105/verification.json), [D3M record](../d3m/2026-11-05-airgap-r4c.md) |
| R4d: storage publication | Parent-directory barriers, final-WAL-fsync EIO/SIGKILL and same-run inspection without retry | [Verified increment](09-storage-publication.md), [264 passing tests and 17 browser cases](evidence/r4d-20261105/verification.json), [D3M record](../d3m/2026-11-05-airgap-r4d.md) |
| R4e: exclusive WAL ownership | Lease before recovery, competing-process refusal, crash/close release and retained-store recovery boundaries | [Verified increment](10-wal-ownership.md), [287 passing tests and 21 browser cases](evidence/r4e-20261105/verification.json), [D3M record](../d3m/2026-11-05-airgap-r4e.md) |
| R4f: native WAL handoff and UTF-8 closure | JVM/native lease exclusion, process-death handoff, native receipt replay and Unicode path preservation | [Verified increment](11-native-handoff-utf8.md), [22 regressions and archive evidence](evidence/r4f-20261105/verification.json), [D3M record](../d3m/2026-11-05-airgap-r4f.md) |
| R4g: measured kernel short writes | Real partial ring writes, incomplete/full frame recovery and successful continuation under a restored limit | [Verified increment](12-kernel-short-writes.md), [five scenarios and 20 browser cases](evidence/r4g-20261105/verification.json), [D3M record](../d3m/2026-11-05-airgap-r4g.md) |
| R4h: wider release closure | Complete vendor/native rebuild provenance, arbitrary-write crash recovery and additional deployment environments | Open; [operating procedures](../lcnc/airgap-demonstrator/20-operating-procedures.md) |

The [v72 retained English follow-up](evidence/english-query-handoff-v72-20261105/README.md)
verifies the native inert Documents-to-Panels handoff, ten URL/ownership guards,
three exact-program syntax queries, and selected B trace/Evidence ownership when
older A invocation reads return. It retains its final-JAR identity, original
inspection, failed attempts, after-close CAS proof and independent audit; it
does not close the planned R3/R4 gates.

The [v73 KIF comment follow-up](evidence/kif-leading-comment-v73-20261105/README.md)
retains an actual v72 RED and v73 GREEN HTTP comparison for the same commented
CRLF/Unicode source, whole-input refusals without bag/CAS changes, healthy
replay attention renewal, and independent after-close CAS/WAL verification.
Its manual declared-KG scope does not establish source entailment or complete
KIF conformance.

The [v74 native reviewed-proof paths](evidence/reviewed-taxonomy-path-v74-20261105/README.md)
show actual ordered premises, English/KIF evidence-card focus without route
changes, valid UNKNOWN and source-backed reflexivity, then real source-stale409
with cleared proof and unconfirmed currentness. The retained parser, explicit
synthetic review and independent provenance audit stay scoped to the manual
translation assumptions; this is not independent English entailment.

The [v75 native evidence queries](evidence/reviewed-proof-query-v75-20261105/README.md)
run both inspection-only proof-query starters through the real protected quad
engine and display typed English/KIF rows with route-preserving source focus.
Actual guarded refusals and source-stale clearing keep data-query rows separate
from goal truth, physical data authority and current host review provenance.
This checkpoint remains pinned to its own tested source and artifacts.

## R2 acceptance and verification

The upstream candidate is `jnorthrup/mlir-nix` revision
`571b57d3ebdcdabe7ac76765b2875de7b98b9dab`, `lib/Confix/confix.ll`.
The existing [upstream handoff](../sulong-bitcode/README.md#concrete-upstream-handoff)
records its assembly probe. R2 must execute those actual scanner functions;
the scalar-addition fixture cannot satisfy this gate.

1. Retain the unchanged upstream IR and provenance. Build a narrow buffer ABI
   with checked input/row/work capacities and exact target-layout assertions.
2. Keep every allocation, pointer, guest Value and Context under the existing
   serial userspace kernel seat. Copy only source identities, rows, faults and
   measured observations into host results. Release buffers on every exit.
3. Compare successful JSON/CBOR/YAML row spans, tags, depths and children with
   the corresponding Kotlin scanner on a retained corpus. Preserve known
   permissive parsing semantics; scanner success is not strict validation.
4. Exercise Unicode, empty input, truncation, capacity exhaustion, malformed
   input, rejected authority, cancellation/drain and repeated scans.
5. Run through the production LCNC route, verify exact CAS evidence identities,
   and reopen the completed receipt without native execution.
6. Compile affected targets, rerun current meaningful regressions with external
   networking unavailable, then integrate and push through the endorsed
   [D3M procedure](../../PRELOAD.md#distributed-3way-merge--d3m).

This release does not claim a general Nix evaluator, native-memory sandbox,
hard native cancellation or a performance advantage. The release evidence
must distinguish staged-cache offline execution from a sealed distribution.

R2 verification is complete for the bounded Linux x86-64 profile. The combined
D3M source passed **61 selected regressions** with external networking
unavailable; two existing native Horn endpoint tests were conditionally
skipped because their external solver profile was not configured. JVM main
and all test sources compile, as do JS main, Documents and Spacegraph JS
applications, Wasm-JS, Wasm-WASI and Linux x64. The target build staged an
existing Skiko dependency over the network; it is not offline distribution
closure. A separate fresh isolated native rebuild matched the pinned bitcode.

The actual LCNC route scanned the retained source into seven rows, fed that
count to the scalar engine to produce 107, committed exact CAS evidence, and
reopened the receipt/source without either LLVM runner. All bridge allocation
was released. The known YAML disagreement is retained. The separate native
sanitizer audit passed 150,026 bounded cases; it is not an exhaustive proof.

All 494 enumerated remote branches and six local branches were contained by
the final D3M audit (501 refs including origin/HEAD). No conflicts occurred and
no branches were deleted in that R2 publication. [R3a](04-observed-transfers.md)
adds measured wire activity and native inspection. [R3b](05-document-traversal.md)
now verifies a bounded passage from a real retained PDF through that path.
R4a now verifies a bounded sealed filesystem and fresh-process restart.
Dense recursive visualization, complete source-build closure and wider
deployment environments remain open.


## R3b acceptance

The retained *Coinbase, Inc. v. Suski* opinion traverses production source
admission, a 2,028-byte passage envelope, real LLVM scanning (151 rows), native
indexed selection and browser source verification. The exact 177-unit quote
retains physical-page anchors and separate original/extraction identities.
Eight consumed wires are observed; both project revisions are recorded.

The final combined code passed 201 selected JVM regressions, the listed platform
compiles, nine document browser cases and 11 animation/replay regressions.
The frozen D3M inventory contains 511 remote branches and nine local branches
plus origin/HEAD; no conflict or branch deletion occurred. Reopening without
LLVM happens within one JVM. The evidence does not claim clean-machine closure,
general Nix evaluation, legal adjudication or exhaustive all-page processing.

## R4a acceptance

The same document path runs from a packaged JDK, application and system-tool
closure with no workspace or build cache mounted. A second JVM reopens the
same receipt and six document revisions with installation assets, native
modules and PDF tools hidden. Ten browser cases, four startup refusals and
five integrity refusals pass; an independent audit verifies all 27 retained
CAS objects. The generated launcher and a freshly extracted archive are
both exercised. Two archives assembled from the pinned files are identical.

The combined source passes 219 selected JVM regressions and the listed
platform compiles. The frozen D3M cutoff contains 519 remote heads, nine local
branches and origin/HEAD; nine sequential merges required no conflict
resolution. Runtime and source companion hashes, process measurements,
failed attempts and limits are in the [R4a runbook](06-offline-runtime.md).
This is a Linux filesystem-namespace proof on the existing host kernel;
the browser remains an external verification input. It does not establish
bare-metal installation, power-loss recovery or a fully reproducible source
build of the vendor JDK and every native dependency.

## R4b acceptance

The bounded document profile now contains 23 dependency jars instead of 107;
the full JDK remains. The ordinary named-preset link loads five nodes and
eight wires without altering scratch storage or running automatically. A
writer killed after its completed receipt response performs no cleanup; a
new inspector recovers the exact receipt, six revisions and quotation.

The combined source passed 237 selected JVM regressions and the affected
platform compiles. Eleven browser cases pass from the extracted runtime;
startup/integrity refusals and independent CAS verification pass. D3M retains
535 publication-cutoff refs through seven sequential merges without conflicts. The
[runbook](07-runtime-profile-recovery.md) records hashes, measurements and
limits. This does not close arbitrary-write crash safety, power-loss
durability or the full vendor/native source-build gate.


## Retained-source deployment boundary

The [v77 source-promotion checkpoint](evidence/retained-source-promotion-v77-20261105/README.md) verifies actual missing-source refusal, same-CID BLOB promotion and separate-JVM reopening with the old producer directory hidden. It uses the pinned v76 artifact and does not establish the full sealed English host.

## Retained English NAL application

The [v77 retained-English checkpoint](../evidence/2026-11-05/retained-english-nal-v77/README.md) applies the existing NAL operations to one bounded atomic-symbol inspection. It preserves source and premise identities, separates deduction from weak induction hypotheses, refuses guarded or incomplete frames, and performs no live admission. The old zero-argument example remains byte-identical.

## R4c acceptance

Three extracted-archive scenarios kill the actual writer JVM before document
processing, before native scanning, or after scanning returns. Each recovers
an interrupted receipt without fabricated outputs, permits an explicit new
run with a distinct identity, then reopens both rows in a third JVM without
the miner or LLVM. Twenty-one browser cases, six startup refusals, five
package-integrity refusals and independent CAS audits pass.

The combined source passes 247 selected JVM tests, with one conditional
external-native-solver check skipped, plus affected-target compilation.
D3M retains 540 frozen refs through six merges; both textual conflicts and
the concurrent NAL assertion overlap have preserved evidence and intent
resolutions. The [runbook](08-interrupted-execution.md) records archive
identities and measurements. These are cooperative node-boundary failures;
arbitrary-write crash safety and vendor/native rebuild closure remain open.

## R4d acceptance

The JVM filesystem boundary now syncs new directory entries before dependent
publication. A real syscall trace checks every new CAS shard's parent barrier.
Path-filtered EIO and SIGKILL at the final WAL fsync leave an unacknowledged
completion that a new inspector recovers under the same run identity, with no
automatic retry, miner or LLVM execution. A separate pre-finish interruption
still recovers as interrupted and requires an explicit new run.

The combined source passes 264 selected tests, with one conditional
external-native-solver skip, fresh affected-target compilation, seventeen
browser cases, seven startup refusals and five integrity refusals. D3M retains
542 frozen refs through 3 merges and the evidenced structured-input
integration fix. The [runbook](09-storage-publication.md) retains failed
attempts, exact identities and measurements. The kernel survives these process
faults; power-loss safety and full vendor/native rebuild closure remain open.

## R4e acceptance

The board WAL now owns an exclusive filesystem lease before opening or
recovering its file. A direct two-owner overwrite baseline, current red/green
checks and separate packaged JVM contenders demonstrate the fix. Reaping a
killed owner and normal close each permit a later owner, with the retained
receipt and six document revisions unchanged. Two constructed short tails
recover as interrupted; complete CRC corruption and missing/changed CAS
payloads refuse admission.

The combined source passes 287 selected JVM checks with one conditional
external-solver skip, fresh affected-target compilation and 21 browser cases.
D3M retains 544 frozen refs through four merges with two preserved
conflicts and intent resolutions. The [runbook](10-wal-ownership.md) separates
historical-output inspection, fresh native execution and fault scope.
Power-loss safety, cross-platform ownership proof and full vendor/native
source rebuild closure remain open.

The final R4e publication also retains a clean D3M merge of Documents-only
query column widths, with fresh Documents compilation. The packaged runtime
inputs and runtime/source archive identities are unchanged; the runbook
records the separate publication-source comparison.

## R4f acceptance

A measured missing-locale defect is fixed by packaging glibc C.utf8 and
requiring UTF-8 native argument encoding before state admission. JVM/native
WAL owners exclude one another through direct and alias paths, release on
close or process death, recover only constructed short tails and continue
sequence ordering. Actual native BoardStoreElement replay preserves a retained
receipt and quotation with the JDK hidden. A separate fresh JVM/Sulong
document traversal succeeds from a Unicode state directory.

The [runbook](11-native-handoff-utf8.md) records 22 passing regressions,
three locale refusals, a valid Unicode reopen, fresh traversal and CAS/WAL
audits, 93/93 directory barriers, seven integrity refusals and the source
update's required R4e base. D3M retains 545 refs through one conflict-free
merge. Linux process ownership is measured; power-loss safety, wider deployment
and complete vendor/native source rebuild remain open.

## R4g acceptance

Linux writes actual 9-, 48- and 137-byte prefixes of the final 139-byte WAL
frame before the owned JVM is killed. Recovery preserves the prior frames
and marks the same run interrupted while retaining the uncommitted completed
CAS command. A complete-frame control recovers the unacknowledged completion.
Restoring the limit after a nine-byte write lets the same append finish,
flush and return one acknowledged receipt. Five packaged scenarios and
20 browser cases pass with independent byte, CAS, trace and process audits.

R4g reuses the exact R4f runtime and source chain. Application and build inputs remain unchanged; earlier JVM checks retain
their own standing.
D3M freezes 545 refs, already integrated, with zero new merges or conflicts.
The [runbook](12-kernel-short-writes.md) retains preliminary log-limit effects
and defines the surviving-kernel boundary. Power loss, wider storage/deployment
and complete vendor/native rebuild provenance remain open.
