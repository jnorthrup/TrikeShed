package borg.trikeshed.narsese

import borg.trikeshed.parse.*
import borg.trikeshed.userspace.nio.channels.spi.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class JevaElementTest {
    @Test fun parentCancellationRetainsNestedInferenceUntilCurationLanesJoin() = runTest {
        val owner = SupervisorJob(coroutineContext[Job])
        val ops = Operations { request -> if (request["op"] == "status") status() else answer() }
        val element = JevaElement(CoroutineScope(coroutineContext + owner + ops), JevaConfig(runtime = JevaRuntime.PYTHON))
        val curator = CurationElement(CoroutineScope(coroutineContext + owner + element + ops), answerWorkers = 1)
        element.retain(curator)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        try {
            val active = curator.submit {
                entered.complete(Unit); release.await()
                curator.answer("active") { element.ask("active page", questions) }
            }
            entered.await()
            val queued = curator.submit { curator.answer("queued") { element.ask("queued page", questions) } }
            owner.cancel()
            yield()
            assertFalse(owner.isCompleted)
            assertFalse(active.isCompleted)
            assertFailsWith<IllegalStateException> { element.ask("external page", questions) }
            release.complete(Unit)
            active.await().getOrThrow(); queued.await().getOrThrow()
            curator.drain(); element.drain(); owner.join()
            assertEquals(2, ops.inferences)
            assertTrue(ops.closed.isCompleted)
            assertTrue(element.supervisor.isCompleted)
        } finally {
            release.complete(Unit)
            curator.drain(); element.drain(); owner.cancelAndJoin()
        }
    }

    class Operations(val respond: suspend (Map<String, Any?>) -> Map<String, Any?>) : ProcessOperations {
        val closed = CompletableDeferred<Unit>()
        var opens = 0
        var statuses = 0
        var inferences = 0
        override suspend fun exec(command: String, args: List<String>, stdin: ByteArray?, env: Map<String, String>): ProcessResult = error("Not used")
        override suspend fun open(command: String, args: List<String>, env: Map<String, String>): ProcessPipes {
            opens++
            val responses = Channel<ByteArray>(1)
            val exited = CompletableDeferred<Unit>()
            assertEquals("1", env["HF_HUB_OFFLINE"])
            assertEquals("1", env["TRANSFORMERS_OFFLINE"])
            return object : ProcessPipes {
                override suspend fun write(bytes: ByteArray) {
                    val request = reifyMap(bytes.decodeToString())
                    if (request["op"] == "status") statuses++ else inferences++
                    responses.send((jsonOf(respond(request)) + "\n").encodeToByteArray())
                }
                override suspend fun read(output: ProcessOutput, bytes: ByteArray): Int {
                    if (output == ProcessOutput.Stderr) { exited.await(); return -1 }
                    val value = responses.receiveCatching().getOrNull() ?: return -1
                    value.copyInto(bytes)
                    return value.size
                }
                override suspend fun closeInput() { responses.close(); exited.complete(Unit); closed.complete(Unit) }
                override suspend fun awaitExit() = 0
                override suspend fun close() { responses.close(); exited.complete(Unit); closed.complete(Unit) }
            }
        }
    }
    val questions = mapOf("stated" to Jev.noul("Does the page state the norm?"))
    fun status() = mapOf("available" to true, "error" to null, "model_identity" to "fixture@revision")
    fun answer() = mapOf("model" to "fixture@revision", "answers" to mapOf("stated" to mapOf(
        "kind" to "noul", "noul" to 0.9, "candidate_probabilities" to listOf(0.1, 0.9), "null_probability" to 0.0)))

    @Test fun cancelledWaiterDoesNotDiscardAcceptedInferenceAndDrainStopsAdmission() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val ops = Operations { request -> if (request["op"] == "status") status() else {
            entered.complete(Unit); release.await(); answer()
        } }
        val element = JevaElement(CoroutineScope(coroutineContext + ops), JevaConfig(runtime = JevaRuntime.PYTHON), capacity = 1)
        val caller = async { withContext(element) { DecisionProvider.JEVA.ask("", "page", questions) } }
        entered.await()
        caller.cancelAndJoin()
        val queued = async(start = CoroutineStart.UNDISPATCHED) { element.ask("second page", questions) }
        val draining = async(start = CoroutineStart.UNDISPATCHED) { element.drain() }
        yield()
        assertFalse(draining.isCompleted)
        assertFailsWith<IllegalStateException> { element.availability() }
        release.complete(Unit)
        assertEquals(0.9, Jev.noulOf(queued.await()["answers"] as Map<*, *>, "stated"))
        draining.await()
        assertEquals(2, ops.inferences)
        assertEquals(1, ops.statuses)
        assertEquals(1, ops.opens)
        assertTrue(ops.closed.isCompleted)
        assertTrue(element.supervisor.isCompleted)
        element.drain()
    }

    @Test fun unavailableAndFailedInferenceAreVisibleAndDoNotProduceEvidence() = runTest {
        var available = false
        val ops = Operations { request -> if (request["op"] == "status") {
            if (available) status() else mapOf("available" to false, "error" to "ModuleNotFoundError: jeva")
        } else mapOf("error" to "ValueError: state has too many tokens") }
        val element = JevaElement(CoroutineScope(coroutineContext + ops), JevaConfig(runtime = JevaRuntime.PYTHON))
        try {
            val first = element.availability()
            assertEquals(false, first["available"])
            assertContains(first["error"].toString(), "ModuleNotFoundError")
            assertFails { element.ask("page", questions) }
            assertEquals(0, ops.inferences)
            available = true
            assertEquals(true, element.availability()["available"])
            val failed = assertFails { element.ask("overflow", questions) }
            assertContains(failed.message.orEmpty(), "too many tokens")
            assertEquals(false, element.metadata["available"])
        } finally { element.drain() }
        assertTrue(ops.closed.isCompleted)
    }

    @Test fun ownerCancellationStillJoinsTheAdmittedProcessExchange() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val owner = SupervisorJob(coroutineContext[Job])
        val ops = Operations { request -> if (request["op"] == "status") status() else {
            entered.complete(Unit); release.await(); answer()
        } }
        val element = JevaElement(CoroutineScope(coroutineContext + owner + ops), JevaConfig(runtime = JevaRuntime.PYTHON))
        val caller = async { element.ask("page", questions) }
        entered.await()
        owner.cancel()
        yield()
        assertFalse(owner.isCompleted)
        release.complete(Unit)
        assertEquals(0.9, Jev.noulOf(caller.await()["answers"] as Map<*, *>, "stated"))
        element.drain()
        owner.join()
        assertTrue(ops.closed.isCompleted)
    }

    @Test fun cancelledReleaseWaiterStillReleasesAfterTheAdmittedInference(): Unit = runTest {
        val entered = CompletableDeferred<Unit>()
        val continueInference = CompletableDeferred<Unit>()
        val ops = Operations { request -> if (request["op"] == "status") status() else {
            entered.complete(Unit); continueInference.await(); answer()
        } }
        val element = JevaElement(CoroutineScope(coroutineContext + ops), JevaConfig(runtime = JevaRuntime.PYTHON), capacity = 1)
        try {
            val asking = async { element.ask("active page", questions) }
            entered.await()
            val releasing = async(start = CoroutineStart.UNDISPATCHED) { element.release() }
            assertFalse(releasing.isCompleted)
            assertFalse(ops.closed.isCompleted)
            releasing.cancelAndJoin()
            continueInference.complete(Unit)
            assertEquals(0.9, Jev.noulOf(asking.await()["answers"] as Map<*, *>, "stated"))
            val status = element.availability()
            assertEquals(true, status["available"])
            assertEquals(false, status["loaded"])
            assertEquals("fixture@revision", status["model_identity"])
            assertNull(element.process)
            assertTrue(ops.closed.isCompleted)
            assertTrue(element.accepting)
            assertEquals(1, ops.opens)
            assertEquals(1, ops.statuses)
        } finally { continueInference.complete(Unit); element.drain() }
    }

    @Test fun releaseBeforeFirstResolutionDoesNotPreventLaterProviderAdmission(): Unit = runTest {
        val ops = Operations { status() }
        val element = JevaElement(CoroutineScope(coroutineContext + ops), JevaConfig(runtime = JevaRuntime.PYTHON))
        try {
            element.release()
            assertEquals(0, ops.opens)
            assertEquals(true, element.availability()["available"])
            assertEquals(1, ops.opens)
        } finally { element.drain() }
    }

    @Test fun expiredRuntimeFailsQueuedAsksWithoutComputeUntilExplicitRelease(): Unit = runTest {
        val entered = CompletableDeferred<Unit>()
        val continueInference = CompletableDeferred<Unit>()
        val expired = JevaDeadline.Expired("fixture decision", 600_000, 600_001)
        var fail = true
        val ops = Operations { request -> if (request["op"] == "status") status() else {
            if (fail) { entered.complete(Unit); continueInference.await(); throw expired }
            answer()
        } }
        val element = JevaElement(CoroutineScope(coroutineContext + ops), JevaConfig(runtime = JevaRuntime.PYTHON))
        try {
            val first = async { runCatching { element.ask("first page", questions) } }
            entered.await()
            val second = async(start = CoroutineStart.UNDISPATCHED) { runCatching { element.ask("second page", questions) } }
            val third = async(start = CoroutineStart.UNDISPATCHED) { runCatching { element.ask("third page", questions) } }
            continueInference.complete(Unit)
            assertSame(expired, first.await().exceptionOrNull())
            assertSame(expired, second.await().exceptionOrNull())
            assertSame(expired, third.await().exceptionOrNull())
            assertSame(expired, assertFails { element.ask("later page", questions) })
            assertEquals(false, element.availability()["available"])
            assertEquals(1, ops.inferences)
            assertEquals(1, ops.opens)
            assertEquals(1, ops.statuses)
            fail = false
            val released = element.release()
            assertEquals(true, released["available"])
            assertEquals(false, released["loaded"])
            assertNull(element.expiration)
            assertEquals(released, element.availability())
            assertEquals(1, ops.opens)
            assertEquals(0.9, Jev.noulOf(element.ask("next batch", questions)["answers"] as Map<*, *>, "stated"))
            assertEquals(2, ops.inferences)
            assertEquals(2, ops.opens)
        } finally { continueInference.complete(Unit); element.drain() }
    }
}
