# Inference engines and compatibility atlas

## Selection principle

Choose an engine by the question it can answer and the evidence it returns. “AI” is not an engine contract. Separate symbolic computation, classical statistical NLP, neural encoders, decision models and generative models. Offline availability and CPU feasibility are additional dimensions.

## Engine placement

| Method | Tier and purpose | Output semantics | Principal constraint |
|---|---|---|---|
| Byte/hash/MIME/structural parser | T0–T1 integrity and extraction | Observed bytes/structure | Input limits and parser isolation |
| Regex, finite-state rules, lexicons | T1–T2 exact markers and candidates | Pattern occurrence | Cannot establish deep scope by keyword alone |
| BM25/inverted indexes | T2 retrieval | Ranked candidate passages | Relevance is not truth |
| Graph traversal and bitmap filters | T2 workset selection | Exact membership within indexed epoch | Index completeness and ID identity |
| Stanford CoreNLP | T1–T2 syntax, tags, dependencies | Statistical annotations | Model errors, source alignment, memory and time |
| BERT-family encoder/reranker | T2 semantic candidate ranking | Embedding/similarity/class score | Model/task calibration and token limits |
| Jeva | T2–T3 candidate choice, abstention, rubric score | Model decision | Installed runtime/layout and measured CPU cost |
| Positive Horn/Datalog | T3 bounded deductive closure | Consequence relative to admitted theory | Fragment limits and proof provenance |
| SUMO/Sigma ecosystem | T2–T3 ontology and broader formal reasoning | Typed ontology/theorem results by backend | Full KIF is broader than the current bridge |
| SUMO-Nix and MLIR lowering | T3 native interchange and positive Horn execution; bounded Sulong pivot under review | Pinned frame result and proof | First-proof coverage, actual lowering, ABI ownership and execution profile |
| GraalVM LLVM / Sulong | T1–T3 execution of compatible bitcode; later guarded production kernels | Result of the loaded module, under its declared ABI/fragment | Producer/reader compatibility, memory ownership, runtime mode and actual semantic coverage |
| SAT/SMT/constraint solver | T3 finite consistency/resource constraints | Satisfiable/unsatisfiable/unknown | Encoding validity and backend-specific certificates |
| Argumentation/defeasible rules | T3 conflicts and exceptions | Accepted/rejected/undecided under semantics | Explicit attack/priority semantics |
| NARS/NAL | T3–T4 attention, revisable empirical inference | Frequency/confidence and budget | Evidence dependence and limited resources |
| Rete | T2/T5 incremental matching and action scheduling | Matched activation | Not a theorem prover or learning algorithm by itself |
| Local generative model | T2–T3 proposal/drafting | Generated candidate text/structure | Anchoring, calibration, memory, latency |
| Frontier model | Connected development or permitted online lane | Generated candidate/review | Incompatible with strict offline runtime |

## Stanford from 2006 onward

The 2006 typed dependency work provides a useful historical foundation for grammatical relation extraction. CoreNLP subsequently supplies a pipeline architecture combining tokenization, sentence splitting, tagging and other annotations. These are useful intermediate representations, not a general legal-semantics oracle. [Typed dependency paper](https://aclanthology.org/L06-1260/), [CoreNLP paper](https://aclanthology.org/P14-5010/)

For this repository the executed baseline is the local syntax profile documented in [offline English](../../offline-english.txt). Keep exact words, lemmas, POS/NER, dependency relations and legal markers separately. Preserve the newly merged copied-node identity follow-up, verify its conservative semantic gates, and add worker bounds. Then compare profiles on the same annotated clause set. Full OpenIE/coreference/KBP should be optional capabilities with measured benefit, not enabled merely because the package contains them.

The historical lineage also suggests inspectable alternatives: lexicon lookup, finite-state tokenization, chunking and transformation rules. MontyLingua is a comparison source, but its assessed legacy API can strip modals and lacks source offsets; licensing and runtime compatibility remain unresolved. Do not use it as a drop-in obligation extractor. [Local assessment](../../montylingua-assessment.txt)

## BERT lane

BERT is a pretrained bidirectional transformer representation model. Its useful role here is ranking or a task-specific classifier after exact filters. It is still ML even when it is small, local and CPU-operated. A raw BERT vector is not automatically a high-quality sentence retrieval embedding; select and validate a task-appropriate checkpoint and pooling/reranking method. [BERT paper](https://aclanthology.org/N19-1423/)

The adapter pins checkpoint, tokenizer, max sequence length, pooling/head, quantization and normalization. Long passages are chunked with overlap and exact source mappings. Compare candidate recall and downstream proof completion against BM25 plus structured filters. Reject a model upgrade that improves generic similarity while losing required exception clauses.

## Jeva lane

The Jeva-4B model card describes direct choice, scoring and a null option, with reusable option representations. Those properties fit routing and selection among already identified candidate passages or next actions. They are model claims to test locally, not guarantees that every decision is calibrated or legally sound. [Jeva model card](https://huggingface.co/LIQIIIII/Jeva-4B)

Test option permutation, changing candidate-set size, abstention and out-of-domain inputs. Cache option representations only under exact text, tokenizer, model and configuration identities. The existing loader accepts a particular unquantized layout and refuses incompatible packed layouts. Establish real inference and peak memory before including Jeva in a CPU demo budget. It remains optional if deterministic ranking meets the task.

## SUMO KIF and native reasoning

SUMO supplies an ontology and associated mappings; Sigma is an environment for developing and reasoning over such theories. Neither means that arbitrary English has been safely translated into executable premises. [SUMO repository](https://github.com/ontologyportal/sumo), [Sigma repository](https://github.com/ontologyportal/sigmakee)

The current TrikeShed bridge admits positive scalar ground facts and range-restricted Horn rules. It excludes quantified, modal, negative, functional and other unsupported forms. Native MLIR verification validates dialect structure; it does not prove that a legal interpretation is correct. Preserve source sidecars across lowering and validate returned proofs against the exact ordered frame.

Use explicit subclass/instance rules in the input when their support must appear in proofs. A `SUPPORTED` result means derivable in that frame. `UNKNOWN` means no derivation returned under the stated complete fragment execution; timeout, memory failure and truncated output are execution failures. First-proof-only output needs conservative retraction handling and fresh recomputation.

A later Soufflé comparison can measure larger Datalog workloads and provenance facilities. It should be an isolated benchmark adapter before any replacement decision. Its documented proof-tree interface is useful as a design reference, not evidence that TrikeShed already has those capabilities. [Soufflé provenance](https://souffle-lang.github.io/provenance)

## JVM Sulong execution direction

The requested pivot targets an immutable mlir-nix-produced LLVM bitcode artifact loaded through the JVM Polyglot API, replacing a proposed Native Image isolate/C API path for the next bounded slice. The repository already declares Polyglot/LLVM dependencies and retains a real scalar Sulong proof. That proof does not establish a compiled Nix evaluator or SUMO lane; normal restricted LLVM guests still refuse native access.

WP15 must first identify an actual supported compiler seam and freeze its ABI. A Kotlin string is not automatically a native UTF-8 pointer, and native allocations do not acquire JVM garbage collection merely by loading bitcode. Explicit byte transfer, handle ownership, result release and target-specific dependency packaging are required. Native-capable in-process execution shares daemon authority and cannot promise hard termination of arbitrary native calls. Keep the current working native Horn path until equivalent proof/currentness semantics and the production route pass acceptance. The [Polyglot pivot](22-mlir-nix-polyglot.md) separates compatible kernels, evaluator compilation and further semantic lowering. The [Sulong bitcode contract](22-sulong-bitcode.md) adds the retained scalar-proof baseline, bounded transfer/handle ownership, commonMain drain and an ordered acceptance ladder. Both preserve the current Horn frame/proof contract; neither establishes a complete in-process Nix evaluator.

## Compatibility rules

| Producer → consumer | Allowed crossing | Required guard |
|---|---|---|
| Parser → curator | Source-spanned candidate | Exact anchor and unresolved semantics |
| Retriever → panel | Ranked evidence candidates | Scope/dataset and no truth promotion |
| Jeva/model → formalizer | Suggested choice/mapping | Explicit candidate status and validation |
| Reviewed source → Horn | Admitted supported-fragment proposition | Host review, term mapping, frame identity |
| Horn → NARS | Proof receipt or attention proposal | No added evidence from proof count |
| NARS → Rete | Versioned production candidate | Calibration, dependency and authority policy |
| Rete → analysis | Work request or invalidation | Budget, recursion guard and epoch |
| Rete → sink | Permitted local action | Generation fence and effect receipt |
| RDF query → approval | No direct conversion | Query strings cannot grant host authority |
| Model explanation → source quote | No direct conversion | Exact source recovery required |

## Non-ML methods to bring forward

Use Aho–Corasick or equivalent multi-pattern scanning for large fixed marker sets; finite-state transducers for normalized lexical variants with edit maps; inverted indexes and exact phrase search; union-find and strongly connected components for graph hygiene; interval algebra for temporal overlap; topological dependency analysis; hash joins and sorted merge joins; Datalog semi-naive evaluation; finite-domain constraint solving; deterministic decision tables; and explicit defeasible/argumentation semantics where contradictions matter.

These are candidates selected by workload, not a mandate to add every library. Each earns inclusion by removing a measured bottleneck or expressing a required semantic distinction. Approximate methods such as Bloom filters and MinHash can reduce candidate work, but an exact stage must verify any result used for proof, authority or source identity.

## Engine promotion criteria

An engine becomes a default only after it passes the frozen task corpus, resource envelope, offline readiness, provenance recovery and failure semantics checks. Report latency distributions and error categories, not just an average or a few successful examples. Keep a deterministic reference path for the subset where exact answers are known. A more sophisticated engine may improve recall while making interpretation less reliable; the UI and evaluation must expose both effects.
