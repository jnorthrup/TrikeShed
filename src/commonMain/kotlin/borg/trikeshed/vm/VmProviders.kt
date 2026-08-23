package borg.trikeshed.vm

import borg.trikeshed.platform.Discontinued
import borg.trikeshed.platform.PlatformHost
import borg.trikeshed.platform.discontinued
import borg.trikeshed.pointcut.VmFacet

/**
 * Provider SPI — the `userspace/nio` pattern (`platformNioProviders` → `NioSupervisor`) applied to
 * VM hosting. Each target lists the tiers it can offer; the supervisor probes them in order and
 * binds the first available one. Node and browser are *separate* providers in the js source set
 * because they cannot share implementations.
 */
data class VmCapabilityReport(
    val providerId: String,
    val available: Boolean,
    val languages: List<String>,
    /** in-process | process | node-vm | worker | none */
    val sandboxKind: String,
    val wallBudgetSupported: Boolean,
    val callSupported: Boolean,
    val note: String = "",
) {
    fun toMap(): Map<String, Any?> = mapOf(
        "providerId" to providerId,
        "available" to available,
        "languages" to languages,
        "sandboxKind" to sandboxKind,
        "wallBudgetSupported" to wallBudgetSupported,
        "callSupported" to callSupported,
        "note" to note,
    )
}

interface VmProvider {
    val id: String
    fun isAvailable(): Boolean = false
    fun report(): VmCapabilityReport = VmCapabilityReport(id, false, emptyList(), "none", false, false, "not provided on this target")
    fun open(): VmHost = discontinued("vm.provider.$id.open")
}

/** Per-target provider list, most preferred first (one expect, exactly like `platformNioProviders`). */
expect fun platformVmProviders(): List<VmProvider>

object VmSupervisor {
    private var bound: VmHost? = null
    private var reportsCache: List<VmCapabilityReport>? = null

    /** Capability reports of every provider on this target, probed once. */
    val reports: List<VmCapabilityReport>
        get() = reportsCache ?: platformVmProviders().map { p ->
            runCatching { p.report() }.getOrElse { VmCapabilityReport(p.id, false, emptyList(), "none", false, false, "probe failed: ${it.message}") }
        }.also { reportsCache = it }

    /** The bound host: first available provider, else [VmHost.NONE] with every provider declared dead. */
    val current: VmHost
        get() = bound ?: bind().also { bound = it }

    fun bind(): VmHost {
        for (p in platformVmProviders()) {
            val ok = runCatching { p.isAvailable() }.getOrDefault(false)
            if (ok) {
                val host = runCatching { p.open() }.getOrNull()
                if (host != null) return host
            }
            Discontinued.declare("vm.provider.${p.id}")
        }
        Discontinued.declare("vm.spawn")
        return VmHost.NONE
    }

    /** Tests and servers that own their host install it here. */
    fun install(host: VmHost) { bound = host }

    fun reset() { bound = null; reportsCache = null }
}

/** The host this platform binds; keeps the existing `PlatformHost.subVm: Any?` stub untouched. */
val PlatformHost.vmHost: VmHost get() = (subVm as? VmHost) ?: VmSupervisor.current

val VmHost.isDead: Boolean get() = this === VmHost.NONE

/** The facet a facet id names (`"js"` → GRAAL_JS; enum names accepted too). */
fun vmFacetOf(id: String): VmFacet? = VmFacet.entries.firstOrNull { it.id == id || it.name == id }
