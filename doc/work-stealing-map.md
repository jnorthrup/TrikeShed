# Work-Stealing Map — the non-Anthropic dispatch fleet

Date: 2026-08-20. The claim/steal/failover/rotation code that routes work to
non-Anthropic runners. Companion to `midpoint-map.md` (M2 selection lane, M4
transports). Provider neutrality is enforced **by construction** here: the
discovered brain fleet contains zero Anthropic endpoints — admission is
capability + key lease only. Layers are numbered in actual steal order — each
layer exhausts before the next escalates.

## L0 — The claim scheduler (in-process stealing)

`context/nuid/NuidFanoutElement.kt` — the core stealing loop:

- `WorkgroupSlot` (line ~60): per-worker buffered `Channel<Claim>` inbox +
  accepted channel. `tryTake()` is the steal primitive — non-blocking claim
  intake; first taker wins, losers stand down.
- `dispatch()` (line ~214): concentric narrowing — filter registry by
  `canHandle(nuid)` (trait × subnet), sort by `scope.level` ascending, offer
  the claim to every candidate at the innermost level, `pollForWinner` with
  50ms default timeout, then **escalate one level outward** up to
  `escalationBudget` (default 3). The stealing gradient: local workers get
  first refusal, remote workers steal what locals leave unclaimed.
- `claimWinnerCapability()` (line ~266): resolves which capability in the
  winner's `TraitSpace` matched — feeds `LcncFanoutElement`'s reducer pick.
- Idiomatic CCEK throughout: Mutex-guarded registry, cached snapshot,
  lifecycle-gated (`OPEN/ACTIVE` required), channels not callbacks.

### L0+ — Beyond the process: mesh, then overlay (the outward gradient)

When escalation exhausts the in-process registry, the same NUID-addressed
claim continues outward on two shipped transports (joined at midpoint M4):

- **Mesh ring** (`rewire.md` §6, Immediate Cuts #3/#4): discovery is passive
  UPnP/SSDP on `239.255.255.250:1900` (`NT: urn:trikeshed:workspace:1`,
  `USN: uuid:workspace-<nuid>` — each node announces presence + capability
  set); transport is SSH tunnels over the litebike Tls protocol, peers
  authenticated **by NUID** (capability + nonce + subnet). Subnet scopes
  `mesh.worker.<id>` are exactly this ring. `JvmMulticastAdapter` already
  joins the mDNS/SSDP groups; the announcement payload + peer registry is
  Cut #3, the tunnel layer is Cut #4.
- **Overlay ring** (`dht/id/NUID.kt` + `dht/routing/RoutingTable` +
  `util/oroboros/OroborosNetwork.kt`): numeric XOR-metric NUIDs,
  `DhtContentGateway.lookup(contentId) → RoutingTable.getClosest(target, 20)`
  returns `List<Join<NUID, Address>>`; fetch verbs DhtLookup / IpfsFetch /
  StreamFetch / FanoutFetch. `global.relay` scope lands here.
- **Wire form**: `wireproto/ConfixWorker.kt` already CBOR-round-trips the
  NUID-borne `ReactorAction` (capability, nonce, subnet, verb, payload) —
  the claim crosses both rings without re-encoding.
- **The M4 embedding** (`midpoint-map.md`): `Subnet.level ↔ NetMask.bits`,
  capability ↔ trait bits — the seam that makes the three rings one gradient:
  in-process claim → mesh peer → overlay fanout.

## L1 — Model routing (which brain steals the question)

- `modelmux/ModelMux.kt` — `CapabilityRouter.route(models, action, requiredCaps)`:
  pure filter over ACP model cards; `RouteResult = Series<ModelEntry> j AcpAction`
  in precedence order — position 0 is the primary, the tail is the steal queue.
- `modelmux/AutoFallbackRule.kt` — `evaluate(signal, candidatesProjection, …)`:
  on `HTTP_429 | QUOTA_EXHAUSTED`, emits a `FallbackRequestDescriptor`
  (next model + lease + attempt) from a **Cursor projection** of candidates;
  `MAX_RETRIES = 3` hard cap.
- `modelmux/CausalRoutingRule.kt` — quota/latency gate:
  `quotaRemaining > threshold && latencyEstimateMs < bound`, ranked by
  remaining quota descending — steal goes to the fattest quota.
- `modelmux/RoutingStrategy.kt` — sealed strategy hierarchy (Priority /
  Weighted / CostOptimized / RoundRobin / Auto). **Note: all five are
  identity stubs today** — vocabulary ahead of implementation (see
  ccek-consistency-pass.md §4; promoted to `doc/todo.md` intake).
- Observability: `ModelSelectionEvent.ModelSelected` (provider/model/strategy/
  requestId, JSON line onto the forge event stream — the pointcut hook),
  `ProviderHealth` (reactor-only writes), `QuotaTelemetry.recordSnapshot`
  (couch-persisted, guarded by `MuxReactorElement.Key` in context — write API
  is reactor-internal, correctly CCEK).

## L2 — Credential stealing (key rotation: same provider, next key)

`keymux/KeyMux.kt` — `rotate(key)` (line ~404): on 429, cycle to the next
credential in the `persist` source (`keymux.conf`); invalidate all matching
sources; write the next value through. `JulesRestClient` calls this from its
`retryingGet` (429 → rotate → fresh transport per attempt, exponential backoff
on 5xx). `LeaseMetadata` (leasedTo + expiry) + lazy `activeLeases` Series is
the admission ledger — the "key lease" half of the neutrality invariant.
Rotation runs **before** provider surrender: a quota-starved provider gets a
second key before L3 hands the request to a different provider entirely.

## L3 — The brain fleet (provider failover: next provider entirely)

`jules/BrainClient.kt` — `discoverEndpoints()` (line ~256): the whole fleet,
admitted solely by env-var key presence:

| Family | Endpoints |
|--------|-----------|
| NVIDIA NIM (`integrate.api.nvidia.com`) | deepseek-v4-pro, nemotron-super-120b, mistral-large-2, deepseek-v4-flash, nemotron-super-49b, glm-5.2, kimi-k2.6, gpt-oss-120b, inkling, minimax-m3, nemotron-ultra-253b, codestral-22b, nemotron-ultra-550b, laguna — **14 models, one key** |
| OpenRouter | glm-5.2, nemotron-ultra-550b:free |
| Direct | z.ai (glm-5.2), Groq (llama-3.3-70b), DeepSeek, Cerebras, OpenAI (gpt-4o-mini, gated on `sk-` prefix), Perplexity (sonar), xAI (grok-2), Moonshot, MiniMax (M3 + Text-01) |

Failover loop (`chat`, line ~95): outer timeout bounds the whole sequence;
per-endpoint failure logs + advances; `lastGoodModelId` is sticky affinity
(steal-back: a recovered provider only regains work after the current one
fails). Zero Anthropic endpoints in the fleet.

## L4 — Fleet-slot stealing (the flywheel)

`jvmMain/jules/FlywheelDriver.kt` — cycle: poll → answer → drain → induct →
dispatch (line ~288). The stealing economics, verbatim from the phase
ordering comments:
- **ANSWER before DRAIN**: a blocked conversation frees a slot the wheel
  reuses *this cycle* — answering steals capacity back from blockage.
- **INDUCT before DISPATCH**: queue must hold new work before dispatch reads.
- `maxSlots` cap; dispatch only fills capacity freed by drain; active-scope
  WAL-observation gate before dispatch (line ~519).
- `rankByProximity` (causal) orders the intake — board saturation feeding
  RANK is a `doc/todo.md` intake item (UnifiedBoard.bottleneck()).
- Terminal-session slot release: `JulesRestClient.deleteSession` wiring is a
  `doc/todo.md` intake item — un-reaped sessions currently squat fleet slots.

## Composition (one steal, end to end — doc order = steal order)

```
work arrives (Nuid: capability j nonce j subnet)
  → L0  NuidFanoutElement.dispatch — innermost workgroup steals the claim,
        escalation outward if unclaimed
  → L0+ mesh peer (SSH/UPnP, NUID-authenticated) → DHT/Oroboros overlay
        (RoutingTable.getClosest → FanoutFetch), CBOR wire via ConfixWorker
  → L1  ModelMux route — capability-ranked brain queue;
        429/quota → AutoFallbackRule hands the request to the next model
  → L2  KeyMux.rotate — same provider, next credential, before giving up
  → L3  BrainClient failover — next provider entirely; sticky lastGoodModelId
  → L4  FlywheelDriver — slot accounting; answer-first steals capacity back
```

Every hand-off is provider-blind: trait match, quota, latency, key presence.
No layer inspects provider identity to privilege it.
