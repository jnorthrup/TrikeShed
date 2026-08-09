# Prong 2: ISAM-Indexed Memory Retrieval Route

## CONTEXT

Paper 2607.26637v1, RQ2: "organization's unambiguous payoff is search cost"
(organized stores roughly halve retrieval cost where material is large). RQ1:
"file counts misdescribe organization" because hierarchy lives in headings,
not just files. The paper's search agent "traverses the filesystem and returns
attributed answers."

TrikeShed's ISAM layer is the indexed traversal engine that branches off Couch
blobs. The components exist but are not wired to memory file indexing:

- `src/commonMain/kotlin/borg/trikeshed/couch/isam/ConfixIsamIsomorphism.kt` —
  extracts flat ISAM schema from a Confix index via three facets: Tags
  (ConfixIndexK.Tags), Spans (ConfixIndexK.Spans), DirectChildren
  (ConfixIndexK.DirectChildren). Maps Confix tree structure to RecordMeta.
- `src/commonMain/kotlin/borg/trikeshed/couch/isam/ConfixIsamFactory.kt` —
  constructs indexed store from Confix document tree.
- `src/commonMain/kotlin/borg/trikeshed/couch/isam/ConfixWal.kt` —
  write-ahead log for index durability.
- `src/commonMain/kotlin/borg/trikeshed/couch/isam/WalFrame.kt` — WAL frame
  format.
- `src/commonMain/kotlin/borg/trikeshed/couch/isam/Stringpool.kt` — the
  FunnelHash production surface (beta-buckets). Membership queries without
  SHA-256 per probe.
- `src/commonMain/kotlin/borg/trikeshed/couch/isam/DurableAppendLog.kt` —
  append-only log interface.
- `src/commonMain/kotlin/borg/trikeshed/couch/CouchStore.kt` — has
  `CouchChangesProjection` (observable mutation stream) and
  `MutationEvent.Inserted/Updated/Deleted`.

The paper's management agent mandate ("is this already stored elsewhere?"
strategy step 1) maps to Stringpool membership queries. The paper's
"continuously rewriting a memory bank can degrade it" warning gets a durability
floor from ConfixWal (append-only trail, recoverable).

## TASK

1. Read `ConfixIsamIsomorphism.kt` to confirm the three-facet extraction
   (Tags, Spans, DirectChildren). Confirm that `inferIsamSchemaFromConfixIndex`
   takes a `ConfixIndex` + `keyNames: Map<Int, String>` and returns
   `Series<RecordMeta>`.

2. Define `MemoryIndexRoute` in
  `src/commonMain/kotlin/borg/trikeshed/couch/isam/MemoryIndexRoute.kt`:
   - A route is an ISAM index over the memory store keyed by a specific facet
     (taxonomy path, temporal locator, agent provenance, content hash).
   - `typealias MemoryIndexRoute = Join<IndexKind, ConfixIndex>` where
     `IndexKind` is a sealed class: `Taxonomy`, `Temporal`, `Provenance`,
     `Membership`.
   - Each route is a lazy Cursor projection over the same underlying CAS blobs
     (no duplication).

3. Wire `CouchStore`'s `CouchChangesProjection` to trigger ISAM reindexing on
   mutation. When `MutationEvent.Inserted` or `Updated` fires for a memory
   document, parse the content as a ConfixDoc, run
   `ConfixIsamFactory` to build/update the ConfixIndex, and append a
   `WalFrame` to the `ConfixWal`. This makes the index eventually-consistent
   with the document store.

4. Implement `Stringpool`-backed membership check:
   `fun exists(description: String, contentHash: Long): Boolean` using the
   existing beta-bucket FunnelHash. This answers the management agent's
   "is this already stored elsewhere?" question without a full scan.
   `mix64(hashCode, seed)` only; never SHA-256 per probe.

5. Implement temporal-locator indexing: parse `[S{n}T{m}]` source locators
   from memory file content (the paper's inline source attribution format).
   Index them as an ISAM route keyed by (session, turn), so the search agent
   can query "what was said in session 6" and get the cited memory files.

6. Wire the ISAM route query results as a Cursor into the forge blackboard
   camera. When the search agent queries a route, the top-k results appear as
   `BlackboardSurfaceRow` entries with lane="memory", facet="isam",
   provenance=<route kind>, causalKey=<query hash>.

7. Verify: write `MemoryIndexRouteTest` under `src/commonTest/kotlin/` that
   inserts three memory documents with different source locators, queries the
   Taxonomy route and Temporal route, and asserts the correct files are
   returned by each route independently.

## BUILD GATE

```
./gradlew jvmMainClasses --console=plain
```

## TERMINAL SURFACE

ISAM route query results visible as a Cursor in the blackboard camera frustum.
The RTS camera can switch between route kinds (taxonomy view vs temporal view
vs provenance view) over the same underlying memory blobs.

## FAN-IN NOTES

This prong owns the index layer. It consumes the `MemoryFile` type from Prong 1
(via ContentId) but does not modify BtrfsReflinkStore. It reads CouchStore's
changes projection but does not modify CouchStore internals. Patches commute.
