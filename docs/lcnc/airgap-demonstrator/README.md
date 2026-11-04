# LCNC airgap demonstrator blueprints

Prepared 4 November 2026 for the TrikeShed implementation programme. Initial inspected baseline: `d4efbd25855bea0b7a97c2954c696a3adec69e31`; the subsequent upstream copied-node follow-up at `7e7998042` is reconciled in the repository baseline. These are implementation specifications and research decisions. Proposed behaviour is not a claim that the demonstrator already exists.

The demonstrator is a visible, interruptible curation and inference factory. Retained documents, questions, evidence, memories and indexed archives enter from the left. Configurable panels, symbolic engines and optional models occupy the middle. Reviewed outputs, revisable beliefs and guarded Rete productions leave on the right and feed later cycles. Every visible transfer resolves to retained evidence. Every inference states its scope. The operator can stop admission, inspect outstanding work, change the scaffold, and continue as a new version.

## Implementation timeline and cost envelope

Plan for a first instrumented vertical slice in **4–7 calendar days**, a convincing integrated demonstrator in **18–32 days**, and an offline release candidate with recovery, production revocation and sustained-load evidence in **45–75 days**. The wider research programme continues for **3–6 months**. These are conditional engineering estimates for round-the-clock agent work, not measured delivery rates. Dependency bottlenecks, parser isolation and semantics dominate the schedule.

The planning configuration is six implementation/review lanes, averaging 65% active time: 93.6 active agent-hours per day. Sol and Terra share the work; difficult semantic reviews use the requested higher reasoning settings. A model's reasoning setting and its processing-speed tariff are separate variables. At the researched Fast tariff and the base token-use assumption, the mixed Sol/Terra estimate is **$4.80 per active agent-hour**, or **$449 per day** before reserve, machines and human review. At the heavy token-use assumption it is **$14.85 per hour**, or **$1,390 per day**. The [cost workbook](15-timeline-and-costs.md) supplies rates, formulas, sensitivity, milestone budgets and the first-48-hour recalibration rule. No paid execution or automation has been started by this planning work.

The first release must work with deterministic CPU methods and retained fixtures. Stanford parsing is an optional local statistical lane; BERT, Jeva and generative models are separately selectable local capabilities. Development may use connected frontier models while the delivered demonstrator runs offline from a sealed bundle.

## Reading routes

| Reader or decision | Read first |
|---|---|
| Programme owner | [Charter](00-charter.md), [storyboard](13-demonstration-storyboard.md), [timeline and costs](15-timeline-and-costs.md) |
| Runtime implementer | [Architecture](02-tier-architecture.md), [execution and control](06-execution-and-control.md), [wire contracts](19-wire-contracts.md) |
| Graal and Spacegraph implementer | [Visualization](07-visualization.md), [storyboard](13-demonstration-storyboard.md), [acceptance](16-validation.md) |
| Curator and ontology implementer | [Sources](03-source-intake-and-airgap.md), [evidence](04-evidence-rdf-and-memory.md), [engine atlas](08-engine-atlas.md) |
| NARS and Rete implementer | [Consolidation](10-nars-rete-consolidation.md), [CPU plan](11-cpu-and-indexes.md), [contracts](19-wire-contracts.md) |
| Sol or Terra taking a work package | [Repository baseline](01-repository-baseline.md), [25 packages](14-work-packages.md), relevant mechanism chapter |

## Complete document map

1. [Programme charter and invariants](00-charter.md)
2. [Repository baseline and extension seams](01-repository-baseline.md)
3. [Tier architecture and coordination](02-tier-architecture.md)
4. [Source intake, denaturing and airgap deployment](03-source-intake-and-airgap.md)
5. [Evidence, RDF, wiki and breadcrumb memory](04-evidence-rdf-and-memory.md)
6. [LCNC contracts, cardinality and wizards](05-lcnc-wizards.md)
7. [Execution events, clocks, pause and rescaffolding](06-execution-and-control.md)
8. [Realtime sparks, flyovers and RTS zoom](07-visualization.md)
9. [Inference engines and compatibility atlas](08-engine-atlas.md)
10. [Legal counsel panels and trial simulation](09-legal-simulation.md)
11. [NARS learning, Rete production and deoptimization](10-nars-rete-consolidation.md)
12. [CPU algorithms, indexes and resource budgets](11-cpu-and-indexes.md)
13. [Fiction, news, history and IPFS scenarios](12-domain-packs.md)
14. [Demonstration storyboard and fixtures](13-demonstration-storyboard.md)
15. [Twenty-five implementation work packages](14-work-packages.md)
16. [Round-the-clock timeline and cost workbook](15-timeline-and-costs.md)
17. [Validation and benchmark programme](16-validation.md)
18. [Primary research sources and claim boundaries](17-research-sources.md)
19. [Decisions, risks and bounded experiments](18-decisions-and-experiments.md)
20. [Proposed wire contracts and worked records](19-wire-contracts.md)
21. [Offline and development operating procedures](20-operating-procedures.md)
22. [Requirement and acceptance traceability](21-traceability.md)

## Decisions that hold the design together

- Use the existing LCNC execution and CCEK ownership contracts; extend their observations and boundaries. Do not create a second executor for the animation.
- Make tiers repeatable groups in a typed graph. Three columns are a useful initial view, not a hardcoded architecture.
- Separate source statements, scenario assumptions, proofs, probabilistic hypotheses and production authority throughout storage, ports and UI.
- Use one durable semantic event history with a bounded projection for human viewing. Telemetry summaries may coalesce; source, decision and effect receipts may not silently disappear.
- Compile only an explicit, auditable subset into production. Familiarity reduces work only while its preconditions, evidence and policy remain valid.
- Prefer exact lexical and symbolic methods first, then measure what optional models add on the same frozen inputs.

The first useful result is a person following one question through a real source clause, a visible branch, an honest unresolved condition, a checked proof and a reversible production change. Scale follows that causal chain.
