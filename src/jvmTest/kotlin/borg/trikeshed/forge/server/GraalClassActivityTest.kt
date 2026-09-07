package borg.trikeshed.forge.server

import borg.trikeshed.graal.vitals.JvmVitals
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GraalClassActivityTest {
    @Test
    fun classUpdatesSurviveABurstLargerThanTheSseQueue() = runBlocking {
        val wire = GraalWire(JvmVitals(), null, null, this)
        val release = CompletableDeferred<Unit>()
        val complete = CompletableDeferred<Unit>()
        val frames = mutableListOf<String>()
        val stream = launch(start = CoroutineStart.UNDISPATCHED) {
            wire.route("GET", GraalWire.EVENTS_PATH, "") { bytes ->
                val frame = bytes.decodeToString()
                if (frame.startsWith("data:")) {
                    release.await()
                    frames.add(frame)
                    if (frames.size == 600) complete.complete(Unit)
                }
            }
        }
        try {
            yield() // Let the per-stream flow collectors subscribe.
            val producer = async {
                repeat(600) { wire.classFileChanged("projects/test/build/live/classes/p/C$it.class", "compiled", false) }
            }
            yield()
            assertTrue(!producer.isCompleted, "A full queue must suspend, not silently discard updates")
            release.complete(Unit)
            withTimeout(5000) { producer.await(); complete.await() }
            assertEquals(600, frames.size)
            assertTrue(frames.first().contains("\"kind\":\"class-update\""))
            assertTrue(frames.last().contains("C599.class"))
        } finally {
            release.complete(Unit)
            stream.cancelAndJoin()
        }
    }
}
