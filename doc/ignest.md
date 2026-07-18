# Ingest: commonMain Confix / Cursor / CAS design

## Current gap

`utils/ingest` has a useful commonMain scheduling and media-detection SPI, but the payload is still DTO-shaped (`String`, `Map`, `List`) and does not persist extracted bytes, metadata, or manifests through TrikeShed CAS. The JVM Tika adapter detects files but does not expose extracted content as Cursor rows or canonical Confix, and its metadata helper references an undefined `detector`. The root tree has no memvid production implementation; only `src/commonTest/kotlin/borg/trikeshed/memvid/MemvidStoragePipelineTest.kt` defines the intended archive behavior.

## Boundary

Portable code owns identity and structure:

- `ConfixDoc` is the canonical manifest/metadata representation.
- `Cursor` / `RowVec` is the lazy tabular view for documents, frames, and extracted records.
- `CasStore` owns all payload bytes and canonical Confix bytes by `ContentId`.
- commonMain never imports Apache Tika, Apache Camel, `java.io`, or kotlinx JSON/CBOR serializers.

Platform adapters own extraction:

- JVM Tika reads a source and emits portable extracted records and byte payloads.
- suffix/magic-byte detection remains the dependency-free JVM fallback.
- other targets can supply the same SPI without changing archive identity or schemas.

## Memvid archive contract

Add `borg.trikeshed.memvid` in root commonMain.

- `MemvidDocument(path, mediaType, bytes)` is input only.
- Split each document into deterministic `maxFrameBytes` chunks.
- Put every chunk in `CasStore`; frame rows store document ordinal, frame ordinal, byte range, chunk CID, and a lazy payload cell that resolves through CAS.
- Put a canonical Confix document manifest in CAS. Its CID is both `ArchiveId` and `ManifestCid`.
- Return a typed meta-series keyed by `MemvidK`: archive identity, document count, frame count, document cursor, and frame cursor.
- Restore joins frames in ordinal order, verifies each chunk through `CasStore.get`, then verifies the restored document CID before returning bytes.
- Empty archives are valid; `maxFrameBytes <= 0` is rejected.
- Identical ordered inputs and frame size produce identical manifest bytes and archive CID.

The document cursor schema is stable and includes ordinal, path, media type, byte size, document CID, first frame ordinal, and frame count. The frame cursor schema is stable and includes document ordinal, frame ordinal, offset, length, chunk CID, and payload. Cursor metadata uses `ColumnMeta` and `IOMemento`; outside `borg.trikeshed.lib`, access `Series` with `.b(index)`.

## Tika4all ingest contract

Evolve `utils/ingest` without moving JVM libraries into commonMain.

- Replace DTO-only extraction results with a portable envelope containing source identity, media facet, requested projections, canonical Confix metadata CID, payload CID(s), and Cursor projections.
- Persist raw input, extracted text/bytes, and canonical Confix metadata through the injected `CasStore`.
- Expose detected files and extraction outputs as stable Cursor schemas; keep `Series` lazy.
- JVM Tika maps Tika metadata into Confix deterministically (sorted keys, repeated values preserved), stores extracted payloads in CAS, and returns only portable envelope values.
- Keep `JvmMediaFormatChannel` as fallback when Tika fails or is absent.
- Build `utils/ingest` as a composite consumer using `includeBuild("../..")`; do not add portable serializer formats beyond Confix.

## Camel decision

Do not add Apache Camel now. Camel is JVM-only and would duplicate the existing `IngestSchedule` coroutine/channel fan-in while widening the dependency and native-image surface. Permit a future JVM adapter only if a concrete connector requirement appears (for example S3, Kafka, or JMS) and a focused benchmark shows value. Such an adapter must terminate at the same commonMain ingest SPI and must not own manifests, CAS identity, retries, or canonical state.

## Jules split

### J1 — memvid commonMain archive

Own only:

- `src/commonMain/kotlin/borg/trikeshed/memvid/**`
- `src/commonTest/kotlin/borg/trikeshed/memvid/**`

Do not edit `CasStore`, Confix, Cursor, Gradle, or `utils/ingest`. Implement the archive contract above and make the focused common tests pass on JVM plus one non-JVM compile target.

### J2 — tika4all portable ingest

Own only:

- `utils/ingest/src/**`
- `utils/ingest/build.gradle.kts` only if required for existing Tika dependencies

Do not edit root `src/**`. Introduce the commonMain Confix/Cursor/CAS envelope and JVM Tika adapter, preserve suffix fallback, and prove CAS corruption detection and deterministic metadata identity with tests. Do not add Camel.

## Gates

1. Root: `./gradlew compileKotlinJvm` and focused memvid tests pass.
2. Root: at least `compileKotlinJs` or `compileKotlinWasmJs` compiles the memvid commonMain implementation.
3. Ingest: `utils/ingest/gradlew jvmTest` or `../../gradlew -p utils/ingest jvmTest` passes using the composite build.
4. No `java.*`, Tika, Camel, kotlinx JSON, or kotlinx CBOR imports in either commonMain tree.
5. CAS corruption is detected on read; identical input yields identical CIDs.
6. All schemas are asserted by column name and `IOMemento`, not only row counts.
