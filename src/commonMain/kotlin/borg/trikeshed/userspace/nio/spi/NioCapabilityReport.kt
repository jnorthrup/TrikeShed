package borg.trikeshed.userspace.nio.spi

import kotlinx.serialization.Serializable
import kotlin.coroutines.CoroutineContext

/**
 * Launch-time report of the available native I/O backend.
 *
 * This element is registered into [NioSupervisor] by each platform's
 * [platformNioProviders] so dashboards and the Forge UI can report which
 * backend is actually executing I/O without guessing from the source set.
 */
@Serializable
data class NioCapabilityReport(
    /** JVM/Node report "io_uring" or "uring_emulated" from the canonical selector. */
    val backendName: String,
    /** True when the Linux host has a usable io_uring instance at launch. */
    val ioUringAvailable: Boolean,
    /** Human-readable capability vector: "read", "write", "fsync", "poll", "net". */
    val capabilities: List<String>,
    /** Best-effort kernel/module hint. Empty when not on Linux or unavailable. */
    val kernelHint: String,
    /** Epoch ms when the report was produced. */
    val checkedAt: Long,
    /** Structured module/probe evidence; absent on targets not yet wired to host discovery. */
    val uringProbe: borg.trikeshed.userspace.UringProbeReport? = null,
) : CoroutineContext.Element {
    companion object Key : CoroutineContext.Key<NioCapabilityReport>
    override val key: CoroutineContext.Key<*> get() = Key

    fun toReadable(): String = buildString {
        append("NIO backend=$backendName uring=$ioUringAvailable")
        if (capabilities.isNotEmpty()) append(" caps=${capabilities.joinToString(",")}")
        if (kernelHint.isNotBlank()) append(" kernel=$kernelHint")
    }
}
