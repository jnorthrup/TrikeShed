package borg.trikeshed.narsese

import borg.trikeshed.lib.*
import borg.trikeshed.parse.*
import borg.trikeshed.userspace.*
import borg.trikeshed.userspace.UringOp.Companion.UringSubmission
import borg.trikeshed.userspace.nio.channels.spi.*
import borg.trikeshed.userspace.nio.spi.NioSupervisor
import kotlinx.coroutines.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.abs
import kotlin.test.*

/** Real userspace file mappings and the complete Kotlin lane, against a tiny upstream CPU checkpoint. */
class JevaInferenceTest {
    val reference by lazy { reifyMap(resource("reference.json").decodeToString()) }
    fun resource(name: String): ByteArray = requireNotNull(javaClass.getResourceAsStream("/jeva/inference/$name"))
        .use { it.readBytes() }

    fun checkpoint(): Path = Files.createTempDirectory("trikeshed-jeva-inference-").also { directory ->
        for (name in (reference["files"] as Map<*, *>).keys) {
            val path = directory.resolve(name as String)
            Files.createDirectories(path.parent)
            Files.write(path, resource(name))
        }
    }

    fun remove(directory: Path) {
        Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
    }

    fun questions(): Map<String, Map<String, Any?>> = (reference["questions"] as Map<*, *>).entries.associate { (id, raw) ->
        id as String to (raw as Map<*, *>).entries.associate { it.key as String to it.value }
    }

    class Processes : ProcessOperations {
        var calls = 0
        override suspend fun exec(command: String, args: List<String>, stdin: ByteArray?, env: Map<String, String>): ProcessResult {
            calls++; error("Kotlin inference must not execute a process")
        }
        override suspend fun open(command: String, args: List<String>, env: Map<String, String>): ProcessPipes {
            calls++; error("Kotlin inference must not open a process")
        }
    }

    class Mappings : UringTrace {
        val mapped = mutableSetOf<Long>()
        val unmapped = mutableSetOf<Long>()
        override fun channel(id: Long, availability: String, nativeCapabilities: Long) {}
        override fun submit(channel: Long, submission: UringSubmission) {}
        override fun complete(channel: Long, submission: UringSubmission, result: Int) {}
        override fun failed(channel: Long, submission: UringSubmission, failure: String) {}
        override fun memory(event: MemoryTraceEvent) {
            if (event.failure == null) when (event.operation) {
                MemoryOperation.MAP -> { check(event.fd >= 0); mapped.add(event.mapping) }
                MemoryOperation.UNMAP -> unmapped.add(event.mapping)
                else -> Unit
            }
        }
    }

    fun probability(expected: Any?, actual: Any?) {
        val e = (expected as Number).toDouble(); val a = (actual as Number).toDouble()
        assertTrue(a.isFinite() && abs(e - a) < 3e-5, "Expected upstream probability $e, got $a")
    }

    fun probabilities(reply: Map<String, Any?>) {
        val answers = reply["answers"] as Map<*, *>
        for ((id, raw) in reference["expected"] as Map<*, *>) {
            val expected = raw as Map<*, *>; val actual = answers[id] as Map<*, *>
            if (id == "stated") probability((expected["q"] as List<*>)[1], actual["noul"])
            else {
                probability(expected["p_null"], actual["null_probability"])
                val joint = actual["probabilities"] as Map<*, *>
                for ((label, value) in expected["joint"] as Map<*, *>) probability(value, joint[label])
                assertEquals((expected["joint"] as Map<*, *>).keys, joint.keys)
                assertEquals("b", actual["choice"])
            }
        }
    }

    @Test fun mappedKotlinLaneMatchesUpstreamAndClosesBorrowedWeights(): Unit = runBlocking {
        val directory = checkpoint()
        val nio = NioSupervisor(); val processes = Processes(); val mappings = Mappings()
        nio.open()
        val element = JevaElement(CoroutineScope(coroutineContext + nio + processes + mappings),
            JevaConfig(model = directory.toString(), python = "/no-python", maxStateLength = 128, maxOptionLength = 64))
        try {
            val status = element.availability()
            assertEquals(true, status["available"], status["error"].toString())
            assertEquals("kotlin", status["runtime"])
            assertEquals(true, status["mapped_weights"])
            val inference = requireNotNull(element.inference)
            val borrowed = inference.weights["context_proj.weight"]
            val first = element.ask(reference.getValue("state") as String, questions())
            probabilities(first)
            assertEquals(status["model_identity"], first["model"])
            val repeated = element.ask(reference.getValue("state") as String, questions())
            assertEquals(first["answers"], repeated["answers"])
            val initialUsage = first["usage"] as Map<*, *>; val repeatedUsage = repeated["usage"] as Map<*, *>
            assertTrue((initialUsage["encoded_option_tokens"] as Number).toLong() > 0)
            assertEquals(0L, (repeatedUsage["encoded_option_tokens"] as Number).toLong())
            assertTrue((repeatedUsage["cached_option_tokens"] as Number).toLong() > 0)
            assertEquals(4, inference.options.count)
            assertTrue(inference.weights.mappedBytes > 0)
            assertTrue(mappings.mapped.isNotEmpty(), "The numerical path must access actual file mappings")
            assertEquals(0, processes.calls)
            assertNull(element.process)
            element.drain()
            assertEquals(0L, inference.weights.mappedBytes)
            assertEquals(mappings.mapped, mappings.unmapped)
            assertEquals(0, inference.options.count)
            assertFailsWith<IllegalStateException> { borrowed.b(shapeOf(0, 0)) }
            assertFailsWith<IllegalStateException> { inference.infer("state", Jeva.questions(questions())) }
            assertFailsWith<IllegalStateException> { element.ask("state", questions()) }
            assertFailsWith<IllegalStateException> { element.availability() }
            assertTrue(element.supervisor.isCompleted)
        } finally { element.drain(); nio.close(); remove(directory) }
    }

    @Test fun tokenOverflowFailsWithoutEvidenceAndTheSameMappedModelCanRecover(): Unit = runBlocking {
        val directory = checkpoint(); val nio = NioSupervisor(); val processes = Processes()
        nio.open()
        val element = JevaElement(CoroutineScope(coroutineContext + nio + processes),
            JevaConfig(model = directory.toString(), python = "/no-python", maxStateLength = 128, maxOptionLength = 64))
        try {
            assertEquals(true, element.availability()["available"])
            val inference = requireNotNull(element.inference)
            val failure = assertFailsWith<IllegalArgumentException> { element.ask("x".repeat(200), questions()) }
            assertContains(failure.message.orEmpty(), "exceeds 128 tokens")
            assertEquals(0, inference.options.count)
            assertEquals(false, element.metadata["available"])
            probabilities(element.ask(reference.getValue("state") as String, questions()))
            assertSame(inference, element.inference)
            assertEquals(true, element.metadata["available"])
            assertEquals(0, processes.calls)
        } finally { element.drain(); nio.close(); remove(directory) }
    }

    @Test fun releaseUnmapsModelAndClearsOptionsWhileCurationCanReloadOnDemand(): Unit = runBlocking {
        val directory = checkpoint(); val nio = NioSupervisor(); val processes = Processes(); val mappings = Mappings()
        nio.open()
        val element = JevaElement(CoroutineScope(coroutineContext + nio + processes + mappings),
            JevaConfig(model = directory.toString(), python = "/no-python", maxStateLength = 128, maxOptionLength = 64))
        val curator = CurationElement(CoroutineScope(coroutineContext + element), answerWorkers = 1)
        element.retain(curator)
        try {
            val first = element.ask(reference.getValue("state") as String, questions())
            val original = requireNotNull(element.inference)
            val borrowed = original.weights["context_proj.weight"]
            assertTrue(original.weights.mappedBytes > 0)
            assertEquals(4, original.options.count)
            val released = element.release()
            assertEquals(true, released["available"])
            assertEquals(false, released["loaded"])
            assertEquals(first["model"], released["model_identity"])
            assertNull(element.inference)
            assertEquals(0L, original.weights.mappedBytes)
            assertEquals(0, original.options.count)
            assertEquals(mappings.mapped, mappings.unmapped)
            assertFailsWith<IllegalStateException> { borrowed.b(shapeOf(0, 0)) }
            val mapped = mappings.mapped.toSet()
            repeat(3) { assertEquals(released, element.availability()) }
            assertEquals(released, element.release())
            assertNull(element.inference)
            assertEquals(mapped, mappings.mapped)
            assertTrue(element.accepting && element.supervisor.isActive)
            assertTrue(curator.accepting && curator.supervisor.isActive)
            assertTrue(curator in element.dependents)

            val answer = CompletableDeferred<Map<String, Any?>>()
            curator.submit {
                answer.complete(curator.answer("after-release") {
                    element.ask(reference.getValue("state") as String, questions())
                })
            }.await().getOrThrow()
            val resumed = answer.await()
            probabilities(resumed)
            assertEquals(first["model"], resumed["model"])
            assertEquals(first["answers"], resumed["answers"])
            assertTrue(((resumed["usage"] as Map<*, *>)["encoded_option_tokens"] as Number).toLong() > 0)
            val reloaded = requireNotNull(element.inference)
            assertNotSame(original, reloaded)
            assertEquals(4, reloaded.options.count)
            assertTrue(reloaded.weights.mappedBytes > 0)
            assertEquals(true, element.metadata["loaded"])
            assertTrue(mappings.mapped.size > mapped.size)
            assertEquals(0, processes.calls)
            curator.drain(); element.drain()
            assertEquals(0L, reloaded.weights.mappedBytes)
            assertEquals(0, reloaded.options.count)
            assertEquals(mappings.mapped, mappings.unmapped)
            assertTrue(element.supervisor.isCompleted)
        } finally { curator.drain(); element.drain(); nio.close(); remove(directory) }
    }

    @Test fun changedCheckpointAfterReleaseCannotAnswerUnderThePreviousIdentity(): Unit = runBlocking {
        val directory = checkpoint(); val nio = NioSupervisor(); val mappings = Mappings()
        nio.open()
        val element = JevaElement(CoroutineScope(coroutineContext + nio + mappings),
            JevaConfig(model = directory.toString(), maxStateLength = 128, maxOptionLength = 64))
        try {
            val first = element.ask(reference.getValue("state") as String, questions())
            element.release()
            val path = directory.resolve("head.safetensors")
            val bytes = Files.readAllBytes(path)
            val payload = 8 + (0..7).sumOf { (bytes[it].toLong() and 255) shl (8 * it) }.toInt()
            bytes[payload] = (bytes[payload].toInt() xor 1).toByte()
            Files.write(path, bytes)
            assertEquals(first["model"], element.availability()["model_identity"])
            val failure = assertFailsWith<IllegalStateException> {
                element.ask(reference.getValue("state") as String, questions())
            }
            assertContains(failure.message.orEmpty(), "model identity changed while reloading")
            assertEquals(first["model"], element.metadata["model_identity"])
            assertEquals(false, element.metadata["available"])
            assertEquals(false, element.metadata["loaded"])
            assertNull(element.inference)
            assertEquals(mappings.mapped, mappings.unmapped)
            assertEquals(false, element.release()["available"], "release must not hide a changed model identity")
            assertEquals(element.metadata, element.availability())
            assertNull(element.inference)
        } finally { element.drain(); nio.close(); remove(directory) }
    }

    @Test fun failedDecisionCanReleaseAndRemainAvailableForLaterCuration(): Unit = runBlocking {
        val directory = checkpoint(); val nio = NioSupervisor(); val mappings = Mappings()
        nio.open()
        val element = JevaElement(CoroutineScope(coroutineContext + nio + mappings),
            JevaConfig(model = directory.toString(), maxStateLength = 128, maxOptionLength = 64))
        try {
            val first = element.ask(reference.getValue("state") as String, questions())
            val original = requireNotNull(element.inference)
            assertFailsWith<IllegalArgumentException> { element.ask("x".repeat(200), questions()) }
            assertEquals(false, element.metadata["available"])
            val released = element.release()
            assertEquals(true, released["available"])
            assertEquals(false, released["loaded"])
            assertEquals(first["model"], released["model_identity"])
            assertNull(released["error"])
            assertNull(element.inference)
            assertEquals(0L, original.weights.mappedBytes)
            assertEquals(0, original.options.count)
            assertEquals(mappings.mapped, mappings.unmapped)
            assertEquals(released, element.availability())
            assertNull(element.inference)
            val resumed = element.ask(reference.getValue("state") as String, questions())
            assertEquals(first["model"], resumed["model"])
            assertEquals(first["answers"], resumed["answers"])
            assertNotSame(original, element.inference)
        } finally { element.drain(); nio.close(); remove(directory) }
    }

    @Test fun releaseDoesNotHideALoadFailureAfterAResolvedModelWasUnloaded(): Unit = runBlocking {
        val directory = checkpoint(); val nio = NioSupervisor()
        nio.open()
        val element = JevaElement(CoroutineScope(coroutineContext + nio),
            JevaConfig(model = directory.toString(), maxStateLength = 128, maxOptionLength = 64))
        try {
            val status = element.availability()
            assertEquals(true, status["available"])
            element.release()
            Files.delete(directory.resolve("head.safetensors"))
            assertFails { element.ask(reference.getValue("state") as String, questions()) }
            val failed = element.metadata
            assertEquals(false, failed["available"])
            assertEquals(status["model_identity"], failed["model_identity"])
            assertNotNull(failed["error"])
            assertEquals(failed, element.release())
            assertEquals(failed, element.availability())
            assertNull(element.inference)
        } finally { element.drain(); nio.close(); remove(directory) }
    }
}
