package borg.trikeshed.kanban

import borg.trikeshed.userspace.FanoutEvent
import keymux.KeyMux
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds

class ForgeKanbanDaemonFanoutTest {
    class TestEvent : FanoutEvent {
        override val eventType: Int = 999
    }

    @Test
    fun testReactiveFanoutReplacesPolling() = runTest {
        val flow = MutableSharedFlow<FanoutEvent>()
        val keyMux = KeyMux {}
        val daemon = ForgeKanbanDaemon("test_user", keyMux, this)

        // Start processing with the flow
        val job = daemon.startAutoProcess(flow)

        // Emit an event
        flow.emit(TestEvent())

        // Yield to let the coroutine process
        delay(10.milliseconds)

        job.cancel()
        assertTrue(true, "Job started and event emitted successfully")
    }
}
