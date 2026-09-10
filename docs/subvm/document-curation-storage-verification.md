# Document curation storage verification

The 2026-09-08 run compiled the current userspace IO, storage adapters, migrated context, and application route sources, then ran two separate JVM processes. The [retained output](document-curation-storage-sample.json) records both runs; the [source manifest](document-curation-storage-source.sha256) identifies the compiled closure and support dependency. This is scoped source compilation, not a full repository build. Historical index-only artifacts are separate.

```bash
bash scripts/verify-document-curation-storage.sh \
  /Users/jim/work/TrikeShed/build/libs/TrikeShed-jvm-0.1.0-SNAPSHOT.jar \
  /Users/jim/work/TrikeShed/utils/subvm \
  /private/tmp/document-curation-storage-final
```

Use a new output directory when repeating the command: the script deliberately refuses to overwrite its persistent state directory. It compiles current common/JVM userspace sources (no binary IO overlay input), rebuilds the curation index and migrated application route closure with Kotlin 2.4.10/JVM 25 and the Compose compiler, and launches `DocumentCurationStorageHarness write` and `reopen` as separate JVMs. Managed Tika, Camel, and CoreNLP use the supplied sub-VM installation. Host NLP/Spring jars are not added. Recorded outputs are under the specified temporary directory.

Both phases exercise `JvmKanbanServer.routeHttp` → the existing `KanbanModule` handler for `POST /api/lcnc/run` → registered `document.curate`. This fixture calls the production HTTP parser and handler in process; it does not bind a network listener. The application context and its route consumers were compiled from current source with the domain `CcekReactorBinding` type. No removed `CCEK` facade was invoked.

The real input is PRELOAD.md, UTF-16 file offsets `[7896,7972)`, with this exact admission body:

```json
{"type":"document.curate","inputs":{"source":{"name":"PRELOAD.md:identified-passage","mediaType":"text/plain","text":"Bounded\n  channels carry work through stages and return results or failures."}}}
```

The original CID is `sha256:ae2057b87da072887d990dfc076e81e2337ee7821d125650e16bd7ed147f2304`. Tika adds a final newline; the extracted text CID is `sha256:f5ed45c116a09ce0c7b9966c64f38097a2e8ad6942719706c2d3c340eb8239b0`. Both processes obtained the same real managed CoreNLP sentence, 12 tokens and 14 dependencies, and projected cursor sheets. Actual dependencies include `root(0,3)`, `nsubj(3,2)`, `obj(3,4)`, `conj:and(3,8)`, and `conj:or(9,11)`; the sample preserves every span, token, NER value and dependency. All NER tags for this passage are `O`.

The reopen process submits retained content without resending source bytes:

```json
{"type":"document.curate","inputs":{"source":{"name":"PRELOAD.md:identified-passage","mediaType":"text/plain","originalCid":"sha256:ae2057b87da072887d990dfc076e81e2337ee7821d125650e16bd7ed147f2304"}}}
```

The model is explicitly a deterministic fixture. It receives the available linguistic index before returning proposals. For the real passage it returns an empty proposal list, and the retained record says `model proposed no assertions`. The separately labeled `Acme pays Beta.` control proposes one grounded triplet; the first run submits receipt `sha256:1ec5d2cbe977c39bb5864121479359a1e13b338b213231ac5d7a7ea5d07563e5`. The second JVM replays three curator ledger frames, submits zero new receipts, and reports that same receipt as a duplicate. The verification bag's separate WAL restores its signal (size 1); this does not establish recovery of the bag's receipt/basis metadata.

Storage uses an existing directory and one writer per owner:

- `document-source.bin`: dedicated staging extent (1 MiB in this fixture, 64 MiB daemon default). Stage → sync → exact readback precedes managed extraction. It is scratch space; CAS preserves earlier versions.
- `document-cas.wal`: typed, versioned, CRC-framed append storage for original bytes and curation receipts; successful put forces storage before return.
- `document-curator.wal`: durable curation reservation/submission ledger used for replay and duplicate suppression.
- `document-belief-test.wal`: verification-only bag WAL, sharing the fixture CAS. The daemon's existing bag uses a separate global CAS; this fixture does not prove daemon cross-CAS lookup or metadata recovery.

The source volume recorded actual correlated SQ/CQ `OPENAT`, `WRITE`, `FSYNC`, `READ`, and `CLOSE` completions in each phase. CAS/log use the same userspace file API's exclusively owned synchronous compatibility channels. On this macOS host the report is `uring_emulated`, `ioUringAvailable=false`, `nativeCapabilities=0`, with native availability `HOST_UNSUPPORTED: JNI io_uring requires a known Linux arm64 or x86_64 host`. No Linux native io_uring run is claimed.

The script passed 15 curator checks, 9 facet/admission checks, and all 10 persistence tests. Persistence tests cover reopen and duplicates, frame CRC/type/version/length validation, torn tails, partial IO, and controlled failures. Both fixture JVMs drained successfully. Source hashes matched disk when the fixture completed; the external HTTP client was subsequently extended for live provider failure reporting and storage-sheet capture. It is included in that snapshot manifest but was not executed by this fixture.

Live daemon/provider evidence is pending the coordinated full build. The separate [HTTP client](../../scripts/verify-document-curation-http.py) sends only the identified PRELOAD passage and labeled positive control, retains real provider content, and repeats `originalCid` admission after an external daemon restart. It rejects `providerId=fixture` as live evidence. No live provider, daemon restart, new NARS rule, or reasoning bridge is established by the fixture result above.
