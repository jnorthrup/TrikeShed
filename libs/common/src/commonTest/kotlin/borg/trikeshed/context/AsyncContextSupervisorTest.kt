package borg.trikeshed.context

import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertFalse

private object TestKey : CoroutineContext.Key<AsyncContextElement>

class AsyncContextSupervisorTest {
    @Test
    fun supervisorJobIsCancelledWhenParentCanceled() = runBlocking {
        val parent = Job()
        val element = object : AsyncContextElement(parentJob = parent) {
            override val key = TestKey
            fun supervisorActive() = supervisor.isActive
        }

        // supervisor created and active
        assertTrue(element.supervisorActive())

        // cancel parent job, supervisor should be cancelled
        parent.cancel()

        // cancellation should propagate
        assertFalse(element.supervisorActive())
    }

    @Test
    fun supervisorIsCancelledOnClose() = runBlocking {
        val element = object : AsyncContextElement() {
            override val key = TestKey
            fun supervisorActive() = supervisor.isActive
        }

        assertTrue(element.supervisorActive())
        element.open()
        element.close()
        assertFalse(element.supervisorActive())
    }
}
