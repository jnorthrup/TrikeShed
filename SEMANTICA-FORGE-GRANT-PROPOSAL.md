# Proposal: Forge Evidence Workspace for Accountable AI

**Applicant:** TrikeShed / Forge maintainers  
**Funding request:** **US$50,000**, one-time, twelve-month open-source development grant  
**Use of funds:** model-token spend and maintainer living expenses only.  
**License and delivery model:** all grant-funded code, specifications, fixtures, benchmarks, and user documentation are released in the existing TrikeShed repository under its project license; no proprietary control plane is required to use the core workflow.

## 1. Executive summary

Semantica demonstrates a valuable open-source semantic surface: ingestion and semantic extraction, provenance, decision intelligence, policy-aware reasoning, and graph/RDF interoperability. Its grant application asks for US$50,000 over twelve months to stabilize and test that broad semantic layer, publish GraphRAG benchmarks, improve integrations, publish regulated-domain guides, and grow contributors.[1] Forge proposes the complementary product cut: an **evidence-first, local-first workspace** in which a user can ingest a source, see its evidence and policy outcome, inspect the decision and causal chain it created, and act on the resulting Kanban work without leaving the product.

The economic claim is deliberately plain: grant funding purchases time to live and the model tokens that turn that time into public software. It does not pretend to purchase a staffed organization, a sales operation, or a conventional software-services payroll.

The deliverable is not another headless graph, vector, or agent-memory service. It is a commercializable open workspace for accountable AI operations:

```text
source / agent event
  → content-addressed source + line spine
  → extraction, validation, conflict, and policy pointcut
  → append-linked evidence receipt
  → Kanban card + causal/rule projection
  → one Forge page / board / graph / gallery workspace
  → human decision or agent execution
  → superseding receipt and visible state transition
```

This makes the accountability promise operational. A reviewer can answer four questions from a single Forge card: **what happened, what evidence supports it, which policy or rule applied, and what decision or execution followed?** It also gives Semantica’s strongest semantic concepts a durable runtime substrate rather than copying its Python object topology.

## 2. The funding problem

AI agents increasingly act on unstructured records, yet normal RAG interfaces reduce evidence to retrieved chunks and normal work-management tools reduce decisions to unlinked tickets. The resulting gap is not merely retrieval quality: a team cannot reliably inspect the source, transformation, policy, causal antecedent, actor, and later supersession behind a consequential action.

Semantica explicitly targets governed, explainable, auditable knowledge for high-stakes domains; its published scope includes semantic extraction, graph construction, decision tracking, provenance, and reasoning.[2] Its provenance model records source attribution, transformations, responsibility, SHA-256 integrity information, and version chaining.[3] Its decision model captures scenario, reasoning, outcome, confidence, causal links, and policy checks.[4] These are the right semantics, but they must become a cohesive user-facing operating surface.

Forge supplies that missing product surface. It treats the workspace—not an external graph database, vector database, or MCP endpoint—as the center of gravity. External formats and services remain adapters and export targets. The product therefore serves regulated and review-heavy teams that need both AI assistance and a human-inspectable chain of custody.

## 3. What will be built

### A. Evidence-backed Forge ingest (Months 1–3)

Build `ForgeIngestElement`, a complete CCEK reactor element with bounded admission and the lifecycle:

```text
CREATED → OPEN → ACTIVE → DRAINING → CLOSED
```

For every file, archive, repository event, or agent event, the element will:

1. create a whole-source `ContentId` and a per-line `LineCas` spine;
2. retain source location and extraction spans as evidence, rather than treating extracted entities as detached facts;
3. run typed validation, conflict, and policy pointcuts;
4. append one immutable `ProvenanceReceipt` linked to the prior receipt;
5. reduce the admitted event into canonical Kanban cards, Rete facts, and causal nodes; and
6. render successful and failed outcomes in Forge.

A failed validation, low-confidence extraction, or policy rejection becomes a **visible triage/blocked card with the supporting evidence**, not a swallowed exception or an empty search result. Successful ingestion becomes a page, card, causal neighborhood, and gallery item bonded to the same source and receipt identities.

The baseline already has deterministic Markdown/archive reduction into board, Rete facts, causal nodes, and correlations in `src/commonMain/kotlin/borg/trikeshed/kanban/ForgeKanbanIngest.kt:38-162`; it also has whole-file CAS plus per-line `LineCas` indexing in `src/commonMain/kotlin/borg/trikeshed/memory/MemoryStore.kt:44-87`. The funded work converts these isolated seams into one bounded, receipt-producing ingest path.

### B. Decision and evidence inspector (Months 3–5)

Add first-class, content-addressed decision facts and causal-edge projections. Each transition records:

- source and destination card state;
- actor NUID and delegated actor where applicable;
- decision category, rationale, outcome, and confidence;
- support/source CIDs, policy/rule CID, and prior receipt CID;
- valid-time and recorded-time intervals; and
- explicit `CAUSED`, `INFLUENCED`, or `PRECEDENT_FOR` relationships.

The selected-card inspector will render **Why**, **Based on**, **Policy**, **Supersedes**, **Affected work**, and **Receipt chain**. Users can traverse upstream causes and downstream consequences without reconstructing history from a mutable board dump. This applies Semantica’s published decision and causal concepts while replacing random object identity with content-derived identity.[4]

### C. Search aperture and evidence-ranked context (Months 5–7)

Implement deterministic reciprocal-rank fusion as a lazy `Series` projection across independently ranked routes:

- taxonomy and repository route;
- line-level `LineCas` linked matches;
- causal distance and dependencies;
- valid-time and recorded-time route;
- provenance, actor, and policy route; and
- optional vector similarity.

Tie-breaking is deterministic by `ContentId`. Search will focus the Forge blackboard and show the same region set in page, board, graph, and gallery; it will not return an orphan result list. Semantica documents RRF/hybrid retrieval as part of its GraphRAG/search surface, while Forge applies the technique to the user’s current visual and decision context.[2]

Camera levels expose progressively more evidence:

- **L0:** source route heat, conflict density, policy status, and provenance density;
- **L1:** repository/taxonomy groups and Kanban summaries;
- **L2:** linked source lines, dependencies, and causal neighborhoods;
- **L3:** source body, extraction spans, full receipt chain, and exportable evidence bundle.

The baseline camera and the page/board/gallery/graph sections exist in `ForgeBlackboardCamera.kt:149-188`, while `regionalTopK` is presently only a `spine.take(k)` seam in `LineCasRtsView.kt:51-60`. Funding turns it into the evidence-ranked aperture rather than funding a cosmetic UI.

### D. Policy, conflict, bitemporal history, and audit export (Months 7–10)

Compile policy versions into content-addressed pointcut/Rete rules. Every ingestion or transition emits a pass, fail, or exception receipt recording the inputs, rule/policy CID, approver NUID, justification, and timestamp. Conflicting claims remain paired and visible until a resolution receipt is appended.

The result is a bitemporal workspace: users can ask both “what was valid at that time?” and “what did the system know and record at that time?” Supersession and invalidation append new evidence; they do not overwrite prior state.

Export selected evidence bundles as PROV-O, JSON-LD, RDF, and CSV. Semantica’s published provenance documentation maps entities, activities, agents, derivations, invalidation, delegation, and bundles to PROV-O and describes checksum-chain verification; Forge will expose compatible exports as projections of receipt-backed state rather than treat an external graph as authority.[3] Semantic extraction is retained as an optional adapter that can produce entities, relations, events, and time-aware triplets, but critical assertions remain evidence-linked and reviewable.[5]

### E. Open benchmark, pilot kits, and maintainership (Months 10–12)

Release reproducible fixtures, a benchmark harness, migration examples, and three end-to-end pilot kits:

1. **Security incident intake:** advisory → extracted indicators → policy gate → containment decision → evidence export.
2. **Life-science evidence review:** publication → claimed outcome/span → conflicting studies → review/approval card → provenance bundle.
3. **Financial or legal review:** source record → policy evaluation → exception approval → bitemporal decision audit.

Each kit demonstrates extraction, rules, human review, and export using the same canonical receipt flow. The reasoning component is explicitly restricted to a connected, explainable rule path with support CIDs; Semantica’s public reasoning guide correctly frames RETE as incremental multi-condition rule evaluation, and the funded implementation makes the resulting activation inspectable in Forge.[6]

## 4. Product differentiation and architecture

Forge has three inseparable roles:

1. **Ingestion plane:** source bytes and agent events are admitted once, content-addressed, evaluated, and receipted.
2. **Kanban operating system:** source-derived work, dependencies, approvals, policies, conflicts, and execution readiness are canonical visible state.
3. **User workspace:** page, board, gallery, and causal graph are one camera-addressable blackboard, not disconnected dashboards.

The implementation preserves TrikeShed’s six-ring topology rather than creating parallel stores:

| Ring | Funded responsibility | Forge-observable result |
|---|---|---|
| 4 — Pointcut | validation, policy, conflict interception | policy badge, approval lane, blocked/triage card |
| 3 — Causal | decisions, dependencies, rule activations | causal graph and “why/affected” inspector |
| 2 — Logical | receipt chain, bitemporal/provenance facets | evidence timeline and audit bundle |
| 1 — Per-line | line CIDs, neighbor linkage, source spans | exact supporting source region |
| 0 — Physical | source, receipt, and export bytes in CAS | stable `ContentId` identity |
| -1 — Mesh | NUID-scoped source/receipt replication | availability and sync status by CID |

This is materially different from a graph wrapper. The same `ContentId` is carried from source to line, receipt, rule support, card, causal node, search result, and export. The authority is the append-linked source/receipt history; Kanban, graph, gallery, vector search, MCP/ACP, RDF, and PROV-O are projections or adapters.

## 5. Deliverables and acceptance criteria

| Deliverable | Acceptance test | Public artifact |
|---|---|---|
| Evidence-backed ingest | a Markdown, archive, and agent event each become a source CID, line spine CID, receipt CID, card, and causal projection | fixtures, replay test, Forge demo |
| Failure visibility | malformed, conflicting, and policy-rejected inputs create inspectable blocked cards with evidence | negative fixtures and screenshots |
| Decision inspector | every consequential card transition has an actor, rationale, support CIDs, rule/policy CID, receipt predecessor, and temporal fields | API/schema and interactive sample |
| Aperture search | every result focuses a visible Forge region; page/board/graph/gallery agree on identity | deterministic ranking corpus and UI test |
| Integrity verification | verifier detects modified receipt, deleted receipt, sequence gap, and wrong predecessor | CLI/API verifier and corruption fixtures |
| Graceful operation | bounded ingest drains admitted work before close; no admitted item exits without success/failure receipt | lifecycle stress test and telemetry |
| Interoperability | selected receipt bundle exports as PROV-O, JSON-LD, RDF, and CSV | versioned fixture outputs |
| Adoption | three pilot kits, architecture guide, contribution guide, and monthly release notes | documented examples and public backlog |

The hard product gate is: **a feature is incomplete unless a user can inspect its receipt, source evidence, and effect in Forge.** This prevents grant funds from disappearing into headless integrations.

## 6. Measurement and evaluation

The project will publish raw benchmark inputs, expected outputs, hardware/runtime configuration, and scripts. We will report distributions (p50/p95), corpus size, error taxonomy, and failure examples—not only a headline score.

| Metric | Baseline | Twelve-month target |
|---|---|---|
| Source-to-visible-card latency | measured on release corpus before alpha | p50/p95 published for each ingest format and corpus tier |
| Evidence completeness | measured percentage of cards carrying source CID, line spine CID, and receipt CID | 100% for successful and failed admitted items |
| Receipt-chain integrity | no automated verification baseline | corruption suite detects modified receipt, deletion, sequence gap, and broken predecessor |
| Projection parity | no source-to-board replay measure | deterministic replay produces equivalent board/causal projection for fixed input corpus |
| Search focusability | no aperture evaluation baseline | 100% of returned results resolve to a visible page/board/gallery/graph region |
| Policy accountability | no transition audit baseline | 100% of policy-gated transitions carry policy result and receipt link |
| Operator review burden | measured in pilot tasks | publish task time, evidence-navigation count, and unresolved-conflict rate before/after Forge |
| Rule explainability | no receipt-linked activation baseline | every exposed activation includes rule-version and support CIDs |

We will not claim an unmeasured “accuracy gain.” Semantica’s grant proposes GraphRAG-versus-plain-RAG benchmarks; Forge’s evaluation adds the missing operational measurements: evidence completeness, replay parity, integrity detection, and the time required for a human to reach an auditable decision.[1]

## 7. Twelve-month execution plan

| Quarter | Work | Observable release |
|---|---|---|
| Q1 | `ForgeIngestElement`, source/line identity, receipts, failed-ingest cards, lifecycle/drain tests | alpha: source → receipt → card → graph flow |
| Q2 | decision model, causal inspector, bitemporal facets, policy/exception receipts | beta: inspectable approval and decision workflow |
| Q3 | multi-route RRF, camera aperture, conflict workflow, PROV-O/JSON-LD/RDF/CSV export | beta: evidence-ranked workspace and audit bundle |
| Q4 | three pilot kits, benchmark harness, accessibility/reliability pass, docs, contributor bounties | v1: reproducible demo, measurements, and adoption kit |

Monthly release notes will include completed deliverables, metric deltas, open risks, and spend by budget category. A public issue board will map each funded work package to its acceptance criterion and released evidence.

## 8. Budget

| Category | Amount | Purpose |
|---|---:|---|
| Maintainer living expenses | $26,000 | twelve months of basic runway to sustain focused open-source work |
| Model tokens | $24,000 | coding, analysis, test repair, documentation, and evaluation runs that produce the listed public releases |
| **Total** | **$50,000** | **12 months** |

This budget has no fictional staffing plan. The maintainer does the work with models; models consume tokens; the grant buys the minimum runway for both. The public accounting is therefore equally direct: monthly living-expense and token-spend totals, linked to the releases, benchmark runs, fixtures, and issue closures produced in that month. The proposed amount and twelve-month horizon align with Semantica’s published FLOSS/fund request, while the deliverable is intentionally narrower and more testable: a user-visible evidence workspace instead of an attempt to reproduce every semantic backend.[1]

## 9. Risk management

| Risk | Control |
|---|---|
| Semantic extraction is uncertain | retain source spans, confidence, method, and raw evidence; send uncertain results to triage rather than asserting them as fact |
| Scope expands into another general-purpose graph platform | enforce the Forge-observable product gate and ship external stores only as adapters |
| Inconsistent state across source, graph, vector, and audit layers | receipt/CAS identity is authority; other surfaces are replayable projections |
| UI becomes a decorative dashboard | require each card/search result to navigate to source, receipt, policy result, and causal context |
| Benchmark theater | publish raw fixtures, scripts, failures, and p50/p95 distributions; do not claim unsourced accuracy improvements |
| Maintainer concentration | document architecture, use small bounded work packages, and reserve bounties for reproducible contributor tasks |

## 10. Sustainability and public value

The grant funds a durable open-source capability rather than a hosted dependency. Teams can self-host the core workflow, inspect every evidence receipt, export standards-compatible bundles, and use optional graph/vector/model integrations without surrendering the canonical record. The first release creates reusable public goods: a receipt schema, integrity fixtures, a bitemporal decision model, an evidence-first ingest pattern, a benchmark harness, and domain pilot kits.

The funding case is not a claim that grant money creates a self-sustaining company. It buys a bounded period in which a maintainer and model-token budget can ship and measure public infrastructure. Any later support, deployment, or domain-adapter revenue is separate from—and not used to justify—the grant request.

## 11. Funding decision requested

Approve a **US$50,000, twelve-month grant** for the Forge Evidence Workspace. The grant converts already-articulated semantic concepts—provenance, decision intelligence, semantic extraction, causal rules, policy, and export—into a single inspectable operating product. At the end of the term, funders and users will be able to replay a source through Forge, inspect the complete evidence and decision chain, verify its integrity, export its audit bundle, and reproduce the published evaluation.

## Sources

[1] https://github.com/semantica-agi/semantica-grant
[2] https://github.com/semantica-agi/semantica
[3] https://github.com/semantica-agi/semantica/blob/main/docs/guides/provenance.md
[4] https://github.com/semantica-agi/semantica/blob/main/docs/guides/decision-intelligence.md
[5] https://github.com/semantica-agi/semantica/blob/main/docs/guides/semantic-extraction.md
[6] https://github.com/semantica-agi/semantica/blob/main/docs/guides/reasoning.md
