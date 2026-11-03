package borg.trikeshed.narsese

import borg.trikeshed.userspace.nio.channels.spi.HostMemory
import borg.trikeshed.userspace.nio.channels.spi.HostMemoryOperations
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.TimeSource

/** Opt-in sampled host limits. A sample is evidence, not a reservation of system or GPU memory. */
data class JevaHostLimit(
    val minFreeBytes: Long,
    val maxSwapBytes: Long,
    val maxFreeDropBytes: Long,
    val maxSwapIncreaseBytes: Long,
    val sampleMs: Long,
    val lifetimeMs: Long,
) {
    init {
        require(minFreeBytes > 0 && maxSwapBytes >= 0 && maxFreeDropBytes >= 0 && maxSwapIncreaseBytes >= 0)
        require(sampleMs > 0 && lifetimeMs > 0)
    }
}

internal suspend fun JevaHostLimit.watch(operations: HostMemoryOperations): suspend () -> Unit {
    val started = TimeSource.Monotonic.markNow()
    fun remaining(): Long = (lifetimeMs - started.elapsedNow().inWholeMilliseconds).also {
        check(it > 0) { "Jeva host monitor lifetime exceeded" }
    }
    suspend fun read(): HostMemory {
        val sample = withTimeoutOrNull(minOf(sampleMs, remaining())) { operations.sample() }
            ?: error("Jeva host memory sampling timed out")
        remaining()
        require(sample.freeBytes >= 0 && sample.swapBytes >= 0) { "Jeva host memory sample invalid" }
        check(sample.freeBytes >= minFreeBytes && sample.swapBytes <= maxSwapBytes) { "Jeva host memory limit exceeded" }
        return sample
    }
    val baseline = read()
    return {
        while (true) {
            delay(minOf(sampleMs, remaining()))
            val current = read()
            check(current.freeBytes >= baseline.freeBytes - minOf(maxFreeDropBytes, baseline.freeBytes)) {
                "Jeva host free memory drop exceeded"
            }
            check(current.swapBytes - minOf(current.swapBytes, baseline.swapBytes) <= maxSwapIncreaseBytes) {
                "Jeva host swap increase exceeded"
            }
        }
    }
}
