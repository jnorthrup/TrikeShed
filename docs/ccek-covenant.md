# The CCEK Covenant

The durable anti-rolloff anchor for the core. Every pin here is a falsifiable gate a reviewer can check against cited source lines.

> **Companion specs:** [docs/concentric-lcnc-ccek-spec.md](concentric-lcnc-ccek-spec.md) (the concentric LCNC mapping); [doc/ccek-consistency-pass.md](../doc/ccek-consistency-pass.md) (cruft audit). This page fronts those specs; it never duplicates their content.

## 1. The Definition

CCEK = **CoroutineContext.Element.Key** — named for the exact Kotlin type path:

```
kotlin.coroutines.CoroutineContext
  .Element
    .Key
```

Pinned at `src/commonMain/kotlin/borg/trikeshed/ccek/CCEK.kt`, lines 31–37:

> "The substrate IS that mechanism — services, reactors, and channels are context Elements addressed by typed Keys, composed with `+`, resolved with `[Key]`, removed with `minusKey` — which is what gives every CCEK citizen block compatibility with none of lexical scoping's inclusion/exclusion quirks."

**Falsifiable gate:** open `CCEK.kt` lines 31–37 and verify the type-path claim. The definition is the Kotlin type, not a prose metaphor.

> **Status:** verified-live — source lines exist and match.

## 2. The Negative Space

LLMs snap to trained shapes that erase the design. The following are **NOT** what CCEK is:

- **NOT a CEK machine.** CCEK is not Continuation-Environment-Control-Key in the Scheme/CPS sense. There is no `control` operator, no prompt/op distinction, no delimited continuations mechanism here.
- **NOT Spring DI.** There is no `@Autowired`, no bean registry, no dependency graph resolution at startup. Services are coroutine context elements, not injected beans.
- **NOT call-semantics builds.** Composition is `+` on context elements, not method chaining, not builder patterns, not fluent APIs.

The negative space is pinned because these are the shapes that LLMs produce most readily when "explaining" CCEK. A page that states the positive without the negative is one revision away from rolloff.

> **Status:** verified-live — the negative-space pins are derived from the KDoc's explicit type-path claim and the absence of CEK/Spring/call patterns in the source.

## 3. Rings Are Blocks

Pinned at `src/commonMain/kotlin/borg/trikeshed/lcnc/LcncRunner.kt`:

**Line 45:**
> "A node holding children IS a ring; a node naming a `subprogram` is a NAMED ring."

**Lines 281–283:**
> "Ring entry IS withContext: any suspend runner in the subtree reads `currentCoroutineContext()[LcncScopeFrame]` — block compatibility through the context machinery, not the grammar."

**Falsifiable gate:** open `LcncRunner.kt` lines 45 and 281–283. Verify that ring entry uses `withContext(childFrame)`. The block is a coroutine scope, not a function call.

> **Status:** verified-live — source lines exist and match the cited semantics.

## 4. Substrate Composition

Services, reactors, and channels are `CoroutineContext.Element` instances addressed by typed `Key` objects. Composition:

```kotlin
// Elements compose with +
val ctx = reactorScope + supervisor + Dispatchers.Default + reactor + CoroutineName("CCEK-reactor")

// Elements resolve with [Key]
val job = ctx[Job]
val reactor = ctx[MuxReactorElement.Key]

// Elements remove with minusKey
val without = ctx.minusKey(CoroutineName.Key)
```

Every CCEK citizen (reactor, channel, service) is a context element. This is what `CCEK.kt:34–36` pins: "composed with `+`, resolved with `[Key]`, removed with `minusKey`."

> **Status:** verified-live — composition pattern appears throughout the codebase.

## 5. Machine Status (Current Tree State)

What is landed vs pending, read from the tree — not from session memory:

| Component | Status | Evidence |
|-----------|--------|----------|
| CCEK.kt definition | **Landed** | `src/commonMain/kotlin/borg/trikeshed/ccek/CCEK.kt` |
| LcncRunner rings-are-blocks | **Landed** | `src/commonMain/kotlin/borg/trikeshed/lcnc/LcncRunner.kt` |
| CcekReactorBinding | **Landed** | `CCEK.kt:52-76` — reactor scope, choreograph, user context |
| ArticulatedNode | **Landed** | `CCEK.kt:122-317` — signal fanout, agents, projections |
| MuxReactorElement | **Landed** | `userspace/reactor/MuxReactorElement.kt` |
| Executor state | **Partial** | The reactor runs, but reconcile elements are inert pending CCEK completion |
| D1–D10 decision letters | **Absent** | No D1–D10 letters artifact exists in-tree. The covenant does not cite one as existing. |

The absence of a landed D-letters decision artifact is stated plainly: the design decisions are encoded in source, not in a separate letters document. If such a document existed, it would be referenced here. It does not.

> **Status:** verified-live — tree state derived from current source; D-letters absence confirmed.

## 6. Unblock Relationship

The inert escape-velocity elements (`IpfsAdapter`, `CasReplicationElement`, reconcile elements) are explicitly "pending a complete CCEK implementation." This is quoted from the source comments' intent:

- `cas/IpfsAdapter.kt:1` — "intentionally inert pending a complete CCEK implementation"
- `cas/CasReplicationElement.kt:1` — "intentionally inert pending a complete CCEK implementation"

CCEK completion — giving these elements proper CCEK owners with lifecycle management — is the unblock for wave 2's escape-velocity deliverable. See [escape-velocity.md](escape-velocity.md).

> **Status:** verified-live — source comments quoted verbatim; cross-link to escape-velocity page.

## 7. Reactor Core, Adapter Macros

The covenant (user, 2026-08-29): the internal **async reactor design is the architecture**; MCP and ACP are adapter macros over it — retained for working functionality and low-bandwidth links, never allowed to become the design center. The dependency direction is the falsifiable part: adapters import and compose reactor/core primitives; the core never imports adapter shapes.

The reactor core in-tree:

- `MuxReactorElement` — `src/commonMain/kotlin/userspace/reactor/MuxReactorElement.kt` (a context Element, per §4).
- `CcekReactorBinding` — `CCEK.kt:52–76` (reactor scope, choreograph, user context).
- Two-plane discipline — `src/jvmMain/kotlin/borg/trikeshed/daemon/OroborosDaemon.kt:1298`: "Initial two-plane reconcile. File reads and CAS writes stay off the reactor thread."

Direction audit (current tree):

- `borg/trikeshed/mcp/McpServerHandler.kt` imports core (`cas`, `job`, `lib`, `memory`) — **correct direction**: the MCP surface is an adapter wrapping the core.
- **Known inversion:** `borg/trikeshed/ccek/Seat.kt:11–12` imports `modelmux.acp.AcpAction` — the core referencing an ACP-namespace type. Pinned as a contour deficit for wave-2 flattening; wave 1 documents it, does not fix it.

**Falsifiable gate:** grep the core packages (`ccek/`, `userspace/reactor/`) for `mcp`/`acp` imports; the only permitted hit is the pinned `Seat.kt` inversion above (or fewer, once flattened). Any new hit is a covenant violation.

> **Status:** verified-live for the direction audit (one known inversion, pinned); design-covenant for the adapter-macro boundary itself.
