@file:Suppress("UNCHECKED_CAST")

package borg.trikeshed.context.nuid

import borg.trikeshed.context.AsyncContextElement
import borg.trikeshed.context.AsyncContextKey
import borg.trikeshed.context.ElementState
import borg.trikeshed.lib.Join
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.get
import borg.trikeshed.lib.j
import borg.trikeshed.lib.size
import kotlinx.coroutines.Job
import kotlin.random.Random

/**
 * NUID — Non-designated Unique IDentifier with concentric-subnet authorization.
 *
 *   NUID = Join<Capability, Join<Nonce, Subnet>>
 *
 * The Capability is the **what** (Hilbert-space principal — process, CAS,
 * wireproto, sctp, modelmux, …). The Subnet is the **where** (concentric
 * routing scope: `local` ⊃ `lan.localhost` ⊃ `mesh.worker.<id>` ⊃
 * `global.relay`). The Nonce is an undesignated random bearer — unguessable,
 * revocable, never reused.
 *
 * NUID is a bearer token, not a user identity. Two holders of the same
 * Capability + Subnet are interchangeable for routing purposes; only the
 * Nonce distinguishes bearers. This is the ARPANET-style "capability, not
 * identity" compromise PRELOAD.md gestures at.
 *
 * Subnet containment (concentric): a worker registered on `lan.localhost`
 * sees requests from `local`, `lan.localhost`, and any inner scope below.
 * It does **not** see `mesh.worker.<id>` or `global.relay` activity.
 * Authority flows inward, not outward.
 *
 * Trait matching: a Workgroup advertises a `TraitSpace` (the Capabilities
 * it can fulfill). A request carrying an unrelated Capability does not
 * route, even if the Subnet matches. Both sides of the dispatch must
 * satisfy trait × subnet.
 *
 * Lifecycle: this file deliberately does NOT participate in the CCEK
 * fanout element. The element that owns the Lifecycle for a NUID-bearing
 * fanout is `LcncFanoutElement` (T-CCEK-FANOUT, next cut). Here we only
 * define the algebra.
 */

// ── Capability ───────────────────────────────────────────────────────────

/**
 * A Hilbert-space capability identifier. Sealed so the set of authorized
 * verbs is finite and recognizable; new capabilities extend the hierarchy
 * (a `data class` variant over an existing category), not the open
 * namespace. Each leaf knows its family root — matching is exact at the
 * leaf or wildcard at the family.
 */
sealed class Capability(val category: String) {
    open fun familyRoot(): Capability? = null

    // ── Process / IO verb family ────────────────────────────────────
    data class Process(val name: String) : Capability("process") {
        override fun familyRoot(): Capability = ProcessAll
    }
    data class Cas(val mode: String) : Capability("cas") {
        override fun familyRoot(): Capability = CasAll
    }
    data class Wireproto(val route: String) : Capability("wireproto") {
        override fun familyRoot(): Capability = WireprotoAll
    }

    // ── Reactor / mesh verb family ──────────────────────────────────
    data object Sctp : Capability("sctp") {
        override fun familyRoot(): Capability = SctpAll
    }
    data object Model : Capability("modelmux") {
        override fun familyRoot(): Capability = ModelAll
    }
    data object BlackBoard : Capability("blackboard") {
        override fun familyRoot(): Capability = BlackBoardAll
    }

    /** Free-form catch-all for caps that don't fit a family yet. */
    data class Custom(val kind: String, val token: String) : Capability("custom") {
        override fun familyRoot(): Capability = CustomAll
    }

    /** Wildcard roots — match any leaf in the same family. */
    data object ProcessAll : Capability("process*")
    data object CasAll : Capability("cas*")
    data object WireprotoAll : Capability("wireproto*")
    data object SctpAll : Capability("sctp*")
    data object ModelAll : Capability("modelmux*")
    data object BlackBoardAll : Capability("blackboard*")
    data object CustomAll : Capability("custom*")

    data object Trajectory : Capability("trajectory") { override fun familyRoot() = TrajectoryAll }
    data object TrajectoryAll : Capability("trajectory*")
}

/** Internal capability equality + family matching. */
infix fun Capability.matches(other: Capability): Boolean {
    if (this == other) return true
    if (this == other.familyRoot()) return true
    if (this.familyRoot() == other) return true
    return false
}

// ── Subnet ──────────────────────────────────────────────────────────────

/**
 * A concentric routing scope, parsed once at the CCEK boundary.
 *
 * Concentric: a worker registered on scope X can fulfill requests whose
 * Subnet lives at scope X or any inner scope. Routing from outer to inner
 * is the **only** allowed direction.
 *
 * Examples (innermost → outermost):
 *   core                                              (worker-local)
 *   process.core                                      (this process only)
 *   local                                             (the machine)
 *   lan.localhost                                     (the LAN)
 *   mesh.worker.<id>                                  (a mesh worker)
 *   global.relay                                      (a public relay)
 *
 * Grammar: dot-separated. Each level is a string. The number of segments
 * is the level; lower level is always strictly inner.
 */
data class Subnet(val segments: Series<String>) {
    /** Level: number of segments. Lower level ⇒ strictly inner. */
    val level: Int get() = segments.size

    /** The string form (`a.b.c`). */
    override fun toString(): String = (0 until segments.size).joinToString(".") { segments[it] }

    companion object {
        /** Parse a dotted path. Empty string → `core` (innermost). */
        fun parse(text: String): Subnet {
            if (text.isEmpty()) return core
            val parts = text.split('.')
            return Subnet(parts.size j { i -> parts[i] })
        }

        /** Innermost scope — the worker's own private namespace. */
        val core: Subnet = Subnet(1 j { "core" })
        /** This process only. */
        val process: Subnet = Subnet(2 j { i -> if (i == 0) "process" else "self" })
        /** The local machine. */
        val local: Subnet = Subnet(1 j { "local" })
        /** The LAN. */
        val lanLocalhost: Subnet = Subnet(2 j { i -> if (i == 0) "lan" else "localhost" })
    }
}

/**
 * Concentric containment: this Subnet ≤ `other` iff `other.level ≥ this.level`
 * and every segment of `this` matches the corresponding segment of `other`.
 *
 * Routing rule: a worker registered at scope `W` may dispatch a request
 * scoped at `R` **only if** `R` is contained in `W` (the worker's scope
 * covers at least the request's prefix).
 */
infix fun Subnet.contains(other: Subnet): Boolean {
    if (other.level < level) return false           // other is too far in
    val ownSize = level
    if (ownSize == 0) return true                   // root covers all
    for (i in 0 until ownSize) {
        if (this.segments[i] != other.segments[i]) return false
    }
    return true
}

// ── Nonce ────────────────────────────────────────────────────────────────

/**
 * An undesignated random bearer token. Treated as opaque bytes; the
 * length is parameterized so different Nix modes can pick the strength
 * they need.
 *
 * `Random` is the default. `Derived` is provided so a causally-refined
 * task can chain its prior `causalKey` without leaking the random bearer.
 */
sealed class Nonce(val bytes: ByteArray) {
    init { require(bytes.isNotEmpty()) { "Nonce must be non-empty" } }
    override fun equals(other: Any?): Boolean = other is Nonce && bytes.contentEquals(other.bytes)
    override fun hashCode(): Int = bytes.contentHashCode()

    /** Random nonce — length bytes from a Random source. */
    class RandomBytes(length: Int = 16, rng: Random = Random(0L)) :
        Nonce(ByteArray(length) { rng.nextInt(0, 256).toByte() })

    /** Deterministic nonce — derived from a prior causalKey, so refreshes
     *  can be causally traced. Always 32 bytes. */
    class Derived(priorCausalKey: String) : Nonce(derive(priorCausalKey))

    /** Restored nonce — constructed from arbitrary bytes during deserialization. */
    class Restored(bytes: ByteArray) : Nonce(bytes)

    companion object {
        private fun derive(text: String): ByteArray {
            var h1 = 0x811c9dc5.toInt()
            var h2 = 0xdeadbeef.toInt()
            val bytes = text.encodeToByteArray()
            for (b in bytes) {
                h1 = (h1 xor b.toInt()) * 0x01000193
                h2 = (h2 xor b.toInt()) * 0x85ebca6b.toInt()
            }
            val out = ByteArray(32)
            for (i in 0 until 8) out[i] = (h1 ushr (i * 4)).toByte()
            for (i in 0 until 8) out[8 + i] = (h2 ushr (i * 4)).toByte()
            for (i in 16 until 32) out[i] = (bytes[i % bytes.size].toInt() xor h1 xor h2).toByte()
            return out
        }
    }
}

// ── NUID ─────────────────────────────────────────────────────────────────

/**
 * The wire shape. Defined as a typealias on Join so it composes with the
 * rest of the TrikeShed Join algebra (lattice projections, series views,
 * etc.) for free.
 *
 *   Nuid = Join<Capability, Join<Nonce, Subnet>>
 *
 * Read as: `nuid.a` is the capability, `nuid.b.a` is the nonce,
 * `nuid.b.b` is the subnet.
 */
typealias Nuid = Join<Capability, Join<Nonce, Subnet>>

/** Constructor: NUID of (capability, nonce, subnet). */
fun nuid(cap: Capability, nonce: Nonce, subnet: Subnet): Nuid = cap j (nonce j subnet)

val Nuid.capability: Capability get() = a
val Nuid.nonce: Nonce get() = b.a
val Nuid.subnet: Subnet get() = b.b

// ── Trait space ──────────────────────────────────────────────────────────

/**
 * A Workgroup advertises a TraitSpace — the set of Capabilities it can
 * fulfill. Matching is exact at the leaf level; a request carrying
 * `Process("spawn")` matches a workgroup with `Process("spawn")` only.
 * The wildcard roots (`ProcessAll`, …) match anything in their family.
 */
fun interface TraitSpace {
    fun capabilities(): Series<Capability>
    fun can(offer: Capability): Boolean =
        capabilities().let { caps -> (0 until caps.size).any { i -> caps[i] matches offer } }

    companion object {
        /** Common empty trait space — for routers that claim no work. */
        val EMPTY: TraitSpace = TraitSpace { 0 j { error("empty") } }
    }
}

// ── Workgroup ────────────────────────────────────────────────────────────

/**
 * A registered Workgroup: workers advertise a Capability set, a Subnet
 * scope, and a name. Matching a request against the registry is exact
 * at both axes — the request's NUID capability must be in the workgroup's
 * TraitSpace AND the request's Subnet must be contained in the
 * workgroup's registered Subnet.
 */
data class Workgroup(
    val name: String,
    val scope: Subnet,
    val traits: TraitSpace,
) {
    fun canHandle(request: Nuid): Boolean =
        traits.can(request.capability) && (scope contains request.subnet)
}

// ── CCEK bridge: the NUID-aware bearer element ───────────────────────────

/**
 * Carrier for the request authorization context. A CCEK element so it
 * participates in structured-concurrency scope — when the parent scope
 * cancels, the bearer is no longer routable. Lifecycle forwarding beyond
 * construction is intentionally minimal; the owning Fanout element
 * (next cut) drives the CREATED → OPEN → ACTIVE → DRAINING → CLOSED
 * transitions.
 */
class NuidElement(
    parentJob: Job? = null,
    val nuid: Nuid,
) : AsyncContextElement(ElementState.CREATED, parentJob) {
    companion object Key : AsyncContextKey<NuidElement>()
    override val key: AsyncContextKey<NuidElement> = Key
}
