# Native retained English opener and visual query — v71

Both native browser cases passed with a genuinely empty `trikeshed.subvm.home`: no installed or mounted guest modules, real `GET /api/documents/english` reporting `available: false`, and **zero fresh inspection requests**. The UI opened actual stored parser observations through the new production retained route. It did not substitute a cached response for a fresh parsing action.

The current-source case opened the original 41-byte inspection of:

> Tenant is a Person. A Person is an Agent.

The response preserved the original receipt payload and CID, adding only explicit retrieval metadata: `parserExecuted: false`, `modelCalled: false`, and `currentness: checked-before-and-after-open`. The page states that the parser was not run and recorded execution is not reverified. The original CoreNLP 4.6 producer remains separate historical evidence, not a newly authenticated producer in this test.

A manual navigation to the production Panels gallery then loaded the inert copular diagram and the connected query preset. The user-visible receipt CID and `current` mode were entered explicitly; no automatic context/CID navigation is claimed. One native authored run queried that retained dataset and produced two case-preserved **syntax** rows:

| Sentence | Subject node / word | Shared head / word | Copula node |
| --- | --- | --- | --- |
| `Tenant is a Person.` | `1:0` / Tenant | `4:0` / Person | `2:0` |
| `A Person is an Agent.` | `2:0` / Person | `5:0` / Agent | `3:0` |

The eight-pattern query joins recorded `cop` and `nsubj` edges on the same full governor ID. These word/identity rows are not full quotations, class assertions, instance/subclass choices or legal force. Full quotations and source spans stay in the opened inspection. No new review, approval, inference, world fact or NARS confidence was produced.

## Current and stale cases stayed separate

The positive fixture used an exact private copy of the **never-amended** original producer runtime for receipt `3f958…`. Production project remounting and the canonical English verifier checked the real selected source tuple. The new UI successfully reopened that receipt without a parser installation.

The negative fixture used the actual v70 runtime **after** its deliberate source amendment. Its selected document has current CID `aff67b…`, revision 2 and sequence 1, while old receipt `96cf…` records the original `efc743…` source, revision 1 and sequence 0. The native opener requested the real current tuple and explicit old range `0..41`. It returned `409: Retained English inspection belongs to another source revision`, displayed no inspection and offered no historical fallback. No head was rewound, renamed or mixed with the positive fixture.

A separate Manual navigation project was legitimately created in each reduced fixture. The original selected project's head, manifest, intake policy and source mirror remained byte-identical to that case's own archive. Original ledger rows remained present; whole-ledger equality is not claimed because of the added navigation target.

## Native controls

| Control | Observed result |
| --- | --- |
| Invalid CID syntax | Open button disabled; no request. |
| Changed range `0..40` | Immediate old-result removal, explicit request `409`; no automatic reopen. |
| Well-formed unknown CID | Real retained route `409`, no substituted inspection. |
| Edit CID while an actual response is held | Owned cancellation/epoch fence; late result cannot repaint the edited selection. |
| Navigate to another document while an actual response is held | Old controller cannot repaint the new document. |
| Three deliberately altered transport responses | Receipt-ID, range and source-CID mismatches rejected by the UI; pristine restoration used the real endpoint again. These are labelled negative controls, not fabricated positive responses. |
| One native current copular run | Two exact source-linked rows, original named graph/current-source descriptor, and actual retained program/run/invocation JSON. |
| Separate amended source | Native `409`, zero inspections, zero authored runs, zero query dispatches, no historical fallback. |

The positive case made 15 retained-open requests across explicit opens and controls, one authored run and one real English query dispatch. The stale case made one retained-open request and no run/query dispatch. Both browsers had no page errors or external requests. Both live owner trees, default review/English owners, books, project scopes, Kanban owners and listeners fully drained and closed.

## Frozen identity and retained bytes

| Artifact | SHA-256 / content ID |
| --- | --- |
| Frozen source | `4bdcc227781fa68b07c0105655a19f3e0c11959f` |
| Main JAR | `6ae17af797780fa01aac7db107908241d9066a976628f3711f7cddc4e66c1355` |
| Documents JS | `2d5eb1391a648c3d46adff6f42818182acfaff188ef6a63d668a9e6157730e94` |
| Spacegraph JS | `449b64d2e884e3b3533695a03abd4185fd7ae558a884af18917651b0b2657d19` |
| Pages JS | `ded11c2d63b300ff59e04a09666c3fb63244f6ca5a02f18348d1b1f647832d4c` |
| Original source | `sha256:efc7434909a40c8650ed56d2d44c44751cc0877c7b867eb252174cfff7053883` |
| Current positive inspection | `sha256:3f958801fe455258b0b4dea29a8cc4b77c26ce508e8988b67055c67cde221a0d` |
| Separate stale inspection | `sha256:96cf4246c2ac61240d6f1f5ef24c53a6243cbdca65a11f98225ec02618586919` |
| New durable query receipt | `sha256:7f52bb88d7f4f15da2bc70ebdd7bc0116dd7ddcb4adf37197d93bcd61366a57f` |
| New authored program | `sha256:72c5be05ebe0bba2acd3a717002803414f9b84fd7a3abd30f6ec0d6a331e6574` |
| New invocation ledger | `sha256:4f2640e949560f160fca0a0b41e5b513687eb7c0c219caebf192ad71c57d9863` |

The coordinated build passed all 389 selected checks without failures, errors or skips and compiled all three browser bundles. The fixture and closed-store reader compiled fresh against the frozen main JAR plus 119 immutable dependency JARs, using only their own proof namespaces. No production overlay, friend access, old compiled fixture class or live build output was used. The initial external fixture compile had a bracket typo; that failed preparation and source are preserved, and the corrected fresh compile preceded both first-pass browser runs.

After full shutdown, the production bounded PackCasStore reopened and the canonical English verifier rechecked both old source/inspection pairs. All five positive blocks and both stale blocks matched the exported bytes. The three new stored JSON blocks matched actual browser content responses and run outputs. Eight small selected-project files were independently hashed against each case's own archive; the included `runtime/…` excerpts are evidence, not a complete restartable store.

Model/provider calls, automatic intake, review intake and analysis Rete facts stayed zero. The primary blackboard value map stayed at its readiness baseline. Operational board/run records were kept in a separate actual Kanban context. Outbound socket probes were denied in the fixture process and the browsers made no external requests. This proves that bounded process filter, not a full OS sandbox, enterprise host bootstrap or untrusted LLVM containment. No Confix/LLVM native-scanner execution is claimed by this particular UI proof. Runtime logs retain the existing interpreter-fallback warning; no performance benchmark is claimed.

## Inspect and replay

- [Stored inspection opens without an installed parser](positive/screenshots/native-retained-open-no-installed-parser.png)
- [Current visual copular query completes](positive/screenshots/native-retained-inspection-copular-query-completed.png)
- [Durable bindings](positive/screenshots/native-retained-copular-durable-bindings.png) and [source descriptor](positive/screenshots/native-retained-copular-durable-source.png)
- [Late open cannot repaint another document](positive/screenshots/native-retained-late-open-selection-isolated.png)
- [Amended source refuses the old receipt](stale/screenshots/native-retained-stale-refuses-without-fallback.png)

The exact actual positive source archive is [input-unmutated-runtime.tar.gz](positive/input-unmutated-runtime.tar.gz). The negative archive is preserved by the preceding [v70 amended-source evidence](../english-reviewed-taxonomy-v70/closed-runtime-after-mutation.tar.gz). Case setup records bind their archive hashes. Neither is silently restored to the other case's head.

Replay the portable independent audits from this directory:

```sh
node positive/independent-audit/audit.cjs .
node positive/independent-audit/combined-audit.cjs .
node positive/independent-audit/after-close-audit.cjs .
```

The positive audit checks exact source/payloads, shared-head joins and current named-graph bindings. The combined audit checks the genuinely amended source and native refusal. The after-close audit checks retained JSON/CBOR bytes and the eight project-file excerpts. Twelve in-memory tamper controls must be rejected; none mutate captured artifacts or execute production services. Parser execution is bounded by the actual empty SDK, fresh availability response, zero fresh inspection requests and production retrieval flags, not by an invented global parser-call counter.
