# Managed NLP → curation facets: executed sample

Verified 2026-09-08T16:28:25Z. [Actual output](document-curation-index-sample.json) and [source hashes](document-curation-index-source.sha256) were retained from the connected run. Tokens, dependencies, NER labels, model input, cursor sheets, source CIDs, pointcut stages and fixture refusals in that JSON are execution output.

The input is the exact passage in [PRELOAD.md, line 192](../../PRELOAD.md#L192), at UTF-16 file offsets [7896, 7972):

```text
Bounded
  channels carry work through stages and return results or failures.
```

File CID: `sha256:31d1ea37ba34d0de82b18f094dcdec5317b016dde137e061d377522637fd6da8`. The original passage is 76 ASCII bytes; its original CID is `sha256:ae2057b87da072887d990dfc076e81e2337ee7821d125650e16bd7ed147f2304`. Tika retained that whitespace and appended a newline, yielding 77 extracted characters and extracted-text CID `sha256:f5ed45c116a09ce0c7b9966c64f38097a2e8ad6942719706c2d3c340eb8239b0`. Sentence/token coordinates refer to this extracted text, not file bytes. The single sentence spans [0, 76).

Managed CoreNLP 4.5.10 returned 12 tokens and 14 dependencies. Every NER label is `O`; no named entity was reported. Selected actual edges are below; parenthetical integers are sentence-local token indices, with governor 0 denoting the parser root.

| Governor | Relation | Dependent | Dependent UTF-16 span |
|---|---|---|---|
| ROOT (0) | root | carry (3) | [19, 24) |
| carry (3) | nsubj | channels (2) | [10, 18) |
| return (8) | nsubj | channels (2) | [10, 18) |
| carry (3) | obj | work (4) | [25, 29) |
| carry (3) | conj:and | return (8) | [49, 55) |
| return (8) | obj | results (9) | [56, 63) |
| return (8) | obj | failures (11) | [67, 75) |
| results (9) | conj:or | failures (11) | [67, 75) |

The model received the same source-linked sentences/tokens/dependencies under `linguistics` after NLP completed. Parse confidence, interpreted source certainty and external verification are unavailable; these fields are distinct from model proposal confidence. The parser's conjunction edges are retained without converting this compound sentence into a supported assertion. For this passage the deterministic model fixture returned an empty proposal array; curation recorded `model proposed no assertions` and submitted 0 attributions.

Execution path:

```text
DocumentCurationLegos.create (explicit Volume/CAS/log/bag/model ownership)
  → LcncRunner.runProcedure (scope.in → document.curate)
  → DocumentFeed.submit → DocumentInputElement → Camel → Tika
  → DocumentCuratorElement → CoreNlpRuntime → source.curationIndex → model
  → retained DocumentCurationRecord → record.curationIndex
  → SentenceCursor / TokenCursor / DependencyCursor → sheetSeed
  → existing sheet.count → scope.out
```

The existing sheet consumer returned 1 sentence row; nested sheets contain 12 token rows and 14 dependency rows. Rows retain original/extracted CIDs and sentence/token indices; token and sentence rows retain text spans. The registered LCNC path was exercised; the daemon's optional registration compiles, but the daemon still needs an explicitly owned conforming storage binding before this is an operational daemon feature.

The identified-source edit fixture changed only `failures` to `errors`. Actual NLP token 11 changed accordingly, both source CIDs changed and downstream cursor sheets changed. Five labeled fixtures cover negation, condition, modality, attribution and alternative readings. Their proposed polarity/modality remained inspectable and all stayed pending. Each was also submitted with a deliberately incorrect unconditional-positive model proposal; source clause structure rejected all five with 0 attributions. The supported control `Acme pays Beta.` submitted one source attribution through the existing belief intake.

With CoreNLP absent from a process-local module root (real Tika/Camel still available), the same LCNC procedure returned `UNAVAILABLE`, retained the installation error, skipped the model, produced no proposals/attributions, and returned an empty sheet. Restoring the managed module restored NLP and model invocation. The fixture ran 14 model calls across 15 submissions; feed/route/jobs drained, and the real passage's ordered correlated stages were `tap → exchange → extraction → input → nlp → model → join → record`.

Verification passed:

- Current-source production overlay compilation, including the LCNC adapter, registry/key contracts, optional SubVmLegos registration, cursor consumer and managed feed sources.
- Connected managed-library LCNC harness, including source edit, source qualification, failed unconditional proposals, missing NLP, recovery and drain.
- 15 DocumentCurator checks: prerequisite ordering, prompt content, failing/invalid NLP, independent-document overlap, replay/provenance, conflict, CAS validation and drain among them.
- 5 facet tests and 3 LCNC adapter tests (JUnit: `OK (8 tests)`).

Reproduce from the repository root with an existing compatible support jar and installed managed modules:

```bash
bash scripts/verify-document-curation-index.sh \
  /Users/jim/work/TrikeShed/build/libs/TrikeShed-jvm-0.1.0-SNAPSHOT.jar \
  /Users/jim/work/TrikeShed/utils/subvm
```

The script uses Kotlin 2.4.10/JDK 25 and locally cached dependencies. Optional third argument chooses the output directory; default `/private/tmp/trikeshed-document-curation-index` retains compiler output, exact classpath, source hashes, toolchain, harness output and regression results. The managed libraries and Spring were checked absent from the application classpath; CoreNLP/Tika/Camel run through their existing managed module loaders.

This is source-overlay execution against the identified support jar, with a deterministic model and volatile userspace Volume/CAS/log fixtures. It is not a clean whole-repository build, live-provider run, disk durability test, native io_uring validation or live-daemon validation. Host file IO only identifies the repository passage in the verification harness; document ingestion goes through the existing userspace volume/input element. No runtime, storage backend or removed CCEK facade was fabricated to make the run pass.
