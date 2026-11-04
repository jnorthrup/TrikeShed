# Realtime visualization sparks flyovers and RTS zoom

## Visual contract

The screen explains the state of a real run at human speed. Its primary questions are: what entered, what is being considered, what is waiting, why did this branch happen, what is supported, and what can the operator change now? Visual richness serves those questions.

Start with left-to-right tier groups and stable internal lanes. Sources remain on the left even when the middle expands. Outputs and recurring productions remain on the right. A minimap shows the complete hierarchy and current viewport. Nested scopes can be entered without losing the parent path. The existing Graal landscape supplies navigation and inspector conventions; Spacegraph supplies LCNC semantics.

## Screen composition

```text
┌ Run / definition / epoch ─ live or replay ─ airgap profile ─ lag ─ Pause ┐
│ Sources            Analysis scopes                  Outputs            │
│ ┌ Agreement ┐      ┌ Question tree ─────────────┐    ┌ Findings ┐       │
│ │ page/span │──●──▶│ select → panel → proof     │─●─▶│ sources  │       │
│ └───────────┘      │      ↘ unresolved frontier │    └──────────┘       │
│ ┌ Questions ┐──●──▶│ model / rules / Horn / NAL │    ┌ Rete ─────┐     │
│ ┌ Evidence  ┐      └───────────────────────────┘───▶│ shadow/live│     │
│ ┌ Memories  ┐             ↑ invalidation ──────────│ suspended  │     │
│                                                └──┴────────────┘     │
├ Inspector: selected object, timers, scope, proof, sources, controls ───┤
│ Event strip / checkpoint markers / disagreements / resource budget    │
└ Minimap / zoom path / source coverage / omitted events ───────────────┘
```

The inspector is pin-able so hover does not constantly replace a source the operator is reading. Selection and hover differ. Keyboard navigation can select a node, traverse incoming/outgoing edges, expand its scope and open its source.

## Sparks and wires

A wire has stable authored identity and a port-to-port relationship. A transfer animation is derived from an observed delivery event. One spark may represent one transfer or an aggregated group; the legend and flyover report the represented count and time interval. A long wire does not imply a longer processing duration.

Animate position along a cached curve using presentation time. Use a bounded duration, initially 250–900 ms, to make transfer direction readable. Record actual transfer latency separately. For a fast completed transfer, animation can continue after completion as a presentation of the recent event; the inspector shows its true timestamp and mode. Never display it as still executing solely because the particle is moving.

At high rates, increase a wire's rate indicator, count badge or moving band instead of adding one particle per item. Use logarithmic visual scaling with numeric counts; a tenfold rate should not consume tenfold rendering work. Errors and refusals travel as distinct shapes or terminate at the refusing boundary. A missing downstream acknowledgement is a waiting condition, not a successful arrival.

## Visual encoding

| Semantic dimension | Visual channel | Additional text |
|---|---|---|
| Data transfer | Directional particle/band | Count and covered interval |
| Queued | Hollow marker and queue bar | Queue count and oldest wait |
| Active execution | Animated border or timer arc | Elapsed active time; engine |
| Waiting for input/review | Stationary pause glyph | Exact missing dependency |
| Completed | Short completion accent | Result status and receipt |
| Failed/refused | Cross/stop glyph | Failure versus semantic refusal |
| Hypothetical scope | Dashed enclosure | Scenario and assumption label |
| Proof support | Solid highlighted causal path | Fragment and proof coverage |
| Empirical hypothesis | Distinct dotted enclosure | Frequency/confidence and evidence basis |
| Stale/withdrawn | Desaturated hatch | Invalidating dependency |
| Production generation | Thick outlined node | Shadow/active/suspended and version |

Color reinforces shape and text; it is never the sole carrier. Do not use saturation as a single blended confidence/throughput/CPU metric. The operator selects a lens: activity, evidence, cost, uncertainty or source coverage. Each lens has a legend and preserves the underlying topology.

## Timer design

Each expanded node can show queue wait, active duration, deadline remaining and last progress event. For a known finite batch, show completed/admitted counts. For an open search, show steps, frontier size and budget consumed. A stalled heartbeat indicates no recent observation, not necessarily a dead process.

Timer rings use a denominator only for a real deadline or fixed work count. If the work estimate is heuristic, label it as an estimate and avoid visually treating it as a guarantee. Paused presentation freezes displayed animation while the live-time badge continues to warn that execution is progressing, unless execution is also held.

## Levels of detail

| Level | Approximate view | Objects shown | Suppressed detail |
|---|---|---|---|
| L0 Factory | Whole programme | Tier territories, aggregate flows, global blockers | Individual clauses and invocations |
| L1 Scope | One domain/run | Sources, panels, engine groups, output families | Most internal steps |
| L2 Workflow | One question | Nodes, ports, queues, branch decisions | Raw payloads |
| L3 Invocation | One execution | Inputs, outputs, timers, batch progress | Full source unless selected |
| L4 Evidence | One result/premise | Source span, proof step, assumptions, review lineage | Unrelated graph |
| L5 Engine detail | Diagnostic drilldown | Match sets, solver frontier, dependency parse or model receipt | Only details the adapter actually exposes |

LOD follows projected screen size and selection. Selected objects remain inspectable even below the ordinary threshold. Introduce hysteresis around zoom thresholds so labels and nodes do not flicker. Collapse/expand transitions preserve a stable parent anchor and interpolate camera position rather than re-running a force layout every frame.

## Stable layout

Persist user-authored coordinates. For new dynamic branches, reserve lanes or deterministic slots within a scope. Use a stable ordering from branch identity and role. Re-layout is an explicit action or a bounded background operation whose result preserves pinned nodes.

At overview scale, aggregate edges between groups and display counts. At detail scale, recover exact edges. Maintain an aggregation map from each visible object to the represented semantic IDs and journal interval. Without this map, clicking an aggregate cannot explain itself.

A quadtree or comparable spatial index supports viewport culling and picking. Rendering outside the viewport can be skipped, but state accounting continues. Keep the graph model and scene buffers separate; repeated panning should not deserialize source documents or recompute inference.

## Flyover information hierarchy

The first flyover line is the node's role and current domain outcome. The next lines show what it received, what it is doing, what it awaits and what it produced. A compact footer shows engine, elapsed/queue time, resource budget, source frame and observation age. Expand for exact IDs, parameter values and raw receipt.

A legal analyst flyover might read: “Checking exception applicability. Two conditions supported; one unresolved. Using agreement capture A and scenario S. No conclusion published.” A Horn flyover might read: “Positive Horn query completed. Supported by three premises. First proof retained; alternate proofs not enumerated.” A model ranker flyover reports candidate count and selected/abstained result, without inventing an explanation of hidden model reasoning.

Use retained structured state and deterministic templates for these descriptions. Optional language-model summaries are derivative annotations with links to the underlying state; they cannot replace the numeric status or explain an unobserved internal process.

## Renderer budget

Initial acceptance targets on the named reference workstation: p95 frame time at most 33.3 ms during normal navigation, with a 16.7 ms stretch target; control acknowledgement visible within 250 ms when the host is available; ordinary semantic update visible within 500 ms excluding deliberate presentation delay. These are targets, not measured results.

Cap visible animated particles initially at 1,000 and active labeled nodes around 500, then tune with measurements. Larger graphs remain navigable through aggregation and culling. A 50,000-node retained graph is not a requirement to draw 50,000 labels. Use Canvas/WebGL buffers if the existing rendering path requires them; keep DOM for the accessible inspector and bounded controls.

Move projection/aggregation work off the animation hot path. Batch updates, reuse typed buffers where practical, and avoid allocating objects per spark per frame. The rendering loop reads a stable snapshot plus incremental changes. The host's semantic event throughput must not depend on the monitor refresh rate.

## Accessibility and motion

Reduced-motion mode replaces traveling sparks with directional rate bands and changing counters. A table/tree view exposes the same nodes, states and causal links. Focus indicators remain stable during updates. Screen-reader announcements are reserved for selected-run milestones, failures and control acknowledgements; announcing every event would recreate the fire hydrant in audio.

## Visual acceptance scenarios

Show a quiet graph with no fake activity; a burst with accurate aggregation; a slow parser with honest elapsed time; a blocked join naming the missing seat; a replay at one-tenth speed; a late event from an old epoch rejected from the current view; and a production invalidation that visibly returns work to analysis. An operator should identify the blocker and recover its exact source within three navigation actions from the selected result. Measure that task with actual users after the first integrated slice.
