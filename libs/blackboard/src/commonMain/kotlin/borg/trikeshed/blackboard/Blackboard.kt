package borg.trikeshed.blackboard

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** Ring level for replication scope (0=local, 1=rack, 2=region, 3=wide-area, 4=federation). */
typealias RingLevel = Int

/** Unique identifier for an agent or node. */
data class AgentId(val bytes: ByteArray) {
    override fun equals(other: Any?) = other is AgentId && bytes.contentEquals(other.bytes)
    override fun hashCode() = bytes.contentHashCode()
    override fun toString() = bytes.joinToString("") { "%02x".format(it) }
    companion object {
        fun random() = kotlin.random.Random.Default.nextBytes(20)
    }
}

/** Vector clock for CRDT conflict resolution. */
data class VectorClock(val clocks: Map<AgentId, Long> = emptyMap()) {
    fun increment(author: AgentId) = VectorClock(clocks + (author to (clocks[author] ?: 0) + 1))
    fun merge(other: VectorClock) = VectorClock(
        buildMap(clocks.size + other.clocks.size) {
            putAll(clocks)
            other.clocks.forEach { (k, v) -> this[k] = maxOf(this[k] ?: 0, v) }
        }
    )
    fun isAfter(other: VectorClock) = clocks.all { (k, v) -> v >= (other.clocks[k] ?: 0) } && clocks != other.clocks
}

/** A fact on the blackboard — the fundamental unit of shared state. */
data class Fact(
    val key: String,
    val value: ByteArray,
    val version: Long,
    val author: AgentId,
    val clock: VectorClock,
    val ttlMs: Long = Long.MAX_VALUE,
    val room: String,
    val tags: Set<String> = emptySet(),
    val createdAt: Long = System.currentTimeMillis(),
) {
    override fun equals(other: Any?) = other is Fact && key == other.key && room == other.room && version == other.version
    override fun hashCode() = 31 * (31 * key.hashCode() + room.hashCode()) + version.hashCode()
}

/** Pattern-matched subscription for live blackboard updates. */
data class Subscription(
    val room: String,
    val pattern: FactPattern,
    val callback: suspend (Fact) -> Unit,
    val rings: Set<RingLevel> = setOf(0, 1),
) {
    fun matches(fact: Fact) = pattern.matches(fact)
}

/** A predicate for matching facts. */
sealed class FactPattern {
    data class ByKey(val exact: String) : FactPattern() { override fun matches(f: Fact) = f.key == exact }
    data class ByPrefix(val prefix: String) : FactPattern() { override fun matches(f: Fact) = f.key.startsWith(prefix) }
    data class ByTag(val tag: String) : FactPattern() { override fun matches(f: Fact) = tag in f.tags }
    data class ByAuthor(val author: AgentId) : FactPattern() { override fun matches(f: Fact) = f.author == author }
    object All : FactPattern() { override fun matches(f: Fact) = true }
    abstract fun matches(fact: Fact): Boolean
}

/** Trigger: condition → action, cascading workflow. */
data class Trigger(
    val room: String,
    val pattern: FactPattern,
    val action: TriggerAction,
    val once: Boolean = false,
)

sealed class TriggerAction {
    /** Run a local coroutine when the trigger fires. */
    data class Coroutine(val handler: suspend (Fact, Blackboard) -> Unit) : TriggerAction()
    /** Start a cascading workflow stage. */
    data class WorkflowStage(val stageId: String, val params: Map<String, String> = emptyMap()) : TriggerAction()
    /** Propagate the fact to another room. */
    data class Propagate(val targetRoom: String, val transform: (Fact) -> Fact = { it }) : TriggerAction()
}

/** MUD/MOO agent — a persona that interacts with the blackboard. */
data class Agent(
    val id: AgentId,
    val name: String,
    val capabilities: Set<String> = emptySet(),
    var currentRoom: String = "lobby",
    var metadata: Map<String, String> = emptyMap(),
)

/** A named namespace (room) on the blackboard. */
class Room(
    val name: String,
    val replicationRing: RingLevel = 2,
) {
    private val facts = linkedMapOf<String, Fact>()
    private val subscriptions = arrayListOf<Subscription>()
    private val triggers = arrayListOf<Trigger>()
    private val writeLock = Semaphore(1)
    private val roomScope = CoroutineScope(SupervisorJob() + CoroutineName("BlackboardRoom-$name"))

    /** Write a fact; last-writer-wins with vector clock resolution. */
    suspend fun write(newFact: Fact) {
        writeLock.withPermit {
            val existing = facts[newFact.key]
            if (existing == null || newFact.clock.isAfter(existing.clock) ||
                (newFact.clock == existing.clock && newFact.author.bytes.contentHashCode() > existing.author.bytes.contentHashCode())) {
                facts[newFact.key] = newFact
                // Notify subscriptions
                for (sub in subscriptions) {
                    if (sub.matches(newFact)) {
                        roomScope.launch { sub.callback(newFact) }
                    }
                }
                // Fire triggers
                for (trigger in triggers) {
                    if (trigger.pattern.matches(newFact)) {
                        fireTrigger(trigger, newFact)
                        if (trigger.once) triggers.remove(trigger)
                    }
                }
            }
        }
    }

    /** Read a fact by key. */
    fun read(key: String): Fact? = facts[key]

    /** List all facts matching the pattern. */
    fun query(pattern: FactPattern): List<Fact> = facts.values.filter { pattern.matches(it) }

    /** Subscribe to live updates matching the pattern. */
    fun subscribe(sub: Subscription) { subscriptions.add(sub) }

    /** Add a trigger. */
    fun addTrigger(trigger: Trigger) { triggers.add(trigger) }

    /** Remove a trigger. */
    fun removeTrigger(pattern: FactPattern) { triggers.removeIf { it.pattern == pattern } }

    /** Room stats. */
    fun stats() = Triple(facts.size, subscriptions.size, triggers.size)

    private fun fireTrigger(trigger: Trigger, fact: Fact) = when (trigger.action) {
        is TriggerAction.Coroutine -> roomScope.launch { trigger.action.handler(fact, blackboardRef) }
        is TriggerAction.WorkflowStage -> { /* delegated to workflow executor */ }
        is TriggerAction.Propagate -> { /* propagated to target room */ }
    }

    companion object {
        lateinit var blackboardRef: Blackboard
    }
}

/**
 * The Blackboard — a live-updated, CRDT-backed, gossip-distributed
 * shared state board for multi-agent coordination.
 *
 * Usage:
 *   val bb = Blackboard(agent)
 *   bb.joinRoom("planning")
 *   bb.write("planning", "goal", "deliver feature X".encodeToByteArray())
 *   bb.subscribe("planning", ByPrefix("goal")) { fact -> handle(fact) }
 *   bb.query("planning", All)
 */
class Blackboard(
    val agent: Agent,
) {
    private val rooms = linkedMapOf<String, Room>()
    private val roomChannels = linkedMapOf<String, Channel<Fact>>()

    init { Room.blackboardRef = this }

    /** Join (or create) a room on the blackboard. */
    fun joinRoom(name: String, replicationRing: RingLevel = 2): Room {
        return rooms.getOrPut(name) { Room(name, replicationRing).also { roomChannels.putIfAbsent(name, Channel(Channel.UNLIMITED)) } }
    }

    /** Write a fact to a room. */
    suspend fun write(roomName: String, key: String, value: ByteArray, ttlMs: Long = Long.MAX_VALUE, tags: Set<String> = emptySet()) {
        val room = rooms[roomName] ?: error("Room '$roomName' not found. Call joinRoom first.")
        val fact = Fact(
            key = key, value = value, version = 0, author = agent.id,
            clock = VectorClock(mapOf(agent.id to 1)), ttlMs = ttlMs,
            room = roomName, tags = tags,
        )
        room.write(fact)
        roomChannels[roomName]?.send(fact)
    }

    /** Read a fact from a room. */
    fun read(roomName: String, key: String): Fact? = rooms[roomName]?.read(key)

    /** Query facts in a room. */
    fun query(roomName: String, pattern: FactPattern): List<Fact> = rooms[roomName]?.query(pattern) ?: emptyList()

    /** Subscribe to live updates in a room. */
    fun subscribe(roomName: String, pattern: FactPattern, rings: Set<RingLevel> = setOf(0), callback: suspend (Fact) -> Unit) {
        val room = rooms[roomName] ?: error("Room '$roomName' not found")
        room.subscribe(Subscription(roomName, pattern, callback, rings))
    }

    /** Add a trigger to a room. */
    fun addTrigger(roomName: String, pattern: FactPattern, action: TriggerAction, once: Boolean = false) {
        val room = rooms[roomName] ?: error("Room '$roomName' not found")
        room.addTrigger(Trigger(roomName, pattern, action, once))
    }

    /** Get a room. */
    fun room(name: String) = rooms[name]

    /** List all rooms. */
    fun rooms() = rooms.keys.toList()
}
