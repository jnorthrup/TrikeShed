# Quarantined Injection — Pulled From `doc/taste.md` and `doc/rewire.md`

**Detected 2026-07-25.** An adversarial or external process polluted the living docs by appending archival
and unrelated-spec content behind `================` sentinels and duplicate headings. Per the POISON
policy (`doc/POISON_THRESHOLD.md`), injected content past the canonical end of a living doc is
quarantined here and stripped from the live surface.

## Sentinel evidence

- `doc/rewire.md` ended at line 495 (the closing `*` of the *This document is the architecture
  rewire…* sentence). Line 497 was a bare `================` line — a single-sentinel poison append.
- `doc/taste.md` had a duplicate `## DRY / PRELOAD cuts already shipped (Jul 2026 audit pass)`
  heading at line 566 duplicating the genuine one at line 390. After the duplicate, the file
  continued with a `T-REWIRE-3 Follow-up Cuts` block and then a `================` /
  `=== upstream-creeper-node-book.md ===` / `================` triplet at lines 587-589, followed
  by the Upstream Creeper Node spec (lines 590-642). The "Upstream Creeper Node" is a
  **separate spec doc** listed in `doc/INDEX.md` §4 — it was duplicated into taste.md.

## What was injected

### Block A — `doc/rewire.md` line 497

```
================
```

### Block B — `doc/taste.md` lines 566-642

```
## DRY / PRELOAD cuts already shipped (Jul 2026 audit pass)

## T-REWIRE-3 Follow-up Cuts (from doc/rewire.md §9)

These are the separated follow-up tasks from T-REWIRE-3 (Cuts 1 and 7 landed in T-REWIRE-3).

- [ ] **T-REWIRE-3b. Modelmux kanban agent**
  JobCommand handler routing cards through modelmux.

- [ ] **T-REWIRE-3c. UPnP workspace discovery**
  Workspace announce payload over mDNS/SSDP.

- [ ] **T-REWIRE-3d. SSH mesh transport**
  SSH tunnel over litebike Tls carrying Confix docs.

- [ ] **T-REWIRE-3e. IPFS/IPNS bridge**
  CAS blocks as IPFS blocks, IPNS names = manifest CIDs.

- [ ] **T-REWIRE-3f. Progressive rendering**
  Jules jobs reading TreeDoc archives into ForgeDoc.

=====================================
=== upstream-creeper-node-book.md ===
=====================================
# TrikeShed Upstream Creeper Node Specification

The **Creeper Node** is a capability-limited Forge agent deployed on constrained upstream
environments (like OpenWrt Linux routers). It acts as a local-first participant in the
TrikeShed ecosystem, maintaining discovery, routing to VPS resources via deterministic
eligibility, and handling assignment-bound key leases without serving as a central vault
or packet inspector.

## Chapter 1: Current Code Inventory

This inventory maps the core components of the Creeper Node architecture to the live codebase.

* **KeyMux / ModelMux**: Coordinates capability evaluation and state multiplexing.
  * `src/commonMain/kotlin/keymux/KeyMux.kt:159`
  * `src/commonMain/kotlin/modelmux/ModelMux.kt:94`
* **NUID (Node Unique Identifier)**: Capability and identity envelopes.
  * `src/commonMain/kotlin/borg/trikeshed/context/nuid/Nuid.kt:281`
  * `src/commonMain/kotlin/borg/trikeshed/context/nuid/NuidFanoutElement.kt:50`
* **Litebike Transport**: Multi-protocol mesh and listener routing.
  * `src/commonMain/kotlin/borg/trikeshed/litebike/LitebikeListenerElement.kt:40`
  * `src/jvmMain/kotlin/borg/trikeshed/litebike/JvmLitebikeBindAdapter.kt:50`
  * `src/jvmMain/kotlin/borg/trikeshed/litebike/JvmKanbanServer.kt:54`
* **Reactor Streams**: Action/Result wire protocols and async endpoints.
  * `src/commonMain/kotlin/borg/trikeshed/reactor/ReactorCodec.kt:11`
  * `src/commonMain/kotlin/borg/trikeshed/reactor/ReactorEndpoint.kt:13`
* **CAS and Confix**: Object storage and facet parsing.
  * `src/commonMain/kotlin/borg/trikeshed/job/CasStore.kt:9`
  * `src/jvmMain/kotlin/borg/trikeshed/job/MmapCasStore.kt:16`
  * `src/commonMain/kotlin/borg/trikeshed/parse/confix/ConfixIndexK.kt:25`
  * `src/commonMain/kotlin/borg/trikeshed/lcnc/reduction/ConfixReducers.kt:10`
* **Forge Agent / Application State**:
  * `src/commonMain/kotlin/borg/trikeshed/forge/ForgeApp.kt:72`
* **Creeper Node Implementation** (New):
  * `src/commonMain/kotlin/borg/trikeshed/creeper/CreeperNode.kt`
  * `src/commonTest/kotlin/borg/trikeshed/creeper/CreeperNodeTest.kt`

## Chapter 2: Control, Data, and State Planes

The Creeper Node separates operational concerns into distinct planes:

* **Control Plane (Live)**: Facilitates capability distribution and topology discovery via
  `NuidFanoutElement`. NUIDs dictate whether an agent is allowed to process a request block.
* **Data Plane (Mixed)**: Built on Reactor pipelines and Litebike listener channels. Uses
  non-blocking multiplexing to stream chunks (e.g., HTX/SSH payloads). Direct data paths
  avoid unnecessary decryption or central staging.
* **State Plane (Live)**: Anchored by `MmapCasStore` and Confix structural sharing. State
  transitions are purely functional transformations of content addresses (CIDs), eliminating
  global mutable state across the router and its peers.

## Chapter 3: Peer Discovery and Expiring Capability Advertisements

Creeper nodes do not rely on static IP tables or a central discovery service. Instead, they
leverage the NUID layer to broadcast capabilities over localized subnets or mesh links.

* **Capabilities**: Advertisements are mapped to NUID subnet markers. The `CreeperNode`
  subscribes to the `NuidFanoutElement` for discovery packets.
* **Expiration**: Credentials lease handles include built-in TTLs. NUID fanouts
  automatically discard expired or structurally invalid advertisements via continuous
  suspension routines (`consume()` rather than `tryTake()`).

## Chapter 4: Assignment-Bound Key Leases

The Creeper Node holds no persistent root authority or primary keys. It only possesses keys
bounded to its immediate assignments.

* **Opaque Handles**: Key material is represented as opaque CIDs in the CAS.
* **Holder-Sealed Payloads**: Jobs delegated to the Creeper Node are sealed for its
  specific ephemeral key. The router uses `KeyMux` to unwrap the job. The `CreeperNode`
  initializes a `KeyMux` targeting its `CasStore` provider.
```

## Recovery policy

- `doc/taste.md` canonical end is line 565 (blank line after `T-CAS-PROJ-3`).
- `doc/rewire.md` canonical end is line 496 (blank line after the closing `*Every claim maps…*`).
- Any reappearance of these blocks (matched by the `================` sentinel or the
  duplicate `DRY / PRELOAD cuts already shipped` heading) is poison. The detector is a
  simple `grep -n '================' doc/*.md` plus
  `grep -c '^## DRY / PRELOAD cuts already shipped' doc/taste.md` — should be `1`, never `2`.
- The genuine Creeper Node spec lives in its own file; the cut that re-establishes that
  separation is T-REWIRE-1 in `doc/todo.md`.
