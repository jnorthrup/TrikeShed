# Repository baseline and extension seams

## Baseline and evidence status

The inspected checkout is TrikeShed commit `d4efbd25855bea0b7a97c2954c696a3adec69e31`, initially clean. Source inspection and prior checked-in proof reports support the inventory below. The planning task did not rerun their builds or reproduce their experiments. The distinction matters because previous evidence establishes a particular build and fixture, while the coming implementation must verify its own integrated version.

Repository instructions are in [AGENTS.md](../../../AGENTS.md) and [PRELOAD.md](../../../PRELOAD.md). Implementation follows Join, Series, Cursor and Confix taxonomy; asynchronous work belongs to existing typed context elements and owned channels under SupervisorJobs. The commonMain userspace NIO/uring facade owns I/O, with real or emulated execution reported honestly. Do not introduce a replacement CCEK runtime or conceal compilation debt through exclusions. PRELOAD requests 25 concrete implementation tasks; [the work packages](14-work-packages.md) supply that decomposition. No implementation fanout is launched by this document task.

## Upstream reconciliation before publication

The subsequent fetch found upstream master `7e7998042`, containing PR 1086's copied-node follow-up. Its updated offline-English report records full token-index/copy-count identity through the bridge, storage and SVG; conservative refusal by index-only semantic consumers; 63 selected checks and three browser bundles; and a fixed-corpus comparison of CoreNLP 4.5.10 and 4.6.0. This report was read but not rerun here. The earlier copied-node representation gap is therefore historical at the publication baseline. WP05 must preserve and verify that work, then address worker isolation/deadlines and any explicitly chosen copy-aware semantic interpretation. It must not reimplement or remove the completed representation fix.

## Available foundations

The later [Polyglot revision](22-mlir-nix-polyglot.md) inspects the embedding surfaces at TrikeShed `6875a72d6`, after the corpus/inference work in PR 1087, and upstream `mlir-nix` at `571b57d3`. Its scoped inventory supersedes assumptions that LLVM dependencies or a general Nix-to-LLVM export need only boilerplate. The table below remains the original programme inventory; this follow-up is not a new verification of every subsystem or a rerun of PR 1087's evidence.

Paths below are repository-relative so the document survives checkout relocation.

| Area | Inspected seam | Existing substance | Required extension |
|---|---|---|---|
| LCNC vocabulary | `src/commonMain/kotlin/borg/trikeshed/lcnc/LcncContracts.kt` | Host-owned ports, kinds, cardinality, parameters, sources, sinks and effects | Versioned semantic qualifiers, resource and observation capabilities |
| Nested composition | `LcncContracts.kt`, `LcncPulse.kt`, `LcncProgramConfix.kt` | Scope inputs/outputs, each/carry controls, retained pulses and program serialization | Explicit fan-in policy, recursive frontier and epoch semantics |
| Execution service | `src/jvmMain/kotlin/borg/trikeshed/kanban/module/LcncRunService.kt` | Owned runs, queued receipts, pinned versions, case framing, debug service | Durable event stream and graph-version cutover |
| Invocation observations | `src/commonMain/kotlin/borg/trikeshed/lcnc/LcncInvocationLedger.kt` | Bounded node entries, elapsed time, copied values and truncation | Persistent semantic records and transfer causality beyond the recent window |
| Debugging | `src/commonMain/kotlin/borg/trikeshed/lcnc/LcncDebug.kt` | Node checkpoints, step/continue, pause requests, bounded interventions, source stepping | Multi-branch quiescence and explicit restart/fork receipts |
| LCNC scene | `src/commonMain/kotlin/narchy/spacegraph/LcncSpaceGraph.kt` | Graph projection preserves original program; ports and epistemic bindings are explicit | Event-driven overlays, stable group hierarchy and progressive detail |
| Node flyover | `src/jvmMain/kotlin/borg/trikeshed/forge/server/LcncBlipWire.kt` | Asserted, inferred and Graal sections read actual sources | Consistent epoch and event watermark, stale label, queue/timer summaries |
| Graal landscape | `src/commonMain/resources/web/graal.html`, `src/jsMain/kotlin/borg/trikeshed/web/graal/` | Terrain, zoom/pan, inspectors, allocation/vitals views | Workflow hierarchy over retained execution; shared identity navigation |
| Local English | `docs/offline-english.txt`, `CoreNlpRuntime.kt`, document routes | Exact source-spanned syntax observations; upstream follow-up retains copied-node identity | Preserve follow-up, add worker deadline/isolation, broader annotated corpus |
| Review | `src/commonMain/kotlin/borg/trikeshed/lcnc/CurationReview.kt` | Host-owned authenticated review and reusable read versus consuming admission distinctions | Scope-specific production authorization and invalidation event integration |
| Reviewed inference | `ReviewedTaxonomyPlan.kt`, `ReviewedHornPlan.kt` | Bounded taxonomy reports and reified review-record observations | Instrumented execution, checked proof read models and goal selection |
| Native bridge | `docs/sumo-nix-bridge.txt`, `utils/sumo-nix/` | Pinned positive scalar Horn export and native interchange evidence | Production process supervisor and resource accounting |
| RDF | `src/commonMain/kotlin/borg/trikeshed/rdf/QuadStore.kt` | Dictionary-coded G/S/P/O columns and deduplication | Immutable graph epochs, index identity, provenance joins and retention policy |
| Wiki | `src/jvmMain/kotlin/borg/trikeshed/wiki/CurationWiki.kt` | Retained curation projections, sources, sentences, concepts and links | Run/proof/production navigation and stable anchored cross-links |
| Breadcrumbs | `BreadcrumbReelService.kt`, Hermes breadcrumb services | Retained comparison and recall mechanisms | General source/episode distinction, scoped retrieval and feedback audit |
| Beliefs | `src/commonMain/kotlin/borg/trikeshed/narsese/BeliefBagElement.kt` | Bounded active bag, CAS spill, receipt/basis tracking and attention | Exact owner-side receipt-bound attention and explicit validity policy |
| Evidence dependence | `ProductionDoctrine.kt` | Exact basis and conservative overlap revision | Bridge every new observation through this policy; prohibit legacy bypass |
| Rete | `src/commonMain/kotlin/borg/trikeshed/dag/ReteProduction.kt` | Productions declare interests and emit activations; registry has disposers | Restricted compiler, shadow deployment, revocation and generation fencing |
| Class acceleration | `ClassRete.kt`, `collections/bits/RoaringSeries.kt` | Class-ID matching and a commonMain Roaring-shaped set | Benchmark container operations, ID epochs and source proof sidecars |

## Constraints that materially affect the plan

The invocation ledger defaults to **128 recent entries** and reports dropped/truncated data. Its snapshots also obey a bounded value budget. It is a useful inspector, not a complete replay journal. Do not connect a particle renderer and then advertise an audit history that the host never retained.

The existing debug controller operates at checkpoints and can hold execution through a bounded command channel. That is a useful basis for stepping. It does not imply arbitrary native parser interruption, a consistent snapshot across concurrent workers, or reversible external effects. Existing debug state must remain a read/control projection; PRELOAD forbids adding an independent lifecycle state machine around Jobs and Channels.

The live belief bag defaults to **4,096 active cells**, with **256 intake slots**. Active capacity is not total retained corpus size. A successful enqueue does not guarantee residency. Exact receipt mappings are owner-held; a future attention adapter must resolve exact formula and receipt identity within that owner, not assume an angular hash is collision-proof.

`ClassRete` discounts empirical evidence and can apply a support floor. This is not a documentary entailment certificate. Class IDs are classifier-local. A taxonomy bitmap hit must carry an ontology/dictionary epoch and recover exact terms before it can be used in another subsystem.

`RoaringSeries` is not simply the upstream RoaringBitmap library behind a wrapper. It is a custom commonMain representation; inspected union code materializes integer arrays. Published SIMD or intersection benchmarks for upstream libraries cannot be transferred to it. Profile and improve the actual hot operation after correctness is established.

`QuadStore` has an explicit quads view and a graph-blind triples union. The latter is hazardous for hypothetical legal premises, fictional branches and borrowed histories. Source context must be selected explicitly at the query boundary, even when labels happen to match.

## Prior proof reports to preserve

[Offline English](../../offline-english.txt) records real CoreNLP 4.5.10 execution and a process-only network-denial experiment. It also identifies an Enhanced++ copied-node representation refusal, synchronous parsing without a wall deadline, and a sampled private document rather than general legal accuracy. Those are actionable constraints for packages WP05 and WP23.

[SUMO-Nix bridge](../../sumo-nix-bridge.txt) records actual Nix and native MLIR interchange validation, ordered facts/rules, exact source sidecars, positive scalar Horn admission and first-proof-only output. Native timeout, memory and output control remain a production concern. A lost first proof is not evidence that every alternative support disappeared.

[Structured activation](../../nars-structured-activation-plan.txt) and [the next proof experiment](../../native-proof-nars-next-experiment.txt) distinguish goal selection from evidence minting. Follow their read-only selection-first trajectory. Do not seed the whole corpus into the bag or treat a proof hit as another independent observation.

[Reviewed consumer scope](../../reviewed-kif-consumer-scope.txt) contains a later update adding a distinct exact-KIF reviewed path. Earlier paragraphs describing its absence are historical. Current source and the latest update govern the implementation; copying the earlier stop point would incorrectly erase completed work.

[PDF link curation](../../pdf-link-curation.txt) records real URI annotation discovery and bounded selected collection. It does not parse printed citations or establish that a document with no URI annotations has no legal references. Connected collection and offline inspection remain separate operations.

[Jeva layout](../../jeva-layout.txt) documents accepted checkpoint layouts, refused quantization layouts and the lack of real 4B/Q4 inference validation in that workspace. The blueprint gives Jeva a measured optional role; it does not count loader tests as model quality or CPU throughput evidence.

## Integration strategy

Add observations at existing admission, node-start, node-finish, transfer, review, proof and effect boundaries. Introduce a small immutable envelope shared across projections. Keep engine-specific details in bounded extension records. A central service may coordinate retention and indexing, but it must not become a new execution runtime.

Use one writer for the shared contract files and one integration owner for graph and event compatibility. Independent work can own parser adapters, renderer layers, synthetic fixtures and engine experiments. Rebase handoffs on a frozen integrated commit, because the repository is changing rapidly and historical line numbers are weak anchors.

Every implementation report should identify commit, build inputs, retained fixtures, exercised production route and limits. A passing helper test is useful evidence about that helper; the demonstrator requires the actual UI-to-host-to-engine-to-retained-result path.
