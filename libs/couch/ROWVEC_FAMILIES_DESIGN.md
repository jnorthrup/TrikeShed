# RowVec Families Design

Goal: make RowVec specialization the first-class feature that unlocks MiniDuck storage, Duck-like expansion, and blob/document/tree effects.

## 1. Starting premise

MiniDuck should not start from:
- lazy secondary indexes
- SQL parser surface
- tensor lowering

It should start from specialized RowVec families.

`RowVec` is the parent abstraction.
A row may be:
- a flat scalar record
- a zero-length parent shell
- a parent with lazy child families
- a block shell representing an analytical/storage block

## 2. Family model

Each family is a specialization of parent `RowVec` semantics.

Core families:
- `DocRowVec`
  - scalar top-level document fields
  - child families for nested objects/arrays
- `ViewRowVec`
  - Couch-style `id/key/value/doc`
  - child payload expansion when `include_docs` or nested payload traversal is needed
- `JsonRowVec`
  - parse-tree family over JSON blobs
  - child nodes for object keys / array elements / scalar leaves
- `YamlRowVec`
  - parse-tree family over YAML blobs
  - child nodes for mappings / sequences / scalar leaves
- `BlobRowVec`
  - zero-length or sparse shell for opaque payloads
  - child effects for parsers/decoders/previews/metadata
- `BlockRowVec`
  - parent shell for a sealed chunky block
  - children expose doc/view/blob families contained in that block

## 3. Blackboard interpretation

A RowVec family should be treated as a blackboard construct:
- parent row = anchor/context
- child rows = derived or deferred effects
- overlays/provenance/evidence can decorate either parent or child families
- flattening is optional, not fundamental

This lets us represent:
- nested document expansion
- blob-backed parse effects
- derived view/group summaries
- deferred decoding work
without forcing a single rectangular row shape too early.

## 4. Zero-length parent rows

Zero-length parents are valid and useful.

Examples:
- a `BlobRowVec` parent with no scalar cells but children for:
  - metadata
  - MIME/type effects
  - JSON/YAML projections
- a `BlockRowVec` parent with no direct scalar cells but children representing row families inside the block
- a `ViewRowVec` shell whose nested `doc` materialization stays deferred until traversed

Invariant:
- emptiness of the parent does not imply absence of meaning
- it may only mean that meaning has been deferred into children

## 5. Chunk/block connection

DuckDB thinks in blocks/chunks.
MiniDuck should let `BlockRowVec` be the bridge:
- append fills a mutable block family
- seal creates immutable read-many block families
- scans iterate blocks first, then rows/families inside blocks
- derived children (views, JSON/YAML expansions, summaries) hang off sealed blocks

## 6. Why this comes before indexes

If RowVec families are correct, later indexes are just derived children.
If RowVec families are wrong, an index only accelerates the wrong abstraction.

Therefore the implementation order should be:
1. parent/child RowVec family contracts
2. block shells and sealing semantics
3. traversal/flatten/project/explode operators
4. derived indexes/views/summaries
5. optional tensor lowering

## 7. First implementation targets

When implementation starts, prefer:
- `libs/couch/src/commonMain/kotlin/borg/trikeshed/couch/rowvec/RowVecFamily.kt`
- `libs/couch/src/commonMain/kotlin/borg/trikeshed/couch/rowvec/DocRowVec.kt`
- `libs/couch/src/commonMain/kotlin/borg/trikeshed/couch/rowvec/ViewRowVec.kt`
- `libs/couch/src/commonMain/kotlin/borg/trikeshed/couch/rowvec/BlobRowVec.kt`
- `libs/couch/src/commonMain/kotlin/borg/trikeshed/couch/rowvec/JsonRowVec.kt`
- `libs/couch/src/commonMain/kotlin/borg/trikeshed/couch/rowvec/YamlRowVec.kt`
- `libs/couch/src/commonMain/kotlin/borg/trikeshed/couch/rowvec/BlockRowVec.kt`

## 8. One-sentence summary

RowVec families are the primary feature of MiniDuck: they make nested docs, chunky blocks, JSON/YAML/blob effects, and later Duck-like expansion possible without prematurely collapsing everything into flat rows or index-first storage.