# Nonprofit document ingest through Camel and PostgreSQL

This is an executed local LCNC demonstration, not a finished nonprofit agent or client-ready ontology.

## Open

http://localhost:8890/panels?load=npo-ingest-postgres-eip

The original 16-node version was opened in Chrome and run using the Construction Panels Run button. The UI reported `Program completed` for run `75c05218-4dac-4b30-9dfa-dc2c2d1b4f12` with receipt `sha256:a56f8034651d0ca5d842e15645c104cce333e73fda1898e6607fe7a1e7e40f3c`. The palette and evidence drawer were closed and the connected graph fitted to the window. The source-registry revision below has 17 top-level nodes and 18 wires; it has fresh API and PostgreSQL execution evidence, but has not been re-run using the canvas button.

The isolated daemon runs the retained compiled TrikeShed runtime with the tested native source-cache and inventory overlay described below. Its launcher preserves the environment, working directory, data home, module configuration and air-gap settings; only the classpath overlay and explicitly configured owner-only temporary root change. The environment is not printed or saved. Its application data is under `/Users/jim/.local/share/trikeshed/npo-demo/forge`; the original daemon and corpus on port 8888 are not changed. Source changes are isolated in `/Users/jim/work/TrikeShed-npo-lcnc` on `codex/npo-lcnc-flow`. Nothing is committed or pushed.

## The actual flow

1. `project.docs` selects PDFs from the mounted `corpus` project.
2. A typed inventory envelope retains document IDs, original CIDs and project identity. The `source-links` JSON node supplies an explicit original-CID-to-URL registry. Only an exact registry match supplies a URL; an unrelated document or missing registry leaves the source unknown. The graph carries references, not repeated full PDF text.
3. The saved `npo-source-store` composite invokes the existing first-class `vm.camel` resident in native Groovy DSL mode:
   - Split document records.
   - Enrich each metadata-only record through a local `direct:npo-extract` route. It checks the canonical original CID against the exact document ID in `project.docs`, invokes the existing `project.extract` node, and checks the inventory identity again before proceeding. The source-store also reads the completed extraction-origin twin and requires its source/text CIDs to match the request and returned text. These use the isolated daemon's local API and reuse TrikeShed's extractor rather than introducing a substitute parser.
   - Choice routes nonempty extractions to `ready` and missing/empty extractions to `review`.
   - Before insertion, nonempty extracted text must carry a canonical SHA-256 CID equal to the digest of its UTF-8 bytes. Missing, malformed or mismatched text CIDs are rejected before that record reaches JDBC. Empty extraction without a CID can still route to review.
   - Parameterized `jdbc:npo` writes to real PostgreSQL with an upsert key of collection, original CID and extracted-text CID.
   - A second JDBC operation reads stored text back. The route emits source identities, text length and a bounded preview of matching source passages, not the full text.
4. `npo-semantic-map` identifies literal vocabulary candidates (impact, outcome, output, activity, indicator, assumption) in those passages. Each proposal retains the original CID, extracted-text CID and exact UTF-16 offsets. Similarity or proximity never establishes a causal/measurement edge.
5. Review and retrieval displays show the candidates, source receipts and unembedded RAG/vector handoff records. No candidate is automatically approved and no external vector service is called.

Expand the `npo-source-store` composite to inspect the real Camel route. PostgreSQL is in the executed middle of the flow, not represented by a mock node or external replacement pipeline.

## Definitions

Under `src/commonMain/resources/lcnc/nonprofit/`:
- `workflow.json`: main composition.
- `source-store.json`: Camel enrichment, routing, PostgreSQL persistence/readback.
- `semantic-map.json`: bounded, explicitly unapproved vocabulary proposals.

The module resolver in `utils/subvm/build.gradle.kts` adds `camel-postgres` as a child of `camel-jdbc`, with PostgreSQL JDBC 42.7.14. The existing Camel 4.8.5 EIP engine and JDBC component remain the runtime implementation. Install with:

    ./gradlew -p utils/subvm installCamelPostgres

The initial flow is composed of saved LCNC programs and an optional guest dependency. A subsequent native inventory repair modifies the existing `JvmProjectCorpus` class; that change and its targeted deployment are documented below.

## Runtime and database

Daemon port: 8890. It was started with `--airgap`, no coding agents and zero remote session slots. No paid model calls are needed by the flow.

PostgreSQL container: `trikeshed-npo-postgres`, official `postgres:17-alpine` image, bound to `127.0.0.1:15432`, database/user `npo_demo`. Its persistent volume is `trikeshed-npo-demo-data`. Credentials live outside source and outside the LCNC document, in owner-readable files under `/Users/jim/.local/share/trikeshed/npo-demo/`. Do not publish those files.

The native Camel route is OWN-trust host code and uses DEPARTMENT reach for JDBC. It is not a sandbox. The local extraction URL and credential-file location are deployment-specific and must be reviewed before moving this flow to another installation.

The whole program can be exercised with POST `/api/lcnc/run` and body:

    {"program":"npo-ingest-postgres-eip"}

Use the saved programs API `/api/panels/<name>` to read definitions. The programs were saved and read back independently before running.

## Verified evidence

The successful real-PDF run and independent checks are under:

    /Users/jim/.local/share/trikeshed/npo-demo/evidence/

- `real-pdf-completed.json`: HTTP 200, status `completed`, run `41305e98-0972-4b9a-8b69-7c9c1a2a921b`, receipt `sha256:8ae07c70a9f63c0b144e752cf9992bb9005945c3f2ebb364f06a83e178aac111`.
- `regressions.json`: repeat-run idempotence; Split/Choice yielding one ready and one review record; SQL-looking text preserved as data; PostgreSQL text digest matching the retained extraction; all displayed source spans checked against that stored text.

The real source is the public IPPF Africa Region Annual Report 2024, copied from the already-retained public document after a fresh remote request returned 403. It is not a customer-provided logframe. Original PDF SHA-256: `e46da3031714709b87fcd219bb77f89afdd055a58408b97aca77189daa45c9c1`.

That run retained 70,941 extracted characters in PostgreSQL, found 17 vocabulary-bearing source lines and displayed the first 8. The preview is explicitly marked truncated. Full source is retained; the preview is not the full semantic mapping. Zero relationships were accepted.

## Source-registry verification

The previous projection unconditionally assigned the demonstration PDF URL to every document. Three regression probes reproduced that false attribution before the fix and passed after it. The graph now supplies the registry through a real typed scope input/output connection rather than embedding one URL in the projection code.

Fresh execution evidence:
- `source-registry-run.json`: completed real-PDF run `24704af5-65f6-46c4-9953-09aaa30c4208`, receipt `sha256:bb2cd064439da4ae8551febcc5da34b2c5c0d580dcd11d4da350942dab80bd61`.
- `source-registry-verification.json`: independent PostgreSQL readback confirms exact-CID URL attribution and the SHA-256 of the retained extracted text.
- `source-registry-negative-run.json`: the complete graph ran with an empty registry in the explicitly named `regression-source-no-catalog` collection. Both graph output and PostgreSQL retained an unknown source URL; no demonstration URL was substituted.

The source bytes were hashed locally and matched the project inventory CID before adding the registry entry. This checks retained-document identity, not current remote URL availability, publisher identity or truth of the document's assertions.

## Retained-text integrity verification

A real failing probe showed that the former Camel route accepted altered text under a digest belonging to different text. The route now validates canonical CID syntax and computes SHA-256 over the UTF-8 content before inserting the record.

- `text-integrity-run.json`: completed public-PDF run `1fd9fe0e-03ec-45da-b39f-4afd0a35f52f`, receipt `sha256:5c4528c2438e065afc4d9b69c3fe0d466a1913d467a913d2b3fd8bf29cb5281e`.
- Its recorded `npo-source-store` program version equals the saved updated composite CID, `sha256:b1fa8d438651eef8e05fa8876bd9d0424c4622aac0479af89f5ce23bb8d3d6ac`; this was not only a test of unsaved inline code.
- `text-integrity-rejection.json`: an actual LCNC graph wired literal fixture records into the saved composite. It failed with the expected digest-mismatch exception and left zero rows in its uniquely named regression collection.
- `text-integrity-verification.json`: independent PostgreSQL digest verification of the retained public PDF text, successful/failed run identities and targeted-check count.

The integrity checks include matching Unicode text, mismatched text, malformed and missing CIDs, and empty-extraction review routing. This guards incoming records, not historical rows. It proves retained-text byte integrity, not the association between a particular original-document revision and a cached extraction; the changed-source probe below subsequently reproduced a failure at that boundary. Whole-corpus atomicity is also not established: an earlier valid record in a batch may already have been stored when a later record is rejected.

## Project-document identity admission

A failing real-PDF probe showed that metadata-only extraction accepted a supplied original CID belonging to different content. The source-store composite now requires a canonical original CID matching the exact project document ID before extraction and checks that inventory identity again afterward. A prefix match is not an exact-ID match, and missing or malformed identifiers are rejected rather than inferred or repaired.

- `project-identity-run.json`: completed real-PDF parent run `aad8e673-eae6-4bdd-8b68-99ee7790331d`, receipt `sha256:b5aedf70d11b096e461129c4f57991a3b74dfe672ae5a91d4bc94605eb05a7df`.
- The parent executed the updated saved source-store CID `sha256:1bab479d093e13cd031bca89ced3f19a9969227f257edab92c46b66b820b3d4d`.
- `project-identity-rejection.json`: a graph wired the real public PDF ID and a different synthetic original CID into that saved composite. It failed for the expected identity mismatch and inserted zero rows.
- `project-identity-verification.json`: fresh project-inventory identity matched independent SQL readback; the retained text digest also matched. Sixteen targeted checks passed, including missing/malformed original IDs and rejection of prefix-only IDs.

This is an admission check, not atomic version pinning. It does not prove that a cached extraction was generated from the current original version. The changed-source runtime test below now demonstrates a stale native extraction in the isolated runtime. This conclusion comes from execution, not merely absence of a check in one accessor. Imports that provide text directly still have caller-asserted original-document identity. Main-daemon programs are unchanged.

## Changed-source cache containment — historical intermediate step

The isolated upload-lane probe used synthetic HTML, not customer documents. Version ALPHA produced an extraction at project commit sequence 2. Uploading version BETA changed the original CID and advanced the source to sequence 3, but `project.extract` still returned ALPHA and its old text CID. Before containment, the PostgreSQL flow accepted BETA's original CID alongside ALPHA's extraction: all individual hash checks passed, but the cross-version association was wrong.

The source-store composite now reads the extraction twin's identity and project commit sequence through `project.read`. It refuses unknown revision provenance, a changed extraction identity, or an extraction older than the current source revision before insertion. The committed project sequence is used, not the attachment's wall-clock `sequence` field.

- `source-version-probe-red.json`: the observed stale-cache failure before containment.
- `source-version-probe.json` and `source-version-rejection.json`: the same source-replacement scenario executed against the saved composite, rejected with zero database rows.
- `source-version-public-run.json`: the unchanged public PDF still completed in run `fea88bcc-1c18-46a1-8b8c-1e5ebdc5aa6e`, receipt `sha256:81d62d06b773c5fe7370722f1aedb2edfadc43d920b84243b0484cd0de55fc35`.
- `source-version-verification.json`: exact executed composite version, negative/positive run identities, independent SQL checks and removal of all synthetic probe scopes. Seventeen targeted checks passed.

This intermediate change was fail-closed containment, not cache repair. It still returned old native text and conservatively rejected some unchanged re-uploads. Those historical failure artifacts are retained. The native repair and strict successful refresh tests below supersede the sequence-order guard; they do not establish a finished autonomous knowledge base.

Cleanup separately exposed that `project.docs` listed deleted attachment tombstones and derived twins. The subsequent native inventory repair below fixes that behavior in the isolated daemon; it has not been deployed to the main daemon. Test cleanup uses unmount of only its uniquely named synthetic scope and independently verifies removal. The real demonstration corpus and main daemon remain untouched.

## Native inventory repair

`JvmProjectCorpus.docs` now excludes tombstoned records and uses the existing `ProjectNodes.twin` classification to exclude all extraction, notes, footnote and link companions. Filtering occurs before sorting and applying the requested limit, so a deleted first entry cannot consume a live-document slot. Empty but live source files remain visible. No extraction-cache behavior was changed by this native patch.

The full `ProjectCorpusJvmTest` class was compiled with the already-cached Kotlin 2.4.21 compiler against the retained runtime. Seven tests ran: two new inventory cases failed before the implementation change, then all seven passed. Deleted-read and empty-live-file controls also passed. This is targeted native compilation and testing, not a repository-wide build. No dependency download or build-exclusion change was made; the compiler emitted its existing Unsafe deprecation warning.

Only the owned port-8890 daemon was gracefully restarted, with the compiled class directory placed before its existing runtime jar. Port 8888 and the main working tree were not changed. The source and test changes remain uncommitted in this isolated worktree.

Evidence under the existing evidence directory:
- `native-inventory-build.json`: compiler scope, source/bytecode hashes, seven compiled class files and runtime identity.
- `native-inventory-junit-red.log` and `native-inventory-junit-green.log`: actual failing and passing native runs.
- `native-inventory-live.json`: live LCNC upload/delete/list/read checks; deleted source excluded, all four twin families hidden, limit one returns the live source, and a zero-byte live source remains present. The synthetic project was unmounted and its removal verified.
- `native-inventory-public-run.json`: public-PDF parent run `b9a8ce41-9aaf-4e43-b470-1c2d5be22019` completed after deployment, receipt `sha256:c94c8678851e83f3fb2f4507a5c51c9d002aaeabc49c831e910145c4a9f1af4a`.

At that stage, all seventeen existing LCNC/Camel/PostgreSQL checks passed while the stale-cache result was only contained. The native source-version repair below now requires and demonstrates fresh successful output instead.

## Native source-version cache repair

The native extractor now publishes `EXTRACT_ORIGIN_SUFFIX` (`.extract-origin.tsv`) after completing extraction: the exact source CID and retained-text CID, separated by a tab. Cache reuse requires both to match the currently registered source and extraction. A legacy, missing or mismatched origin causes re-extraction; the result is checked again before return. Recognition and batch mining use the same validity check rather than treating any existing extraction as current. Valid unchanged-source re-uploads reuse their extraction.

The miner parses a private, digest-checked snapshot rather than a mutable source filename. It verifies the registered source before publication, serializes mining per document and preserves the original filename in the snapshot so generated text headers stay deterministic. Snapshotting adds local disk I/O; this path is not claimed zero-copy. Temporary snapshots are removed after use. The runtime's owner-only temporary root is explicitly configured under the active profile's scratch directory.

PDF page/TSV caches are now separated by source CID and extraction route. Re-running PDF ingestion cannot reuse another source version's page cache. Origin twins participate in the existing derived-file classification/removal, and TSV is readable through the LCNC text interface. The source-store checks the exact origin pair instead of the earlier temporal-order heuristic, allowing a valid unchanged re-upload through PostgreSQL.

Executed evidence:
- `native-cache-red.log`: five actual native failures before repair, including stale HTML, stale PDF page output, untrusted legacy cache reuse and a source change during ingestion.
- `native-cache-junit-green.log`: all fifteen tests in the full native corpus test class passed, including unchanged-source reuse, batch refresh, configured temporary-root use and reading origin metadata.
- `native-origin-gate-red.log`: the old LCNC sequence guard rejected a valid unchanged re-upload; the explicit origin-pair check replaced it.
- `native-refresh-html-changed.json`, `native-refresh-pdf-changed.json`, `native-refresh-html-unchanged.json`: strict live tests against the saved composite. Changed HTML and PDF inputs returned BETA, not ALPHA, and the correct new source/text pair reached real PostgreSQL. An unchanged re-upload also completed successfully.
- `native-cache-public-run.json`: public-PDF parent run `9c75e4d4-3f95-44f0-b66b-021796d01656` completed, receipt `sha256:ca78a1ae702ccf530366d06c85c2b5a83a261bda5b3f1cc86082e814abdf3395`.
- `native-cache-verification.json`: exact runtime/composite identities, source and bytecode hashes, three strict live cases, independent SQL checks, fifteen native tests, twenty live acceptance checks and verified removal of synthetic probe scopes/rows.

This is deployed only in the isolated port-8890 runtime and remains uncommitted. The main working tree/daemon were not changed. The build is offline and targeted, not a repository-wide build. Origin records are integrity metadata from the trusted local writer, not signatures or publisher authentication. Corpus-wide transactions, parser-code/configuration cache invalidation, every direct API's version-pinning semantics, and historical CAS byte retention have not been established by these tests.

## Enterprise acceptance boundary

The concrete enterprise use case is a bounded, evidence-preserving document-ingest pilot into an existing PostgreSQL estate through Camel. The verified portion is source retention, extraction, explicit source attribution, persistence/readback and proposed vocabulary spans. It is not yet a production approval system or an ontology/vector integration.

The main daemon's saved `nonprofit-pdf-workflow` was inspected separately and has `inspectionOnly: true`; this isolated demonstration must not be presented as a fix deployed to that main program. A registered runner and an empty violation list do not prove execution. Source-version refresh is now demonstrated for changed HTML and PDF inputs in the isolated runtime. A remaining business boundary is durable, versioned analyst approval tied to the exact source and extraction CIDs, with stale decisions rejected before release. Authentication, reviewed ontology alignment and a real retrieval/index adapter require separate acceptance evidence. No enterprise pitch was published or sent in this review.

## Limits and next work

- The isolated daemon logs a caught INFO-level RequestFactory startup exception because no JSR-303 Bean Validation provider is present. This does not stop the demonstrated Camel/PostgreSQL flow; provider-backed RequestFactory validation is not established by these tests.
- The inherited runtime also reports a GraalVM/polyglot version mismatch (interpreted fallback) and a missing spine ISAM codec during worktree indexing. These are not claimed fixed by this configuration.
- CoreNLP is not installed in this isolated module home. Automatic corpus semantic curation reports that missing module; this demonstration deliberately uses the working text extractor and explicit lexical review candidates instead. It does not demonstrate CoreNLP or NARS semantic curation.

- Vocabulary matching is lexical proposal generation, not validated ontology alignment, CoreNLP inference or indicator equivalence.
- The preview is capped at 8 matching lines per document. It does not yet have analyst pagination controls.
- Table reconstruction, diagram edges, scanned-document OCR quality and the customer's indicator catalog remain to be tested on customer examples.
- Review is presently an evidence display, not a complete approval/editing application.
- RAG/vector handoff records have no embeddings and have not been indexed in an external database.
- Batch writes are idempotent per document, not atomic across the entire corpus; an error may leave earlier document writes retained for safe retry.
- Early runs exposed LCNC's bounded reporting payload. Passing references and bounded passages fixed the demonstrated flow without dropping full text from PostgreSQL or loosening the daemon's report limits.

Twenty executable acceptance checks passed in the active profile's scratch directory (`test_lcnc_native_refresh.py`, `test_lcnc_source_version.py`, `test_lcnc_project_identity.py`, `test_lcnc_source_provenance.py`, `test_lcnc_text_digest.py`, `test_npo_camel_flow.py`, `test_npo_semantics.py`). The changed-source probe was also exercised with `LCNC_PROBE_SAVED=1` to verify the installed composite rather than only inline source. They exercise the real daemon; the storage checks execute Camel and PostgreSQL. Synthetic inputs are explicitly regression fixtures, and the metadata-only extraction check uses the real public PDF. The two whole-graph runs above independently cover populated and empty source registries. This is targeted verification, not a repository-wide test-suite pass. No full build or dependency download was performed.
