# libs/blackboard — Live CRDT Blackboard (MUD/MOO Production System)

A shared, live-updated blackboard built on CRDT semantics, designed as a
MUD/MOO-style production system for multi-agent coordination. Agents read/write
facts, subscribe to pattern-matched updates, and trigger cascading workflows.

## Structure

- `Blackboard.kt` — Blackboard, Room, Fact (CRDT), VectorClock, Subscription, Trigger, Agent

## Integration

- Facts are replicated via the gossip engine (`libs/gossip`) across concentric rings
- Triggers can fire EphemeralSpawner workflow stages
- Each room has a CRDT vector clock for conflict resolution
- Agents have MUD-style personas with rooms, capabilities, and metadata

## Usage

```kotlin
val agent = Agent(MemberId.random(), "architect", setOf("planning", "design"))
val bb = Blackboard(agent)
bb.joinRoom("planning", ConcentricRing.Region)
bb.write("planning", "goal", "deliver feature X".encodeToByteArray())
bb.subscribe("planning", ByPrefix("goal")) { fact -> handleNewGoal(fact) }
bb.addTrigger("planning", ByTag("critical"), TriggerAction.Coroutine { fact, bb ->
    alert("Critical fact: ${fact.key}")
})
```
