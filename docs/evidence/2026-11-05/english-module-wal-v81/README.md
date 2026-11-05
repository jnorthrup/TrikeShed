# Focused English module/WAL ownership proof: v81

This records one actual 11-node retained S12 NAL Run, a G07 typed query with two source focuses, a distinct live contender refused by the production WAL lease, and a fresh reader recovering the completed card after graceful writer close. Writer PID 143740 and reader PID 143962 exit 0; contender PID 143939 exits the expected 42. The independent strict and public audits pass and reject five mutations. All 507 selected tests and three JS builds passed.

This is a focused cooperating-process ownership/recovery check. It does **not** repeat the full v80 CoreNLP/corpus workflow and does not prove crash/power-loss durability, hostile-writer or lock-replacement resistance, physical-disk guarantees, remote-filesystem behavior or a full filesystem seal.

## Exact artifact boundary

| Item | Pin |
| --- | --- |
| Published source | `7fb91a0b56cd860a13ce0af8eeef10205e586314` |
| Main JAR SHA256 | `5986b5d76fd50e9a1bc1562c86618650fa1b10c46ddf6ffd38d4dd97f744278c` |
| Documents JS SHA256 | `c795ccc0168c36bf66b59a46acf07276b6813154e8515ef4ec018261f90d2086` |
| Panels JS SHA256 | `d9c4e7ec00718c8cc52f0c55e53621096c43f13a675e5c0d56bf5ba6c9eabe1b` |
| Pages JS SHA256 | `4678fdc9798bb36e7ce23e1c9756fcff182436955fdd6767f193114f6e05ead5` |

The root byte comparison finds all 149 English class files identical to the previous verified v80 main `e2352d79aa5b8a6180ed7152897a80bd98d86a48cd3282e4656268ec1ee7bf0d` at source `fdfa355623f03958536089c81e87162e6c8313ff`. Only four class entries changed: three `BoardFileWal` entries and `LcncKanbanExperience$registry$9`. This supports the stated unchanged-English boundary; it is not a new full parser/corpus runtime replay. The actual classpath/origin records distinguish 120 frozen production/dependency JARs, an asset-only browser JAR and the external observer class directory. No JARs, classes, models or native executables are supplied here.

The full raw Documents JS hash changed. Root's executable-code comparison finds no difference after stripping inline source maps, with **8 webpack eval bodies and 229 wrapper lines**. The initial metadata mislabeled the 229 wrapper-line count as eval bodies; both that exact initial record and corrected comparison are preserved. The old Documents hash is `6e99a60f9942c1242ae20a016e1e7993294175f72d7a1b3638b3bcc760ab48cb`. Neither result claims the full raw JS files are byte-identical. These class/JS comparisons are supplied root records; public replay does not recompute them against omitted runtime binaries. Panels and Pages raw hashes remain unchanged. Current unverified later source work is outside these pins.

## One Run and exact journal

The [actual retained NAL Run](native-one-retained-nal-run-wal.png) produces the unchanged historical S12 report: five dependent quotation occurrences, four unique original premises, twelve ordered pairs, one +289/-0 deduction hypothesis and two +224/-0 weak-induction hypotheses. Duplicate occurrence provenance does not add independent evidence. The outer English owner separately checks currentness before and after evaluation. This run performs no new parser/model execution or source amendment; original physical heads remain unchanged. It establishes no semantic/legal accuracy, calibrated truth, producer/source authentication, review or live observation admission.

The actual source WAL is included as `proof/native-attempt1/after-close-readbacks/board.wal`: **417 bytes**, SHA256 `8579ebcdd44ab7fabaa39e76f83b0f99c0046e3f9ac5c3db5a59c99d573cebcb`. The independent parser checks WAL1 framing, big-endian version, lengths, CRC32C and strict sequences 1, 2, 3. Their submit/start/complete pointers bind captured workflow records. Only the final command body is supplied through an actual fetched CAS block; the earlier two pointer bodies are not represented as fetched content.

The final pointer matches the recovered DONE card and commandCID, separately from programCID and invocationsCID. Complete ordinary output is inline and retained in the final command; computed NAL frameCID is not a fetched CAS report. All eight indexed blocks are supplied and read after close: original S12/G07 inspections and sources, G07 candidate receipt, admitted program, invocation ledger and final command. The actual exported WAL and supplied block bytes can be verified; omitted full runtime/CAS bytes cannot.

## Real contender and graceful handover

While the holder is live, a distinct actual JVM targets the same canonical module directory, WAL and stable sidecar inode. Its production constructor raises `IllegalStateException: Filesystem lease is already held` and the probe exits 42. Captured before/after WAL, CAS, source-head and committed-state fingerprints remain unchanged. This is an ordinary cooperating-owner refusal, not a timeout, simulated response or hostile filesystem attack.

Holder telemetry **stats** the sidecar only. It does not open and close a second same-process sidecar descriptor to hash it: that could release a POSIX record lock. Independent non-owning controller/probe records supply their own hashes and inode metadata. The evidence does not make an in-holder sidecar-descriptor read/hash claim.

After graceful holder drain/close, the fresh production `BoardStoreElement` reader acquires the released lease, replays the same WAL, reconstructs the DONE card/command and closes. Source rows/heads, WAL/sidecar and CAS fingerprints remain unchanged across the reader. Expected board clock/card facts and workflow blackboard receipts are recorded; they are not English/NAL belief admission. The configured host has no installed/mounted SDK, fresh parser POST, generative provider endpoint, bag/KIF/NARS owner or intake hook. Earlier v80 model/parser observations remain tied to their original `fdfa`/main `e235...` pins.

The [G07 typed query](native-retained-G07-typed-query.png) retains two raw attachment rows, including its unmarked `Trustee` branch. Both source focuses preserve the URL and exact citations. It is an unreviewed retained parser observation, not independent legal gold or a semantic repair. Actual loopback namespace/interfaces/routes and external-route refusal are recorded; the filesystem is not sealed.

## Selected records and failures

Only original synthetic S12/G07 source/request/row/inspection CBOR, the eight actual CAS exports, actual 417-byte journal, observer sources, raw requests/responses/states/logs, class origins, separate controller/lock metadata, commands/PIDs/exits/cleanup and two actual PNGs are included. Full packs/prefix bytes, unrelated cases, model/dependency binaries, browser profiles, raw unrelated journals and private PDFs are excluded. Original absolute producer/REF paths are recorded metadata, not a relocated/self-contained CAS promise. Build logs retain synthetic fixture PDF basenames as log references, not PDF content.

Initial audit attempt 1 failed an assumption that raw UI document shape equals admitted serialization. Its exact script/result is preserved. The corrected auditor compares actual `LcncProgramConfix` explicit node/default fields, `kanban:null`, `seq:1` and name as separate request metadata. Attempt 2 and final strict/public checks pass without altering native data. The five mutated copies reject wrong active directory, contender PID equal to writer, changed committed WAL, corrupt CRC and programCID substituted for recovered commandCID.

Successful browser progress is omitted only after confirming all observed/requests/errors/external fields equal the preserved closed raw result; the original hash/path and reason remain in the selection record. Exact build verification and JS byte-comparison records include the initial counter-label error. No failed native/audit attempt or raw result was rewritten.

## Recorded-data replay

Python 3.9+ and a fresh nonexistent directory are sufficient:

```sh
python3 replay-recorded.py.txt --extract /tmp/english-wal-v81-fresh-replay
```

The wrapper verifies `SHA256.json`, safely extracts unique bounded regular archive members against `records-manifest.json`, restores the unchanged auditor beside its receipt-core text dependency and runs recorded-only verification. It requires exact equality with `recorded-audit-result.json`. No JVM, parser, browser, application, query engine or build runs. This mode checks supplied blocks/WAL and recorded artifact relationships; strict-local mode additionally rehashes original locally available artifact and closed runtime bytes. Exact commands and scope are in `recorded-audit-usage.txt`.

Archive/member/script/result hashes are in `verification.json`, `records-manifest.json` and `SHA256.json` (which excludes itself). These hashes identify bytes; they are not an independent origin signature, legal/semantic proof or OS seal.
