# Native English → reviewed taxonomy support — v70

The native Documents pilot passed on its first attempt using one fresh local CoreNLP 4.6 inspection of the exact 41-byte source:

> Tenant is a Person. A Person is an Agent.

The parser supplied source-linked syntax, not class meanings. A configured **synthetic validation reviewer** used the real authenticated review UI to explicitly choose a context, relations, entity sorts and exact IRIs. The selected declarations were:

```lisp
(instance urn:legal:Tenant urn:legal:Person)
(subclass urn:legal:Person urn:legal:Agent)
```

After the exact selection was frozen and approved through the real review WAL, the existing pure taxonomy engine supported `(instance urn:legal:Tenant urn:legal:Agent)`. Both original English occurrences and both derived KIF forms remained visible with exact quotations, spans and full parser bindings. They are **two occurrences in one document**, not independent documents, repeated evidence or increased NARS confidence.

This is conditional support under the reviewer's explicit translation assumptions. The automated fixture approval is not a human legal review, English entailment, legal applicability, or permission to publish world facts.

## Native flow and controls

The browser dropped the real TXT file into a new Manual project, retained the selected document identity, and clicked **Inspect English locally** once. The unmodified `DocumentReviewWire` owned its default lazy CoreNLP reader; no injected reader, mocked annotation, replacement review response or replayed “fresh” inspection was used. The earlier actual producer receipt `3f958…` remains a separate preliminary control.

The walkthrough then exercised real header authentication and candidate inspection, blank default choices, two explicit declarations, translation preview, exact selection freeze, synthetic approval, and reviewed support with English/KIF witnesses.

| Control | Result |
| --- | --- |
| Wrong reviewer credential | Real session response `401`; no candidates or review authority appended. |
| Missing choices / incompatible entity sort | Client refusal; no translation POST. |
| Frozen selection without approval | Reviewed plan `409`; no positive support. |
| Approved explicit premises | `SUPPORTED` for Tenant → Agent, with two origin-bound occurrence witnesses. |
| Absent positive goal | `UNKNOWN`, which is not a refutation. |
| Raw KIF-candidate view of the English carrier | `409`; the origin-bound translation preview is required. |
| Unconfigured native taxonomy backend | `UNAVAILABLE`; this does not claim that a native export or execution was tested. |
| Actual source replacement after approval | New CID, revision and sequence; native plan `409` and prior result removed, not `UNKNOWN`. |
| Late review response after document navigation | Old controller could not repaint the new selection; cancellation remained owned. |

The review WAL grew from 8 to 101 to 194 bytes for the snapshot and approval only. Translation preparation, support/UNKNOWN reads, refusals, source mutation and late refresh did not append reviewer authority or consume the approval. The separate exact-artifact helper suite covers recognized-carrier/native-export guards; this UI proof does not claim universal detection of arbitrarily reauthored or stripped provenance.

## Frozen identity and custody

| Artifact | SHA-256 / content ID |
| --- | --- |
| Frozen source | `243db15b73636742e57d4319f01ef8db3230c657` |
| Main JAR | `77b02c2619ee94ad74de7fc0584a45e7b3d3d50701287d7104cd5d6913d4632a` |
| Documents JS | `f90c03ddc54d564013213bc787816065582960a7bd63275cdae854f677f38ca4` |
| Spacegraph JS | `da3e49e994744b5dbb8981ff7436e20527c8569d0794b2d5e9445985bc8300a5` |
| Pages JS | `ded11c2d63b300ff59e04a09666c3fb63244f6ca5a02f18348d1b1f647832d4c` |
| Original English source | `sha256:efc7434909a40c8650ed56d2d44c44751cc0877c7b867eb252174cfff7053883` |
| Fresh final inspection | `sha256:96cf4246c2ac61240d6f1f5ef24c53a6243cbdca65a11f98225ec02618586919` |
| Explicit declaration D | `sha256:760e1a3ca708b669fcab5c62e2e7820f42456694b68ff0ccc8f3c072dab282db` |
| Derived KIF K | `sha256:38933a7824a9b440f306e3cce9f320609ba8f5934f06936fa5043889baed0d96` |
| Origin-bound carrier R | `sha256:6b4b0a27d43d82cb57d70ce885b5983a08aab45036cb696e1862b88e8cfb6182` |
| Frozen snapshot | `sha256:9f9ff8c8c27031eb164c63acd71e7d507e0ce969a63408a9918d056ef0f31bbf` |
| Synthetic approval event | `sha256:ee3df5915d036442e03ee09d0437bf2acbf1c82132016925bdbd64c97da002f3` |

The coordinated configured run passed all 368 selected checks without failures, errors or skips. Its earlier 366-pass/two-gated-skip attempt is retained separately by the build record; the final run exercised the pinned native checks. All 119 dependency JARs and four frozen application artifacts were verified. The external fixture and post-close reader compiled fresh against that classpath, with only their own proof namespaces and no production overlays, friend access or live build classes.

After every live owner drained, the actual PackCasStore reopened. The production carrier verifier reconstructed the same canonical declaration/KIF/preview; all eight exported artifacts matched their retained bytes. The production CRC-framed review log replayed exactly the snapshot and synthetic approval events without changing its 194 bytes. Its approval event records `synthetic: true` and the fixture-only note explicitly excludes human legal approval and world admission.

**Final source state is deliberately stale.** The positive support was obtained before the actual source amendment. The current closed project head now differs from the old inspection, and its recorded refusal must remain intact. `closed-runtime-after-mutation.tar.gz` preserves that state; it must not be presented as an unchanged current source or silently rewound for a retained-inspection test.

## Scope

One actual native Inspect POST and one response were observed through the unmodified default reader, with real 4.6 runtime/manifest/JAR metadata. This is not a claim of an inaccessible global parser-call counter. The JVM used `-Xmx4g`; the fixture inherited the bounded outbound syscall filter and actual TCP/UDP probes were denied. Chromium made no external requests. This is a reduced production-owner fixture, not a full daemon, LLVM host bootstrap, untrusted native sandbox or operating-system-wide air-gap proof.

Model/provider calls, automatic intake, review intake and Rete facts remained zero. The blackboard value map stayed canonically byte-identical to its explicit readiness baseline. No NARS belief bag was installed, no generation occurred, and no global facts were admitted. The profile is narrow and the source is synthetic; this is not a legal parsing or comprehension accuracy benchmark. Recognized exact carrier guards preserve the tested origin path; fresh manually reauthored KIF with a new authenticated review is a different assertion path.

## Evidence

- [Fresh inspection and review entry](screenshots/native-fresh-inspection-review-entry.png)
- [Synthetic reviewer and blank explicit choices](screenshots/native-synthetic-reviewer-blank-explicit-choices.png)
- [Explicit instance/subclass mappings](screenshots/native-explicit-instance-subclass-mappings.png)
- [Reviewed support and both source witnesses](screenshots/native-reviewed-supported-two-source-witnesses.png)
- [Full English/KIF bindings](screenshots/native-reviewed-full-english-kif-bindings.png)
- [UNKNOWN is not a refutation](screenshots/native-reviewed-unknown-is-not-refutation.png)
- [Stale-source refusal](screenshots/native-stale-source-is-refusal-not-unknown.png)

[Browser result](browser-result.json), [frozen identity](artifact-identity-after.json), [post-close carrier/WAL readback](reopened-cas-wal.json), [exact review WAL](review.wal), and [cleanup](cleanup.json) bind this pilot. Exact source/CBOR bytes are in `cas-readbacks/`; external harness sources are archived in `harness/`.

Replay the independent bounded audits from this directory:

```sh
node independent-audit/audit.cjs .
node independent-audit/after-close-audit.cjs .
```

The first audit rejects six in-memory alterations to sorts, quotations, source identity, approval identity, UNKNOWN classification and carrier bytes. The second independently checks the raw WAL framing/CRC32C and rejects WAL corruption, approval substitution and promotion of the deliberately stale source to current. Neither audit executes production services or mutates captured files. Runtime logs retain the JDK/polyglot interpreter-fallback warning; no performance or full platform compatibility claim is made.
