package borg.trikeshed.narsese

import borg.trikeshed.lib.*
import borg.trikeshed.parse.jsonOf
import borg.trikeshed.userspace.nio.channels.spi.JvmProcessOperations
import borg.trikeshed.userspace.nio.channels.spi.*
import kotlinx.coroutines.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class JevaWorkerProcessTest {
    @org.junit.jupiter.api.io.TempDir lateinit var directory: Path

    private fun script(body: String): String = directory.resolve("worker.sh").also {
        Files.writeString(it, "#!/bin/sh\n$body\n")
        check(it.toFile().setExecutable(true))
    }.toString()

    private val init = """{"available":true,"loaded":true,"model_identity":"fixture","runtime":"kotlin","device":"mlx"}"""
    private val question = _m["q" j _m["type" j "noul", "instructions" j "Decide"]]

    @Test fun workerReplyIsValidatedByTheParent(): Unit = runBlocking {
        val executable = script("""
            IFS= read -r init || exit 1
            printf '%s\n' '$init'
            while IFS= read -r request; do
                printf '%s\n' '{"model":"fixture","answers":{"q":{"kind":"noul","candidate_probabilities":[0.25,0.75],"null_probability":0,"noul":0.75}},"usage":{}}'
            done
        """.trimIndent())
        val lane = JevaElement(CoroutineScope(coroutineContext + JvmProcessOperations()),
            JevaConfig(model = "fixture", device = "mlx", worker = executable, timeoutMs = 5_000))
        try {
            assertEquals(true, lane.availability()["available"])
            val reply = lane.ask("state", question)
            val answers = assertIs<Map<*, *>>(reply["answers"])
            assertEquals(0.75, assertIs<Map<*, *>>(answers["q"])["noul"])
        } finally { lane.drain() }
    }

    @Test fun stalledWorkerIsKilledAtTheExchangeDeadline(): Unit = runBlocking {
        val pid = directory.resolve("pid")
        val executable = script("""
            IFS= read -r init || exit 1
            printf '%s\n' '$init'
            IFS= read -r request || exit 1
            printf '%s' "${'$'}${'$'}" > "${'$'}TRIKESHED_PID_FILE"
            exec /bin/sleep 30
        """.trimIndent())
        val lane = JevaElement(CoroutineScope(coroutineContext + JvmProcessOperations()),
            JevaConfig(model = "fixture", device = "mlx", worker = executable, timeoutMs = 1_000,
                env = _m["TRIKESHED_PID_FILE" j pid.toString()]))
        try {
            assertEquals(true, lane.availability()["available"])
            assertFails { lane.ask("state", question) }
            val child = Files.readString(pid).trim().toLong()
            withTimeout(5_000) {
                while (ProcessHandle.of(child).map { it.isAlive }.orElse(false)) delay(10)
            }
        } finally { lane.drain() }
    }

    @Test fun workerCannotSupplyAnInvalidProbability(): Unit = runBlocking {
        val executable = script("""
            IFS= read -r init || exit 1
            printf '%s\n' '$init'
            while IFS= read -r request; do
                printf '%s\n' '{"model":"fixture","answers":{"q":{"kind":"noul","candidate_probabilities":[0.9,0.9],"null_probability":0,"noul":0.9}},"usage":{}}'
            done
        """.trimIndent())
        val lane = JevaElement(CoroutineScope(coroutineContext + JvmProcessOperations()),
            JevaConfig(model = "fixture", device = "mlx", worker = executable, timeoutMs = 5_000))
        try {
            assertFailsWith<IllegalArgumentException> { lane.ask("state", question) }
            assertNull(lane.process, "an invalid worker reply must retire the child")
        } finally { lane.drain() }
    }

    @Test fun ordinaryAndFatalWorkerFailuresEscapeTheProtocol(): Unit = runBlocking {
        val input = jsonOf(_m["op" j "init", "config" j _m["model" j "fixture", "device" j "mlx",
            "max_state_length" j 10, "max_option_length" j 10, "timeout_ms" j 1000]])
        val ordinary = JevaWorker { throw IllegalStateException("load failed") }
        assertFailsWith<IllegalStateException> { ordinary.exchange(input) }
        val fatal = JevaWorker { throw AssertionError("fatal") }
        assertFailsWith<AssertionError> { fatal.exchange(input) }
    }

    @Test fun failedPipeCloseRetainsOwnershipUntilRetrySucceeds(): Unit = runBlocking {
        var closes = 0
        var opens = 0
        val pipes = object : ProcessPipes {
            override suspend fun write(bytes: ByteArray) = Unit
            override suspend fun read(output: ProcessOutput, bytes: ByteArray) = -1
            override suspend fun closeInput() = Unit
            override suspend fun awaitExit() = 1
            override suspend fun close() { closes++; if (closes < 3) error("pipe close failed") }
        }
        val ops = object : ProcessOperations {
            override suspend fun open(command: String, args: List<String>, env: Map<String, String>): ProcessPipes { opens++; return pipes }
            override suspend fun exec(command: String, args: List<String>, stdin: ByteArray?, env: Map<String, String>): ProcessResult = error("unused")
        }
        val lane = JevaElement(CoroutineScope(coroutineContext + ops),
            JevaConfig(model = "fixture", device = "mlx", worker = "fixture", timeoutMs = 1_000))
        try {
            assertEquals(false, lane.availability()["available"])
            assertEquals(2, closes)
            assertNotNull(lane.process, "the unclosed pipe remains owned")
            assertEquals(false, lane.availability()["available"])
            assertEquals(3, closes)
            assertEquals(1, opens, "a replacement is not started before cleanup")
            assertNull(lane.process)
        } finally { lane.drain() }
    }

    @Test fun failedWorkerIsReapedAndTheNextRequestStartsFresh(): Unit = runBlocking {
        for ((index, failure) in _l[
            "printf '%s\\n' '{\"error\":\"inference failed\"}'; exit 0",
            "exit 71",
            "kill -KILL \"${'$'}${'$'}\"",
        ].withIndex()) {
            val marker = directory.resolve("worker-$index")
            val executable = script("""
                IFS= read -r init || exit 1
                printf '%s\n' '$init'
                IFS= read -r request || exit 1
                if [ ! -e "${'$'}TRIKESHED_MARKER" ]; then
                    : > "${'$'}TRIKESHED_MARKER"
                    $failure
                fi
                printf '%s\n' '{"model":"fixture","answers":{"q":{"kind":"noul","candidate_probabilities":[0.25,0.75],"null_probability":0,"noul":0.75}},"usage":{}}'
                while IFS= read -r request; do :; done
            """.trimIndent())
            val lane = JevaElement(CoroutineScope(coroutineContext + JvmProcessOperations()),
                JevaConfig(model = "fixture", device = "mlx", worker = executable, timeoutMs = 5_000,
                    env = _m["TRIKESHED_MARKER" j marker.toString()]))
            try {
                assertFails { lane.ask("state", question) }
                assertNull(lane.process, "the failed process must be reaped")
                val answer = assertIs<Map<*, *>>(lane.ask("state", question)["answers"])
                assertEquals(0.75, assertIs<Map<*, *>>(answer["q"])["noul"])
            } finally { lane.drain() }
        }
    }

    @Test fun pythonCompatibilityStillAcceptsItsLineProtocol(): Unit = runBlocking {
        val executable = script("""
            IFS= read -r status || exit 1
            printf '%s\n' '$init'
            while IFS= read -r request; do
                printf '%s\n' '{"model":"fixture","answers":{"q":{"kind":"noul","candidate_probabilities":[0.25,0.75],"null_probability":0,"noul":0.75}},"usage":{}}'
            done
        """.trimIndent())
        val lane = JevaElement(CoroutineScope(coroutineContext + JvmProcessOperations()),
            JevaConfig(model = "fixture", device = "mlx", python = executable, runtime = JevaRuntime.PYTHON, timeoutMs = 5_000))
        try {
            val answer = assertIs<Map<*, *>>(lane.ask("state", question)["answers"])
            assertEquals(0.75, assertIs<Map<*, *>>(answer["q"])["noul"])
        } finally { lane.drain() }
    }

    @Test fun availabilityDetectsAnIdleWorkerExitWithoutReloading(): Unit = runBlocking {
        val pids = directory.resolve("pids")
        val executable = script("""
            IFS= read -r init || exit 1
            printf '%s\n' '$init'
            printf '%s\n' "${'$'}${'$'}" >> "${'$'}TRIKESHED_PID_FILE"
            while IFS= read -r request; do
                case "${'$'}request" in
                    *'"status"'*) printf '%s\n' '$init' ;;
                    *) printf '%s\n' '{"model":"fixture","answers":{"q":{"kind":"noul","candidate_probabilities":[0.25,0.75],"null_probability":0,"noul":0.75}},"usage":{}}' ;;
                esac
            done
        """.trimIndent())
        val lane = JevaElement(CoroutineScope(coroutineContext + JvmProcessOperations()),
            JevaConfig(model = "fixture", device = "mlx", worker = executable, timeoutMs = 5_000,
                env = _m["TRIKESHED_PID_FILE" j pids.toString()]))
        var failure: Throwable? = null
        try {
            assertEquals(true, lane.availability()["available"])
            withTimeout(5_000) { while (!Files.exists(pids) || Files.size(pids) == 0L) delay(10) }
            val first = Files.readString(pids).trim().toLong()
            val child = assertNotNull(ProcessHandle.of(first).orElse(null))
            assertTrue(child.destroyForcibly())
            withTimeout(5_000) { while (child.isAlive) delay(10) }
            val status = lane.availability()
            assertEquals(false, status["available"])
            assertEquals(false, status["loaded"])
            assertEquals("fixture", status["model_identity"])
            assertNull(lane.process)
            assertEquals(1, Files.readAllLines(pids).size, "status must not reload the worker")
            val repeated = lane.availability()
            assertEquals(false, repeated["available"])
            assertEquals(false, repeated["loaded"])
            assertEquals("fixture", repeated["model_identity"])
            assertEquals(1, Files.readAllLines(pids).size, "repeated status must not reload the worker")
            val answer = assertIs<Map<*, *>>(lane.ask("state", question)["answers"])
            assertEquals(0.75, assertIs<Map<*, *>>(answer["q"])["noul"])
            assertEquals(2, Files.readAllLines(pids).size)
        } catch (caught: Throwable) {
            failure = caught
            throw caught
        } finally {
            try { lane.drain() } catch (cleanup: Throwable) {
                val original = failure
                if (original == null) throw cleanup
                if (original !== cleanup) original.addSuppressed(cleanup)
            }
        }
    }
}
