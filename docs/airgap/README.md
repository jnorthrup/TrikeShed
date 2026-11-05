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
| R3: visible document traversal | Source intake to scanner/evidence nodes with measured wire activity, pause at supported boundaries and evidence flyovers | Planned; [implementation handoff](03-visible-traversal.md) and [visual acceptance](../lcnc/airgap-demonstrator/07-visualization.md) |
| R4: offline release closure | Reproducible packaged runtime, verified dependency closure and clean-machine startup without external networking | Planned; [operating procedures](../lcnc/airgap-demonstrator/20-operating-procedures.md) |

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
adds measured wire activity and native inspection. R3's full document path
and R4's sealed runtime/clean-machine closure remain open.
