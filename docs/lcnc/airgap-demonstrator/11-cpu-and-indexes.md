# CPU algorithms indexes and resource budgets

## CPU-first execution ladder

The baseline answers exact retrieval, structural comparison, finite rule application and bounded symbolic queries without ML. Add local statistical parsing where it improves candidate structure. Add neural ranking or generation only where evaluation shows a useful gain. This ordering makes the offline system useful even when large model assets are absent.

The physical plan is late materialization: cheap source/context filters, exact dictionary/bitmap selection, lexical ranking, bounded structural work, then expensive inference on a small selected frame. Preserve Series/Cursor views through these stages and materialize only at an adapter or storage boundary that requires it.

## Index inventory

| Index | Key | Payload | Invalidated by |
|---|---|---|---|
| Artifact directory | Content ID | Byte location/length/schema | Corruption or retention removal |
| Source revision | Project/document/revision | Original and derivative CIDs | New source revision |
| Lexical postings | Normalized token/phrase | Passage IDs and positions | Text/tokenizer version |
| Clause structure | Document/block identity | Parent, order, cross-references | Structural extraction version |
| Quad columns | G/S/P/O dictionary codes | Row IDs | Graph/dictionary epoch |
| Predicate arguments | Predicate, argument position, term | Candidate facts/rules | Theory frame |
| Class closure | Ontology epoch, class ID | Ancestor/descendant bitmap | Ontology rebuild |
| Provenance reverse edges | Source/review/policy CID | Dependent results/productions | New dependency records |
| Temporal intervals | Scope and time range | Episode/statement IDs | Timeline correction |
| Optional vector index | Model and text IDs | Embeddings and passage refs | Model/tokenizer/text version |
| Event projection | Run/epoch/sequence | Aggregated visual state | Rebuilt from journal |

Store dictionary identity beside numeric IDs. A term assigned integer 42 in one ontology build is not term 42 in another. Imported indexes require byte-order, width and format validation. Do not assume a custom RoaringSeries serialization is upstream Roaring portable format without conformance evidence.

## Worked retrieval plan

For a question about access restriction under agreement capture A and scenario S:

1. Select current permitted source graphs from S's dataset manifest.
2. Intersect graph membership, source revision and passage-type sets.
3. Retrieve exact terms and configured synonyms with lexical postings.
4. Add referenced definitions and exception clauses through explicit document links.
5. Rank the bounded candidate set; optionally rerank with a local model.
6. Recover exact source spans and validate anchors.
7. Build the analysis frame with selected and excluded counts.

A lexical top-k alone can omit a critical exception with different vocabulary. Structured expansion is therefore part of the measured retrieval baseline. Candidate selection must happen before expensive solving to count as work reduction. Selecting a small proof afterward does not show that the solver did less work.

## Roaring and class acceleration

Roaring partitions integer sets into containers and supports efficient set operations; published results justify evaluating it for sparse/dense mixtures. They do not predict TrikeShed's performance on its own class distributions. [Roaring paper](https://arxiv.org/abs/1402.6407)

Benchmark array-array, array-bitmap, bitmap-bitmap and run-heavy intersections, unions and differences. Measure allocations as well as throughput. The inspected `RoaringSeries.or` converts operands to full arrays; improving container-level union may matter more than adding a native SIMD backend. Preserve exact membership against a reference set for every shape and edge case.

Use class bitmaps to select candidate rule rows. Recover the rule and source basis afterward. A support floor in the empirical ClassRete lane cannot become proof authority. For a taxonomy diamond, duplicate paths remain duplicate dependencies, and cycles need a finite closure strategy.

## Joins and symbolic work

Select hash joins for small unsorted worksets and merge joins for sorted columns when repeated access justifies ordering. Push highly selective graph/argument predicates early. For rule joins, retain argument positions; an entity appearing in the wrong role must not satisfy the rule.

For positive recursive Datalog, evaluate semi-naive delta propagation as an alternative to repeated full joins. The current native solver remains the correctness baseline for its supported fragment until an alternative is verified. Incremental maintenance needs deletion semantics and provenance, so the first implementation can recompute affected bounded frames.

Differential dataflow is a useful reference for incremental collections and changing inputs, but adopting another runtime is not the default. Study the algorithmic approach and benchmark an isolated adapter only if repeated recomputation becomes a demonstrated bottleneck. [Differential dataflow project](https://github.com/TimelyDataflow/differential-dataflow)

## Approximation boundaries

Bloom filters can reject definitely absent candidates; a possible hit must be checked exactly. MinHash can suggest near-duplicate documents, but shared wording does not establish common legal meaning or independent-source dependence. Approximate nearest-neighbour search can rank candidates, but proof completeness must account for retrieval misses.

A bounded selector reports `INCOMPLETE` when it cannot establish coverage. The proof engine can still return a consequence of the selected theory, but the application must not imply that excluded material was considered. Report selection recall separately from proof correctness.

## Initial hardware profiles

These are planning targets to benchmark, not requirements already demonstrated.

| Profile | Hardware assumption | Intended workload |
|---|---|---|
| Small | 8 CPU cores, 16 GiB RAM, local SSD | Deterministic fixtures, small graph, optional one parser worker if measured to fit |
| Reference | 16 CPU cores, 32–64 GiB RAM, NVMe | Full demonstrator, isolated parser and solver, larger retained graph |
| Research | 32+ cores, 128 GiB RAM, NVMe | Larger corpus, alternative engines and optional local models |

The existing launcher permits a 24 GiB Java heap; that setting alone can exceed the small profile's practical budget. Establish a profile-specific heap and worker configuration rather than copying the default. Total memory includes host heap, native workers, browser, mapped pages, model weights and OS cache.

A 4-billion-parameter model has a raw weight lower bound near 8 GB at 16 bits or 2 GB at 4 bits, before metadata, activations, caches and runtime overhead. That arithmetic does not establish supported quantization or actual peak memory. The current Jeva layout constraints must be respected. BERT checkpoints also vary; record the exact one instead of assuming a generic footprint.

## Initial bounded workload

For D2 use one full retained agreement, a 20–100-page PDF fixture, 24–60 structured questions, 100–1,000 source observations and a small exact Horn theory. Use separate synthetic load fixtures with 10,000 and 50,000 graph nodes and 1,000/10,000 events per second. Real inference concurrency starts small: one parser worker, one solver worker and a bounded panel pool. Increase only after resource measurement.

Initial node batches should aim for 25–100 ms of cooperative work where possible. Long parser calls need process supervision rather than an assumed cooperative yield. UI aggregation operates independently at 100–250 ms intervals. These are tuning hypotheses; a benchmark report can revise them without changing semantic contracts.

## Storage arithmetic

At 1,000 retained events per second and an average 300-byte encoded event, the raw stream is about 25.92 GB/day before indexes, replication or compression. At 10,000/s it is 259.2 GB/day. This makes indiscriminate durable per-particle logging inappropriate. Retain semantic boundaries and batch references; aggregate diagnostic rates and keep optional detailed traces under a bounded retention policy.

Measure payloads independently from event envelopes. A million references to one source should not duplicate its bytes. Retention roots include active reviews, selected run histories, deployed productions and exported bundles. Garbage collection must not delete a proof's source while retaining a green “verified” badge.

## Performance experiment protocol

Freeze inputs, versions, machine profile and thread counts. Measure cold start, warm run and restart recovery. Report p50/p95/p99 latency, throughput, peak RSS, allocations, bytes read/written, selected premises and exact result equality. Run UI load concurrently with inference so the shared CPU contention is visible.

For an optimization, use the same answer/coverage requirements and compare total work. Reject a faster result achieved by silently dropping difficult clauses or timing out more often. A CPU-friendly algorithm earns its place through the full pipeline's resource and correctness measurements.
