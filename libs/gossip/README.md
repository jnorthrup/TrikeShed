# libs/gossip — Concentric P2P Gossip + Work Stealing + Cascading Workflows

P2P concentric subnet gossip system with ephemeral spawning, work-stealing
quorum, and cascading workflows. Locally-constrained by default; wide-area
capable via GK Kademlia artifacts.

## Structure

- `ConcentricP2P.kt` — Ring model (Local→Rack→Region→WideArea→Federation), MemberId, GossipMember, MembershipEvent, GossipMessage types
- `ConcentricGossipEngine.kt` — SWIM-style membership, gossip message propagation, heartbeat / suspect / dead lifecycle, anti-entropy sync
- `EphemeralSpawner.kt` — Three-phase spawn (QUERY→QUORUM→SPAWN), cascading DAG workflow executor, work-stealing across rings, MicroserviceFacade (shard/spawn/consult)

## Integration Points

- Uses `libs/gk-kademlia` for NUID-based routing and XOR-distance ring assignment
- Bridges to `libs/blackboard` for live CRDT state sharing
- Bridges to `libs/torrent` for IPFS pubsub/DHT announcement
- Bridges to `libs/couch` via IpfsGateway for CRDT patch distribution

## Usage

```kotlin
val local = GossipMember(MemberId.random(), ConcentricRing.Local, "127.0.0.1", 7000)
val engine = ConcentricGossipEngine(local, seedNodes = listOf(seed1, seed2))
engine.start()

val spawner = EphemeralSpawner(local, engine, blackboard)
val status = spawner.spawn(SpawnSpec("task-1", payload, ConcentricRing.Region))
```
