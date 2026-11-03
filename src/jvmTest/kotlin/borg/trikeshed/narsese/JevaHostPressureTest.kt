package borg.trikeshed.narsese

import borg.trikeshed.lib.*
import borg.trikeshed.userspace.nio.channels.spi.*
import kotlinx.coroutines.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class JevaHostPressureTest {
    @org.junit.jupiter.api.io.TempDir lateinit var directory: Path

    private val baseline = HostMemory(1_000, 100)
    private val limit = JevaHostLimit(500, 300, 300, 100, 20, 5_000)
    private val question = _m["q" j _m["type" j "noul", "instructions" j "Decide"]]
    private val init = """{"available":true,"loaded":true,"model_identity":"fixture","runtime":"kotlin","device":"mlx"}"""

    private fun worker(): Pair<String, Path> {
        val pid = directory.resolve("pid")
        val executable = directory.resolve("worker.sh")
        Files.writeString(executable, """
            #!/bin/sh
            printf '%s' "${'$'}${'$'}" > "${'$'}TRIKESHED_PID_FILE"
            IFS= read -r init || exit 1
            printf '%s\n' '$init'
            IFS= read -r request || exit 0
            exec /bin/sleep 30
        """.trimIndent() + "\n")
        check(executable.toFile().setExecutable(true))
        return executable.toString() to pid
    }

    private fun lane(scope: CoroutineScope, worker: String, pid: Path, memory: HostMemoryOperations, limits: JevaHostLimit = limit) =
        JevaElement(CoroutineScope(scope.coroutineContext + JvmProcessOperations() + memory),
            JevaConfig(model = "fixture", device = "mlx", worker = worker, timeoutMs = 5_000,
                env = _m["TRIKESHED_PID_FILE" j pid.toString()], hostLimit = limits))

    private suspend fun child(pid: Path): ProcessHandle {
        withTimeout(5_000) { while (!Files.exists(pid) || Files.size(pid) == 0L) delay(5) }
        return ProcessHandle.of(Files.readString(pid).trim().toLong()).orElseThrow()
    }

    private class Trace(private val first: HostMemory, private val next: CompletableDeferred<HostMemory>) : HostMemoryOperations {
        override suspend fun sample(): HostMemory = if (next.isCompleted) next.await() else first
    }

    @Test fun preflightRejectsUnsafeOrUnavailableMetricsBeforeStartingAChild() = runBlocking {
        for (sample in _l.get<suspend () -> HostMemory>(
            { HostMemory(499, 100) }, { HostMemory(1_000, 301) }, { error("unavailable") },
        )) {
            val (executable, pid) = worker()
            val memory = object : HostMemoryOperations { override suspend fun sample() = sample() }
            val lane = lane(this, executable, pid, memory)
            try {
                assertEquals(false, lane.availability()["available"])
                assertFalse(Files.exists(pid), "preflight must precede process creation")
            } finally { lane.drain() }
        }
    }

    @Test fun stalledPreflightRejectsBeforeChildCreationAndDrains() = runBlocking {
        val (executable, pid) = worker()
        val pending = CompletableDeferred<HostMemory>()
        val memory = object : HostMemoryOperations { override suspend fun sample() = pending.await() }
        val lane = lane(this, executable, pid, memory, limit.copy(sampleMs = 100))
        try {
            assertEquals(false, withTimeout(5_000) { lane.availability() }["available"])
            assertFalse(Files.exists(pid), "a stalled preflight must not start the child")
            assertFalse(pending.isCompleted)
        } finally { withTimeout(5_000) { lane.drain() } }
        assertTrue(lane.supervisor.isCompleted)
    }

    @Test fun aThresholdCrossingRevokesAndReapsOnlyTheOwnedChild() = runBlocking {
        val (executable, pid) = worker()
        val next = CompletableDeferred<HostMemory>()
        val lane = lane(this, executable, pid, Trace(baseline, next))
        val unrelated = ProcessBuilder("/bin/sleep", "30").start()
        try {
            assertEquals(true, lane.availability()["available"])
            val owned = child(pid)
            val request = async { runCatching { lane.ask("state", question) } }
            next.complete(HostMemory(699, 100))
            assertTrue(withTimeout(5_000) { request.await() }.isFailure)
            withTimeout(5_000) { while (owned.isAlive) delay(5) }
            assertFalse(owned.isAlive)
            assertTrue(unrelated.isAlive)
        } finally {
            try { lane.drain() } finally { unrelated.destroyForcibly(); unrelated.waitFor() }
        }
    }

    @Test fun samplingFailureRevokesAndReapsTheOwnedChild() = runBlocking {
        val (executable, pid) = worker()
        val next = CompletableDeferred<HostMemory>()
        val lane = lane(this, executable, pid, Trace(baseline, next))
        try {
            assertEquals(true, lane.availability()["available"])
            val owned = child(pid)
            val request = async { runCatching { lane.ask("state", question) } }
            next.completeExceptionally(IllegalStateException("sample failed"))
            assertTrue(withTimeout(5_000) { request.await() }.isFailure)
            withTimeout(5_000) { while (owned.isAlive) delay(5) }
        } finally { lane.drain() }
    }

    @Test fun ordinaryDrainCancelsTheSamplerAndReapsTheChild() = runBlocking {
        val (executable, pid) = worker()
        val next = CompletableDeferred<HostMemory>()
        val lane = lane(this, executable, pid, Trace(baseline, next))
        assertEquals(true, lane.availability()["available"])
        val owned = child(pid)
        withTimeout(5_000) { lane.drain() }
        assertFalse(owned.isAlive)
        assertFalse(next.isCompleted, "normal cancellation must not be a sampling failure")
    }

    @Test fun idleLifetimeExpiryReapsTheChildAndCompletesTheSupervisor() = runBlocking {
        val (executable, pid) = worker()
        val memory = object : HostMemoryOperations { override suspend fun sample() = baseline }
        val lane = lane(this, executable, pid, memory, limit.copy(sampleMs = 30, lifetimeMs = 300))
        try {
            assertEquals(true, lane.availability()["available"])
            val owned = child(pid)
            withTimeout(5_000) { while (owned.isAlive) delay(5) }
            assertEquals(false, withTimeout(5_000) { lane.availability() }["available"])
            assertNull(lane.process)
        } finally { runCatching { withTimeout(5_000) { lane.drain() } } }
        assertTrue(lane.supervisor.isCompleted)
    }

    @Test fun stalledSamplerRevokesChildAndCompletesTheSupervisor() = runBlocking {
        val (executable, pid) = worker()
        val pending = CompletableDeferred<HostMemory>()
        var calls = 0
        val memory = object : HostMemoryOperations {
            override suspend fun sample() = if (calls++ == 0) baseline else pending.await()
        }
        val lane = lane(this, executable, pid, memory, limit.copy(sampleMs = 100))
        try {
            assertEquals(true, lane.availability()["available"])
            val owned = child(pid)
            withTimeout(5_000) { while (owned.isAlive) delay(5) }
        } finally { runCatching { withTimeout(5_000) { lane.drain() } } }
        assertTrue(lane.supervisor.isCompleted)
        assertFalse(pending.isCompleted)
    }

    @Test fun samplerCancellationWithoutOwnerCancellationRevokesChild() = runBlocking {
        val (executable, pid) = worker()
        val next = CompletableDeferred<HostMemory>()
        val lane = lane(this, executable, pid, Trace(baseline, next))
        try {
            assertEquals(true, lane.availability()["available"])
            val owned = child(pid)
            next.completeExceptionally(CancellationException("provider failed"))
            withTimeout(5_000) { while (owned.isAlive) delay(5) }
        } finally { runCatching { withTimeout(5_000) { lane.drain() } } }
        assertTrue(lane.supervisor.isCompleted)
    }

    @Test fun hostConfigurationIsCompleteAndValidated() {
        val env = _m[
            "TRIKESHED_JEVA_WORKER" j "/worker",
            "TRIKESHED_JEVA_HOST_MIN_FREE_BYTES" j "500",
            "TRIKESHED_JEVA_HOST_MAX_SWAP_BYTES" j "300",
            "TRIKESHED_JEVA_HOST_MAX_FREE_DROP_BYTES" j "300",
            "TRIKESHED_JEVA_HOST_MAX_SWAP_INCREASE_BYTES" j "100",
            "TRIKESHED_JEVA_HOST_SAMPLE_MS" j "20",
            "TRIKESHED_JEVA_HOST_LIFETIME_MS" j "5000",
        ]
        assertEquals(limit, JevaConfig.configured({ null }, env::get).hostLimit)
        assertFailsWith<IllegalArgumentException> {
            JevaConfig.configured({ null }, (env - "TRIKESHED_JEVA_HOST_SAMPLE_MS")::get)
        }
        assertFailsWith<IllegalArgumentException> {
            JevaConfig.configured({ null }, (env + _m["TRIKESHED_JEVA_HOST_SAMPLE_MS" j ""])::get)
        }
        assertFailsWith<IllegalArgumentException> {
            JevaConfig.configured({ null }, (env - "TRIKESHED_JEVA_WORKER")::get)
        }
        assertFailsWith<IllegalArgumentException> { limit.copy(sampleMs = 0) }
        assertFailsWith<IllegalArgumentException> { limit.copy(maxSwapBytes = -1) }
    }
}
