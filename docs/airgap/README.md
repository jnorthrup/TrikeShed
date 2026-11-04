# Airgap release milestones

This directory records progressively larger executable releases of the
[LCNC airgap programme](../lcnc/airgap-demonstrator/README.md). Each release
retains its code identity, reproduction inputs, actual run evidence, limits
and D3M integration record. A planned gate is not a completed release.

| Release | Behavior | Evidence and standing |
|---|---|---|
| R1: owned scalar execution | Standard MLIR arithmetic through a pinned Sulong module, two wired LCNC nodes, durable receipt reopening | [Verified runbook](../sulong-bitcode/README.md); published master `26d5bf3db` |
| R2: owned Confix scanning | Execute the real pinned upstream JSON/CBOR/YAML scanner, copy bounded rows, release native buffers, compare Kotlin results, retain a whole-program LCNC receipt | In progress; acceptance below |
| R3: visible document traversal | Source intake to scanner/evidence nodes with measured wire activity, pause at supported boundaries and evidence flyovers | Planned; [visual acceptance](../lcnc/airgap-demonstrator/07-visualization.md) |
| R4: offline release closure | Reproducible packaged runtime, verified dependency closure and clean-machine startup without external networking | Planned; [operating procedures](../lcnc/airgap-demonstrator/20-operating-procedures.md) |

## R2 acceptance

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
