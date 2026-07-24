1. *Update KanbanEvent and KanbanState in KanbanFSM.kt.*
   - In `src/commonMain/kotlin/borg/trikeshed/userspace/reactor/KanbanFSM.kt`, use `replace_with_git_merge_diff` to add the required properties to the events as specified:
     ```kotlin
     @Serializable
     data class CycleObserved(val cycleMs: Long, val drained: Int, val dispatched: Int, val alive: Int, val available: Int, override val timestampMs: Long) : KanbanEvent()

     @Serializable
     data class PatchDrained(val sessionId: String, val sha: String, val tag: String, override val timestampMs: Long) : KanbanEvent()

     @Serializable
     data class DispatchFired(val sessionId: String, val title: String, override val timestampMs: Long) : KanbanEvent()
     ```
   - In `KanbanState`, add properties to hold these counts. Wait, the TUI is displaying drain/dispatch counts but `KanbanState` doesn't have them? Let's check `KanbanState` again. The prompt says "The KanbanFSM.reducer() must handle these 3 new events. Add cases to the reduce... function". The UI probably expects `cycleCount`, `drainedCount`, `dispatchedCount`, `aliveSlots`, `availableSlots`? Or maybe it just expects the event to be reduced. The issue mentions "The TUI (FlywheelTui) has no way to display drain/dispatch counts because KanbanFSM.current() always returns zeros". This implies the UI reads these fields from `KanbanState`. We need to add fields if they don't exist. Wait! The previous output of `KanbanState` showed:
   ```kotlin
@Serializable
data class KanbanState(
    val activeProviders: List<String> = emptyList(),
    ...
    val taxonomyNodeCount: Int = 0,
    val recentTaxonomyNodes: List<String> = emptyList(),
)
   ```
   We must add fields to `KanbanState`: `cycleCount`, `lastCycleMs`, `drainedCount`, `dispatchedCount`, `aliveSlots`, `availableSlots`. I will add these.
   - Update `KanbanFSM.reduce()` to properly increment these counts.

2. *Update OroborosDaemon.kt.*
   - Fix the mapping to use `KanbanFSM.reduce(KanbanEvent.CycleObserved(...))` directly instead of `.tryEmit()`, and pass all parameters correctly.
     ```kotlin
        driver.subscribe { ev ->
            println("[FLY-EVENT] $ev")
            val now = System.currentTimeMillis()
            when (ev) {
                is borg.trikeshed.jules.FlywheelDriver.FlywheelEvent.Polled ->
                    borg.trikeshed.userspace.reactor.KanbanFSM.reduce(
                        borg.trikeshed.userspace.reactor.KanbanEvent.CycleObserved(0L, 0, 0, ev.alive, ev.available, now)
                    )
                // Wait, FlywheelEvent.Polled has alive and available, but CycleObserved takes cycleMs, drained, dispatched, alive, available.
                // Wait, FlywheelEvent.Drained has sessionId, sha, tag.
                // Let's look closely at FlywheelDriver.kt to see what FlywheelEvent.Polled contains.
                // Wait, the prompt says "CycleObserved(val cycleMs: Long, val drained: Int, val dispatched: Int, val alive: Int, val available: Int)".
                // But FlywheelEvent.Polled only has (val alive: Int, val available: Int).
     ```
     Wait, I need to check `FlywheelDriver.kt` again to see what properties `FlywheelEvent` really has, or maybe `CycleObserved` is supposed to just pass `0` for `cycleMs`, `drained`, and `dispatched` if `Polled` only has `alive` and `available`. Wait, `FlywheelEvent.Polled` is `data class Polled(val alive: Int, val available: Int)`. I will pass `cycleMs = 0`, `drained = 0`, `dispatched = 0`. Or maybe `FlywheelDriver` doesn't emit `cycleMs`? Ah, wait, if we can't extract them from `Polled`, maybe we need to track them or just map what we have. I will follow the exact mapping from the prompt: "when (ev) { is FlywheelEvent.Polled -> KanbanFSM.reduce(KanbanEvent.CycleObserved(...)) ...".

3. *Write the correct test.*
   - "Test file: src/jvmTest/kotlin/borg/trikeshed/daemon/OroborosDaemonKanbanBridgeTest.kt (new)."
   - "Create a FlywheelDriver with a fake JulesRestClient that returns 3 COMPLETED sessions and 2 available slots. Run driver.cycle() once. Assert: KanbanFSM.current().lastEventKind == "CycleObserved"."
   - Wait, `FlywheelDriver` takes `apiKey`. The rest client is instantiated inside. We might need to mock the server or intercept HTTP, or simply reflect into `FlywheelDriver` to swap the client, or maybe `JulesRestClient` uses `RfxHttpServer`? I'll look at the test requirements.

4. *Run tests and verify.*
