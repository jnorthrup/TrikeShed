# Primary research sources and claim boundaries

## Research register

Sources were inspected on 4 November 2026. The register states what each source supports and what remains a proposal or local measurement. Web documentation is mutable; implementation must pin selected code/assets and retain the exact versions used. Search snippets were used for discovery, with primary pages opened for substantive claims. Failed or redirected access is recorded below rather than represented as a completed audit.

| ID | Primary source | Supported use in the blueprint | Boundary |
|---|---|---|---|
| R01 | [OpenAI API pricing](https://developers.openai.com/api/docs/pricing) | Current Sol/Terra tariff snapshot | Account billing, long context and future prices must be rechecked |
| R02 | [NARS introduction, Pei Wang](https://cis.temple.edu/~pwang/NARS-Intro.html) | Revisable beliefs and resource-bounded reasoning | Does not validate this repository's implementation |
| R03 | [OpenNARS NAL overview](https://github.com/opennars/opennars/wiki/Non-Axiomatic-Logic-%28NAL%29%2C-the-logic-behind-OpenNARS) | Frequency/confidence and inference-family distinctions | Not a calibration result for legal predictions |
| R04 | [SUMO repository](https://github.com/ontologyportal/sumo) | Upstream ontology source and ecosystem | Arbitrary KIF is broader than local admitted fragment |
| R05 | [Sigma repository](https://github.com/ontologyportal/sigmakee) | Ontology engineering and reasoning environment | No automatic English-to-logic correctness |
| R06 | [Typed dependencies, 2006](https://aclanthology.org/L06-1260/) | Historical grammatical relation extraction | Does not establish legal scope interpretation |
| R07 | [CoreNLP toolkit, 2014](https://aclanthology.org/P14-5010/) | Pipeline lineage and primary publication | Runtime behaviour uses pinned current local assets |
| R08 | [BERT, 2019](https://aclanthology.org/N19-1423/) | Bidirectional transformer representations | A selected retrieval/ranking task needs its own validation |
| R09 | [Jeva-4B model card](https://huggingface.co/LIQIIIII/Jeva-4B) | Choice, null option, scoring and option reuse claims | Local CPU inference and calibration unmeasured here |
| R10 | [Roaring paper](https://arxiv.org/abs/1402.6407) | Compressed bitmap algorithm motivation | Published speedups do not transfer to RoaringSeries |
| R11 | [Roaring project](https://roaringbitmap.org/) | Implementations and container/format ecosystem | Custom serialization and performance need conformance tests |
| R12 | [RDF 1.1 concepts](https://www.w3.org/TR/rdf11-concepts/) | Graph/dataset data model | Graph names do not authenticate authority |
| R13 | [PROV-O](https://www.w3.org/TR/prov-o/) | Provenance interchange vocabulary | Application review semantics remain explicit |
| R14 | [SHACL](https://www.w3.org/TR/shacl/) | Graph shape validation | Shape conformance is not truth |
| R15 | [SPARQL 1.1 query](https://www.w3.org/TR/sparql11-query/) | Explicit dataset and graph query semantics | A query result cannot grant a host capability |
| R16 | [RDF Dataset Canonicalization](https://www.w3.org/TR/rdf-canon/) | Standardized canonical dataset identity | Bound adversarial work and pin implementation |
| R17 | [WARC 1.1](https://iipc.github.io/warc-specifications/specifications/warc-format/warc-1.1/) | Capture resource/metadata container | Archive format is not sanitized rendering |
| R18 | [WACZ 1.1.1](https://specs.webrecorder.net/wacz/1.1.1/) | Packaged archive, indexes and context | Offline safe-view policy remains separate |
| R19 | [Apache Tika security model](https://tika.apache.org/security-model.html) | Extraction/output and isolation boundaries | Does not prove local deployment isolation |
| R20 | [IPLD CARv1](https://ipld.io/specs/transport/car/carv1/) | Root/block transport representation | A CAR need not contain a complete coherent closure |
| R21 | [IPFS FAQ](https://docs.ipfs.tech/concepts/faq/) | Content addressing and CID distinctions | Offline availability depends on actual retained blocks |
| R22 | [OpenTelemetry traces](https://opentelemetry.io/docs/concepts/signals/traces/) | Spans, events and causal links as observability reference | Domain evidence/outcome semantics need richer records |
| R23 | [Soufflé provenance](https://souffle-lang.github.io/provenance) | Proof-tree inspection as alternative/reference | No local Soufflé integration or benchmark performed |
| R24 | [Differential dataflow](https://github.com/TimelyDataflow/differential-dataflow) | Incremental-dataflow candidate ecosystem | Architectural research option, not selected replacement runtime |
| R25 | [Timely communication](https://timelydataflow.github.io/timely-dataflow/chapter_5/chapter_5_1.html) | Worker/channel coordination reference | Existing CCEK contract still governs TrikeShed |
| R26 | [Coinbase US user agreement](https://www.coinbase.com/legal/user_agreement/united_states) | Concrete complex-source candidate | No evidentiary capture or applicability finding made here |
| R27 | [Coinbase v. Suski official opinion](https://www.supremecourt.gov/opinions/23pdf/23-3_879d.pdf) | Conflicting-agreement authority-mapping fixture | Historical question; no current-law completeness claim |

## Polyglot pivot sources

The subsequent [Polyglot RFC revision](22-mlir-nix-polyglot.md) and [Sulong bitcode contract](22-sulong-bitcode.md) use this source set, inspected on 4 November 2026. Its code inventory pins `mlir-nix` independently from the existing SUMO-Nix bridge; no runtime benchmark or upstream build was executed for the revision.

| ID | Primary source | Supported use | Boundary |
|---|---|---|---|
| R28 | [GraalVM native/managed execution](https://www.graalvm.org/latest/reference-manual/llvm/NativeExecution/) | Allocation, native-call and mode distinctions | Rolling docs do not certify the pinned runtime or containment |
| R29 | [LLVM interoperability](https://www.graalvm.org/latest/reference-manual/llvm/Interoperability/) | JVM embedding and explicit guest interop | No automatic evaluator ABI or measured zero-copy claim |
| R30 | [LLVM compatibility](https://www.graalvm.org/latest/reference-manual/llvm/Compatibility/) | Need for actual producer/reader verification | Published older version range must not be treated as a current-runtime test |
| R31 | [MLIR LLVM IR target](https://mlir.llvm.org/docs/TargetLLVMIR/) | Lowering/translation split and C wrappers | Does not supply a Nix semantic conversion pass |
| R32 | [Bitcode build formats](https://www.graalvm.org/latest/reference-manual/llvm/Compiling/) | Plain bitcode versus embedded-bitcode containers | Dependency closure remains an artifact-specific obligation |
| R33 | [Polyglot Source API](https://www.graalvm.org/sdk/javadoc/org/graalvm/polyglot/Source.html) and [Context builder](https://www.graalvm.org/sdk/javadoc/org/graalvm/polyglot/Context.Builder.html) | Binary Sources and explicit privilege policy | Illustrative adapter statements are not implemented code |
| R34 | [Polyglot C header](https://github.com/oracle/graal/blob/master/sulong/projects/com.oracle.truffle.llvm.libraries.graalvm.llvm/include/graalvm/llvm/polyglot.h) | Explicit string conversion and length semantics | Header at inspected default branch; deployment must pin its own compatible version |
| R35 | [mlir-nix at 571b57d3](https://github.com/jnorthrup/mlir-nix/tree/571b57d3ebdcdabe7ac76765b2875de7b98b9dab) | Inspected C API, pass registry, guarded JIT and build definitions | No full repository audit, Sulong port or claim that prior bridge pins changed |
| R36 | [LLVM assembler](https://www.llvm.org/docs/CommandGuide/llvm-as.html) | LLVM IR to bitcode assembly stage | Does not compile MLIR or supply missing Nix semantics |
| R37 | [Clang toolchain stages](https://clang.llvm.org/docs/Toolchain.html) | C/C++ frontend, IR and linking stage distinctions | A proposed build still needs a pinned toolchain and actual output |
| R38 | [Context lifecycle API](https://www.graalvm.org/25.0/javadoc/sdk/org/graalvm/polyglot/Context.html) | Explicit close and interruption constraints | Non-interruptible host/native execution prevents an assumed hard per-request deadline |

## Repository evidence register

These records are first-party project evidence, read as reports tied to their stated commits and fixtures. The planning task did not rerun their experiments.

| ID | Local source | Material finding |
|---|---|---|
| L01 | [PRELOAD](../../../PRELOAD.md) | Algebra, ownership, I/O, compile and integration requirements |
| L02 | [Offline English](../../offline-english.txt) | Local parser proof, source fidelity, known bounds and sampled coverage |
| L03 | [SUMO-Nix bridge](../../sumo-nix-bridge.txt) | Pinned native/solver evidence and first-proof-only limitations |
| L04 | [KIF curation inference](../../kif-curation-inference.txt) | Explicit proposition admission and source-context boundary |
| L05 | [Structured activation](../../nars-structured-activation-plan.txt) | Bag capacities, evidence units, dependence and safe attention seam |
| L06 | [Next native-proof experiment](../../native-proof-nars-next-experiment.txt) | Preselection-before-solving and exact role fixture |
| L07 | [Reviewed consumer scope](../../reviewed-kif-consumer-scope.txt) | Evolving reviewed-read/exact-KIF path; latest update governs |
| L08 | [Constellation premises](../constellation-premises.md) | Explicit positive/negative assumptions and pending/conflicted semantics |
| L09 | [Jeva layout](../../jeva-layout.txt) | Supported checkpoint/runtime layouts and unverified real-model inference |
| L10 | [MontyLingua assessment](../../montylingua-assessment.txt) | Legacy extraction, modal loss, alignment and license constraints |
| L11 | [PDF link curation](../../pdf-link-curation.txt) | URI annotation scope and bounded collection behaviour |
| L12 | [Forge checkpoint](../../forge-curation-checkpoint.txt) | Prior UI/runtime proof boundaries and retained evidence |
| L13 | [Sulong scalar proof](../../sulong-bitcode/proof.txt) and [identity metadata](../../sulong-bitcode/proof.json) | Historical actual primitive calls and restricted-profile refusals; no MLIR-Nix, JIT or full evaluator claim |
| L14 | [Retained legal candidate workflow](../../english-legal-patterns.txt) and [v58 evidence](../../evidence/2026-11-04/english-legal-candidates/README.txt) | Scoped source/role/quantity API, LCNC and browser evidence tied to frozen artifacts; no general legal accuracy or production airgap claim |

Source-code seams and inspected limits are enumerated in [the repository baseline](01-repository-baseline.md). Source code outranks stale historical summaries when they disagree about current mechanics; acceptance reports remain tied to the build they actually exercised.

## Access limitations and research not claimed

The attempted pinned SUMO-Nix GitHub tree fetch was restricted. The bridge assessment therefore relies on the repository's pinned-source report and inspected local adapter material, not a fresh full upstream audit. The original Rete paper's publisher endpoint returned 403; this package uses the inspected local Rete interfaces and general pattern-matching design, without claiming to have reviewed that full paper in this session.

A Stanford dependencies site request timed out; the primary 2006 and 2014 publication pages supplied the historical references. A version-specific Tika URL failed, then the current official security-model page was read. A Drools documentation URL failed. A Naiad publication URL redirected to the Microsoft Research home page and was not used as evidence for algorithm details. The OpenTelemetry Markdown endpoint was unsupported, so the HTML span/event/link sections were read instead.

Further research candidates include formal argumentation semantics, temporal reasoning, truth-maintenance systems, SAT/SMT certificate checking, Soufflé magic sets, stronger incremental deletion support, portable bitmap conformance and local-model quantization. They appear in the bounded experiment register with a decision question. Listing them does not imply implementation or demonstrated superiority.

## Claim classes

Every important implementation statement should be classified as: inspected current code, historical measured report, external primary-source capability, proposed design, planning assumption or newly measured result. This package uses those distinctions in prose rather than attaching a badge to every sentence. Cost, hardware, latency targets and delivery windows are planning assumptions. Existing source limits and interfaces are baseline observations. The demonstrator mechanics are proposed design until the corresponding acceptance packet exists.
