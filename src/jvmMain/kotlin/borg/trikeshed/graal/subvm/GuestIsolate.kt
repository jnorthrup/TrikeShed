package borg.trikeshed.graal.subvm

import borg.trikeshed.job.ContentId
import borg.trikeshed.pointcut.VmFacet

/** Trust decides the wall between host and guest: in-process for our own code, a child JVM for anyone else's. */
enum class Trust { OWN, UNTRUSTED }

/** Who served a delegated call. GUEST = original guest function; MEMO = content-hash table; HOST = warm leaf isolate. */
enum class Served { GUEST, MEMO, HOST, SHADOW }

/** Budget a lease grants an isolate. Zero = unlimited for that dimension; [calls] is per guest root. */
data class Budget(
    val statements: Long = GuestBounds.DEFAULT_STATEMENT_LIMIT,
    val wallMillis: Long = GuestBounds.DEFAULT_WALL_MILLIS,
    val calls: Long = 0,
)

/**
 * One crossing of the isolate boundary, content-addressed. The receipt is what the blackboard
 * lands, what the Rete rules reason over, and what a later audit replays.
 */
data class DelegationReceipt(
    val isolate: String,
    val root: String,
    val argsCid: ContentId,
    val resultCid: ContentId,
    val served: Served,
    val nanos: Long,
    val seq: Int,
    val refuted: Boolean = false,
) {
    val cid: ContentId get() = ContentId.of("$isolate|$root|${argsCid.hex}|${resultCid.hex}|$served|$refuted".encodeToByteArray())
    override fun toString() = "delegate[$served] $root args=${argsCid.hex.take(12)} → ${resultCid.hex.take(12)} ${nanos / 1000}µs seq=$seq${if (refuted) " REFUTED" else ""}"
}

/** Live counters an isolate exposes; snapshot is cheap and lock-free. */
data class IsolateStats(
    val evals: Long,
    val calls: Long,
    val hostCalls: Long,
    val rootEnters: Long,
    val delegationsMemo: Long,
    val delegationsHost: Long,
    val refutations: Long,
    val interrupted: Long,
)

/**
 * The sub-VM contract. Both [InProcessIsolate] (a Graal [org.graalvm.polyglot.Context] behind a lock)
 * and [ProcessIsolate] (a child JVM over a line protocol) implement it, so the [Hypervisor] and the
 * blackboard never know which wall they are behind.
 *
 * Everything in and out is [Teleported]; nothing here returns a polyglot Value.
 */
interface GuestIsolate : AutoCloseable {
    val id: String
    val facet: VmFacet
    val trust: Trust
    val bounds: FacetBounds get() = GuestBounds.of(facet)

    /** Evaluate guest source; the result is teleported. */
    fun eval(source: String, name: String = "<eval>"): Teleported

    /** Call a guest-global function with teleported args (host → guest delegation). */
    fun call(root: String, vararg args: Teleported): Teleported

    /** Register a host function the guest may call as `host.call("name", ...)` (guest → host delegation). */
    fun delegate(name: String, fn: (List<Teleported>) -> Teleported)

    /** Stop whatever is running using the facet's [StopStrategy]; the isolate may or may not survive. */
    fun interrupt(): Boolean

    fun stats(): IsolateStats
    val isAlive: Boolean
}
