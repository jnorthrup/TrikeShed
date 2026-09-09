# TrikeShed: An Abstract Machine Specification

## Status and Scope

This document defines an architectural contract, not a certificate that every
subsystem already satisfies it. It separates mathematical structure, required
behavior, and implementation evidence. A type name, diagram, passing test, or
rendered label does not establish an end-to-end guarantee.

TrikeShed organizes indexed data, document projections, persistent records,
reasoning, and asynchronous work around a small compositional vocabulary.
The objective is fewer independent representations and explicit ownership of
state and effects, not the claim that every problem is the same operation.

## I. Product and Indexed Algebra

`Join<A, B>` is a binary product type with projections `a: A` and `b: B`.
It is a type constructor, not a function from `A x B` into an unspecified `C`.

```text
Join<A, B>          = product of A and B
Twin<A>             = Join<A, A>
MetaSeries<I, A>     = Join<I, I -> A>
Series<A>           = Join<Int, Int -> A>
Series2<A, B>       = Series<Join<A, B>>
RowVec              = Series2<Nullable<Value>, () -> ColumnMeta>
Cursor              = Series<RowVec>
```

For a finite `Series`, the first component is a nonnegative size `n`; the
valid index domain is `0 <= i < n`. The representation does not, by itself,
make an arbitrary oracle pure, bounded in cost, or valid outside that domain.

Pointwise mapping retains the domain and composes the oracle:

```text
map(f, (n, a)) = (n, i -> f(a(i)))
map(identity, s) = s
map(g, map(f, s)) = map(g composed with f, s)
```

These are extensional laws for pure, total functions on valid indices.
The corresponding product mapping is `bimap(f, g, (a, b)) = (f(a), g(b))`.
Filtering changes the domain and needs a selection index or materialization;
a relational join needs predicates and multiplicity rules. Neither follows
merely from naming both operations "Join".

Views should retain this representation while it remains useful. Materialize
at explicit ownership, persistence, or foreign-API boundaries. Zero-cost is a
representation objective, not a universal allocation or complexity theorem:
boxing, captured closures, indexing, and backend specialization must be measured.

## II. State, Content, and Revision

Immutable content and mutable authority are distinct:

- A content-addressed object is identified by a digest of defined bytes.
- A revision records which content and predecessor evidence it refers to.
- A named head, working document, queue, or projection may change over time.
- Deletion policy must distinguish tombstones, reference removal, retention,
  and physical reclamation.

An immutable object store does not make all application state monotonic.
Appending evidence can change the current conclusion; retractions and revised
heads must have explicit semantics. A projection may be maintained by
`P(next) = reduce(P(current), event)`, but an arbitrary reducer is not monotone.

Canonical encoding, hash verification, revision conflicts, replay order,
idempotency, and crash recovery are part of the storage contract. No universal
`O(1)` clone or zero-copy guarantee is assumed. Reflinks, mappings, and
userspace I/O are concrete backend mechanisms with their own restrictions.

## III. Confix: Index Before Reification

Confix separates the source representation, a document index, and typed or
structural projections. A consumer should navigate indexed structure and
materialize only the values its operation requires.

A facet plan describes supported exact, ordered, range, or prefix access.
Its contract includes key encoding, comparison, duplicate handling, missing
values, and revision consistency. Sharing a plan should remove independently
maintained interpretations of the same field.

Index construction, path traversal, lookup, decoding, and output allocation
have separate costs. An offset can permit direct access to a token; it does
not make arbitrary path resolution or decoding a large value constant-time.

## IV. Ontology, Evidence, and Incremental Inference

SUMO, NARS, and Rete have distinct responsibilities within a composable system:

- Ontology supplies terms, relations, constraints, and semantic checks.
- NARS-style inference supplies explicit truth functions and evidence handling.
- Rete maintains pattern matches and joins incrementally as facts change.

A useful abstract record separates statement, truth, provenance, and scheduling:

```text
Belief = Join<Statement, Join<Truth, Evidence>>
Truth = Join<Frequency, Confidence>
ScheduledBelief = Join<Belief, Budget>
Derivation = (rule, premise references, substitution, evidence policy, conclusion)
```

This is a proposed interoperability contract, not a claim that a single record
already replaces all existing engine representations. Attention or priority is
not a third truth coordinate. A truth pair is not, by itself, a distance metric;
a collection of beliefs is not thereby a tensor space or differentiable manifold.

Composition must state how terms are normalized, variables unified, evidence
overlap handled, conclusions revised, and retractions propagated. Ontological
constraints become guarantees only where an actual validator enforces them.
Neither alpha filtering nor beta joining automatically computes a causal
gradient. Causal conclusions require their own derivation rules and evidence.

Conflicting beliefs may coexist as evidence. Adjudication is an explicit
workflow with an owner, policy, deadline, and outcome; it must not be described
as a universal commit barrier unless every relevant writer actually obeys it.
Resource budgets bound reasoning work without silently converting unfinished
inference into proof that no conclusion exists.

## V. CCEK: Owned Asynchronous Composition

CCEK means Coroutine, Context, Element, Key. The project contract requires
asynchronous compositions through userspace NIO/uring in `commonMain`, with
bounded channels and owning SupervisorJobs. This is a requirement to implement
and audit, not a claim that every current path already complies.

A singleton typed Key supplies routing/factory identity; a constructed Element
owns live state. Adding an Element to a context does not itself start work,
join branches, enforce a deadline, or discharge cleanup obligations.

Every composition must identify admission, transport, processing, result/error
delivery, cancellation, and ownership. Branched work requires explicit fan-in:
admitted branches must be accounted for and joined. A broadcast or subscriber
drain is not automatically result aggregation.

Where implemented, an Element lifecycle can use:

```text
CREATED -> OPEN -> ACTIVE -> DRAINING -> CLOSED
```

This is a project lifecycle, not a lifecycle supplied universally by Kotlin.
Drain stops admission, completes admitted work, joins children, and closes
resources. Forced termination is a separately reported failure path, not a
successful drain. Queue capacity, overload response, timeouts, and failure
isolation belong in the contract alongside the happy path.

## VI. NUID and Capability Boundaries

The current source expands NUID as Non-designated Unique IDentifier and models
it as `Join<Capability, Join<Nonce, Subnet>>`. It is a bearer-capability model,
not a substitute for a human identity system.

Authorization must specify capability matching, subnet containment direction,
token verification, revocation, expiry where applicable, and delegation.
Routing eligibility and permission to perform an effect are separate checks.
Possessing a value with a capability-shaped type is not proof of authority.

Concentric drawing can display containment; geometry must not grant authority.
Moving a node, zooming a ring, or changing a layout cannot change a security
boundary unless an explicit authorized command changes the semantic model.
Do not infer a sandbox from a nested box or from a subnet label.

## VII. Blackboard and UI Projections

The blackboard is a shared semantic authority with versioned observations.
An editor may have an unpublished draft; a renderer may have a cache; a client
may be disconnected. Those states must remain distinguishable from committed
state and from recorded execution outcomes.

A view is generally a lossy projection, not a bijection. The desired consistency
law is convergence after accepted commands and complete event application:

```text
render(replay(snapshot, accepted events)) = render(authoritative revision)
```

This requires defined ordering, snapshot boundaries, deduplication, conflict
handling, and resynchronization. It does not eliminate synchronization logic.
Reverse editing is a validated command, not an assumed inverse of rendering.

For the construction surfaces:

- Shake proposes typed connections from the selected document and scope.
  Apply only the accepted response for the still-current document revision.
- FD changes layout, not installed wires, node identity, containment, or authority.
  Candidate connections can guide placement without becoming installed cables.
- Socket occupancy is not type correctness, causal correctness, execution success,
  or proof that the graph expresses the operator's intention.
- SVG, Canvas, and GL must project the same semantic model, preserve selection
  and camera contracts, and truthfully report the active backend or fallback.
- Failures must leave the draft recoverable and controls usable. A successful
  endpoint or a healthy server does not prove that the visible workflow works.

## VIII. Evidence and Acceptance

Structural anchors inspected in the repository include `lib/Join.kt`,
`cursor/Cursor.kt`, `context/nuid/Nuid.kt`, `lcnc/LcncTreeShake.kt`,
`web/harness.js`, and `web/patch-layout.js`, under their respective source sets.
`PRELOAD.md` supplies the project implementation contract. These anchors are
not a blanket completion claim for this specification.

For each claimed capability, record its entry point, invariant, owner, refusal
path, resource bound, and observable outcome. Distinguish:

1. Declared architecture or proposed composition.
2. Implemented path with inspected ownership and data flow.
3. Focused verification against that path.
4. Live evidence from the relevant workload and visible interaction.

The acceptance standard is a coherent implementation with inspectable behavior,
not architectural vocabulary alone. Preserve the ambition; make every guarantee
specific enough that a counterexample can falsify it.
