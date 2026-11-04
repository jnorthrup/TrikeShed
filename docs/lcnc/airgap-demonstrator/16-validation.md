# Validation and benchmark programme

## Evidence required for a demonstrator claim

Acceptance follows an actual UI-to-host-to-engine-to-retained-result path. Unit checks support individual contracts; they do not establish the composed workflow. Every run report names the commit, executable/assets, source fixtures, hardware, deployment profile, exercised route, outputs and limits. Proposed targets below are not measured performance.

## Acceptance matrix

| ID | Experiment | Required observation | Owner |
|---|---|---|---|
| V01 | Import and denature HTML | Original unchanged; safe view inert; omissions reported | WP04 |
| V02 | PDF extraction and source selection | Exact retained text anchors; page/layout uncertainty preserved | WP05 |
| V03 | Unicode and normalization | Original words recover exactly; invalid scalar ranges refused | WP05 |
| V04 | Enhanced dependency copies | Distinct nodes/edges retained; unsupported projection refused honestly | WP05 |
| V05 | Source revision race | Stale result cannot publish as current | WP07 |
| V06 | Forged approval | Caller fields/RDF labels cannot create host authority | WP07 |
| V07 | Scenario isolation | Assumed facts stay in their branch | WP10/WP16 |
| V08 | Missing versus negative evidence | Pending, opposed and conflicted remain distinct | WP16 |
| V09 | Real Horn derivation | Held-out goal derived through exact role joins | WP15 |
| V10 | Wrong role or missing premise | No unsupported consequence; no false-world assertion | WP15 |
| V11 | Duplicate proof/source paths | No additional empirical evidence | WP17 |
| V12 | Proof preselection | Selection before solving; answer/coverage compared with full frame | WP08 |
| V13 | Attention-only update | Evidence bytes and basis unchanged | WP17 |
| V14 | Pause during real work | Request and reached boundary are separate; children accounted | WP09 |
| V15 | Rescaffold and resume | New epoch, exact reuse list, old history preserved | WP09/WP14 |
| V16 | Event reconnect/gap | Deduplication, watermark and visible missing interval | WP03/WP11 |
| V17 | Replay | Same retained decisions; zero engine/effect execution | WP03 |
| V18 | Production shadow | Same guarded result as reference; no effects | WP19 |
| V19 | Withdrawal race | Stale generation cannot dispatch a new effect | WP20 |
| V20 | Crash around effect | Idempotent recovery or explicit uncertain outcome | WP20 |
| V21 | Clean offline install | All mandatory assets local; optional absence visible | WP23 |
| V22 | Egress denial | Browser, host and descendants produce no unauthorized outbound traffic | WP23 |
| V23 | CPU/UI contention | Bounded memory and target interaction responsiveness | WP24 |
| V24 | Domain switch | Same mechanics; no cross-domain fact leakage | WP21 |
| V25 | Long run and recovery | Retention bounds, restart reconstruction and valid dependencies | WP25 |

## Semantic reference corpus

Maintain a small hand-reviewed corpus with source spans, explicit assumptions and expected status transitions. Separate training/development examples from held-out examples and adversarial variants. Duplicate or syndicated source families remain in one split. A random sentence split can leak near-identical clauses across evaluation partitions and overstate performance.

The legal matrix starts with six question families and four premise states. Add nested exceptions, multiple guards, passive voice, modal negation, coordination, definitions, footnotes, cross-references, amendment dates and conflicting instruments. Track omissions as well as incorrect extractions. A parser that refuses difficult input may be safer but have lower coverage; report both.

For fiction, use chronology and character-knowledge constraints with intentional deviations. For news, use source copies, corrections and distinct publication/event times. For history, use superseded decisions and derivative summaries. These fixtures test semantics the system actually claims to support.

## Independent correctness checks

Use a simple exact reference implementation or enumerated expected closure for small symbolic theories. Verify returned proof leaves, rule applications and substitutions where available. Do not let the same generated rule define both implementation and expected answer without an independent check.

Differential comparison between engines is useful only on their overlapping fragment. Agreement between two engines does not prove an English-to-logic mapping is correct. Disagreement requires an attributed counterexample and a decision about semantics before changing either engine.

## Runtime fault injection

Terminate a worker before output, after output write and after journal commit. Disconnect the browser while work is admitted. Reconnect with an old watermark. Fill the diagnostic buffer, then the semantic journal storage. Withdraw a source between proof completion and publication. Withdraw production authority between match and effect dispatch.

For each injection, identify durable state, outstanding ownership and permitted retry. A timeout is not a logical UNKNOWN; a successful enqueue is not retained evidence; an absent acknowledgement is not proof that an effect failed. The experiment passes only when the application preserves those distinctions.

## Benchmark dimensions

| Dimension | Measurement | Initial target or decision criterion |
|---|---|---|
| Navigation | p50/p95/p99 frame time | p95 ≤33.3 ms; 16.7 ms stretch on reference hardware |
| Control | Request acknowledgement latency | ≤250 ms host-available target |
| Semantic display | Commit-to-visible latency | ≤500 ms outside deliberate replay lag |
| Retrieval | Required-premise recall and selected work | No loss on declared complete fixture; incomplete cases labeled |
| Proof | Answer/proof correctness and cost | Exact agreement with bounded reference |
| Parsing | Span fidelity, semantic candidate error, coverage | All accepted quote spans exact; errors categorized |
| Memory | Peak RSS/heap/native/browser | Fits named profile without unbounded growth |
| Journal | Bytes/event/day and recovery time | Retention policy and predictable recovery |
| Production | Reference disagreement and saved work | No unsafe fixture action; positive net savings |
| Models | Quality, abstention, latency, peak memory | Improvement over deterministic baseline justifies cost |

Measure control acknowledgement separately from time to reach a quiescent checkpoint. The latter depends on engine granularity and must be reported, not hidden behind the 250 ms target.

## Sustained-run protocol

Begin with a two-hour run on fixed fixtures, then 24 hours, then a 72-hour release candidate soak if the shorter runs remain bounded. Include periodic source changes, review withdrawals, browser reconnects, archive misses and production revalidation. Keep input rate and data growth explicit. A stable heap on a fixed reused corpus does not prove bounded storage with continuously new sources.

Report queue age, throughput, active jobs, unreaped processes, open descriptors, retained artifacts, index size, journal segments, UI lag and effect reconciliation backlog. No silent automatic restart may erase failure evidence. Recovery should produce a retained incident record and resume only eligible work.

## Quality metrics and calibration

For retrieval report recall@k and source-family diversity. For structured extraction report per-field precision/recall and whole-condition correctness. For hypotheses report outcome calibration, abstention/coverage and performance by scenario family. For legal simulation report supported/unsupported claims and scope mistakes rather than a single misleading “legal accuracy” percentage.

A confidence metric is evaluated against the quantity it claims to represent. NAL confidence, model probability, similarity score and source-review status are not interchangeable. A calibration plot is useful only with declared independent outcomes and a sufficiently sized held-out set. Small fixture results should be presented as fixture evidence.

## Release gates

D1 requires V01 or V02, V03, V14 and a real retained source-to-result trace. D2 requires all applicable semantic/control/production/offline cases, with optional ML still allowed to remain unavailable. D3 adds clean installation, sustained-run/recovery evidence and a documented capacity envelope. Research extensions cannot weaken established invariants merely to increase apparent coverage.

The release report lists passes, failures, exclusions and unmeasured claims. A feature demonstrated only by synthetic replay is labeled accordingly. Keep source-private evidence private while retaining public hashes, methods and content-free outcome summaries where useful.
