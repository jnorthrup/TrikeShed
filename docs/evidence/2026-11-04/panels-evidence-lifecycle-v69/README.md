# Panels evidence lifecycle — v69

The v68 copular pilot exposed a real UI race: a deferred camera save invalidated a pending workspace inspection and could leave “Reading workspace…” on screen. An immutable receipt request was also tied to that canvas revision, although its content ID and selected receipt had not changed.

The v69 client settles invalidated workspace loads with an explicit refresh message. Receipt callbacks remain owned by their receipt section and selected request, so a camera save cannot strand an exact content-ID read. A newer content ID, another tab, or a closed drawer still rejects late content. Comparison labels explicitly identify the captured workspace; a stale comparison is not promoted to current state.

This first-attempt browser proof held real production HTTP responses, performed native Fit, observed the deferred `lcnc:shadow-update` camera save, and then released the original response unchanged. It reused the actual closed v68 source, inspection, authored program, run and invocation ledger. There was **no new parser call, inspection, query, graph run, approval or legal inference**.

| Controlled case | Observed result |
| --- | --- |
| Pending workspace response, then Fit/save | Old workspace refused; “Inspection changed before loading completed. Refresh…” replaces the loader. No automatic substitution or refresh. |
| Exact immutable run receipt response, then Fit/save | The same receipt renders with explicit captured-workspace comparison. |
| Select a different receipt while the old one is held | The newer selection stays visible after the old response arrives. |
| Change to Confix & facets while a receipt is held | The new tab is not repainted by late evidence. |
| Close the drawer while a receipt is held | The drawer stays closed; reopening does not inherit the late selection. |

The different receipt is a retained **running** state of the same original run, not another execution. The completed receipt's stored `current` query descriptor describes the original v68 execution. Reading that immutable receipt does not perform a new current-source query or establish present applicability.

## Identity and custody

| Artifact | SHA-256 / content identity |
| --- | --- |
| Frozen source commit | `d891ec320090cf1804a722ee9dcbefcfded0e5d5` |
| Production main JAR, byte-identical to v68 | `236775cffa83486dddc3ccdb4c73f2a04a2fcba44b49ee97f30798d208491e59` |
| New Spacegraph JS | `10d58a2b7b4e09d284b9a1dea4ee02e91850247f20f44bc26153d7a96c942c03` |
| Documents JS, unchanged | `fb3176bc9b4b6c64060be7fe811380b1cb9092ad9c8f8d9a3f39b23d6accb522` |
| Pages JS, unchanged | `ded11c2d63b300ff59e04a09666c3fb63244f6ca5a02f18348d1b1f647832d4c` |
| Original source CID | `sha256:1f310bfb50270455e6aa301123450c519227a30bef7aec0aa58406c9c4a52ae5` |
| Original fresh v68 inspection CID | `sha256:baf539d50f07ad48d282f55cb63bd03ba43edf82a882d51ff0d6e4fd599f0d8e` |
| Original completed run receipt | `sha256:511c9b316a75ef117204af4bf5b439a65bc92c93d2833eff9e3dc183e40eb54d` |
| Previous running state | `sha256:4f479f0470c6b5a5bfab50344ff35dc744310de4a578346312ad1148d8dab9cf` |

The coordinated build executed all 332 selected JUnit methods without failures, errors or skips and built all three JS bundles. External observers compiled only their own namespaces against the frozen JAR and immutable dependencies. No production class overlay, friend access, live build output, provider or parser owner was installed in this proof.

The fixture extracted a private copy of the actual closed v68 runtime, then used production `ProjectScopes.remountLedger` and the normal canonical English receipt verifier. Retained source snapshots matched the real current project rows before the UI controls. Durable project heads, manifests and ledger remained byte-identical afterwards. After every live owner drained and the listener closed, the real PackCasStore reopened and verified the original source, inspection and four stored JSON blocks again. Workspace-computed program hashes are not assumed to name retained blocks.

The actual Panels client, production preset catalog, Kanban/workspace/content owners and receipt rendering were used in a reduced localhost fixture. Transport rejects accidental execution POSTs as a fixture constraint; the browser record shows none were sent. This is not a new production API policy or a full daemon restart test. The fixture process inherited the same bounded outbound syscall-denial filter as the v68 pilot; it is not a general OS sandbox claim. Its model, review, automatic intake, analysis Rete and analysis blackboard counts remained zero. Operational board/run records are separate from legal world facts.

The original v68 parser and one authored query remain documented under `../english-copular-syntax/`, including its failed capture attempts and interpreter-performance warning. This directory proves the subsequent UI lifecycle repair only. Syntax observations still carry no approved taxonomy, positive legal force or resolved guard semantics.

## Evidence

- [Invalidated workspace loader settles](screenshots/stale-workspace-load-settles.png)
- [Immutable receipt survives native camera save](screenshots/immutable-receipt-after-camera-save.png)
- [New selection rejects the late old receipt](screenshots/new-cid-rejects-late-previous-request.png)

[Actual browser result](result.json), [frozen identity](artifact-identity-after.json), [after-close readbacks](reopened-cas-readbacks.json), [unchanged project heads](source-heads-after.json), and [cleanup](cleanup.json) bind the controlled cases. `cas-readbacks/` contains exact retained bytes; `harness/` archives the external sources. The independent audit uses built-in Node only and does not execute a parser or production service.

Replay the independent bounded audit from this directory:

```sh
node independent-audit/audit.cjs .
```

Its five in-memory tamper controls reject a wrong held receipt CID, an invented Run POST, stale workspace overwrite, tab overwrite and a wrong newer selection. They do not execute production APIs or mutate the captured artifacts.
