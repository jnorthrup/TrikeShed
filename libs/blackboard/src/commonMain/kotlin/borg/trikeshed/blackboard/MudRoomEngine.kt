package borg.trikeshed.blackboard

import kotlinx.coroutines.*

/** A named namespace (room) on the blackboard. Pure MUD/MOO — no gossip deps. */
class Room(
    val name: String,
) {
    private val facts = mutableMapOf<String, Fact>()
    private val subscriptions = mutableListOf<Subscription>()
    private val triggers = mutableListOf<Trigger>()
    private val writeLock = Semaphore(1)
    private val roomScope = CoroutineScope(SupervisorJob() + CoroutineName("BlackboardRoom-$name"))

    suspend fun write(fact: Fact) {
        writeLock.withPermit {
            val existing = facts[fact.key]
            if (existing == null || fact.clock.isAfter(existing.clock) ||
                (fact.clock == existing.clock && fact.author.bytes.contentHashCode() > existing.author.bytes.contentHashCode())) {
                facts[fact.key] = fact
                for (sub in subscriptions) {
                    if (sub.pattern.matches(fact)) roomScope.launch { sub.callback(fact) }
                }
                for (trigger in triggers) {
                    if (trigger.pattern.matches(fact)) {
                        // Fire trigger in scope
                        roomScope.launch {
                            try {
                                when (trigger.action) {
                                    is TriggerAction.Coroutine -> trigger.action.handler(fact, blackboardRef!!)
                                    is TriggerAction.WorkflowStage -> Unit // delegated
                                    is TriggerAction.Propagate -> blackboardRef?.write(trigger.action.targetRoom, fact.key, fact.value)
                                }
                            } finally {
                                if (trigger.once) triggers.remove(trigger)
                            }
                        }
                    }
                }
            }
        }
    }

    fun read(key: String): Fact? = facts[key]
    fun query(pattern: FactPattern): List<Fact> = facts.values.filter { pattern.matches(it) }
    fun subscribe(sub: Subscription) { subscriptions.add(sub) }
    fun addTrigger(trigger: Trigger) { triggers.add(trigger) }
    fun removeTrigger(pattern: FactPattern) { triggers.removeIf { it.pattern == pattern } }
    fun stats() = Triple(facts.size, subscriptions.size, triggers.size)
    fun close() { roomScope.cancel() }

    companion object {
        var blackboardRef: Blackboard? = null
    }
}

/** MUD/MOO-style command engine — lives in blackboard to avoid gossip cycle. */
enum class CommandVerb { Look, Say, Go, Drop, Get, Tell, Emote, Examine, Create, Status, Who }

data class MudCommand(
    val verb: CommandVerb,
    val target: String? = null,
    val arguments: String = "",
    val room: String = "lobby",
)

data class CommandResult(
    val text: String,
    val broadcast: Boolean = true,
)

class MUDRoomEngine(
    val agent: Agent,
    val blackboard: Blackboard,
) {
    init { agent.currentRoom = "lobby"; blackboard.joinRoom("lobby") }

    fun execute(cmd: MudCommand): CommandResult = when (cmd.verb) {
        CommandVerb.Look -> {
            val room = cmd.target ?: agent.currentRoom
            blackboard.joinRoom(room)
            val facts = blackboard.query(room, FactPattern.All)
            CommandResult("Room: $room\n${facts.joinToString("\n  ") { "${it.key}: ${it.value.take(80).decodeToString()}" }.takeIf { it.isNotEmpty() } ?: "  Nothing here."}")
        }
        CommandVerb.Say -> {
            blackboard.writeSuspend(roomName = agent.currentRoom, key = "say",
                value = "${agent.name}: ${cmd.arguments}".encodeToByteArray(), tags = setOf("say"))
            CommandResult("You say: ${cmd.arguments}")
        }
        CommandVerb.Go -> {
            val t = cmd.target ?: return CommandResult("Go where?")
            agent.currentRoom = t; blackboard.joinRoom(t)
            CommandResult("You enter $t")
        }
        CommandVerb.Drop -> {
            val key = cmd.target ?: return CommandResult("Drop what?")
            blackboard.writeSuspend(roomName = agent.currentRoom, key = key,
                value = cmd.arguments.encodeToByteArray(), tags = setOf("dropped", agent.name))
            CommandResult("You drop $key: ${cmd.arguments}")
        }
        CommandVerb.Get -> {
            val key = cmd.target ?: return CommandResult("Get what?")
            (blackboard.read(agent.currentRoom, key)?.let { CommandResult("You pick up $key: ${it.value.decodeToString().take(200)}") })
                ?: CommandResult("There is no $key here.")
        }
        CommandVerb.Tell -> {
            val who = cmd.target ?: return CommandResult("Tell who?")
            blackboard.writeSuspend(roomName = "tell-${agent.id}-${who}", key = "message",
                value = "${agent.name}: ${cmd.arguments}".encodeToByteArray(), tags = setOf("tell", "pm"))
            CommandResult("You tell $who: ${cmd.arguments}")
        }
        CommandVerb.Emote -> {
            blackboard.writeSuspend(roomName = agent.currentRoom, key = "emote",
                value = "${agent.name} ${cmd.arguments}".encodeToByteArray(), tags = setOf("emote", agent.name))
            CommandResult("${agent.name} ${cmd.arguments}")
        }
        CommandVerb.Examine -> {
            val key = cmd.target ?: return CommandResult("Examine what?")
            (blackboard.read(agent.currentRoom, key)?.let { f ->
                CommandResult("$key:\n  value: ${f.value.decodeToString().take(200)}\n  author: ${f.author}\n  version: ${f.version}\n  tags: ${f.tags.joinToString(", ")}")
            }) ?: CommandResult("You don't see that here.")
        }
        CommandVerb.Create -> {
            val name = cmd.target ?: return CommandResult("Create what?")
            blackboard.joinRoom(name); CommandResult("Room '$name' created.")
        }
        CommandVerb.Status -> {
            val stats = blackboard.rooms().map { name ->
                blackboard.room(name)?.let { r -> val (f, s, t) = r.stats(); "  $name: $f facts, $s subs, $t triggers" } ?: "  $name: unknown"
            }.joinToString("\n")
            CommandResult("Agent: ${agent.name}\nID: ${agent.id}\nRoom: ${agent.currentRoom}\nCapabilities: ${agent.capabilities.joinToString(", ")}\nRooms:\n${stats.takeIf { it.isNotEmpty() } ?: "  none"}")
        }
        CommandVerb.Who -> CommandResult("You are alone in ${agent.currentRoom} (gossip integration in gossip layer).")
    }
}
