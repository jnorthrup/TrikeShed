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
    /** Platform-visible backend name, e.g. "io_uring", "posix_aio", "kqueue", "wepoll", "js_fetch", "jvm_nio". */
    val backendName: String,
    /** True when the Linux host has a usable io_uring instance at launch. */
    val ioUringAvailable: Boolean,
    /** Human-readable capability vector: "read", "write", "fsync", "poll", "net". */
    val capabilities: List<String>,
    /** Best-effort kernel/module hint. Empty when not on Linux or unavailable. */
    val kernelHint: String,
    /** Epoch ms when the report was produced. */
    val checkedAt: Long,
) : CoroutineContext.Element {
    companion object Key : CoroutineContext.Key<NioCapabilityReport>
    override val key: CoroutineContext.Key<*> get() = Key

    fun toReadable(): String = buildString {
        append("NIO backend=$backendName uring=$ioUringAvailable")
        if (capabilities.isNotEmpty()) append(" caps=${capabilities.joinToString(",")}")
        if (kernelHint.isNotBlank()) append(" kernel=$kernelHint")
    }
}
