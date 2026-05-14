package borg.trikeshed.userspace.kernel

import borg.trikeshed.lib.ByteSeries
import borg.trikeshed.userspace.nio.spi.ByteRegion
import kotlin.time.Duration

/**
 * Reactor-level readiness surface. Speaks ByteRegion/ByteSeries — no ByteArray calling conventions.
 * Readiness polling is separated from buffer transfer so callers control buffer lifecycle.
 */

interface SelectableChannelOps {
    suspend fun pollReadable(timeout: Duration? = null): Boolean
    suspend fun pollWritable(timeout: Duration? = null): Boolean
    fun tryRead(dst: ByteRegion): Int
    fun tryWrite(src: ByteSeries): Int
}


