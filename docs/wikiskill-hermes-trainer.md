# WikiSkill Hermes trainer

TrikeShed carries a fixed `1A` training, benchmarking, and performance-watermark corpus in `src/commonMain/resources/hermes/wiki-trainer/1A`. It is a test/analysis fixture, not a production knowledge seed. It exists to force one auditable lane:

`immutable Hermes transcript → NLPCore facts ↔ BrainClient/ModelMux translation ↔ NARS proposal → deterministic causal admission → NARS evidence → persistent wiki pattern → atomic skill candidate → disjoint validation`

The architecture authority is Tang et al., *WikiSkill: Compiling Agent Experience into Persistent Knowledge for Skill Evolution*, [arXiv:2608.27454v1](https://arxiv.org/abs/2608.27454). Paper benchmark numbers are not copied into local results. The paper supplies the loop design; `1A` supplies local bytes and expected decisions.

## What is bundled

| Layer | Asset | Function |
|---|---|---|
| Raw | `raw/*.md` | Two training and two held-out synthetic Hermes transcripts, each SHA-256 addressed |
| CoreNLP | `nlp/dependencies.jsonl` | Exact source spans, token indices, predicates, and supporting dependencies |
| Translator | `translation/round-trips.jsonl` | BrainClient/ModelMux NLPCore→NARS proposal and NARS→NLPCore evidence-projection contracts |
| NARS | `nars/causal-decisions.jsonl` | Proposed edges, admit/refuse answers, and budgets only for admitted evidence |
| Analysis | `analysis/performance-watermark.json` | Reproducible semantic baseline plus Oroboros incident actuals and unmeasured green-field targets |
| Deliverable | `deliverable/oroboros-actual-to-greenfield.json` | First sample Oroboros artifact: TrikeShed actuals transformed into an idealized, measurable mockup |
| Wiki | `wiki/` | Persistent pattern, index, evolution log, and skill-impact statement |
| Candidate | `candidate/grounded-causal-link/` | One atomic `SKILL.md` plus its pattern-mapped `PURPOSE.md` |
| Gate | `validation/expected-results.json` | Incumbent score, held-out miss, target score, and promotion rule |

Every path is in the generated `CommonResources` allowlist. The same bytes are therefore available to JVM, browser, Worker, and native targets without relying on a source-tree filesystem.

NLPCore is the structured-language side of the boundary; the supervised `vm.corenlp.extract` lego supplies Stanford tokens and dependencies. BrainClient is the translation client, and ModelMux routes its model seat. Their output is a proposal or evidence projection—not evidence authority. CID, source-span, and dependency-direction checks remain deterministic.

## First Oroboros deliverable

The corpus doubles as the first sample deliverable trained from TrikeShed actuals toward an idealized grass-roots green-field. The actuals are preserved, not cleaned out of the story: twelve retained `.claude/worktrees` account for 15,232 directories and 92 percent of the recorded checkout walk; daemon logs and Kotlin compiler-session files previously triggered full-tree reconciliation. The operator separately reports a 48-hour accumulation of abandoned task trees. That duration remains labeled as operator-reported context until a receipt measures it.

The mockup target inventories age, owner, head, lease, and last useful delta; removes stale trees from the active watch set without deleting them; coalesces bursts; and emits content-addressed reconciliation receipts. Its watermark demands zero self-generated reconciles, full source-edit visibility, one reconcile per coalesced burst, and zero destructive cleanup.

## The 1A discriminator

The incumbent `ConstructionPatternGate` checks that subject text, object text, a causal phrase, and an allowed dependency label are present. It does not check an actual indexed CoreNLP edge. The held-out sentence says:

> The watcher reconciliation caused the daemon log write.

The held-out proposal reverses it: daemon log write causes watcher reconciliation. The incumbent admits that proposal because all words still appear. `1A` therefore records the incumbent as 3/4 and requires a grounded candidate to reach 4/4 by preserving the CoreNLP subject-to-object direction.

This is a deterministic fixture result, not a paper result. The candidate remains unpromoted until an outer validator evaluates it and observes a score strictly greater than the active skill.

## WikiSkill correspondence

- Raw transcript files are immutable; their manifest CIDs fail integrity tests when bytes change.
- The training sample includes both success and failure and stays under the paper's eight-trace and 15,000-character limits.
- Validation traces are disjoint from training traces.
- The wiki layer is independent of candidate acceptance and is never rolled back.
- The candidate is one skill change and its `PURPOSE.md` names the wiki pattern it implements.
- Promotion requires candidate score greater than incumbent score.

## Honest implementation boundary

The repository has working `wiki.consolidate` and `wiki.propose` nodes. `ConstructionBotNode` already routes its forward language-to-NARS proposal through BrainClient/ModelMux. The direct `vm.corenlp.extract` record wire and the reverse NARS-to-NLPCore projection are represented by `1A` but are not yet joined as one runtime node family. The outer candidate-validation, promotion, and rollback transaction described by the paper is also still absent. The `1A` bundle establishes the acceptance surface for that work; it does not claim those missing joins have landed.
