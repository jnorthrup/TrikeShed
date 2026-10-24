package borg.trikeshed.mcp

import borg.trikeshed.parse.reifyStrict
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private sealed interface Step {
    class Text(val text: String) : Step
    class Raw(val bytes: ByteArray) : Step
    object TooLong : Step

    /** Lets everything that is runnable run to its next suspension point before the next line arrives. */
    object Pause : Step
    class Do(val action: suspend () -> Unit) : Step
}

private class Script(private val steps: List<Step>) : LineSource {
    private var at = 0
    override suspend fun read(maxBytes: Int): LineRead {
        while (true) {
            when (val step = steps.getOrNull(at++) ?: return LineRead.Eof) {
                is Step.Text -> return LineRead.Line(step.text.encodeToByteArray())
                is Step.Raw -> return LineRead.Line(step.bytes)
                Step.TooLong -> return LineRead.TooLong
                Step.Pause -> repeat(4) { yield() }
                is Step.Do -> step.action()
            }
        }
    }
}

private class Recorder : LineSink {
    val lines = mutableListOf<String>()
    override suspend fun write(line: ByteArray) { lines += line.decodeToString() }
    fun replies(): List<Map<*, *>> = lines.map { reifyStrict(it) as Map<*, *> }
    fun ids(): List<Any?> = replies().map { (it["id"] as? Number)?.toLong() ?: it["id"] }
    fun codes(): List<Int?> = replies().map { ((it["error"] as? Map<*, *>)?.get("code") as? Number)?.toInt() }
}

private fun req(id: Int, method: String = "ping") = """{"jsonrpc":"2.0","id":$id,"method":"$method"}"""
private fun call(id: Int, tool: String, args: String = "{}") =
    """{"jsonrpc":"2.0","id":$id,"method":"tools/call","params":{"name":"$tool","arguments":$args}}"""
private fun cancel(id: Number) = """{"jsonrpc":"2.0","method":"notifications/cancelled","params":{"requestId":$id}}"""
private const val INITIALIZED = """{"jsonrpc":"2.0","method":"notifications/initialized"}"""

private val NO_ARGS = mapOf("type" to "object", "additionalProperties" to false)

class McpLineServerTest {
    private val gate = CompletableDeferred<Unit>()
    private var cancelledInTool = 0

    private fun server() = McpToolServer(
        "fixture", "1",
        listOf(
            McpTool("echo", "echo", mapOf("type" to "object", "properties" to mapOf("text" to mapOf("type" to "string")), "additionalProperties" to false)) { a ->
                McpToolResult(mapOf("text" to a["text"]))
            },
            McpTool("slow", "waits for the gate", NO_ARGS) { gate.await(); McpToolResult(mapOf("done" to "slow")) },
            McpTool("watch", "records that it was cancelled", NO_ARGS) {
                try { gate.await() } catch (c: CancellationException) { cancelledInTool++; throw c }
                McpToolResult(mapOf("done" to "watch"))
            },
        ),
    )

    private suspend fun run(
        vararg steps: Step,
        maxInFlight: Int = 8,
        maxLineBytes: Int = 4_096,
        events: MutableList<String> = mutableListOf(),
    ): Recorder {
        val sink = Recorder()
        McpLineServer(server(), maxLineBytes, maxInFlight) { events += it }.serve(Script(steps.toList()), sink)
        return sink
    }

    @Test
    fun eachRequestGetsExactlyOneSingleLineReply() = runTest {
        val sink = run(Step.Text(req(1)), Step.Text(call(2, "echo", """{"text":"hi"}""")))
        assertEquals(setOf<Any?>(1L, 2L), sink.ids().toSet())
        assertEquals(2, sink.lines.size)
        assertTrue(sink.lines.none { '\n' in it || '\r' in it }, "one reply, one line")
        val echo = sink.replies().first { (it["id"] as Number).toInt() == 2 }
        assertEquals("hi", ((echo["result"] as Map<*, *>)["structuredContent"] as Map<*, *>)["text"])
    }

    @Test
    fun blankLinesAndNotificationsProduceNoReplyAndTakeNoSlot() = runTest {
        val sink = run(Step.Text(""), Step.Text("   "), Step.Text(INITIALIZED), Step.Text(req(1)), maxInFlight = 1)
        assertEquals(listOf<Any?>(1L), sink.ids())
    }

    @Test
    fun anOverlongLineGetsOneErrorAndTheStreamResynchronises() = runTest {
        val events = mutableListOf<String>()
        val sink = run(Step.TooLong, Step.Text(req(5)), events = events)
        assertEquals(listOf<Any?>(null, 5L), sink.ids())
        assertEquals(-32600, sink.codes()[0])
        assertTrue("exceeds 4096" in sink.lines[0], sink.lines[0])
        assertEquals(listOf("line-too-long"), events)
    }

    @Test
    fun invalidUtf8IsAParseErrorNotACrash() = runTest {
        val events = mutableListOf<String>()
        val sink = run(Step.Raw(byteArrayOf(0x7b, -1, -2, 0x7d)), Step.Text(req(3)), events = events)
        assertEquals(-32700, sink.codes()[0])
        assertEquals(3L, sink.ids()[1])
        assertEquals(listOf("invalid-utf8"), events)
    }

    @Test
    fun malformedRequestsAreAnsweredInlineWithoutTakingASlot() = runTest {
        val sink = run(
            Step.Text("this is not json"),
            Step.Text("""{"jsonrpc":"2.0","id":1.5,"method":"ping"}"""),
            Step.Text("""[{"jsonrpc":"2.0","id":1,"method":"ping"}]"""),
            Step.Text(req(9)),
            maxInFlight = 1,
        )
        assertEquals(listOf<Int?>(-32700, -32600, -32600, null), sink.codes())
        assertEquals(9L, sink.ids().last())
    }

    @Test
    fun aDuplicateInFlightIdIsRefusedAndTheOriginalStillAnswers() = runTest {
        val events = mutableListOf<String>()
        val sink = run(
            Step.Text(call(1, "slow")), Step.Pause,
            Step.Text(call(1, "echo", """{"text":"imposter"}""")), Step.Pause,
            Step.Do { gate.complete(Unit) },
            events = events,
        )
        assertEquals(listOf<Any?>(null, 1L), sink.ids(), "the refusal cannot carry the ambiguous id; the original keeps it")
        assertEquals(-32600, sink.codes()[0])
        assertTrue("already in flight" in sink.lines[0])
        assertEquals("slow", ((sink.replies()[1]["result"] as Map<*, *>)["structuredContent"] as Map<*, *>)["done"])
        assertEquals(listOf("duplicate-id"), events)
    }

    @Test
    fun numericallyEqualIdsAreOneRequest() = runTest {
        val sink = run(
            Step.Text(call(7, "slow")), Step.Pause,
            Step.Text("""{"jsonrpc":"2.0","id":7.0,"method":"ping"}"""), Step.Pause,
            Step.Do { gate.complete(Unit) },
        )
        assertEquals(-32600, sink.codes()[0], "7 and 7.0 name the same request")
        assertEquals(1, sink.lines.count { "error" in it })
    }

    @Test
    fun overCapacityRequestsAreShedWithTheirOwnIdAndNeverQueued() = runTest {
        val events = mutableListOf<String>()
        val sink = run(
            Step.Text(call(1, "slow")), Step.Pause,
            Step.Text(req(2)), Step.Pause,
            Step.Do { gate.complete(Unit) },
            maxInFlight = 1, events = events,
        )
        assertEquals(listOf<Any?>(2L, 1L), sink.ids(), "the shed reply came while the slow request was still running")
        assertEquals(-32001, sink.codes()[0])
        assertTrue("1 requests in flight" in sink.lines[0], sink.lines[0])
        assertEquals(listOf("shed"), events)
    }

    @Test
    fun cancellingAnInFlightRequestSuppressesItsReplyAndFreesTheSlot() = runTest {
        val events = mutableListOf<String>()
        val sink = run(
            Step.Text(call(1, "watch")), Step.Pause,
            Step.Text(cancel(1)), Step.Pause,
            Step.Text(req(2)),
            maxInFlight = 1, events = events,
        )
        assertEquals(listOf<Any?>(2L), sink.ids(), "no reply for the cancelled request, and its slot was released")
        assertEquals(1, cancelledInTool, "the tool itself observed the cancellation")
        assertEquals(listOf("cancel"), events)
    }

    @Test
    fun aCancellationThatArrivesBeforeTheRequestEverRanStillFreesItsSlot() = runTest {
        // No pause between the request and its cancellation: the job has not been dispatched yet when it is cancelled.
        val sink = run(
            Step.Text(call(1, "watch")),
            Step.Text(cancel(1)), Step.Pause,
            Step.Text(req(2)),
            maxInFlight = 1,
        )
        assertEquals(listOf<Any?>(2L), sink.ids(), "a cancelled request must neither answer nor leak its slot: ${sink.lines}")
        assertEquals(listOf<Int?>(null), sink.codes())
    }

    @Test
    fun aRequestCancelledBeforeItRanGetsNoReplyEvenWhenItsHandlerNeverSuspends() = runTest {
        // `ping` finishes without suspending, so only an explicit check can honour a cancellation that arrived first.
        val sink = run(Step.Text(req(1)), Step.Text(cancel(1)), Step.Pause, Step.Text(req(2)), maxInFlight = 1)
        assertEquals(listOf<Any?>(2L), sink.ids(), "no reply for the cancelled request: ${sink.lines}")
    }

    @Test
    fun aCancelledIdCanBeUsedAgain() = runTest {
        val sink = run(
            Step.Text(call(1, "watch")),
            Step.Text(cancel(1)), Step.Pause,
            Step.Text(req(1)),
        )
        assertEquals(listOf<Any?>(1L), sink.ids())
        assertEquals(listOf<Int?>(null), sink.codes(), "the reused id is served, not refused as a duplicate")
    }

    @Test
    fun cancellingAnUnknownOrFinishedRequestIsIgnored() = runTest {
        val events = mutableListOf<String>()
        val sink = run(Step.Text(req(1)), Step.Pause, Step.Text(cancel(1)), Step.Text(cancel(99)), Step.Text(req(2)), events = events)
        assertEquals(setOf<Any?>(1L, 2L), sink.ids().toSet())
        assertTrue(events.isEmpty(), "nothing was cancelled: $events")
    }

    @Test
    fun eofLetsAcceptedRequestsFinishAndReplyBeforeServeReturns() = runTest {
        launch { repeat(6) { yield() }; gate.complete(Unit) }
        val sink = run(Step.Text(call(1, "slow")))
        assertEquals(listOf<Any?>(1L), sink.ids(), "the reply was written before serve returned")
    }

    @Test
    fun aFailingSinkFailsServeAndCancelsWhatWasInFlight() = runTest {
        val broken = object : LineSink {
            override suspend fun write(line: ByteArray) = throw IllegalStateException("client went away")
        }
        assertFailsWith<IllegalStateException> {
            McpLineServer(server(), 4_096, 8).serve(Script(listOf(Step.Text(call(1, "watch")), Step.Pause, Step.Text(req(2)), Step.Pause)), broken)
        }
        assertEquals(1, cancelledInTool, "nobody is left to read, so in-flight work was cancelled")
        assertFalse(gate.isCompleted)
    }

    @Test
    fun parametersAreValidated() {
        assertFailsWith<IllegalArgumentException> { McpLineServer(server(), 0, 1) }
        assertFailsWith<IllegalArgumentException> { McpLineServer(server(), 1, 0) }
    }
}
