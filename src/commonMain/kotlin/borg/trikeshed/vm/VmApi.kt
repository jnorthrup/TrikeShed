package borg.trikeshed.vm

import borg.trikeshed.cursor.Cursor
import borg.trikeshed.platform.discontinued
import borg.trikeshed.pointcut.VmFacet
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * The common VM API — the contract every hosting tier speaks, on every target.
 *
 * Tiers (preferred order): in-process Graal DAG leafs (jvm) → process isolates speaking
 * [SubVmProtocol] (jvm, POSIX) → node `vm` / browser `Worker` sandboxes (js, wasmJs). A host
 * (jvm or native) may hold process children that are themselves whole Graal DAGs, so a leaf can
 * move between tiers without its callers changing: [Teleported] is the one value ABI at every edge.
 *
 * Every operation has a [discontinued] default: a tier overrides only what it provides, and what
 * it does not provide is dead by construction and reported by the Forge host view.
 */
data class VmBudget(val statements: Long = 0, val wallMillis: Long = 0, val calls: Long = 0) // 0 = unlimited

enum class VmTrust { OWN, UNTRUSTED }

data class VmSpec(
    val id: String,
    val facet: VmFacet,
    val trust: VmTrust = VmTrust.OWN,
    val budget: VmBudget = VmBudget(),
    /**
     * Host directories seeded (text/source files only) into a private guest world at
     * `/workspace/<dirname>` before first eval. Non-empty ⇒ the guest gets a
     * snapshot-capable VFS instead of IOAccess.NONE. OWN trust only.
     */
    val world: List<String> = emptyList(),
    /**
     * Guest module whose classpath this VM mounts — a directory under `utils/subvm` in the same
     * shape as a TrikeShed deploy (`classes/` then the jars in `lib/`). When set, the guest
     * resolves host classes from THAT classpath instead of the daemon's own, which is how
     * `vm.corenlp` and `vm.camel` call real libraries that are deliberately NOT dependencies of
     * TrikeShed. Null keeps the previous behaviour (resolve from the host classpath, JVM facet only).
     */
    val module: String? = null,
)

data class VmStats(
    val evals: Long = 0,
    val calls: Long = 0,
    val hostCalls: Long = 0,
    val refutations: Long = 0,
    val interrupted: Long = 0,
)

sealed class VmEvent {
    abstract val vmId: String
    abstract val seq: Long
    data class Spawned(override val vmId: String, override val seq: Long, val spec: VmSpec) : VmEvent()
    data class Evaluated(override val vmId: String, override val seq: Long, val resultCid: String, val nanos: Long) : VmEvent()
    data class Revoked(override val vmId: String, override val seq: Long, val reason: String) : VmEvent()
    data class Landed(override val vmId: String, override val seq: Long, val root: String, val property: String, val value: String) : VmEvent()

    fun toMap(): Map<String, Any?> = when (this) {
        is Spawned -> mapOf("kind" to "spawned", "vmId" to vmId, "seq" to seq, "facet" to spec.facet.id, "trust" to spec.trust.name)
        is Evaluated -> mapOf("kind" to "evaluated", "vmId" to vmId, "seq" to seq, "resultCid" to resultCid, "nanos" to nanos)
        is Revoked -> mapOf("kind" to "revoked", "vmId" to vmId, "seq" to seq, "reason" to reason)
        is Landed -> mapOf("kind" to "landed", "vmId" to vmId, "seq" to seq, "root" to root, "property" to property, "value" to value)
    }
}

/** One guest, behind whichever wall its tier provides. */
interface VmHandle : AutoCloseable {
    val id: String
    val facet: VmFacet
    /** in-process | process | node-vm | worker | none */
    val tier: String get() = "none"
    fun eval(source: String, name: String = "<eval>"): Teleported = discontinued("vm.eval")
    fun call(root: String, vararg args: Teleported): Teleported = discontinued("vm.call")
    fun stats(): VmStats = discontinued("vm.stats")
    val isAlive: Boolean get() = false
    override fun close() {}
}

/** The hypervisor contract. [NONE] is the bare interface: everything dead, nothing hidden. */
interface VmHost : AutoCloseable {
    val platform: String get() = "none"
    val languages: Set<VmFacet> get() = emptySet()
    fun spawn(spec: VmSpec): VmHandle = discontinued("vm.spawn")
    fun get(id: String): VmHandle? = null
    fun ids(): List<String> = emptyList()
    fun revoke(id: String, reason: String): Unit = discontinued("vm.revoke")
    /** Every guest as a Cursor row ([VM_COLUMNS]); the Forge sheet/board/graph views render it as-is. */
    fun rows(): Cursor = emptyList<VmRow>().asCursor()
    val events: Flow<VmEvent> get() = emptyFlow()
    override fun close() {}

    companion object {
        val NONE: VmHost = object : VmHost {}
    }
}
