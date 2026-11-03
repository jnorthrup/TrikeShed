package borg.trikeshed.narsese

import borg.trikeshed.lib.*
import borg.trikeshed.userspace.nio.channels.spi.JvmProcessOperations
import kotlinx.coroutines.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class JevaFootprintDiagnosticTest {
    @org.junit.jupiter.api.io.TempDir lateinit var directory: Path

    private fun worker(body: String): String = directory.resolve("worker.sh").also {
        Files.writeString(it, "#!/bin/sh\n$body\n")
        check(it.toFile().setExecutable(true))
    }.toString()

    private val init = """{"available":true,"loaded":true,"model_identity":"fixture","runtime":"kotlin","device":"mlx"}"""
    private val questions = _m["q" j _m["type" j "noul", "instructions" j "Decide"]]

    @Test fun samplerFailureRevokesWhileOwnerIsActive(): Unit = runBlocking {
        val owner = SupervisorJob()
        val revoked = CompletableDeferred<Unit>()
        val observed = CompletableDeferred<Throwable>()
        val handler = CoroutineExceptionHandler { _, failure -> observed.complete(failure) }
        val scope = CoroutineScope(owner + Dispatchers.Default + handler)
        try {
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                monitorFootprint(owner, { error("sample failed") }, { revoked.complete(Unit) })
            }
            assertNotNull(withTimeoutOrNull(2_000) { revoked.await() }, "A lost sampler must revoke the worker")
            assertEquals("sample failed", withTimeout(2_000) { observed.await() }.message)
            assertTrue(owner.isActive)
        } finally { owner.cancelAndJoin() }
    }

    @Test fun deliberateSamplerShutdownDoesNotRevoke(): Unit = runBlocking {
        val owner = SupervisorJob()
        val sampled = CompletableDeferred<Unit>()
        val revoked = CompletableDeferred<Unit>()
        val scope = CoroutineScope(owner + Dispatchers.Default)
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            monitorFootprint(owner, { sampled.complete(Unit) }, { revoked.complete(Unit) })
        }
        try { withTimeout(2_000) { sampled.await() } } finally { owner.cancelAndJoin() }
        assertFalse(revoked.isCompleted)
    }

    @Test fun samplerRevocationFailureIsNotSwallowed(): Unit = runBlocking {
        val owner = SupervisorJob()
        val observed = CompletableDeferred<Throwable>()
        val handler = CoroutineExceptionHandler { _, failure -> observed.complete(failure) }
        val scope = CoroutineScope(owner + Dispatchers.Default + handler)
        try {
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                monitorFootprint(owner, { error("sample failed") }, { error("revocation failed") })
            }
            assertEquals("revocation failed", withTimeout(2_000) { observed.await() }.message)
        } finally { owner.cancelAndJoin() }
    }

    @Test fun footprintLimitRequiresAnIsolatedMlxWorker(): Unit = runBlocking {
        assertFailsWith<IllegalArgumentException> { JevaConfig(mlxMaxFootprintBytes = 0) }
        assertFailsWith<IllegalArgumentException> { JevaConfig(mlxMaxFootprintBytes = 9_007_199_254_740_992L) }
        assertEquals(123L, JevaConfig.configured({ null }, { if (it == "TRIKESHED_MLX_MAX_FOOTPRINT_BYTES") "123" else null }).mlxMaxFootprintBytes)
        assertFailsWith<IllegalArgumentException> { JevaElement(this, JevaConfig(device = "mlx", mlxMaxFootprintBytes = 1)) }
        val executable = worker("""
            IFS= read -r init || exit 1
            case "${'$'}init" in *'"mlx_max_footprint_bytes":1'*) printf '%s\n' '$init' ;; *) exit 2 ;; esac
            while IFS= read -r request; do :; done
        """.trimIndent())
        val lane = JevaElement(CoroutineScope(coroutineContext + JvmProcessOperations()),
            JevaConfig(model = "fixture", device = "mlx", worker = executable, mlxMaxFootprintBytes = 1, timeoutMs = 5_000))
        try { assertEquals(true, lane.availability()["available"]) } finally { lane.drain() }
    }

    @Test fun footprintMarkerIsReportedWithoutRawStderr(): Unit = runBlocking {
        val executable = worker("""
            IFS= read -r init || exit 1
            printf '%s\n' '$init'
            IFS= read -r request || exit 1
            printf '%s\n' 'TRIKESHED_MLX_FOOTPRINT_LIMIT' 'private-worker-detail' >&2
            exit 71
        """.trimIndent())
        val lane = JevaElement(CoroutineScope(coroutineContext + JvmProcessOperations()),
            JevaConfig(model = "fixture", device = "mlx", worker = executable, timeoutMs = 5_000, mlxMaxFootprintBytes = 1))
        try {
            assertEquals(true, lane.availability()["available"])
            val failure = assertFails { withTimeout(3_000) { lane.ask("state", questions) } }
            assertContains(failure.message.orEmpty(), "physical footprint")
            assertFalse(failure.message.orEmpty().contains("private-worker-detail"))
            assertNotNull(failure.cause, "The original transport failure must remain available as the cause")
        } finally { lane.drain() }
    }

    @Test fun footprintMarkerOnFailedWriteIsReported(): Unit = runBlocking {
        val ready = directory.resolve("ready")
        val executable = worker("""
            IFS= read -r init || exit 1
            printf '%s\n' '$init'
            exec 0<&-
            printf '%s\n' 'TRIKESHED_MLX_FOOTPRINT_LIMIT' 'private-worker-detail' >&2
            : > "${'$'}TRIKESHED_READY_FILE"
            exec /bin/sleep 30
        """.trimIndent())
        val lane = JevaElement(CoroutineScope(coroutineContext + JvmProcessOperations()),
            JevaConfig(model = "fixture", device = "mlx", worker = executable, timeoutMs = 5_000,
                mlxMaxFootprintBytes = 1, env = _m["TRIKESHED_READY_FILE" j ready.toString()]))
        try {
            assertEquals(true, lane.availability()["available"])
            withTimeout(3_000) { while (!Files.exists(ready)) delay(10) }
            val failure = assertFails { withTimeout(3_000) { lane.ask("state", questions) } }
            assertContains(failure.message.orEmpty(), "physical footprint")
            assertFalse(failure.message.orEmpty().contains("private-worker-detail"))
            assertNotNull(failure.cause, "The original transport failure must remain available as the cause")
            assertNull(lane.process)
        } finally { lane.drain() }
    }

    @Test fun markerWithoutConfiguredLimitDoesNotClaimRevocation(): Unit = runBlocking {
        val executable = worker("""
            IFS= read -r init || exit 1
            printf '%s\n' '$init'
            IFS= read -r request || exit 1
            printf '%s\n' 'TRIKESHED_MLX_FOOTPRINT_LIMIT' >&2
            exit 71
        """.trimIndent())
        val lane = JevaElement(CoroutineScope(coroutineContext + JvmProcessOperations()),
            JevaConfig(model = "fixture", device = "mlx", worker = executable, timeoutMs = 5_000))
        try {
            assertEquals(true, lane.availability()["available"])
            val failure = assertFails { withTimeout(3_000) { lane.ask("state", questions) } }
            assertFalse(failure.message.orEmpty().contains("physical footprint"))
        } finally { lane.drain() }
    }

    @Test fun markerDoesNotRelabelAnExchangeTimeout(): Unit = runBlocking {
        val executable = worker("""
            IFS= read -r init || exit 1
            printf '%s\n' '$init'
            IFS= read -r request || exit 1
            printf '%s\n' 'TRIKESHED_MLX_FOOTPRINT_LIMIT' >&2
            exec /bin/sleep 30
        """.trimIndent())
        val lane = JevaElement(CoroutineScope(coroutineContext + JvmProcessOperations()),
            JevaConfig(model = "fixture", device = "mlx", worker = executable, timeoutMs = 400, mlxMaxFootprintBytes = 1))
        try {
            assertEquals(true, lane.availability()["available"])
            val failure = assertFails { withTimeout(3_000) { lane.ask("state", questions) } }
            assertIs<TimeoutCancellationException>(failure)
            assertFalse(failure.message.orEmpty().contains("physical footprint"))
        } finally { lane.drain() }
    }

    @Test fun silentCrashDoesNotClaimFootprintRevocation(): Unit = runBlocking {
        val executable = worker("""
            IFS= read -r init || exit 1
            printf '%s\n' '$init'
            IFS= read -r request || exit 1
            exit 71
        """.trimIndent())
        val lane = JevaElement(CoroutineScope(coroutineContext + JvmProcessOperations()),
            JevaConfig(model = "fixture", device = "mlx", worker = executable, timeoutMs = 5_000))
        try {
            assertEquals(true, lane.availability()["available"])
            val failure = assertFails { withTimeout(3_000) { lane.ask("state", questions) } }
            assertFalse(failure.message.orEmpty().contains("physical footprint"))
        } finally { lane.drain() }
    }

    @Test fun closedStdoutWithOpenStderrDoesNotHoldTheLane(): Unit = runBlocking {
        val executable = worker("""
            IFS= read -r init || exit 1
            printf '%s\n' '$init'
            IFS= read -r request || exit 1
            exec 1>&-
            exec /bin/sleep 30
        """.trimIndent())
        val lane = JevaElement(CoroutineScope(coroutineContext + JvmProcessOperations()),
            JevaConfig(model = "fixture", device = "mlx", worker = executable, timeoutMs = 5_000))
        try {
            assertEquals(true, lane.availability()["available"])
            val failure = assertFails { withTimeout(3_000) { lane.ask("state", questions) } }
            assertFalse(failure is TimeoutCancellationException, "EOF handling must not wait for the child's open stderr indefinitely")
            assertFalse(failure.message.orEmpty().contains("physical footprint"))
            assertNull(lane.process)
        } finally { lane.drain() }
    }
}
