# Extended Jules Task Trees — Document Evidence Pipeline

## Direction

The extended queue has one purpose:

> Turn documents into inspectable, replayable evidence; use that evidence to
> build taxonomies and numerical retrieval projections without losing the
> source bytes or inventing unsupported facts.

The words map to concrete system roles:

- **Document ingest** preserves source bytes in CAS, identifies media, extracts
  portable text/structure, and records stable source spans.
- **Document comprehension** is progressive enrichment over those spans:
  entities, claims, relations, summaries, and classifications. Deterministic
  extraction runs first; ModelMux work is explicit, versioned, and attributable.
- **Taxonomical evidence building** means every `isA`, `partOf`, `mentions`, or
  related edge carries source CID, span, method, confidence, and derivation.
  A taxonomy edge without evidence is not committed knowledge.
- **Couch** is the revisioned, queryable evidence ledger. It stores evidence
  documents and change history; payload bytes remain in CAS.
- **Tensors** are derived projections keyed by evidence identity: embeddings,
  similarity neighborhoods, and aggregate features. They are disposable and
  rebuildable, never canonical truth.

## Canonical flow

```text
source bytes
  → CAS ContentId
  → TreeDoc archive + deterministic frames
  → Tika/fallback extraction
  → normalized blocks and source spans
  → evidence records
  → Couch committed evidence ledger
  → evidence-backed taxonomy lattice
  → tensor/embedding projection
  → retrieval with citations
  → Forge document, board, graph, and gallery projections
```

## Invariants

1. Raw and extracted payload bytes live once in CAS.
2. Every derived record points to immutable source/extraction CIDs.
3. Every claim and taxonomy edge points to an exact source span.
4. Couch stores metadata, revisions, and CID references—not duplicate blobs.
5. Tensor rows are keyed by evidence ID and model/version metadata.
6. Tensor similarity never creates a fact; it proposes candidates requiring
   evidence-backed confirmation.
7. Deterministic parsers precede model enrichment.
8. Model prompts, responses, model ID, and pass version are attributable, while
   credentials never enter CAS, Couch, Confix, logs, or Forge state.
9. Re-running the same pass over the same inputs is idempotent.
10. Forge surfaces are projections, not independent mutable truth.

## Dependency DAG

```text
J13 Evidence contract + Couch vertical slice
 ├─ J14 Portable document extraction and span normalization
 ├─ J15 Evidence-backed taxonomy reducer
 └─ J16 Evidence tensor projection

J14 + J13 ──→ J17 ModelMux comprehension passes
J15 + J16 ──→ J18 Citation-preserving hybrid retrieval
J14 + J15 + J17 + J18 ──→ J19 Progressive Forge evidence surface
J13..J19 ──→ J20 Corpus replay, evaluation, and operational gates
```

Only J13 is the immediate foundation. J14–J16 become parallel after J13 lands.
Do not manufacture dependencies among siblings.

---

## J13 — Evidence Contract and Couch Ledger Vertical Slice

**Outcome:** one extracted span can become an idempotent evidence document,
commit through the existing Couch ingress, and be queried with its provenance
intact.

**Own:**

- `src/commonMain/kotlin/borg/trikeshed/evidence/**`
- `src/commonTest/kotlin/borg/trikeshed/evidence/**`

**Use existing:** `ContentId`, `CouchStore`, `Document`, `Field`, `Series`,
Confix-facing value shapes, and committed Couch change sequence.

**Deliver:**

- `SourceSpan`: source CID, extraction CID, block/page coordinates, character
  or byte range, and optional media locator.
- `EvidenceRecord`: stable evidence ID, subject, predicate, object/value,
  source span, extraction method, confidence, pass ID/version, model ID when
  applicable, creation time, and supersession link.
- `EvidenceLedger`: lower a record to Couch `Document`; idempotent put; get by
  evidence ID; query by source CID, subject, predicate, and pass ID.
- Stable evidence identity derived from canonical semantic fields, excluding
  timestamps and mutable status.
- Redacted operational representation that cannot expose model credentials or
  prompt authorization headers.

**Tests:** deterministic ID, changed span changes ID, duplicate put is
idempotent, revision mismatch rejects, source query returns exact provenance,
tombstone/supersession remains traceable, Couch replay produces equivalent
head, and no payload-byte duplication.

**Non-goals:** no Tika, model call, tensor math, new Couch implementation,
serializer, storage engine, or UI.

**Gate:** focused common tests plus `compileKotlinJvm` and one non-JVM compile.

---

## J14 — Portable Extraction and Source-Span Normalization

**Outcome:** PDF, office, HTML, Markdown, CSV, image/OCR, and plain-text inputs
produce one portable extraction envelope with deterministic blocks and spans.

**Own:**

- focused additions under `borg/trikeshed/ingest/evidence/**`
- JVM adapter additions beside `JvmTikaIngestAdapter`
- matching common/JVM tests

**Deliver:**

- Portable `ExtractionEnvelope` referencing raw CID, extracted payload CID,
  media type, metadata CID, ordered blocks, and extraction warnings.
- Stable block schema: block ID, kind, text CID or value, page/section/table
  coordinates, source range, language, and parent block ID.
- JVM Tika adapter maps metadata deterministically and preserves repeated
  values; suffix/magic fallback remains available.
- TreeDoc frames remain the byte-preserving archive; extraction is a derived
  projection, not a replacement archive.

**Tests:** identical input produces identical envelope identity; page/table
spans survive; metadata ordering is deterministic; extraction failure emits a
warning and retains source CID; corrupt CAS payload is rejected.

**Non-goals:** no Camel, model comprehension, taxonomy inference, Couch fork,
or duplicated raw/extracted bytes.

---

## J15 — Evidence-Backed Taxonomy Reducer

**Outcome:** extracted structure and comprehension claims become a taxonomy
whose every edge can be explained by evidence.

**Own:**

- `borg/trikeshed/evidence/taxonomy/**`
- matching common tests

**Deliver:**

- `TaxonId`, `TaxonAssertion`, and evidence-linked relation vocabulary:
  `isA`, `partOf`, `mentions`, `definedBy`, `contradicts`, `supports`.
- Reducer from ordered `EvidenceRecord` changes to an immutable taxonomy view.
- Integration adapter to existing `TypeDefOracle`/`IsALattice` where the
  relation is truly `isA`; do not force arbitrary relations into that lattice.
- Contradictions and competing classifications remain visible instead of
  last-write-wins erasure.
- Confidence is an observation attached to evidence, not truth precedence.

**Tests:** edge requires evidence ID; source citation round-trip; duplicate
assertions collapse without losing provenance; contradiction preserved;
superseded evidence recomputes the view deterministically.

**Non-goals:** no ontology editor, generic graph database, model calls, or
unsupported automatic truth resolution.

---

## J16 — Evidence Tensor Projection

**Outcome:** evidence records have rebuildable tensor representations for
similarity and clustering while Couch evidence remains canonical.

**Own:**

- `borg/trikeshed/evidence/tensor/**`
- matching common tests

**Use existing:** `CursorTensor`, common tensor algebra, ModelMux embedding
surface, CAS identity, and evidence IDs.

**Deliver:**

- `EvidenceTensorRow`: evidence ID, source CID, embedding model/version,
  dimensions, normalization metadata, and vector.
- Cursor-to-tensor projection with stable row order and reverse row-to-evidence
  lookup.
- Versioned tensor manifest in CAS; no embedding vectors copied into Couch
  evidence documents.
- Incremental rebuild: unchanged evidence/model pairs retain row identity;
  changed model version produces a new projection.
- Similarity returns candidate evidence IDs plus scores and projection version.

**Tests:** row/evidence bijection, dimension mismatch rejection, deterministic
manifest, cosine-neighbor ordering, model-version isolation, and rebuild from
Couch evidence + CAS tensor manifest.

**Non-goals:** tensor score does not create taxonomy edges or claims; no new
ANN dependency in the first cut.

---

## J17 — Progressive ModelMux Comprehension Passes

**Outcome:** explicit jobs enrich normalized spans into attributable evidence.

**Own:**

- `borg/trikeshed/evidence/comprehension/**`
- matching tests with fake model endpoint only

**Deliver:**

- Versioned pass descriptors for entity extraction, claim extraction, relation
  extraction, classification, and summary.
- Deterministic preprocessing and batching by source span.
- ModelMux route requirements declared per pass; KeyMux credentials remain
  outside all persisted data.
- Strict output validation before an `EvidenceRecord` is committed.
- Prompt/template CID, model ID, route ID, input span IDs, and parser version
  recorded as derivation metadata.
- Retry creates an attempt record; it does not silently overwrite evidence.

**Tests:** malformed model output commits nothing; same validated output is
idempotent; credentials absent; citations cannot reference spans outside the
input batch; deterministic parser path works without a model.

---

## J18 — Citation-Preserving Hybrid Retrieval

**Outcome:** lexical, taxonomy, Couch, and tensor retrieval return evidence and
source citations—not unsupported generated answers.

**Own:**

- `borg/trikeshed/evidence/retrieval/**`
- matching common tests

**Deliver:**

- Query plan over Couch fields, taxonomy relations, and tensor candidates.
- Deterministic fusion with per-channel ranks retained.
- Result rows include evidence ID, source CID, exact span, relation path,
  similarity/rank observations, and projection versions.
- Optional ModelMux synthesis consumes only retrieved evidence and emits a
  citation map validated against the supplied evidence IDs.

**Tests:** every result is traceable; stale tensor rows are rejected; lexical
and taxonomic exact evidence outranks unsupported similarity; synthesis with
invented citation fails validation.

---

## J19 — Progressive Forge Evidence Surface

**Outcome:** dropping a document visibly progresses from archived source to
blocks, evidence, taxonomy, and retrieval without creating a second state
owner.

**Own:** focused Forge projection adapters and tests; no shell redesign.

**Deliver:**

- Evidence status projected from committed jobs/Couch changes.
- Document view highlights source spans behind claims.
- Graph edges open their supporting and contradicting evidence.
- Tensor neighborhoods are labeled as similarity, never facts.
- Reprocessing adds a new pass version and preserves prior evidence lineage.

**Tests:** projection rebuild equivalence, citation navigation, contradiction
visibility, and no direct UI mutation of Couch/taxonomy state.

---

## J20 — Corpus Replay, Evaluation, and Operational Gates

**Outcome:** the complete corpus pipeline is reproducible and measured.

**Deliver:**

- Fixed public-domain fixture corpus covering text, PDF, table, image/OCR, and
  contradictory claims.
- Replay from source CIDs into a fresh Couch head and tensor projection.
- Metrics: extraction coverage, citation validity, unsupported-claim count,
  taxonomy contradiction retention, tensor rebuild determinism, latency, and
  bounded memory/channel depth.
- Regression gate fails on lost citations, non-deterministic IDs, leaked
  credentials, hidden contradictions, or tensor/evidence row drift.

**Non-goals:** benchmark scores do not replace correctness evidence.

---

## Dispatch Discipline

Before every new Jules session:

1. Worktree, index, and untracked set are clean.
2. Checked-out branch matches the requested Jules starting branch.
3. Fetch succeeds and local `HEAD == @{upstream}`.
4. Dispatch one immediate foundation first; verify repository binding and prompt
   read-back before spending another slot.
5. Each job owns disjoint paths and must deliver a non-empty PR with focused
   tests.
6. Never dispatch stale tasks from the replaced J13–J22 list. In particular,
   do not add QUIC/HTTP3, a second graph engine, a second CAS, a parallel FSM,
   or retired conductor machinery as part of this evidence pipeline.
