# TrikeShed Upstream Creeper Node Architecture

The **Creeper Node** is a capability-limited Forge agent deployed on constrained upstream environments (like OpenWrt Linux routers). It acts as a local-first participant in the TrikeShed ecosystem, maintaining discovery, routing to VPS resources via deterministic eligibility, and handling assignment-bound key leases without serving as a central vault or packet inspector.

## Chapter 1: Current Code Inventory

This inventory maps the core components of the Creeper Node architecture to the live codebase.

*   **KeyMux / ModelMux**: Coordinates capability evaluation and state multiplexing.
    *   `src/commonMain/kotlin/keymux/KeyMux.kt:159` (Live)
    *   `src/commonMain/kotlin/modelmux/ModelMux.kt:94` (Live)
*   **NUID (Node Unique Identifier)**: Capability and identity envelopes.
    *   `src/commonMain/kotlin/borg/trikeshed/context/nuid/Nuid.kt:281` (Live)
    *   `src/commonMain/kotlin/borg/trikeshed/context/nuid/NuidFanoutElement.kt:50` (Live)
*   **Litebike Transport**: Multi-protocol mesh and listener routing.
    *   `src/commonMain/kotlin/borg/trikeshed/litebike/LitebikeListenerElement.kt:40` (Live)
    *   `src/jvmMain/kotlin/borg/trikeshed/litebike/JvmLitebikeBindAdapter.kt:50` (Mixed)
    *   `src/jvmMain/kotlin/borg/trikeshed/litebike/JvmKanbanServer.kt:54` (Live)
*   **Reactor Streams**: Action/Result wire protocols and async endpoints.
    *   `src/commonMain/kotlin/borg/trikeshed/reactor/ReactorCodec.kt:11` (Live)
    *   `src/commonMain/kotlin/borg/trikeshed/reactor/ReactorEndpoint.kt:13` (Live)
*   **CAS and Confix**: Object storage and facet parsing.
    *   `src/commonMain/kotlin/borg/trikeshed/job/CasStore.kt:9` (Live)
    *   `src/jvmMain/kotlin/borg/trikeshed/job/MmapCasStore.kt:16` (Live)
    *   `src/commonMain/kotlin/borg/trikeshed/parse/confix/ConfixIndexK.kt:25` (Live)
    *   `src/commonMain/kotlin/borg/trikeshed/lcnc/reduction/ConfixReducers.kt:10` (Live)
*   **Forge Agent / Application State**:
    *   `src/commonMain/kotlin/borg/trikeshed/forge/ForgeApp.kt:72` (Live)

## Chapter 2: Control, Data, and State Planes

The Creeper Node separates operational concerns into distinct planes:

*   **Control Plane (Live)**: Facilitates capability distribution and topology discovery via `NuidFanoutElement`. NUIDs dictate whether an agent is allowed to process a request block.
*   **Data Plane (Mixed)**: Built on Reactor pipelines and Litebike listener channels. Uses non-blocking multiplexing to stream chunks (e.g., HTX/SSH payloads). Direct data paths avoid unnecessary decryption or central staging.
*   **State Plane (Live)**: Anchored by `MmapCasStore` and Confix structural sharing. State transitions are purely functional transformations of content addresses (CIDs), eliminating global mutable state across the router and its peers.

## Chapter 3: Peer Discovery and Expiring Capability Advertisements

(Proposed / Mixed)

Creeper nodes do not rely on static IP tables or a central discovery service. Instead, they leverage the NUID layer to broadcast capabilities over localized subnets or mesh links.

*   **Capabilities**: Advertisements are mapped to NUID subnet markers.
*   **Expiration**: Credentials lease handles include built-in TTLs. If a node is partitioned, its capabilities naturally expire, preventing stale data routing or zombie authorizations. NUID fanouts automatically discard expired or structurally invalid advertisements.

## Chapter 4: Assignment-Bound Key Leases

(Live / Proposed)

The Creeper Node holds no persistent root authority or primary keys. It only possesses keys bounded to its immediate assignments.

*   **Opaque Handles**: Key material is represented as opaque CIDs in the CAS.
*   **Holder-Sealed Payloads**: Jobs delegated to the Creeper Node are sealed for its specific ephemeral key. The router uses `KeyMux` to unwrap the job, process the model (via `ModelMux`), and submit the result back to the CAS. Unrelated jobs remain cryptographically opaque.

## Chapter 5: ModelMux Eligibility and Deterministic Routing

(Live)

When an event arrives at the `LitebikeListenerElement`, the Creeper Node uses `ModelMux` to determine eligibility.

1.  **Eligibility**: Does the Creeper Node possess the NUID and capability to execute the reduction?
2.  **Local Execution**: If eligible and constrained (e.g., small Confix parsing), the agent processes it locally on the OpenWrt constraint.
3.  **VPS/Pooled Routing**: If the task exceeds local capacity, the deterministic routing algorithm forward it via Litebike to pooled VPS resources. Local node acts merely as the ingress mesh, not a bottleneck.

## Chapter 6: SSH / Litebike Transport

(Live)

The router handles inbound multi-protocol connections using `rbcursive`-inspired sniffing (`ProtocolDetector`).

*   Connections mapping to SSH or Socks5 are tunneled via `LitebikeListenerElement`.
*   The transport layer treats byte streams as `ReactorAction` sequences, maintaining encrypted boundaries. The router can bridge disjoint networks without ever peeking into the payload.

## Chapter 7: Git and Prebuilt Node Bundle Install

(Mixed / Proposed)

Creeper nodes on OpenWrt often lack JVM access.
*   **Prebuilt JS Bundle**: TrikeShed compiles to JS (`NodeForgeWindowManagerTest` proves Node.js compatibility).
*   **Git Upgrades**: The router maintains a shallow Git clone. Updates are pulled as signed tags.
*   **Deployment**: The deployment script uses native OS primitives (like `wget` or `git`) to fetch the new JS bundle and replaces the executing process.

## Chapter 8: procd Lifecycle, Rollback, and Watchdog

(Proposed)

On OpenWrt, the agent integrates with `procd`.
*   **Watchdog**: A `/api/health` channel (serviced by `JvmKanbanServer` analogs in JS/Native) responds to `procd` polls.
*   **Rollback**: If a new Git bundle fails health checks, `procd` kills the process. A startup shell script detects the exit code and rolls back to the previous CAS bundle CID.

## Chapter 9: CAS, Confix, and Git Object Flow

(Live)

All configuration, job definitions, and artifacts are stored in `CasStore`.
*   **Confix Parsing**: The router parses configurations on-demand without memory inflation (`ConfixReducers`).
*   **Git Integration**: Git objects are inherently content-addressed. The Creeper Node's CAS seamlessly overlays Git packs for configuration syncs without requiring a local Git daemon.

## Chapter 10: Failure Recovery and Direct-Peer Survival

(Live)

Creeper nodes are designed to fail safely.
*   **Recovery**: On reboot, the B+Tree checkpoint (e.g., `CowBPlusTree` via `JobRepositoryRecoveryTest`) is mapped via `MmapCasStore`. Missing pages fail visibly.
*   **Survival**: If the internet uplink dies, local subnets continue discovering the Creeper Node via Litebike and evaluating local CAS jobs.

## Chapter 11: Threat Model and Redaction

(Live)

*   **No Central DB**: An attacker rooting the Creeper Node only gains access to currently active assignment leases.
*   **Redaction**: The "One CID, Five Lenses" rule means raw data can be stripped from the local CAS while leaving structural CIDs intact, effectively redacting history while maintaining cryptographic proof of execution.

## Chapter 12: Operating Runbook

(Proposed)

1.  **Bootstrap**: `git clone <repo> && ./install_creeper.sh`
2.  **Monitor**: Connect to `Litebike` metrics port or view local CAS index.
3.  **Wipe**: `rm -rf /var/lib/creeper/cas` — the node will re-sync required context upon next NUID capability lease.

## Chapter 13: Wire and Control Shapes

(Live)

Data structures over the wire are serialized as CBOR via Confix (`ConfixCborEmitter`).
*   **Control Envelope**: `Join<Nuid, Join<Verb, Payload>>`
*   **Wire Proto**: Framed via standard TLS or SSH transport, deserialized by Reactor codecs into lazy `Series` to prevent OOMs on the router.

## Chapter 14: Implementation Cuts and Executable Acceptance Tests

(Live)

All architectural claims are backed by executable tests:
*   `ProcessReactorEndpointJvmTest`: Verifies constrained execution capabilities.
*   `MuxReactorHudJvmTest`: Verifies live reactor states and deterministic routing.
*   `JobRepositoryRecoveryTest`: Proves zero-loss state recovery from the CAS block store.
*   `ConfixSerializationTest`: Proves multi-format (JSON/CBOR/YAML) resilience on edge devices.
