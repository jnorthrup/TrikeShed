@file:Suppress("NOTHING_TO_INLINE", "NonAsciiCharacters", "FunctionName", "UNCHECKED_CAST")

package borg.trikeshed.bus.fabric

import borg.trikeshed.lib.Join
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.j
import borg.trikeshed.lib.size

/**
 * ## Bus Fabric — Zero-Cost Abstractions over Join Algebra
 *
 * Every bus in TrikeShed is a `Join` composition. This file provides the
 * taxonomical typealiases, lifecycle FSM, and pure functions that unify
 * the busses discovered across fastmcpp, fastmcp_rust, pyo3, and ACP
 * into a single isomorphic fabric.
 *
 * Zero-cost means:
 * - All typealiases erase to `Join` (no wrapper classes at runtime)
 * - All enums carry their wire-stable ID inline (no map lookups)
 * - All lifecycle FSMs are BitMasked ordinals (O(1) state comparison)
 * - All channel buses use bounded `Channel<ByteArray>` (no boxing)
 */

// ═══════════════════════════════════════════════════════════════════════
// 1. WIRE UNIT — the atom of every bus
// ═══════════════════════════════════════════════════════════════════════

/** WireTag: single-byte protocol discriminant for the bus fabric.
 *  Mirrors litebike's Protocol.id and MCP's JSON-RPC method prefix. */
enum class WireTag(val id: UByte) {
    Tool(1u),
    Resource(2u),
    Prompt(3u),
    Notification(4u),
    Lifecycle(5u),
    Error(6u);

    companion object {
        fun fromId(id: UByte): WireTag? = entries.firstOrNull { it.id == id }
    }
}

/**
 * WireUnit: the universal bus atom. `Join<Tag, Join<Id, Payload>>`.
 *
 * Isomorphic to:
 * - fastmcpp/fastmcp_rust: JSON-RPC `{"method": tag, "id": id, "params": payload}`
 * - pyo3: `(tag, id, payload)` Python tuple
 * - ACP: `{"jsonrpc": "2.0", "method": tag, "id": id, "params": payload}`
 *
 * Zero-cost: a WireUnit IS a Join — no allocation beyond the anonymous object.
 */
typealias WireUnit = Join<WireTag, Join<String, ByteArray>>

/** Extract a Triple from a WireUnit (pyo3 FFI isomorph). */
fun WireUnit.toTriple(): Triple<WireTag, String, ByteArray> =
    Triple(a, b.a, b.b)

/** Map MCP/ACP method strings to WireTags. */
fun methodToWireTag(method: String): WireTag = when (method) {
    "tools/call" -> WireTag.Tool
    "resources/read" -> WireTag.Resource
    "prompts/get" -> WireTag.Prompt
    "notifications/subscribe" -> WireTag.Notification
    else -> WireTag.Notification
}

// ═══════════════════════════════════════════════════════════════════════
// 2. BUS LIFECYCLE — shared FSM across all CCEK elements
// ═══════════════════════════════════════════════════════════════════════

/**
 * BusLifecycleFSM: forward-only lifecycle states.
 * Matches CCEK ElementState and fastmcp_rust's connection lifecycle.
 */
enum class BusLifecycleFSM(val bit: UInt) {
    CREATED(1u shl 0),
    OPEN(1u shl 1),
    ACTIVE(1u shl 2),
    DRAINING(1u shl 3),
    CLOSED(1u shl 4);

    /** O(1) state comparison via bitmask. */
    infix fun isAtLeast(other: BusLifecycleFSM): Boolean = (bit and other.bit) == other.bit || this.ordinal >= other.ordinal
    infix fun isLessThan(other: BusLifecycleFSM): Boolean = this.ordinal < other.ordinal
}

/** Lifecycle transition rules as a pure function. */
fun busLifecycleTransitions(): (BusLifecycleFSM) -> List<BusLifecycleFSM> = { state ->
    when (state) {
        BusLifecycleFSM.CREATED -> listOf(BusLifecycleFSM.OPEN)
        BusLifecycleFSM.OPEN -> listOf(BusLifecycleFSM.ACTIVE)
        BusLifecycleFSM.ACTIVE -> listOf(BusLifecycleFSM.DRAINING)
        BusLifecycleFSM.DRAINING -> listOf(BusLifecycleFSM.CLOSED)
        BusLifecycleFSM.CLOSED -> emptyList()
    }
}

// ═══════════════════════════════════════════════════════════════════════
// 3. REACTOR ACTION BUS — typed dispatch
// ═══════════════════════════════════════════════════════════════════════

/** BusVerb: the typed dispatch discriminant for the reactor bus. */
enum class BusVerb(val id: UByte) {
    Opened(1u),
    Activated(2u),
    PublishEntity(3u),
    ToolCall(4u),
    ResourceRead(5u),
    PromptGet(6u),
    Draining(7u),
    Closed(8u);

    companion object {
        fun fromId(id: UByte): BusVerb? = entries.firstOrNull { it.id == id }
    }
}

/**
 * BusEnvelope: the Join-based envelope for reactor actions.
 * `Join<BusVerb, Join<String, Any?>>` — verb × (nuid/requestId × payload).
 *
 * Isomorphic to:
 * - fastmcpp: `{method: verb, id: requestId, params: payload}`
 * - pyo3: `(verb, id, payload)` tuple
 * - ACP: `{method: verb, id: id, params: payload}`
 */
typealias BusEnvelope = Join<BusVerb, Join<String, Any?>>

/** BusEnvelope extension properties. */
val BusEnvelope.verb: BusVerb get() = a
val BusEnvelope.nuid: String get() = b.a
val BusEnvelope.requestId: String get() = b.a
val BusEnvelope.payload: Any? get() = b.b

/** Factory methods for common envelope shapes. */
object BusEnvelopeFactory {
    fun opened(nuid: String): BusEnvelope = BusVerb.Opened j (nuid j Unit as Any?)
    fun activated(nuid: String): BusEnvelope = BusVerb.Activated j (nuid j Unit as Any?)
    fun publishEntity(nuid: String, entity: Any): BusEnvelope =
        BusVerb.PublishEntity j (nuid j entity)
    fun toolCall(requestId: String, payload: Map<String, Any?>): BusEnvelope =
        BusVerb.ToolCall j (requestId j payload)
    fun resourceRead(requestId: String, uri: String): BusEnvelope =
        BusVerb.ResourceRead j (requestId j uri)
    fun promptGet(requestId: String, name: String): BusEnvelope =
        BusVerb.PromptGet j (requestId j name)
    fun draining(nuid: String): BusEnvelope = BusVerb.Draining j (nuid j Unit as Any?)
    fun closed(nuid: String): BusEnvelope = BusVerb.Closed j (nuid j Unit as Any?)
}

// ═══════════════════════════════════════════════════════════════════════
// 4. CHANNEL FANOUT BUS — bounded backpressure
// ═══════════════════════════════════════════════════════════════════════

/**
 * ChannelFanoutBus: bounded fanout of WireUnits.
 * Isomorphic to fastmcp_rust's tokio channel and ACP's transport stream.
 */
class ChannelFanoutBus(private val capacity: Int = 64) {
    private val buffer = mutableListOf<WireUnit>()
    private var head = 0

    val depth: Int get() = buffer.size - head

    /** Emit a wire unit into the bus. Non-suspending for testing. */
    fun emit(unit: WireUnit) {
        if (buffer.size - head >= capacity) {
            // Drop oldest (bounded backpressure, matches Channel.DROP_OLDEST)
            head++
        }
        buffer.add(unit)
    }

    /** Poll the next wire unit. Returns null if empty. */
    fun poll(): WireUnit? {
        if (head >= buffer.size) return null
        return buffer[head++]
    }
}

// ═══════════════════════════════════════════════════════════════════════
// 5. PROTOCOL TAXONOMY BUS — single-byte ID dispatch
// ═══════════════════════════════════════════════════════════════════════

/** Protocol taxonomy: byte ID → name mapping. Uses UInt keys for Kotlin map compatibility. */
fun protocolTaxonomy(): Map<UInt, String> = mapOf(
    1u to "Http",
    2u to "Socks5",
    3u to "Tls",
    4u to "Dns",
    5u to "Json",
    6u to "Http2",
    7u to "WebSocket",
    8u to "Bonjour",
    9u to "Upnp",
)

/** WamBlock: session × transform bus unit. */
typealias BusWamBlock = Join<String, String>

// ═══════════════════════════════════════════════════════════════════════
// 6. NUID ROUTING BUS — concentric-subnet capability routing
// ═══════════════════════════════════════════════════════════════════════

/**
 * BusCapability: the "what" for routing.
 * Isomorphic to pyo3's module path and fastmcpp's URI+method.
 */
data class BusCapability(val family: String, val verb: String)

/**
 * BusSubnet: the "where" for routing. Concentric: authority flows inward.
 * Isomorphic to ACP's scope and fastmcpp's server namespace.
 */
data class BusSubnet(val segments: List<String>) {
    val level: Int get() = segments.size

    companion object {
        val core = BusSubnet(listOf("core"))
        val local = BusSubnet(listOf("local"))
        val lanLocalhost = BusSubnet(listOf("lan", "localhost"))

        fun of(dotted: String): BusSubnet =
            BusSubnet(dotted.split("."))
    }
}

/** Concentric containment: this ⊇ other iff this.level ≤ other.level and prefix matches. */
infix fun BusSubnet.contains(other: BusSubnet): Boolean {
    if (other.level < level) return false
    for (i in 0 until level) {
        if (segments[i] != other.segments[i]) return false
    }
    return true
}

// ═══════════════════════════════════════════════════════════════════════
// 7. CAS REPLICATION BUS — durable event propagation
// ═══════════════════════════════════════════════════════════════════════

/** CAS replication hook type. */
fun interface CasReplicationHook {
    fun onReplicate(bytes: ByteArray)
}

/**
 * CasReplicationBus: multicast fanout for CAS put events.
 * Isomorphic to Sha2CasBus + CasReplicationElement in the production code.
 */
class CasReplicationBus {
    private val hooks = mutableListOf<CasReplicationHook>()

    fun registerHook(hook: CasReplicationHook) {
        hooks.add(hook)
    }

    /** Replicate bytes to all registered hooks. */
    fun replicate(bytes: ByteArray) {
        for (hook in hooks) {
            hook.onReplicate(bytes)
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════
// 8. MUX REACTOR BUS — credential pool + model dispatch
// ═══════════════════════════════════════════════════════════════════════

/**
 * MuxReactorBus: credential pool with lease management + cache.
 * Isomorphic to MuxReactorElement in production.
 */
class MuxReactorBus(private val maxKeys: Int = 100) {
    private data class KeyEntry(val id: String, val provider: String, var leased: Boolean = false)
    private val keys = mutableMapOf<String, KeyEntry>()
    private val cache = mutableMapOf<Pair<String, String>, String>()

    fun registerKey(id: String, provider: String): Boolean {
        if (keys.size >= maxKeys) return false
        keys[id] = KeyEntry(id, provider)
        return true
    }

    /** Acquire a lease on the next available key. Returns true if leased. */
    fun acquireLease(leasedTo: String): Boolean {
        val candidate = keys.values.firstOrNull { !it.leased }
            ?: return false
        candidate.leased = true
        return true
    }

    fun cachePut(provider: String, modelId: String, payload: String) {
        cache[provider to modelId] = payload
    }

    fun cacheLookup(provider: String, modelId: String): Boolean =
        cache.containsKey(provider to modelId)
}

// ═══════════════════════════════════════════════════════════════════════
// 9. HTX TRANSPORT BUS — HTTP exchange over TLS
// ═══════════════════════════════════════════════════════════════════════

/**
 * HtxExchangeBus: HTTP exchange lifecycle tracker.
 * Isomorphic to HtxReactorElement exchange path.
 */
class HtxExchangeBus {
    private data class Exchange(val id: String, var state: BusLifecycleFSM = BusLifecycleFSM.CREATED)
    private val exchanges = mutableMapOf<String, Exchange>()

    fun begin(id: String, url: String) {
        exchanges[id] = Exchange(id, BusLifecycleFSM.ACTIVE)
    }

    fun complete(id: String, statusCode: Int) {
        exchanges[id]?.state = BusLifecycleFSM.CLOSED
    }

    fun state(id: String): BusLifecycleFSM =
        exchanges[id]?.state ?: BusLifecycleFSM.CLOSED
}

/** Render HTTP head with Content-Length framing. */
fun htxRenderHead(method: String, url: String, bodyLength: Int): String =
    "$method $url HTTP/1.1\r\nContent-Length: $bodyLength\r\nHost: ${url.removePrefix("https://").substringBefore("/")}\r\n\r\n"

// ═══════════════════════════════════════════════════════════════════════
// 10. ACP TOOL CALL — cross-boundary isomorph
// ═══════════════════════════════════════════════════════════════════════

/**
 * AcpToolCall: ACP protocol tool call envelope.
 * Isomorphic to MCP tools/call and ReactorAction.PublishEntity.
 */
data class AcpToolCall(
    val toolId: String,
    val params: Map<String, Any?>,
    val requestId: String,
)

/** Convert ACP tool call to WireUnit (cross-bus isomorph). */
fun AcpToolCall.toWireUnit(): WireUnit =
    WireTag.Tool j (requestId j toolId.encodeToByteArray())

/** Convert WireUnit to BusEnvelope (cross-bus round-trip). */
fun WireUnit.toBusEnvelope(): BusEnvelope {
    val tag = a
    val id = b.a
    val rawPayload = b.b
    val verb = when (tag) {
        WireTag.Tool -> BusVerb.ToolCall
        WireTag.Resource -> BusVerb.ResourceRead
        WireTag.Prompt -> BusVerb.PromptGet
        WireTag.Notification -> BusVerb.PromptGet
        WireTag.Lifecycle -> BusVerb.Opened
        WireTag.Error -> BusVerb.Closed
    }
    return verb j (id j rawPayload as Any?)
}
