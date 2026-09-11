package org.bereft.ingest

import borg.trikeshed.lib.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel

/**
 * SPI for scheduling media/format ingest into a Forge document tree.
 *
 * This is the open seam. Platform implementations register through the
 * existing TrikeShed SPI provider pattern
 * (see `borg.trikeshed.userspace.nio.file.spi.FileSystemProvider`); this
 * interface itself stays in commonMain and carries no execution logic.
 *
 * Ported from the J01 ingest-cascade contract. The original brief proposed
 * editing CCEK.kt to add an `IngestComplete` ForgeSignal — as a standalone
 * project we instead expose results via [Channel] and let the host wire them
 * into whatever reactor it has.
 */
interface IngestSchedule {

    /**
     * Schedule an ingest job for [source]. Returns a [Channel] that receives
     * [IngestEnvelope] as each projection completes. Non-blocking — the caller
     * drains the channel at its own pace.
     */
    fun schedule(
        source: String,
        projections: Set<IngestProjection>,
    ): Channel<IngestEnvelope>

    /**
     * Schedule a batch of sources. Returns a single merged channel. Fan-in
     * requires a [CoroutineScope]; see [IngestScheduleMerge.run] for the
     * default implementation platform impls delegate to.
     *
     * PRELOAD: [sources] is a [Series]<String>, not a List — size paired with
     * an index oracle, projected with `α` rather than mapped.
     */
    fun scheduleBatch(
        scope: CoroutineScope,
        sources: Series<String>,
        projections: Set<IngestProjection>,
    ): Channel<IngestEnvelope> = IngestScheduleMerge.run(scope, this, sources, projections)
}
