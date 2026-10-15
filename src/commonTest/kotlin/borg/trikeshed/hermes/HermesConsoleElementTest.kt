package borg.trikeshed.hermes

import borg.trikeshed.lcnc.media.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class HermesConsoleElementTest {
    private fun command(id: String) = ManualMediaInput(
        LcncUserSignal(id, MediaPatchPanelId("hermes/xterm"), 1, 0, LcncSignalLane.MANUAL, "command", id), id,
    )

    @Test
    fun drainJoinsOpenAndQueuedTurnsInOrder() = runTest {
        val release = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        val failures = mutableListOf<Throwable>()
        lateinit var element: HermesConsoleElement
        element = HermesConsoleElement(this,
            open = {
                assertSame(element, currentCoroutineContext()[HermesConsoleElement])
                events += "opening"
                release.await()
                events += "opened"
            },
            execute = { events += it.input },
            failed = { _, failure -> failures += failure }, capacity = 2,
        )
        try {
            assertTrue(element.open())
            runCurrent()
            assertTrue(element.submit(command("first")))
            assertTrue(element.submit(command("second")))
            assertFalse(element.submit(command("overflow")))
            val drained = async { element.drain() }
            runCurrent()
            assertFalse(element.accepting)
            assertFalse(element.open())
            assertFalse(element.submit(command("late")))
            assertFalse(drained.isCompleted)
            assertEquals(listOf("opening"), events)
            release.complete(Unit)
            drained.await()
            assertEquals(listOf("opening", "opened", "first", "second"), events)
            assertTrue(failures.isEmpty())
            assertTrue(currentCoroutineContext().isActive)
        } finally {
            release.complete(Unit)
            element.drain()
        }
    }

    @Test
    fun cancelledDrainStillJoinsAcceptedCommands() = runTest {
        val release = CompletableDeferred<Unit>()
        val completed = mutableListOf<String>()
        val failures = mutableListOf<Throwable>()
        val element = HermesConsoleElement(this, open = {}, execute = {
            if (it.input == "first") release.await()
            completed += it.input
        }, failed = { _, failure -> failures += failure })
        try {
            assertTrue(element.submit(command("first")))
            runCurrent()
            assertTrue(element.submit(command("second")))
            val draining = async { element.drain() }
            runCurrent()
            draining.cancel()
            runCurrent()
            assertFalse(draining.isCompleted, "cancelling a drain caller must not abandon its join")
            assertFalse(element.accepting)
            release.complete(Unit)
            draining.join()
            assertEquals(listOf("first", "second"), completed)
            assertTrue(failures.isEmpty())
        } finally { release.complete(Unit); element.drain() }
    }

    @Test
    fun requiresAnActiveOwnerJob() = runTest {
        var escaped: HermesConsoleElement? = null
        val ownerless = object : CoroutineScope { override val coroutineContext = this@runTest.coroutineContext.minusKey(Job) }
        try {
            assertFailsWith<IllegalArgumentException> {
                escaped = HermesConsoleElement(ownerless, open = {}, execute = {}, failed = { _, _ -> })
            }
            val owner = Job().also { it.cancel() }
            assertFailsWith<CancellationException> {
                escaped = HermesConsoleElement(CoroutineScope(coroutineContext + owner), open = {}, execute = {}, failed = { _, _ -> })
            }
            assertTrue(owner.children.none())
        } finally { escaped?.drain() }
    }

    @Test
    fun failedTurnDoesNotDiscardFollowingTurns() = runTest {
        val completed = mutableListOf<String>()
        val failed = mutableListOf<String?>()
        val element = HermesConsoleElement(this,
            open = { error("boot failed") },
            execute = { if (it.input == "bad") error("turn failed") else completed += it.input },
            failed = { command, _ -> failed += command?.input },
        )
        assertTrue(element.open())
        assertTrue(element.submit(command("bad")))
        assertTrue(element.submit(command("next")))
        element.drain()
        assertEquals(listOf(null, "bad"), failed)
        assertEquals(listOf("next"), completed)
        assertFalse(element.accepting)
        element.drain()
    }

    @Test
    fun resetWaitsForTheAdmittedTurnAndDrainJoinsTheFollowingCommand() = runTest {
        val turnRelease = CompletableDeferred<Unit>()
        val resetRelease = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        val failures = mutableListOf<Throwable>()
        val element = HermesConsoleElement(this, open = {}, execute = {
            events += "${it.input}:started"
            if (it.input == "first") turnRelease.await()
            events += "${it.input}:finished"
        }, reset = { resume ->
            events += "reset:$resume:started"
            resetRelease.await()
            events += "reset:$resume:finished"
        }, failed = { _, failure -> failures += failure }, capacity = 2)
        try {
            assertTrue(element.submit(command("first")))
            runCurrent()
            assertTrue(element.reset(resume = false))
            assertTrue(element.submit(command("next")))
            assertFalse(element.reset(), "Reset must share the bounded command lane")
            val drained = async { element.drain() }
            runCurrent()
            assertFalse(drained.isCompleted)
            assertFalse(element.reset())
            assertFalse(element.submit(command("late")))
            assertEquals(listOf("first:started"), events)

            turnRelease.complete(Unit)
            runCurrent()
            assertFalse(drained.isCompleted, "Drain must also join the reset operation")
            assertEquals(listOf("first:started", "first:finished", "reset:false:started"), events)
            resetRelease.complete(Unit)
            drained.await()
            assertEquals(listOf("first:started", "first:finished", "reset:false:started",
                "reset:false:finished", "next:started", "next:finished"), events)
            assertTrue(failures.isEmpty())
            assertFalse(element.reset())
        } finally {
            turnRelease.complete(Unit)
            resetRelease.complete(Unit)
            element.drain()
        }
    }

    @Test
    fun failedResetReportsNoCommandAndAllowsTheFollowingTurn() = runTest {
        val failure = IllegalStateException("reset failed")
        val events = mutableListOf<String>()
        val failedCommands = mutableListOf<ManualMediaInput?>()
        val failures = mutableListOf<Throwable>()
        val element = HermesConsoleElement(this, open = {}, execute = { events += it.input },
            reset = { resume -> assertTrue(resume); events += "reset"; throw failure },
            failed = { command, cause -> failedCommands += command; failures += cause; events += "failed" })
        try {
            assertTrue(element.reset())
            assertTrue(element.submit(command("next")))
            element.drain()
            assertEquals(listOf("reset", "failed", "next"), events)
            assertEquals(listOf<ManualMediaInput?>(null), failedCommands)
            assertSame(failure, failures.single())
            assertFalse(element.reset())
        } finally { element.drain() }
    }

    @Test
    fun parentCancellationReportsUnfinishedCommandsAndClosesAdmission() = runTest {
        val owner = SupervisorJob(coroutineContext[Job])
        val failed = mutableListOf<String?>()
        val element = HermesConsoleElement(CoroutineScope(coroutineContext + owner),
            open = {}, execute = { awaitCancellation() },
            failed = { command, _ -> failed += command?.input },
        )
        assertTrue(element.submit(command("running")))
        runCurrent()
        assertTrue(element.submit(command("queued")))
        owner.cancel()
        element.drain()
        owner.join()
        assertEquals(listOf<String?>("running", "queued"), failed)
        assertFalse(element.submit(command("late")))
    }
}
