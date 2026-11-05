# Three-file synthetic data capsule relocation: v84

This separately records actual local relocation, production replay and a read-only query using three new regular data copies while the entire old v83 proof root is hidden. Production reader PID 150089, namespace exit and reader exit are 0. Independent public-data audit passes, repeats byte-identically and rejects four mutations. The [primary v83 workflow evidence](../nars-exact-role-v83/README.md) remains unchanged at its original cutoff; this is the later capsule proof, not a relabeling of the earlier fourteen-case run.

## Exactly three semantic inputs

| Capsule-relative file | Bytes | SHA256 |
| --- | ---: | --- |
| `cas/pack` | 366,478 | `a7ba5f6e7c0e0cb25909566ba31169b372984c68e54e8711e69bcdf61a71a566` |
| `beliefs.wal` | 637 | `b03f753a36f90c205fb45d9f67c4ee5736a0779118341b3789fc30abfaf32da4` |
| `host/.kanban/board.wal` | 5,838 | `eb04f8089e2231334d3b29ef67bd9e5861491dbf63590aa45d6d641f4d164cdd` |

Total copied semantic data: **372,953 bytes**. These are independent regular byte copies, not hardlinks or symlinks. The public archive includes exactly these three files under `proof/capsule/`. It excludes the generated `board.wal.lock` as input: the actual production constructor created a new zero-byte lease sidecar, whose metadata is recorded. No lease or original inode identity is transferred.

The independent pack parser verifies every 37-byte kind/length/digest header and payload hash: 166 unique BLOB records, zero REF records. The actual production reader performs all 166 bounded reads; 113 selected exported blocks match the new pack. No old source file, REF resolver or network retrieval is required for these observed data dependencies. Verification/data-copy manifests are external expectations and provenance, not a payload resolver or additional semantic input.

This particular small synthetic closed pack is authorized for publication here. Other complete runtime trees, packs, SDKs, models, JARs/classes/native executables, private PDFs and user documents are not included. The payload is not a portable executable or a complete machine image.

## External runtime pins

The actual reader uses source `87e7bb3e945546137c595ff5aaf53781f4168275` and external main JAR SHA256 `f459698815fdc18e0edb00d06b28cdc503ad085e8b6210ff127166cb5d1347ff`, plus 119 dependency JARs and a newly compiled observer. Runtime and compiled classes remain external. No browser asset JAR is needed. This documentation branch starts from published `c4c07ef1150cb29affb74f1b8b8bcfb43a58d8e2`; it does not claim the reader ran against later v85 artifacts.

Exact CodeSources, runtime manifests, commands, compiled-source reuse hashes and class-hash metadata are included. No new product source, build, ordinary fourteen-case rerun or model/English parser execution is introduced by this package. Production strict KIF syntax parsing remains part of the actual exact-role selection query; it is not a new English/CoreNLP/LLM execution.

## What the actual reader established

The bwrap invocation makes the root read-only, shadows the whole old v83 proof root with tmpfs, and permits the new capsule/output paths to be writable. Actual mountinfo and before/after probes show the six specified old pack/WAL/readback/source/observer paths absent. Controller and reader share the changed private mount/network setup, only loopback is up, routes are empty, and external TCP reports `ENETUNREACH`. These are recorded local boundaries, not a full R4 rootfs closure, hostile escape guarantee, remote-filesystem/federation/IPFS result or hardware/crash/power-loss durability proof.

Production `PackCasStore`, `DocumentAppendLog`/`BeliefBagElement` and `BoardFileWal`/`BoardStoreElement` read the copied bytes. Independent WAL1 framing/version/sequence/length/CRC32C checks cover six configured DOC-LOG1 frames `R,B,A,A,D,E` and 42 board frames. The actual eviction E frame and verified SignalCodec spill support final empty bag replay; this is not a fresh unconfigured empty bag offered as persistence evidence. All 42 board pointers resolve to copied command bodies and fourteen recovered cards with their run/program/status identities. No active priority recovery after eviction is claimed.

One real `NalNodes` exact-role delegate query reads manual C2/pays/Tenant and returns a complete documentary match for plain lexical `Landlord` with its source-scoped PID, `MATCH_NOT_RESIDENT`, `beliefs:[]`, and null typed angular/proposition targets. It does not revive the evicted belief or perform admission, attention or a tick. C2 and both canonical definitions retain ordered lexical roles and null entity IDs; this establishes no manual-source authentication, legal truth, review authority, source currentness or independent evidence.

All three data hashes stay identical before/after replay and query. Original source files are unchanged in the non-owning controller records. Stat-only lease observation while held avoids opening/closing another same-process descriptor; after-close controller hashing records the new empty sidecar. Actual board/bag/worker/parent/CAS/GuestModules owners drain/close with zero remaining children. The new lease is bookkeeping, not a fourth copied semantic input.

## Preserved history and audit scope

Attempt 1 compiles the observer, enters the private namespace, then fails `ip lo up` with EPERM under default capability drop **before any reader JVM starts**. Its commands, source, compile exit/log and failure log remain. A controller-only preflight then fails `metadata.mkdir()` with `FileExistsError` before attempt-2 directory/namespace/reader creation; its recorded error summary and exact old run.py remain. Only directory reuse handling changes there.

Successful attempt 2 grants `CAP_NET_ADMIN` inside the private user/network setup to bring loopback up and reuses the exact already compiled observer with recorded hashes. Its fresh output remains separate. Root read-only/old-root mask/route/data assertions are retained. No failed result is relabeled as successful and no old runtime is reset.

The independent first audit already passes; its exact script/result snapshot remains. The final audit adds explicit manifest/controller/report bindings, repeats identically and rejects changed old-path visibility, pack payload, belief WAL frame and typed-target revival. This is supplied-data/recorded-observation consistency, not independent authentication of the execution log, producer or source semantics.

## Public data replay without Java

Python 3.9+ with a fresh nonexistent directory is sufficient:

```sh
python3 replay-recorded.py.txt --extract /tmp/nars-data-capsule-v84-fresh-replay
```

The wrapper verifies `SHA256.json`, safely extracts unique bounded regular members against `records-manifest.json`, restores the unchanged external auditor siblings and requires byte equality with `recorded-audit-result.json`. The auditor's **default mode already uses public supplied data and recorded metadata**. It takes no `--recorded-only` flag. The wrapper does not pass `--check-external-artifacts`, launch Java, execute production replay/query, access old producer/JAR/model paths or recreate the namespace.

The optional external-artifact audit mode is documented in `recorded-audit-usage.txt` but was **not executed** by the independent auditor and cannot run from this data-only package alone. Actual production relocation evidence comes from the preserved closed reader records, not from this Python audit invocation.

The ordinary archive contains only the three semantic data files plus the selected raw result/readbacks/controller/mount/network/command/exit/source/manifest/audit/history records. Per-member hashes and sizes are in `records-manifest.json`; `SHA256.json` hashes every outer file except itself. `verification.json` binds exact pins/counts and auditor hashes. Hashes identify evidence bytes, not a separate origin signature, executable-portability guarantee, semantic proof or OS seal.
