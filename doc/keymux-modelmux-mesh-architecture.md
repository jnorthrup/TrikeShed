# KeyMux + ModelMux Mesh Architecture

> Assignment-bound credential leasing and capability-based model routing across
> local and pooled VPS resources, coordinated by the TrikeShed reactor.

## 1. Architectural position

The reactor is the control plane. KeyMux and ModelMux are reactor services, not
independent schedulers and not competing sources of truth.

- **Reactor** owns assignments, worker lifecycle, leases, provider state, and
  observable operational events.
- **KeyMux** resolves authority and issues the least credential needed by one
  assignment for a bounded time.
- **ModelMux** selects a model execution route from local and mesh-advertised
  resources.
- **Forge agents** execute assignments and return results and measurements.
- **Forge/Kanban** projects committed and live reactor state. It does not own
  credentials, leases, provider selection, or worker state.
- **CAS/Confix** carries durable assignment and result identity. Live flows are
  observation and coordination surfaces, not durability.

The system remains local-first. VPS capacity is an additive execution pool, not
the owner of the workspace.

## 2. Deployment topology

```text
                         Pooled execution mesh

      Local workstation                 VPS resource pool
  ┌────────────────────────┐     ┌───────────────────────────┐
  │ Forge / Kanban         │     │ VPS A: local model       │
  │ Reactor + Job Nexus    │◄───►│ VPS B: cloud providers   │
  │ KeyMux + ModelMux      │     │ VPS C: tools / storage   │
  │ CAS / Confix state     │     │ VPS N: mixed capability  │
  └────────────┬───────────┘     └─────────────┬─────────────┘
               │                                │
               └──────── encrypted mesh ────────┘
                                ▲
                                │
                    ┌───────────┴───────────┐
                    │ WRT Linux sentinel    │
                    │ upstream Forge agent  │
                    │ capability-limited   │
                    └───────────────────────┘
```

The WRT sentinel is a mesh participant and Forge agent. It is upstream and
well-positioned to maintain connectivity, discovery, and bounded background
work, but it is not a mandatory relay for every request and it is not a vault.
It accepts only assignments matching its advertised resources and policy.

## 3. Mesh substrate

The existing mesh contract remains authoritative:

- Passive peer discovery through mDNS and UPnP/SSDP.
- One litebike bind point per node.
- Encrypted peer transport through the existing TLS/SSH tunnel line.
- NUID authorization by capability, nonce, and concentric subnet.
- Confix documents as the portable control and replication payload.
- CAS content IDs as durable object identity.

A peer advertisement contains no secret material. It announces:

```text
PeerAdvertisement =
    peer NUID
    + reachable endpoint
    + capability set
    + model cards
    + capacity/load envelope
    + lease-broker reachability
    + advertisement expiry
```

Advertisements are soft state. They expire unless refreshed. Assignment and
result state remains durable through the Job Nexus and CAS.

## 4. Assignment execution flow

```text
1. Assignment is committed to the reactor.
2. Reactor derives required worker, tool, and model capabilities.
3. ModelMux ranks eligible local and mesh execution routes.
4. Selected route declares the provider authority it requires.
5. KeyMux requests an assignment-bound credential lease.
6. NUID policy authorizes assignment, holder, capability, and subnet.
7. Lease broker delivers an opaque handle or sealed credential payload only
   to the selected holder over its encrypted session.
8. Forge agent executes under structured assignment scope.
9. Result, usage, latency, errors, and provider health return to the reactor.
10. Lease is released in `finally`; TTL reclaims it after holder failure.
11. Committed result rebuilds Forge/Kanban projections.
```

Routing and leasing form one transaction boundary: a route is not dispatchable
until its required authority has been leased. A lease is not issued without a
specific committed assignment and selected holder.

## 5. KeyMux: assignment-bound authority

### 5.1 Responsibility

KeyMux answers:

> What authority may this assignment borrow, on which node, for how long, and
> under which provider/model/tool scope?

KeyMux does not choose the best model and does not replicate the vault. It
combines ordered credential sources with reactor-owned lease state and policy.
The current source precedence remains useful: environment, persisted local
source, API source, and reactor source are all `KeySource` bindings.

### 5.2 Credential inventory

A secure arena may hold credentials with different operational roles:

- **Primary** — normal provider access.
- **Guest** — deliberately restricted authority for an external or temporary
  worker.
- **Expiring** — credential whose own validity ends at a fixed time.
- **Backup** — normally dormant authority enabled after policy-defined failure.
- **Coordination** — authority for mesh control operations, never a generic
  model-provider credential.

These are metadata and policy on credential records, not separate KeyMux
instances and not builder modes.

```kotlin
enum class CredentialRole {
    PRIMARY,
    GUEST,
    EXPIRING,
    BACKUP,
    COORDINATION,
}

data class CredentialDescriptor(
    val keyId: String,
    val provider: String,
    val role: CredentialRole,
    val capabilities: Series<String>,
    val permittedSubnets: Series<Subnet>,
    val expiresAtMs: Long?,
    val status: MuxKeyStatus,
)
```

Descriptors are safe to advertise or project. Secret values are not.

### 5.3 Lease contract

```kotlin
data class CredentialLeaseRequest(
    val assignment: Nuid,
    val holder: Nuid,
    val provider: String,
    val modelId: String?,
    val requiredCapabilities: Series<String>,
    val requestedTtlMs: Long,
)

data class CredentialLease(
    val leaseId: String,
    val keyId: String,
    val assignment: Nuid,
    val holder: Nuid,
    val provider: String,
    val modelId: String?,
    val issuedAtMs: Long,
    val expiresAtMs: Long,
    val authority: CredentialAuthority,
)

sealed class CredentialAuthority {
    data class OpaqueHandle(val handle: String) : CredentialAuthority()
    data class SealedPayload(val ciphertext: ByteSeries) : CredentialAuthority()
}
```

Prefer `OpaqueHandle`: the provider call is made by a trusted broker or sidecar
and the worker never receives reusable secret text. Use `SealedPayload` only
when the remote worker must call the provider directly; seal it to the holder's
session identity and assignment scope.

### 5.4 Lease invariants

- A lease names exactly one assignment and one holder.
- Lease TTL cannot exceed credential expiry or assignment deadline.
- Lease capability cannot exceed the credential descriptor.
- Lease subnet must be contained by the credential policy.
- Release is idempotent.
- Expiration and revocation prevent further broker use immediately.
- Worker completion always attempts release in `finally`.
- Crashed or disconnected workers are recovered by TTL.
- Secret values never appear in Forge state, Kanban events, logs, model cards,
  peer advertisements, cache keys, or operational history.
- Rotation changes the credential behind a stable descriptor; assignment code
  does not need to learn the new secret.

### 5.5 Existing implementation seam

`MuxReactorElement` already owns:

- credential records,
- `leasedTo`, lease start, and lease expiry,
- provider concurrency limits,
- explicit release,
- expired-lease reclamation,
- immutable state snapshots and Kanban events.

The missing operation is requirement-driven acquisition. The current `tick()`
leases the next available key while spawning a synthetic reactor agent. Replace
that implicit coupling with an explicit reactor command:

```kotlin
suspend fun leaseCredential(request: CredentialLeaseRequest): CredentialLeaseResult
```

The reactor validates policy, selects an eligible descriptor, records the
lease, then allows dispatch. `tick()` may still drive scheduling, but it must
not invent the holder or select an unrelated key independently of the
assignment.

## 6. ModelMux: route selection over pooled resources

### 6.1 Responsibility

ModelMux answers:

> Which eligible execution route best satisfies this assignment now?

A route is the composition of a peer, endpoint, model card, required authority,
and current operational envelope.

```kotlin
data class ModelRoute(
    val routeId: String,
    val peer: Nuid,
    val endpoint: ReactorEndpoint,
    val model: AcpModelCard,
    val provider: String,
    val requiredKeyCapabilities: Series<String>,
    val locality: RouteLocality,
    val observations: RouteObservations,
)

enum class RouteLocality { LOCAL, LAN, VPS, GLOBAL_RELAY }

data class RouteObservations(
    val inFlight: Int,
    val capacity: Int,
    val latencyEwmaMs: Double,
    val failureEwma: Double,
    val estimatedCostPerMillionTokens: Double?,
    val backoffUntilMs: Long?,
    val observedAtMs: Long,
)
```

### 6.2 Eligibility before ranking

A route is eligible only when:

- its peer advertisement is fresh,
- its NUID subnet contains the assignment route,
- its model card satisfies the required action and capabilities,
- its node has available capacity,
- its provider is not benched or in backoff,
- KeyMux can issue the required assignment-bound authority,
- assignment policy permits its locality and cost class.

No score may make an ineligible route eligible.

### 6.3 Ranking

After eligibility, use a deterministic lexicographic rank rather than a vague
single "best" score:

1. explicit assignment/provider pin,
2. local or already-resident model,
3. reusable valid cache hit,
4. healthy route with capacity,
5. lower failure/backoff pressure,
6. lower latency class,
7. lower estimated cost,
8. stable route ID tie-break.

Weights may later refine ranking, but deterministic ordering makes routing
explainable and reproducible. Every decision emits a redacted `RouteDecision`
with candidates, rejection reasons, selected route, and observation timestamp.

### 6.4 Cache role

Cache state informs routing but does not become provider authority.

- A valid response-cache hit may satisfy the assignment without a new lease.
- A model-metadata cache hit avoids rediscovery.
- A cache miss is an observation, not automatically a provider switch.
- Cache keys contain request identity and model/provider identity, never secret
  values or lease handles.
- Cached responses remain subject to assignment privacy and TTL policy.

### 6.5 Failure and retry

Retries are assignment attempts, not hidden loops inside the HTTP client.

- Record the failed route and error class.
- Release or revoke the failed attempt's lease.
- Recompute eligibility from current observations.
- Select a different route when policy allows.
- Preserve one assignment identity and append attempt facts to its causal log.
- Never submit the same non-idempotent tool action twice without an explicit
  idempotency key.

## 7. WRT Linux sentinel

The sentinel is a small, continuously available Forge agent on the upstream
router. Its useful capabilities are expected to be connectivity-oriented:

- peer discovery and advertisement refresh,
- encrypted tunnel maintenance,
- reachability and latency observation,
- low-cost queue watching,
- bounded assignment dispatch or forwarding,
- lease renewal/release on behalf of work it owns,
- optional lightweight provider calls when hardware and policy allow.

The sentinel must not:

- persist the entire secure arena,
- broadcast or log credential values,
- become the sole mesh coordinator,
- inspect unrelated network payloads,
- accept work outside its NUID capabilities,
- run memory-heavy local models unless explicitly advertised,
- become a required hop between healthy peers.

If the sentinel disappears, existing peer sessions and assignments continue.
Only sentinel-owned leases and connectivity duties enter TTL recovery.

## 8. Security model

### Control plane

- NUID identifies bearer authority and concentric subnet.
- Assignment identity binds dispatch, lease, attempt, and result.
- Peer advertisements are signed or authenticated by the mesh session.
- Replay protection uses assignment identity, lease identity, expiry, and nonce.

### Data plane

- Mesh transport is encrypted.
- Secret delivery is point-to-point and holder-bound.
- Provider responses follow assignment data policy.
- CAS stores encrypted sensitive payloads or redacted durable facts, never raw
  reusable credentials.

### Observability

Safe telemetry includes:

- key ID or descriptor ID,
- provider and model ID,
- holder and assignment NUID,
- lease state and expiry,
- route decision and rejection reason,
- latency, token usage, cache state, and error class.

Unsafe telemetry includes:

- credential values,
- authorization headers,
- opaque lease handles,
- sealed payload bytes,
- full prompts or responses unless assignment policy explicitly permits them.

## 9. State ownership

| State | Canonical owner | Persistence |
|---|---|---|
| Assignment lifecycle | Job Nexus / reactor | WAL + CAS/Confix |
| Credential value | Secure arena | arena-specific encrypted storage |
| Credential descriptor | KeyMux inventory | encrypted config / reactor bootstrap |
| Active lease | Reactor lease ledger | durable event or recoverable TTL state |
| Model and node advertisement | Mesh peer registry | soft state with expiry |
| Route observation | Reactor / ModelMux | bounded operational history |
| Model response cache | Reactor model cache | configured local persistence |
| Forge/Kanban view | Projection only | rebuilt from canonical state |

## 10. Delivery cuts

### T-MESH-1 — Assignment-bound credential leasing

- Add `CredentialDescriptor`, `CredentialLeaseRequest`, and
  `CredentialLeaseResult` in commonMain.
- Add explicit requirement-driven lease acquisition to `MuxReactorElement`.
- Preserve release and TTL reclamation already present.
- Change ModelMux call paths to acquire a lease before dispatch and release it
  in `finally`.
- Verify provider/model mismatch is rejected and no secret appears in state or
  events.

### T-MESH-2 — Mesh resource advertisements and ModelMux routes

- Define expiring `PeerAdvertisement` and `ModelRoute` commonMain algebra.
- Project local and VPS model cards into one route series.
- Implement eligibility and deterministic ranking with rejection reasons.
- Verify capability, subnet, capacity, backoff, cache, latency, and cost order.
- Keep discovery and transport behind existing reactor/litebike endpoints.

### T-MESH-3 — WRT sentinel deployment adapter

- Compose the existing litebike bind point, peer registry, reactor endpoint,
  KeyMux lease client, and ModelMux route executor for WRT Linux.
- Advertise only actual sentinel capabilities.
- Prove that direct peer execution survives sentinel shutdown.
- Prove sentinel-owned assignment failure releases by `finally` or TTL.

## 11. Acceptance evidence

1. One committed assignment selects an eligible VPS route and obtains exactly
   one matching credential lease.
2. A route lacking authority is rejected before dispatch.
3. Lease state is visible by descriptor ID, assignment, holder, and expiry;
   secret values are absent from every projection and log.
4. Successful completion releases the lease.
5. Agent termination causes TTL reclamation and makes the credential available.
6. Provider backoff or VPS loss causes a new route decision with a recorded
   rejection reason.
7. A valid response-cache hit completes without issuing a provider lease.
8. Two peers continue direct execution after the WRT sentinel is stopped.
9. All mesh control payloads round-trip through Confix and preserve NUID and
   assignment identity.
10. Existing KeyMux, ModelMux, and reactor tests remain green.

## 12. Non-goals

- No peer-to-peer replication of credential values.
- No new consensus protocol.
- No universal central gateway.
- No new HTTP or mesh framework.
- No cloud-owned workspace state.
- No secret material in Forge/Kanban.
- No replacement of the existing reactor, litebike, NUID, CAS, or Confix
  foundations.
