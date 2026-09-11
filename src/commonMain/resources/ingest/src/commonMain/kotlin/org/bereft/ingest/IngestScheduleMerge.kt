package org.bereft.ingest

import borg.trikeshed.lib.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * Default fan-in merge for [IngestSchedule.scheduleBatch].
 *
 * Spawns one coroutine per source that drains each source's own result channel
 * into the merged channel. The merged channel is closed when every per-source
 * job completes. No artificial dependency ladder — all sources are independent
 * and run side-by-side.
 *
 * Structured concurrency: the fan-in runs in [scope] and is cancelled when the
 * scope is. Callers own the scope.
 */
object IngestScheduleMerge {

    fun run(
        scope: CoroutineScope,
        scheduler: IngestSchedule,
        sources: Series<String>,
        projections: Set<IngestProjection>,
        capacity: Int = Channel.BUFFERED,
    ): Channel<IngestEnvelope> {
        val merged = Channel<IngestEnvelope>(capacity)
        // PRELOAD: iterate the String Series via index, not (0 until n).map.
        val n = sources.size
        val jobs = ArrayList<Job>(n)
        for (i in 0 until n) {
            val path = sources.b(i)
            jobs += scope.launch {
                val ch = scheduler.schedule(path, projections)
                for (result in ch) {
                    merged.send(result)
                }
            }
        }
        // Supervisor: close the merged channel once every per-source job is done.
        scope.launch {
            jobs.forEach { it.join() }
            merged.close()
        }
        return merged
    }
}
