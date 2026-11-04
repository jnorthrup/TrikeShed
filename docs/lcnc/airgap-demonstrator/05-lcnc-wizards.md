# LCNC contracts cardinality and wizards

## Construction model

Extend the existing host-owned LCNC vocabulary. The browser renders capabilities returned by the host and sends a program definition for validation. It does not maintain a second compatibility table. Grouping and movement are presentation changes; parameter, wiring, scope and capability changes create a new executable definition.

The initial wizard asks for sources, the desired question/output, available engines, resource limits and review policy. It generates an editable graph and a cost/coverage preview. Every generated edge remains inspectable. A wizard is a constrained authoring assistant, not an authority to execute new effects.

## Port contract dimensions

Current kinds such as text/json/id need additional qualifiers at the semantic boundary. Proposed qualifiers include payload schema, cardinality, context scope, evidence class, freshness, effect capability and availability mode. Qualifiers belong to a versioned descriptor and do not require a heavyweight runtime type for every combination.

| Dimension | Example | Compatibility rule |
|---|---|---|
| Payload | anchored passage, question frame, Horn theory | Exact schema or declared adapter |
| Cardinality | one, optional, finite many, stream | Explicit cardinality transformation |
| Context | source version, scenario branch, global operational | Same frame or explicit scoped merge |
| Evidence class | observation, assumption, proof, hypothesis | No implicit promotion |
| Freshness | current revision, historical as-of | Consumer explicitly accepts the mode |
| Effect | read, retain artifact, admit belief, deploy production | Host capability required for effectful boundary |
| Execution | deterministic, statistical local, generative local/remote | Deployment policy and assets must permit it |

## Cardinality algebra

`One<T>` requires exactly one value; `Optional<T>` has zero or one; `Many<T>` is a bounded finite collection; `Stream<T>` has a declared admission/window policy. A port's optional marker is not the same as a collection that happens to be empty.

One-to-many fanout preserves the artifact identity and creates delivery identities. It does not clone semantic evidence. Many-to-one reduction requires a named operator: collect, select, join, aggregate, rank, consensus or proof composition. Many-to-many cross-products require an estimate and explicit global cap.

Zip requires equal lengths or a declared partial policy. Broadcast applies one shared context to many independent items. A join states key fields and missing-match behaviour. An `each` scope binds one item with its ordinal and source collection identity; outputs retain that provenance. A `carry` loop declares carried bindings and termination criteria.

For a source count S, questions Q, seats P and depth D, the naive work count may be much larger than S×Q×P. If each branch creates B children, branch count through depth D is Σ(B^i), i=0..D. The wizard computes this bound before admission. For B=4 and D=5 it is 1,365 branches per root, before multiplying seats. The operator sees that expansion and can set a global branch limit instead.

## Scope and loops

Use explicit scope inputs and outputs. Internal node identities include stable authored node ID and invocation path; repeated iterations share an authored node but have distinct invocation IDs. View collapse never changes execution. Expanding a scope for inspection must not instantiate more workers.

A feedback edge is legal only through a declared epoch/window/delay boundary or a bounded fixed-point scope. This prevents accidental immediate cycles from creating an unbounded event storm. Fixed-point equality compares canonical domain outputs, not wall times or generated run IDs. Oscillation detection retains repeated-state signatures and stops with a reason.

## Source wizard

The source wizard inventories the selected files, captures and histories. It shows source completeness, retained extraction availability and expected work. The user chooses original view, safe view and parsing profile independently. A pre-existing extraction is reused only when its lineage matches the requested source revision.

The question editor supports both a structured table and a flowchart over the same question graph. Nodes declare an issue, premise additions/removals, expected result type and possible expansion rules. Edges can mean sequence, alternative, dependency or counterfactual branch; these semantics must not be inferred from arrow geometry.

## Intent and panel wizard

Choose a domain pack, then roles. Legal roles might be clause analyst, claimant counsel, respondent counsel, authority checker and adjudicator. Fiction roles might be continuity checker, chronology checker and editor. Roles bind procedures and sources, not just a name or a prompt.

For each role choose deterministic rules, a symbolic engine, a local ranker, a local generative model, a remote model when deployment permits, or a human review queue. Show what the role can observe and produce. A deterministic role cannot produce fluent original prose unless it has a template or a generation capability.

Select fan-in policy, disagreement handling, recursion budget and escalation rules. A panel with no eligible adjudicator returns a comparison rather than fabricating a winner. A model unavailable offline remains visibly unavailable; the wizard can propose a supported replacement and show the changed semantics before the graph is run.

## Production wizard

Start from a retained candidate: a deterministic proof pattern, a repeated operational observation or an empirical hypothesis. Show source basis, conditions, scope, counterexamples and expected saved cost. Choose a target effect from the allowed local effect vocabulary. Infer no new credentials or external privileges.

Generate a restricted production representation, an interpretation of its guards in plain language, a shadow comparison plan and a revocation dependency set. The operator can inspect exact rule matching, fallback and expiration. The deployment step is separate from creating the candidate; authority follows the existing host policy.

## Rescaffolding wizard

When a run is held, edits fall into four categories:

| Edit | Reuse allowed | Required action |
|---|---|---|
| Layout, color, collapsed groups | All semantic results | New view version only |
| Presentation pace and detail | All execution results | Update viewer settings |
| Breadth, depth, filters or engine parameters | Only unaffected inputs/subresults | Fork definition and dependency analysis |
| Source, mapping, review or production effect | Only artifacts with still-valid exact dependencies | Invalidate affected results; re-admit under new policy |

The preview names reused artifacts, re-executed nodes, discarded pending work and effects already committed. An edit cannot rewrite the past. A new branch carries its parent run/checkpoint and difference manifest. Comparing runs uses matching question/context identities, not visual positions.

## Validation and explanation

Validation has four stages: syntax, port compatibility, semantic scope/capability and resource feasibility. Return all independent errors in a bounded report. An unknown node type remains drawable for inspection but is not executable. A graph cannot hide an effect behind a display-style sink label.

For every refused connection, explain the specific mismatch and a valid adapter if one exists. Examples: “This is a source statement; this port requires a reviewed scenario premise,” or “This collection has 120 items; the selected solver accepts at most 64 selected premises.” Avoid implementation-only messages in the operator flow.

## Preset versioning and portability

Presets declare their schema version, required node contracts, engine asset set and fixture dependencies. Import performs a dry validation and reports missing capabilities. A migration preserves the old definition and emits a change report. Never silently coerce unknown fields into defaults that broaden effects or discard conditions.

The three showcase presets are agreement review, synthetic proof and fiction/news comparison. They share the same scheduling, event and evidence contracts, while keeping domain-specific mappings explicit. The initial preset is deliberately small; arbitrary cardinality is supported through bounded composition, not a promise of unlimited capacity.
