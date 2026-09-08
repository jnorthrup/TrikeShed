# Tasks 33–42 reconciliation — associative engine / grid front page / canvas context (capped 2026-08-23)

Track: `2026-08-22-tasks33-42-associative.md` (10 tasks; no session map was ever minted — no owner).
Opened: 2026-08-22. Capped: 2026-08-23. Reconciled against the working tree.

| # | task | disposition | evidence |
|---|---|---|---|
| 33 | MIME in commonMain (`nlp/mail/Eml.kt`) | RETIRED | no owner, zero artefacts: `src/commonMain/kotlin/borg/trikeshed/nlp/mail/` does not exist |
| 34 | TokenSpine — the missing link (`nlp/lemma/TokenSpine.kt`) | RETIRED | no owner, zero artefacts: no `TokenSpine`/`tokenSpine` symbol anywhere under `src/` |
| 35 | Residual matcher (`recruit/MatchKernel.kt`) | RETIRED | no owner, zero artefacts: `src/commonMain/kotlin/borg/trikeshed/recruit/` does not exist; no `MatchKernel`/`MatchReceipt` |
| 36 | NAL evidence; feedback is the learner (`recruit/MatchEvidence.kt`) | RETIRED | no owner, zero artefacts (depends on 35) |
| 37 | Bias as visible wires | RETIRED | no owner, zero artefacts (depends on 36) |
| 38 | Sources, engine, sink as catalog widgets (needs 28) | RETIRED | no owner, zero artefacts; no `source.mailbox`/`match.residual`/`sink.shortlist` widgets |
| 39 | `_design/recruit` views | RETIRED | no owner, zero artefacts: no `_design/recruit` string under `src/` |
| 40 | Phone-class bench (last) | RETIRED | no owner, zero artefacts (depends on 34–39) |
| 41 | Front-page grid over views | PARTIAL→RETIRED remainder | `src/commonMain/kotlin/borg/trikeshed/lcnc/editor/DatabaseView.kt` + `src/commonTest/kotlin/borg/trikeshed/lcnc/editor/DatabaseViewTest.kt` exist; DatabaseView contains no `_design`/`ViewQuery` binding — grid is not bound to a store view; remainder retired with the engine |
| 42 | Museum demotion; clean contexts | DEFERRED→vm-substrate | `src/commonMain/resources/web/index.html:58-60` still carries `sidebar-gallery` + `{{GALLERY}}`; `docs/index.html` has 9 `sidebar-gallery` hits; the Host view supersedes the front-door plan |

33–40 retired as a block: the associative/recruit engine was specified but never
dispatched (no session IDs, no branches, no files). Retirement is by absence, not rejection;
the spec remains in the source dispatch if a future track picks it up.

Rows: 10. Terminal sessions reconciled: 10. INCOMPLETE: none.
