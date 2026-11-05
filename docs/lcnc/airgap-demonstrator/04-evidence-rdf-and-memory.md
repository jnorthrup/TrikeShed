# Evidence RDF wiki and breadcrumb memory

## Semantic layers

The store distinguishes seven classes of record: acquisition, extraction observation, source statement, scenario assumption, proof result, empirical belief and production authorization. Each has an immutable payload identity and a named scope. Review records refer to the exact object assessed and the assessment question.

A source may state that a user must do something. The extraction can accurately represent that statement without proving that the duty applies to a particular person at a particular time. A hypothetical premise can make a simulation branch eligible without entering the global world graph. A proof establishes a consequence of an admitted theory; an empirical belief summarizes observations under a declared evidence policy.

The distinction is structural. Do not rely on prose warnings appended to an otherwise undifferentiated triple store.

## RDF graph policy

Use named graphs for source/version contexts and separate derived result graphs for run frames. RDF datasets support a default graph and named graphs, but a graph name alone does not authenticate who asserted its contents. Host-owned review and admission records remain the authority boundary. [RDF 1.1 concepts](https://www.w3.org/TR/rdf11-concepts/)

Recommended graph classes are `source`, `observation`, `review`, `scenario`, `derivation`, `belief` and `production`. These are proposed taxonomy labels, not new current API names. A frame's dataset manifest lists exactly which graph identities participate and how. Default queries use that explicit manifest. The graph-blind triples view is permitted only for an intentionally declared union with its semantics visible.

Represent a statement as an identified record when it needs polarity, modality, time, quotation and attribution. Reify the statement record and link its subject/predicate/object terms. This avoids pretending that every described proposition is asserted in the world. Use PROV-compatible entity/activity/agent links for interchange, supplemented by application-specific review and assumption relations. [PROV-O](https://www.w3.org/TR/prov-o/)

## Evidence record fields

| Field group | Required content |
|---|---|
| Identity | Record schema, canonical payload CID, source record IDs |
| Source | Original CID, extracted-text CID, capture/edition, source revision |
| Anchor | Exact quotation, coordinate system, half-open range, page/block/time reference |
| Meaning | Statement kind, exact terms, polarity, modality, condition structure |
| Scope | Dataset, scenario, jurisdiction if relevant, valid time, analysis cutoff |
| Transformation | Extractor/model identity, configuration, mapping policy and ontology IDs |
| Assessment | Assessment target, host reviewer identity, decision, revision and reason |
| Dependence | Exact source leaves, derivation parents, duplicate/overlap classification |
| Coverage | Selected subset, omitted items, unsupported forms and truncation |

Use null only when a field is truly unavailable and the schema permits it. Omitted, unknown and empty values must not collapse. An empty condition list says unconditional only if a reviewed interpretation establishes that meaning; parser failure is an unresolved condition.

## Content identity and canonicalization

Hash canonical boundary bytes with a schema/version tag. A serialization change can change a CID even if a human considers the proposition equivalent. Preserve logical equivalence as a separate relation and never use it to merge source occurrences automatically.

For RDF exports containing blank nodes, ordinary line sorting does not provide canonical dataset identity. Either use explicit scoped identifiers for application records or use a declared RDF canonicalization algorithm when signing/comparing datasets. RDFC-1.0 provides a standardized option; apply input/time limits to adversarial graph shapes. [RDF Dataset Canonicalization](https://www.w3.org/TR/rdf-canon/)

TrikeShed content identifiers are not automatically IPFS CIDs. When exporting into IPLD, retain an explicit mapping between internal payload hash, codec, block CID and root path. Identity conversion is a tested adapter operation.

## Time and retraction

Retain valid time and transaction/observation time separately. A contract amendment may become effective on one date and be imported later. A transcript correction changes the currently accepted transcription without changing when the recorded conversation occurred.

Retraction is a new record that withdraws current applicability or authority; it does not erase the historical artifact. Derived frames carry an `asOf` boundary. Current queries consult source validity and review lineage. Historical replay can show why a result was produced using the then-current frame.

A production's dependency record is separate from NAL observation evidence. Withdrawing a source can suspend current production use without pretending the past observation never occurred. Whether empirical evidence is revised, discounted or retained depends on a named evidence policy; arbitrary subtraction from packed counts is prohibited.

## Wiki projection

Wiki pages are navigable projections of retained artifacts. A page CID identifies that projection, not the source document. Preserve existing CurationWiki behaviour and extend links in this order: run → question → selected evidence → exact source passage → review record → proof step → production candidate → deployment/effect.

Source links resolve locally. If the linked source is unavailable, display its identity and availability status instead of fetching it. Browser navigation to a source span must verify the selected text revision. A revised source gets a new page/anchor target; the old link continues to represent history.

Generate backlink indexes by artifact identity. Text-label matching can suggest related pages but must not establish evidence dependence. A wiki article can summarize a result while keeping the source statement, simulation assumption and derived conclusion separately styled and inspectable.

## Breadcrumb recall

A breadcrumb is a compact retained pointer into an episode: what question was considered, what sources were used, what action occurred, what outcome was observed, and what remained unresolved. It may include a summary, but the summary is a derivative that points to the original turn or artifact.

Use three retrieval passes: exact identifiers and lexical terms; structured context filters; optional semantic ranking. Merge with deduplication by source occurrence and episode ancestry. Return a bounded candidate set with retrieval scores and exclusion reasons. Scores affect ordering, not confidence in the underlying claim.

History from the user, an agent and a borrowed collection has distinct attribution and allowed-use scope. Agent-generated text is not an independent witness to the source it summarized. Two agents citing the same document share that evidence basis. Borrowed memories may provide leads while being ineligible for factual admission until their sources are available.

For offline operation, store tokenizers, lexical indexes and any optional embeddings locally. Query-dependent embedding caches include model, tokenizer, normalization and text identities. Deleting or withdrawing an episode must invalidate indexes and prevent stale summaries from reintroducing it into current output.

## Review workflow

Assess extraction fidelity, term mapping, scenario membership, logical-fragment admission and production use as separate decisions. A single UI wizard may guide these steps, but the retained decisions must remain distinct. Use the existing authenticated review actor and non-consuming read capability for inspection. Preserve consuming permits for the mutating sinks that require them.

SHACL-like shape checks can validate required fields and relationship structure. They cannot establish source truth or legal applicability. SPARQL can retrieve explicit selected graphs and provenance chains; a query returning `reviewStatus=approved` does not manufacture a host review capability. [SHACL](https://www.w3.org/TR/shacl/), [SPARQL 1.1](https://www.w3.org/TR/sparql11-query/)

## Acceptance conditions

Follow one output back to exact source bytes through all transformations. Repeat the same proof via two paths and verify no extra empirical evidence appears. Keep conflicting assumptions in sibling scenarios without cross-contamination. Withdraw a review and verify current production eligibility changes while historical wiki pages remain inspectable. Recover the same provenance chain after restart from the semantic journal and immutable artifacts.

## Implemented query-row boundary: R3e

The [R3e integration](../../airgap/15-wire-geometry.md) retains bounded native
Horn proof quads and two fixed inspection programmes over supplied proof and
selection context. Typed source identity, ordered premise uses and exact row
coverage remain refusal boundaries. These projections do not establish native
execution, authenticated review or a production permit.

An actual query-row failure exposed `LinearArrayMap` entries with identity
hashing despite structural map equality. Entry equality/hash semantics are now
corrected, so equal parsed nested rows remain usable in hash-based coverage
and ordering. The query assertions were preserved. Before/after diagnostics
use the actual quad engine and JSON reifier; tests cover reordered fields,
nulls, indexed lookup and both hash-index directions. This common representation
contract matters wherever an evidence row becomes a set member or lookup key.
