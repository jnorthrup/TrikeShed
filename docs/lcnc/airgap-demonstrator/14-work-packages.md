# Twenty five implementation work packages

## Execution discipline

These are ownership units for the coming Sol/Terra implementation campaign, following PRELOAD's 25-task requirement. They are not 25 new modules and not a request to launch 25 concurrent agents. Keep a six-lane working limit initially: one integration/contracts owner, two runtime/data owners, two UI/adapter owners and one independent verification/research owner. Reassign lanes as dependencies clear.

The suggested model split uses Sol for cross-cutting semantics and integration, Terra for bounded implementation and measurement, with either model reviewing the other's work. Use the requested ultra reasoning setting where the selected runtime supports it; record the actual model and effort. Model labels are planning assignments, not claims of a universal skill hierarchy. Avoid paying for deep reasoning on mechanical formatting once a contract is settled.

Hours below are estimated active agent-hours for implementation and local verification. They exclude calendar waiting, long soak periods and the programme-level 50% integration/rework reserve. They are hypotheses to replace with measured throughput after the first 48 hours.

## Dependency and ownership table

| Package | Ownership | Depends on | Hours low–high | Delivery |
|---|---|---|---|---|
| WP01 | Sol contracts/integration | None | 16–28 | Frozen baseline and contract inventory |
| WP02 | Sol semantic schema | WP01 | 28–48 | Artifact/event/frame contracts |
| WP03 | Terra retention | WP02 | 40–72 | Durable semantic journal and replay |
| WP04 | Terra source capture | WP02 | 32–56 | HTML capture and inert view |
| WP05 | Sol parser boundary | WP02 | 48–96 | Anchors, copied nodes, isolated parsing |
| WP06 | Terra source collections | WP02 | 32–56 | Questions/evidence/transcript imports |
| WP07 | Sol evidence/review | WP02, WP04, WP05 | 32–64 | Scoped evidence and review adapters |
| WP08 | Terra retrieval/indexes | WP07 | 36–64 | Exact baseline and candidate worksets |
| WP09 | Sol execution control | WP02, WP03 | 48–96 | Quiescence, pause, fork and restart |
| WP10 | Terra cardinality | WP02, WP09 | 32–64 | Bounded fanout/fan-in and recursion |
| WP11 | Terra observation projection | WP03 | 32–56 | Aggregation, snapshots and watermarks |
| WP12 | Sol/Terra scene integration | WP11 | 56–104 | Event-driven LCNC/Graal graph |
| WP13 | Terra renderer/interaction | WP12 | 64–120 | Sparks, LOD, minimap, flyovers |
| WP14 | Sol LCNC wizards | WP07, WP09, WP10 | 40–72 | Typed source/panel/production authoring |
| WP15 | Terra native solver adapter; Sol review | WP03, WP07 | 32–64 | Pinned Horn execution and gated JVM Sulong slice |
| WP16 | Sol legal panel | WP08, WP10, WP15 | 40–80 | Issue/argument simulation and fixtures |
| WP17 | Sol NARS boundary | WP07, WP08, WP15 | 48–96 | Exact attention/evidence integration |
| WP18 | Terra learning evaluation | WP17 | 48–88 | Held-out empirical policy experiment |
| WP19 | Sol production compiler | WP09, WP15, WP17 | 64–128 | Restricted Rete candidate/shadow path |
| WP20 | Sol revocation/effects | WP19 | 48–96 | Generation fencing and deoptimization |
| WP21 | Terra alternate domains | WP06, WP10, WP14 | 40–80 | Fiction/news/history/IPFS packs |
| WP22 | Terra optional local ML | WP05, WP08 | 32–64 | BERT/Jeva capability experiments |
| WP23 | Sol offline packaging | WP04, WP05, WP15 | 56–112 | Sealed bundle and isolation proof |
| WP24 | Terra performance | WP08, WP13, WP15, WP20 | 48–96 | CPU/renderer/retention benchmarks |
| WP25 | Sol integrated acceptance | All release-critical packages | 64–128 | Demonstration, recovery and release packet |

## WP01 Baseline and ownership

Freeze the implementation starting commit, build identity and existing evidence links. Read current AGENTS/PRELOAD and identify concurrent changes before editing. Inventory executable node contracts, actual routes, parser/solver readiness and current debug capabilities. Deliver a concise seam map and ownership allocation, including a single writer for shared LCNC contracts and serialization. Acceptance is a reproducible baseline build or a precise pre-existing failure report with no exclusions. Hand off exact source paths and current interfaces; historical design documents are supporting context rather than the sole source of truth.

## WP02 Shared boundary contracts

Implement versioned artifact, frame, event, command and result envelopes using existing taxonomical primitives. Define canonical encoding, schema rejection, scope/evidence qualifiers and byte limits. Deliver round-trip fixtures for supported versions and explicit failure cases for stale epochs, missing required fields and invalid identity. Acceptance includes independent decoder agreement and no authority derived from caller-supplied review labels. Keep this package small enough that all downstream owners can read it. It owns schema migration policy and compatibility fixtures, not a new runtime framework.

## WP03 Journal retention and replay

Extend existing durable storage seams to retain semantic boundaries and payload references. Implement append/snapshot/tail recovery with idempotent event identities and gap reporting. Keep bounded diagnostic aggregation separate. Acceptance includes crash before/after payload and event commit, lost acknowledgement, disk-full refusal and snapshot-plus-tail equivalence. Replay must reconstruct the same result and control history without invoking engines or effects. Deliver a trace fixture larger than the existing 128-entry invocation window so the new capability is demonstrated rather than assumed.

## WP04 HTML capture and denaturing

Build the capture manifest and inert-view adapter for a pinned agreement. Preserve headings, tables, definitions and references, while disabling active content and external resource loads in the display. Deliver transformation/coverage reports and exact source-to-view anchors. Acceptance includes script/event-handler URLs, missing resources, dynamic content omission and source replacement. The real agreement fixture remains separate from an original adversarial fixture. Do not import private or licensed corpus bytes into public Git merely to make the demo convenient.

## WP05 Source alignment and parser worker

Preserve and verify the upstream full dependency-node identity follow-up, including copied/enhanced nodes and conservative index-only semantic gates. Do not delete edges or assume historical records reported zero copies. Preserve exact source spellings and Unicode-safe ranges. Add a host-owned process boundary or equivalently demonstrated termination/resource controls around local parsing. Acceptance includes the known negative-guard regression, stale extraction race, copied-node fixture, wall timeout, memory exhaustion and descendant cleanup. Deliver measured readiness, cold/warm resource use and explicit supported profiles. A successful parse does not approve its legal interpretation.

## WP06 Composite source imports

Implement versioned question graphs, evidence collections and transcript segment manifests. Validate branch references, time units, source attribution and collection completeness. Preserve authored assumptions separately from evidence assertions. Acceptance includes broken flowchart links, duplicated exhibits, overlapping transcript segments, absent media and corrected transcripts. Deliver the source wizard's inventory data and F03/F06/F07 fixtures. This package owns formats and adapters; downstream panel behaviour remains in WP16/WP21.

## WP07 Evidence and review integration

Bind source statements, assumptions and reviewed mappings to the existing authenticated review owner. Preserve reusable read versus consuming admission semantics. Add currentness and invalidation events for downstream dependencies. Acceptance proves that a fabricated RDF approval marker, stale quote, changed ontology and withdrawn review cannot become current authority. Deliver navigable evidence records and explicit exclusions for unsupported mappings. Avoid broadening legal modalities into taxonomy facts merely to feed the solver.

## WP08 Retrieval and structured preselection

Build lexical/structural/context selection over retained sources and a small predicate/argument index for the Horn fixture. Include definitions and exceptions through document structure. Compare full theory, structured selection and flat recall with selection occurring before solving. Acceptance includes role swap, near-synonym distractors, irrelevant facts and incomplete-selection reporting. Deliver measured selected work and exact provenance. Optimize actual RoaringSeries operations only if profiling identifies them as material; preserve dictionary/ontology epochs on every numeric-ID boundary.

## WP09 Pause quiescence and forks

Extend existing checkpoint control into owned multi-branch admission holds. Implement command identity, expected epoch/control sequence, outstanding-work reporting, checkpoint manifest and new-definition fork. Acceptance includes double-click commands, stale browser controls, noncooperative workers, browser loss and an effect already committed at pause time. Continue must preserve the immutable definition; semantic edits must create a new epoch. Deliver a visible pause while real work is in flight and a continuation with auditable reused/re-executed artifacts.

## WP10 Cardinality and bounded recursion

Implement explicit map/zip/broadcast/join/reduce policies through existing scope composition. Add global branch budgets, recursive frontier retention, cycle detection and deterministic fan-in accounting. Acceptance includes unequal zip lengths, zero results, timed-out seats, large fanout and repeated subproblems. Show that child scopes consume parent budget rather than receiving unlimited fresh budgets. Deliver a wizard work estimate and a result that preserves deferred branches when the budget ends.

## WP11 Human-view projection

Create a bounded projection of the journal with per-scope counters, interval aggregation, critical-event delivery and watermark snapshots. Define how aggregate objects resolve to underlying semantic IDs. Acceptance includes duplicated/out-of-order delivery, subscriber gaps, coalescing bursts and reconstruction after reconnect. The projection must not alter execution rate unless a separate admission control requests it. Deliver measured projection cost and loss counters. Use existing observability conventions where useful, but retain domain outcomes beyond generic success/error.

## WP12 Shared scene and identity navigation

Connect the existing LCNC graph projection and Graal landscape to the run projection. Preserve authored coordinates and scope hierarchy. Build stable selection, parent paths and cross-links between node, invocation, source and proof. Acceptance includes late events from a prior epoch, nested invocation identity, pinned nodes and changing branch width without global layout churn. Deliver a live source-to-result path before adding high-density visual effects. Empty data remains an honest empty inspector section.

## WP13 Sparks zoom and flyovers

Implement bounded particle/band rendering, timer visuals, minimap, level-of-detail transitions, picking and accessible inspector/table mode. Add reduced motion and persistent live/replay/synthetic labels. Acceptance uses the 10k/50k retained graph fixtures with bounded visible detail and concurrent CPU work. Measure frame-time distributions and control responsiveness. Every spark aggregate must resolve to observed events; no activity animation may imply execution that was not recorded. Deliver a short usability test on finding blockers and source support.

## WP14 LCNC authoring wizards

Build source, panel, production and rescaffolding flows over host contracts. Show cardinality expansion, missing engine assets, semantic mismatches and affected-result previews. Acceptance includes an unknown node type, unsupported effect, hypothetical-to-global connection, optional model absence and scope reduction after pause. Preserve the user's existing graph when a suggested change is declined. Deliver three presets using the same authoring grammar. The wizard may propose a connection; host validation and existing authorization remain decisive.

## WP15 Native positive Horn execution and Sulong review

Retain the pinned SUMO-Nix/native reference path and evaluate the requested JVM Polyglot/Sulong direction through [the bitcode contract](22-sulong-bitcode.md). Allocate the existing 32–64 hours as 8–16 for compiler/ABI feasibility, 8–16 for reproducible bitcode production, and 16–32 for the bounded adapter and production verification. Sol reviews the exact supported computation, lowering, memory ownership and missing runtime facilities after feasibility. A general Nix-to-LLVM compiler or substantial runtime port requires an explicit scope/estimate revision; it is not assumed to fit this allocation.

Bind input/frame CIDs, preserve source sidecars, check certificates and recheck review currentness across either execution mechanism. Implement real bitcode production, explicit UTF-8 input/result ownership and release, compatible host profiles, commonMain channel admission/fan-in and drain. Preserve the ordinary restricted guest policy; native-capable in-process execution is an explicit trusted capability. Deadline, memory and network claims must follow the actual profile: arbitrary native calls inside the daemon do not acquire hard termination or OS isolation from Polyglot flags.

Acceptance includes malformed output, timeout/budget outcomes, unsupported formulas, Unicode and allocation failures, duplicate sources, first-proof reordering, source withdrawal and repeated context cleanup. Deliver actual execution through a production route with real engine events, then cross-engine measurements and sealed-bundle evidence through WP24/WP23. A scalar or scanner pilot establishes transport only. Keep taxonomy, reified review-record observations and world assertions as distinct scopes; promote the Sulong lane only at the intended proof-producing acceptance level.

Own the subsequent [JVM Polyglot embedding direction](22-mlir-nix-polyglot.md), coordinating ABI with WP02, lifecycle with WP09, observations with WP11, offline deployment with WP23 and performance with WP24. First prove an actual bounded kernel using the selected runtime/toolchain. The table's 32–64 hours remain the original supervised-solver estimate; the pivot adds a separately accounted 112–224 hours for the bounded embedding path. Full evaluator compilation or complete Nix lowering remains additional scope requiring the experiment's dependency/coverage findings. Keep 25 ownership packages; these are increments within existing ownership, not new modules or an automatic agent launch.

## WP16 Legal issue and panel simulation

Implement the bounded role procedures and argument records in the legal chapter. Build the 24-case premise matrix, cross-reference variants and a retained Suski authority-mapping fixture. Acceptance emphasizes missing/negative/conflicted support, agreement-version mismatch, unsupported citations and local scenario scope. Deterministic procedures must work without a generative model; optional prose drafting remains a candidate layer. Deliver an issue map with exact sources, unresolved frontier and a clear separation between procedural question, substantive argument and empirical prediction.

## WP17 Exact NARS boundary

Start with read-only structured selection, then implement owner-side receipt/formula-checked attention for existing cells. If adding observation intake, require stable receipt IDs, exact basis and declared evidence units. Acceptance proves byte-identical evidence after attention, duplicate replay idempotence, collision/missing-residency refusal, spill/revival accounting and no use of proof count as evidence. Deliver before/after snapshots and a clear distinction between accepted, processed, retained and active. Do not route the new bridge through basis-free reinforcement.

## WP18 Empirical learning experiment

Create independent episode families with held-out outcomes and explicit counterexamples. Compare a simple deterministic policy, NAL-derived hypothesis and optional model selection using the same context/features. Measure calibration, abstention, coverage and cost, with duplicate-source families kept in one split. Acceptance includes a hypothesis that is rejected or deoptimized when counterevidence arrives. Deliver a bounded research report and candidate data; success does not automatically deploy a rule. If no useful policy emerges, retain that result and keep deterministic reuse available.

## WP19 Restricted production compiler

Compile only the documented guard/action subset into existing Rete production seams. Retain candidate kind, exact dependencies, reference evaluator and generation identity. Implement shadow execution without effects and comparison records. Acceptance includes unsupported negation, wrong argument joins, repeated same-tuple matches, semantic mismatch and negative economics. Deliver the first local cache/routing production with measured savings. Arbitrary generated source and unrestricted actions remain outside the compiler grammar.

## WP20 Revocation and effect reconciliation

Implement reverse-dependency invalidation, generation fencing at effect dispatch and reconciliation of outstanding activations. Source/review/policy changes suspend affected generations immediately. Acceptance races withdrawal against matching and dispatch, restarts after dispatch without acknowledgement, and verifies that replay never repeats the effect. Deliver explicit uncertain-outcome handling for any sink lacking an idempotent receipt protocol. Deoptimization creates a retained path back to analysis and does not erase historical evidence.

## WP21 Alternate domain packs

Implement the original fiction, synthetic news, attributed history and local IPFS fixtures using shared contracts. Add domain-specific chronology/knowledge constraints, source-family deduplication, correction links and missing-block reporting. Acceptance proves context separation across domains and offline resolution without public gateway fallback. Deliver one complete alternate-domain demonstration and import contracts for the others. Avoid a separate custom UI or executor per domain; differences belong in data, procedures and templates.

## WP22 Optional BERT and Jeva experiments

Verify actual local assets and supported runtime layouts, then measure inference readiness, memory, latency and task quality. Compare BM25/structure against BERT reranking and Jeva candidate selection/abstention. Acceptance includes option permutation, out-of-domain input, long passages, missing files and incompatible quantization layout. Deliver a capability report and optional adapter only when it passes; keep unavailable models visibly unavailable. Loader tests alone do not establish successful inference or quality.

## WP23 Offline bundle and deployment

Package exact binaries, schemas, local browser assets, parser resources, ontology pins and selected fixtures. Test installation in a clean no-network environment without inherited caches. Establish process/resource/network boundaries and content-free egress evidence. Acceptance includes missing mandatory/optional assets, malformed archives, browser external resource attempts and descendant process cleanup. Deliver bundle manifest, licenses and operational instructions. Physical-airgap claims require an actual disconnected deployment record, beyond the application's model-egress setting.

## WP24 Performance and capacity

Measure end-to-end latency, CPU, memory, allocations, journal growth, index operations and renderer frame time under named hardware profiles. Compare baseline and optimized paths with identical semantic coverage. Acceptance includes burst load, slow disk, parser contention, active bag pressure, 50k-node retained graph and long-running retention. Deliver a capacity envelope and revised defaults, including event aggregation and heap/worker settings. Reject speedups caused by dropping difficult cases or losing source completeness.

## WP25 Integrated demonstration and release evidence

Run the complete storyboard from the actual UI and production routes on a frozen integrated build. Perform recovery, stale-source, withdrawal and offline-install scenarios. Package the journal, manifests, recordings, benchmark report and known limitations. Acceptance is reviewable by someone who has not seen the development chats. Record which delivery level is achieved and which capabilities remain experimental. This owner reconciles all package evidence and prevents a collection of individually passing helpers from being represented as a finished demonstrator.

## Handoff packet for every package

Each handoff includes the requirement and invariant IDs, starting/ending commit, files owned, interfaces changed, fixture identities, actual commands/routes exercised, result artifacts, performance impact and unresolved limitations. Keep one concrete next action for a successor. A reviewer checks semantics and failure paths before style. Rework returns to the owning package; the integrator does not silently patch around a broken contract in a downstream consumer.

## Concurrent work rules

Use isolated branches/worktrees when implementation begins. Shared schemas and vocabulary changes are serialized through WP02's owner; renderer and adapter changes can proceed against versioned fixtures. Merge only a coherent slice with its acceptance evidence. Do not hold integration until all 25 packages finish. Maintain a working vertical slice throughout, and re-plan after the first 48 hours using observed completion rate and failure categories.
