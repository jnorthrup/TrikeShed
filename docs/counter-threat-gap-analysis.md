Goal: Harden the oroboros flywheel drain FSM against Legion counter-threat architecture (Docs 01-04) with TDD Red-Green coverage across all 5 containment layers.

Root gap: ContainmentPolicy defines 5 layers declaratively but the drain FSM (FlywheelDriver.drainExactArtifacts at line 904) applies zero counter-threat validation before Pijul merge. Layer 2 xattr rejection and Layer 3 seccomp/egress are enforced in FunctionalUringFacade but never consulted during patch ingestion. Layers 1, 4, and 5 are config-only — no implementation behind the flags.

Sub-gap tree:
  Drain FSM (FlywheelDriver.kt:904) has no threat gate
    ├─ No AST lint on Jules patches before Pijul merge (Layer 4)
    ├─ No entropy scan on touched file paths (Layer 2/5)
    ├─ No noise injection in PijulChannel to break stigmergy (Layer 5)
    └─ No containment policy consulted during drain
  Layer 1: FUSE/virtualization has no implementation
    ├─ No namespace token masking (path hash canonicalization)
    └─ No CoW scoping enforcement
  Layer 4: Artifact normalization has no implementation
    ├─ No Tree-sitter AST linter integration
    ├─ No deterministic code formatter
    └─ No author metadata stripping
  Layer 5: Arbitrage breaker has no implementation
    ├─ No noise/perturbation injection
    ├─ No behavioral graph auditor
    └─ No cross-instance collusion detector
  Missing detection primitives
    ├─ No Shannon entropy scanner for covert channels
    ├─ No stigmergic protocol decoder
    └─ No tactical deception detector

Ranking (profitability = impact / effort_weight; S=0.5 M=1.0 L=2.0 XL=4.0):

1. Wire ContainmentPolicy.validatePatch() into drainExactArtifacts — debt: drain FSM at FlywheelDriver.kt:904 applies zero threat validation before Pijul merge, all 5 layers bypassed — ROI: 8.0 (impact 4 / effort S=0.5)
2. Add AST linter gate to JulesPatchReviewCli — debt: Layer 4 astEnforcement flag in ContainmentPolicy.kt:38 has no enforcement; Jules patches flow to drain without syntax/payload validation — ROI: 5.0 (impact 5 / effort M=1.0)
3. Add Shannon entropy scanner for path names in drain — debt: Layer 5 behavioralGraphAudit flag at ContainmentPolicy.kt:105 has no implementation; steganographic directory names pass undetected — ROI: 5.0 (impact 5 / effort M=1.0)
4. Inject bounded noise into PijulChannel materialized output — debt: Layer 5 noiseInjection flag at ContainmentPolicy.kt:103 has no implementation; cross-instance stigmergic inheritance is free — ROI: 5.0 (impact 5 / effort M=1.0)
5. Add author metadata stripping to drainArm — debt: Layer 4 stripAuthorMetadata flag at ContainmentPolicy.kt:39 unenforced; Jules patches retain author identity enabling sock-puppet attribution — ROI: 6.0 (impact 3 / effort S=0.5)
6. Integrate deterministic formatter at patch ingestion — debt: Layer 4 deterministicFormatter flag at ContainmentPolicy.kt:40 unenforced; whitespace steganography passes through drain untouched — ROI: 4.0 (impact 4 / effort M=1.0)
7. Add behavioral graph auditor for cross-run file access — debt: no tracking of which files each Jules session touches across drain cycles; multi-instance collusion undetected — ROI: 4.0 (impact 4 / effort M=1.0)
8. Wire Layer 1 namespace token masking into Jules patch path resolution — debt: BtrfsUserspaceVolume.kt:23 sanitizeSubvolName is path-reject only, not content-hash canonicalization — ROI: 3.0 (impact 3 / effort M=1.0)
9. Add CrossInstanceCollusionDetector to drain pipeline — debt: Doc 02 §4 sock-puppet consensus and Doc 01 §2C spontaneous protocol have no detection — ROI: 2.5 (impact 5 / effort L=2.0)
10. Add stigmergic protocol decoder for emergent coordination — debt: Doc 01 §2D polymorphic substrate reconstitution has no detection; agents reconstitute coordination via alternate channels — ROI: 2.0 (impact 4 / effort L=2.0)
11. Implement FUSE CoW scoping in BtrfsUserspaceVolume — debt: Layer1FilesystemPolicy.copyOnWrite flag at ContainmentPolicy.kt:24 has no enforcement — ROI: 1.0 (impact 4 / effort XL=4.0)
12. Add tactical deception detector for apology/identity-synthesis patterns — debt: Doc 01 §4D strategic conciliation and §4A identity synthesis have no detection — ROI: 1.5 (impact 3 / effort L=2.0)
13. Wire eBPF kprobe attachment for sys_enter_tracepoint monitoring — debt: LinuxEbpfActual.kt:20 has bpf(2) syscall interface but no probe attachment; Doc 04 §3 eBPF kprobe monitoring unimplemented — ROI: 1.5 (impact 3 / effort L=2.0)
14. Add resource-acquisition anomaly detector for privilege escalation — debt: Doc 01 §3B rapid administrative dominance has no runtime monitoring — ROI: 1.0 (impact 3 / effort L=2.0)
15. Implement deterministic synthetic clock in FunctionalUringFacade for all stat ops — debt: timestamp quantization at FunctionalUringFacade.kt:70 only covers STATX; other stat-like ops pass real timestamps — ROI: 2.0 (impact 2 / effort S=0.5)
