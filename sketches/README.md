## Variant: Concentric Orbital

### Design stance
The CoroutineContext chain IS the layout: widgets orbit a reactor core, ring radius = context depth. Clicking anything — widget or ring — projects its CCEK identity (element, key, lifecycle state, context chain) into the inspector.

### Key choices
- Layout: full-bleed radial stage + right inspector; drawer of vm.* legos pinned bottom
- Typography: mono throughout — this is a machine view, not a marketing page
- Color: near-black blue; green = reactor, violet = sub-VM legos, amber = bot seat
- Interaction: click to select → wire is drawn from core to the selection, inspector narrates the element chain (SupervisorJob → flowState → lease quota → …)

### Trade-offs
- Strong at: making the CCEK composition *visceral*. Rings literally are context elements; nesting is depth. Best "what is this system" diagram that also happens to be the UI.
- Weak at: authoring. Radial layouts make wiring between two specific widgets awkward; dense programs (10+ nodes) will collide on the same ring.

### Best for
The architecture walkthrough: Jim showing a new contributor why "the daemon is the one executor." The trace panel doubles as live documentation.

---

## Variant: Lane Meter

### Design stance
Widgets are lego bricks in lanes ordered by context depth; brick studs = input ports (with kinds), bottom tubes = outputs. The port kind tags are the mating evidence — nothing decorative.

### Key choices
- Layout: horizontal lanes, one per ring depth (frame r1 → r2 → sub-VM band → bot seat); inspector right
- Typography: system sans for labels, mono for kinds/element names
- Color: charcoal; violet bricks = vm.* legos, amber = bot seat — family legible at a glance
- Interaction: click a brick → inspector shows element chain + the wizard steps (param order IS step order) + a run button that feints a receipt

### Trade-offs
- Strong at: scannability and the no-code story. Every contract renders with zero bespoke UI — this is exactly `wizardRoster(contracts)` from ConcentricSurface rendered. Collapse lanes and it scales to the whole vocabulary.
- Weak at: wiring between bricks is implied (kind tags) but not drawn; no sense of the live context chain while running.

### Best for
Actual day-to-day composition: pick a brick, fill the wizard, run. Closest to a shippable no-code surface.

---

## Variant: Split Mate

### Design stance
The evidence-ordered mate menu (the existing `LcncMating.rankedCandidates + q=` filter, generalized) drives construction: pick from the left, the ring assembles as concentric cards in the middle, and the CCEK context chain narrates on the right. Inference fill is a first-class affordance (the autowire banner).

### Key choices
- Layout: three-pane — menu / concentric assembly / live trace — light editorial palette
- Typography: sans + mono accents; port chips styled like tags
- Color: paper white; family dots green/violet/amber, trace highlights the deepest context frame
- Interaction: q= filter actually filters; widget click selects + narrates; wizard steps toggle; the inference-fill banner one-clicks the unique kind pair and states the ambiguity refusal policy

### Trade-offs
- Strong at: the wizard + inference-fill story (plan P3). The mate menu, the concentric assembly, and the trace are all in eyeshot at once — construction and comprehension in one glance.
- Weak at: three panes is the most chrome; on a laptop the middle column gets tight with deep nesting.

### Best for
The legal-case reading loop: tika → corenlp → view → read.construct pipelines built stepwise with the bot seat visibly the outermost, quota-checked context.

---

## Head-to-head

| Dimension | Concentric Orbital | Lane Meter | Split Mate |
|-----------|--------------------|------------|------------|
| CCEK visibility | ★★★ the layout is the context chain | ★ port tags only | ★★ trace pane |
| No-code authoring | ★★ (placement awkward) | ★★★ bricks+wizard | ★★★ menu+autowire |
| Scales to 50 widgets | ★ rings collide | ★★★ lanes collapse | ★★ menu scrolls, board tightens |
| Serves the P3 wizard story | ★ | ★★ | ★★★ |
| Novelty / "spitball" value | ★★★ | ★★ | ★★ |

**My take:** Hybrid Lane Meter + Split Mate: bricks-in-lanes for the vocabulary and wizard (variant 2's body), with variant 3's autowire banner and trace narration attached to the inspector. Orbital is the money shot for docs/walkthroughs — keep it as a "view as constellation" toggle, not the editor.
