# Legal Council — 3 panels × 5 experts, variable geometry

> Spec pinned 2026-08-29 from operator directive: "3 PANELS, 5 experts each,
> spitball, variable geometry actual." Status: design brief — recon in flight,
> implementation to follow against this target.

## Product frame

We ship **canned LCNC** — working presets users run as-is — with the intent to
**atomize into flexibility on demand**: every can cracks open on the canvas
into its constituent legos (nodes, wires, rings) the moment a user needs to
rewire it. No black-box nodes that hide a composition. For the council:
`preset-council` ships the default 3×5 **fully drawn** (three panel rings, five
expert seats each, synthesis and ruling seats, evidence wiring visible), and
`council.convene` is the re-geometry tool — feed it a different convening
config and it emits a new drawn program. The can and the atoms are the same
substance at different zoom levels.

## What it is

The council convenes **panels of experts over a dispute/document** and lands a
ruling on the record. The default convening is **3 panels × 5 experts** (15
seats), but the geometry is **configuration, not code**: N panels × M experts,
each panel with its own charge, composed as an LCNC program so the canvas
renders whatever geometry was convened.

## The flow

1. **Intake** — a real document/dispute enters (text in, or a CAS/couch ref).
   `legal.ingest` distills it; every extracted KIF fact lands in the shared
   `KifKnowledgeBase` (provenance on the blackboard, as today).
2. **Spitball (divergent)** — every expert seat argues FREELY: one model call
   per seat (BrainClient → KeyMux → ModelMux → HTX; spend on the daemon's
   quota receipts like all model traffic). An expert's prompt carries:
   - the panel's charge,
   - the distilled document,
   - **banked evidence** (`legal.evidence` query output — the loop the audit
     flagged as open MUST be closed: seats do not argue blind),
   - optionally the other experts' takes for a second rebuttal round.
3. **Panel synthesis (convergent)** — per panel, one synthesis seat reduces its
   M expert takes to a panel position (majority themes, dissents preserved).
4. **Council ruling** — one ruling seat reduces the N panel positions to a
   verdict; `kg.ingest` advances the TribunalInstance lifecycle and the verdict
   lands as a durable record (CAS cid + blackboard fact), not just node output.

## Variable geometry, concretely

- A **convening config** is data: `{panels: [{name, charge, experts: M}],
  document, rounds}` — default `3×5`, one rebuttal round.
- **The council IS an LCNC program** (operator directive: "AS LCNC"). A
  generator lego (working name `council.convene`) takes the config and emits an
  LcncProgram DOCUMENT (nodes + wires, Confix all the way) — the drawing IS the
  run: the canvas renders the convened geometry, and executing the emitted
  document through the ONE concentric executor is how the council sits. No
  host-side fan-out path exists.
- The ring mapping is the concentric machine's own: **each panel is a RING**
  (scope) holding its M expert seats plus one synthesis seat; `scope.out`
  yields the panel position; the council is the outer ring whose ruling seat
  consumes the panels' yields. Rebuttal rounds are additional statements in
  the same ring, in authored order.
- Every seat is an ordinary lego node — no seat logic hidden in host code.

## Hard requirements

- **Headless-runnable**: `POST /api/lcnc/run` with a config + document returns
  the ruling (smoke-testable by curl, same as preset-kanban).
- **Evidence-closed**: expert prompts contain `legal.evidence` results from the
  bank populated by `legal.ingest` on THIS document.
- **On the record**: ruling → TribunalInstance advance → CAS/couch + blackboard
  fact; the board can carry a card for the case.
- **Test seam**: the dialog is injectable (as the existing tests fake it) so
  the whole council runs green in tests with zero model spend.
- **Degrade loudly**: missing credentials surface as a per-seat failure with
  the provider failover order, never a silent empty ruling.

## Non-goals (this pass)

- No new model providers, no browser-side seat execution, no persistence
  scheme beyond the existing CAS/couch/blackboard planes.
