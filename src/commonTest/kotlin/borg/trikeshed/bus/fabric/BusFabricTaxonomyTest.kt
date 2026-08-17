@file:Suppress("NOTHING_TO_INLINE", "NonAsciiCharacters", "FunctionName", "UNCHECKED_CAST")

package borg.trikeshed.bus.fabric

import borg.trikeshed.lib.Join
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.j
import borg.trikeshed.lib.get
import borg.trikeshed.lib.size
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * ## Bus Fabric Taxonomy — Zero-Cost Isomorphs
 *
 * Agglomerates all busses in TrikeShed from the sum of isomorphs found in
 * zero-cost abstraction systems: fastmcpp (C++ MCP), fastmcp_rust (Rust MCP),
 * pyo3 (Rust-Python FFI), ACP (Agent Communication Protocol).
 *
 * ### The Isomorph Map
 *
 * | Concept              | fastmcpp/fasmcp_rust | pyo3          | ACP              | TrikeShed Join Fabric        |
 * |----------------------|----------------------|---------------|------------------|------------------------------|
 * | Wire unit            | JSON-RPC message     | PyTuple       | JSON-RPC envelope| `Join<Tag, Join<Id, Payload>>` |
 * | Typed dispatch       | template specialization| trait dispatch| method routing  | `sealed class ReactorAction`  |
 * | Channel / fanout     | SSE stream           | Python Channel| transport stream | `Channel<ByteArray>`          |
 * | Lifecycle FSM        | connection lifecycle | GIL scope     | session state    | `ElementState` BitMasked      |
 * | Type erasure         | concepts/templates   | PyAny downcast| JSON value       | `Join<A,B>` anonymous impl    |
 * | Address / routing    | URI + method         | module path   | tool ID          | `NUID = Join<Cap, Join<Nonce, Subnet>>` |
 * | Backpressure         | N/A (sync C++)       | bounded ch    | bounded stream   | `Channel(capacity)`           |
 *
 * Every bus in TrikeShed is a `Join` composition. The isomorphs below prove
 * that the *shape* is the same regardless of the transport substrate.
 *
 * TDD RED: all tests below MUST fail until the production code in
 * `BusFabric.kt` is implemented.
 */

// ═══════════════════════════════════════════════════════════════════════
// 1. UNIVERSAL WIRE UNIT — the atom of every bus
// ═══════════════════════════════════════════════════════════════════════

class WireUnitTest {

    /** Every bus carries a tagged wire unit: `Join<Tag, Join<Id, Payload>>`. */
    @Test
    fun taggedWireUnit_shapeIsJoin3() {
        val wire: WireUnit = WireTag.Tool j ("call-001" j ByteArray(8))
        assertEquals(WireTag.Tool, wire.a)
        assertEquals("call-001", wire.b.a)
        assertEquals(8, wire.b.b.size)
    }

    /** fastmcpp isomorph: JSON-RPC method name maps to WireTag. */
    @Test
    fun fastmcpp_method_to_WireTag() {
        assertEquals(WireTag.Tool, methodToWireTag("tools/call"))
        assertEquals(WireTag.Resource, methodToWireTag("resources/read"))
        assertEquals(WireTag.Prompt, methodToWireTag("prompts/get"))
        assertEquals(WireTag.Notification, methodToWireTag("notifications/subscribe"))
    }

    /** pyo3 isomorph: FFI boundary converts Join → tuple at zero cost. */
    @Test
    fun pyo3_boundary_to_tuple() {
        val wire: WireUnit = WireTag.Resource j ("res-42" j ByteArray(4))
        val tuple: Triple<WireTag, String, ByteArray> = wire.toTriple()
        assertEquals(WireTag.Resource, tuple.first)
        assertEquals("res-42", tuple.second)
        assertEquals(4, tuple.third.size)
    }

    /** ACP isomorph: tool-call envelope maps to WireUnit. */
    @Test
    fun acp_toolCall_to_WireUnit() {
        val toolCall = AcpToolCall(
            toolId = "memory/view",
            params = mapOf("path" to "docs/README.md"),
            requestId = "req-99",
        )
        val wire: WireUnit = toolCall.toWireUnit()
        assertEquals(WireTag.Tool, wire.a)
        assertEquals("req-99", wire.b.a)
    }
}

// ═══════════════════════════════════════════════════════════════════════
// 2. BUS LIFECYCLE — every bus element shares the same FSM
// ═══════════════════════════════════════════════════════════════════════

class BusLifecycleTest {

    /** fastmcp_rust isomorph: connection state machine matches CCEK lifecycle. */
    @Test
    fun rust_mcp_lifecycle_matches_ccek() {
        val states = BusLifecycleFSM.entries
        assertEquals(5, states.size, "BusLifecycle must have exactly 5 states")
        assertEquals(BusLifecycleFSM.CREATED, states[0])
        assertEquals(BusLifecycleFSM.OPEN, states[1])
        assertEquals(BusLifecycleFSM.ACTIVE, states[2])
        assertEquals(BusLifecycleFSM.DRAINING, states[3])
        assertEquals(BusLifecycleFSM.CLOSED, states[4])
    }

    /** Forward-only: CLOSED has no outgoing transitions. */
    @Test
    fun lifecycle_closed_is_terminal() {
        val transitions = busLifecycleTransitions()
        assertTrue(transitions(BusLifecycleFSM.CLOSED).isEmpty(),
            "CLOSED must be a terminal state")
    }

    /** Every non-terminal state has exactly one successor. */
    @Test
    fun lifecycle_linear_chain() {
        val transitions = busLifecycleTransitions()
        val nonTerminal = listOf(
            BusLifecycleFSM.CREATED,
            BusLifecycleFSM.OPEN,
            BusLifecycleFSM.ACTIVE,
            BusLifecycleFSM.DRAINING,
        )
        for (state in nonTerminal) {
            val next = transitions(state)
            assertEquals(1, next.size, "$state must have exactly 1 successor")
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════
// 3. REACTOR ACTION BUS — typed dispatch over Join envelope
// ═══════════════════════════════════════════════════════════════════════

class ReactorActionBusTest {

    /** ACP isomorph: Opened → Opened envelope shape. */
    @Test
    fun acp_opened_matches_ReactorAction() {
        val action: BusEnvelope = BusEnvelopeFactory.opened("nuid-1")
        assertEquals(BusVerb.Opened, action.verb)
        assertEquals("nuid-1", action.nuid)
    }

    /** fastmcpp isomorph: tool call → BusEnvelope(Tool, payload). */
    @Test
    fun fastmcpp_toolCall_matches_envelope() {
        val payload = mapOf("name" to "view", "arguments" to mapOf("path" to "/tmp"))
        val envelope = BusEnvelopeFactory.toolCall("req-1", payload)
        assertEquals(BusVerb.ToolCall, envelope.verb)
        assertEquals("req-1", envelope.requestId)
    }

    /** pyo3 isomorph: publish-entity carries typed payload via Join. */
    @Test
    fun pyo3_publishEntity_join_payload() {
        val entity = ByteArray(16) { it.toByte() }
        val envelope = BusEnvelopeFactory.publishEntity("nuid-2", entity)
        assertEquals(BusVerb.PublishEntity, envelope.verb)
        assertEquals(16, (envelope.payload as ByteArray).size)
    }
}

// ═══════════════════════════════════════════════════════════════════════
// 4. CHANNEL FANOUT BUS — bounded backpressure over ReceiveChannel
// ═══════════════════════════════════════════════════════════════════════

class ChannelFanoutTest {

    /** fastmcp_rust isomorph: SSE stream ≅ Channel<WireUnit>. */
    @Test
    fun rust_sse_matches_channel_fanout() {
        val bus = ChannelFanoutBus(capacity = 8)
        val wire: WireUnit = WireTag.Notification j ("notif-1" j ByteArray(2))
        bus.emit(wire)
        val received = bus.poll()
        assertEquals(WireTag.Notification, received?.a)
    }

    /** Sha2CasBus isomorph: durable-before-visible via bounded channel. */
    @Test
    fun sha2cas_durable_before_visible() {
        val bus = ChannelFanoutBus(capacity = 4)
        val wire: WireUnit = WireTag.Resource j ("cas-put" j ByteArray(32))
        bus.emit(wire) // durable + visible
        val received = bus.poll()
        assertTrue(received != null, "Durable put must be visible on channel")
    }

    /** Backpressure: channel respects capacity bound. */
    @Test
    fun backpressure_respects_capacity() {
        val bus = ChannelFanoutBus(capacity = 2)
        val w1: WireUnit = WireTag.Tool j ("t1" j ByteArray(1))
        val w2: WireUnit = WireTag.Tool j ("t2" j ByteArray(1))
        bus.emit(w1)
        bus.emit(w2)
        // Third emit would suspend (we test non-suspending path)
        assertEquals(2, bus.depth)
    }
}

// ═══════════════════════════════════════════════════════════════════════
// 5. PROTOCOL TAXONOMY BUS — single-byte ID dispatch
// ═══════════════════════════════════════════════════════════════════════

class ProtocolTaxonomyTest {

    /** litebike isomorph: Protocol.id drives channel dispatch. */
    @Test
    fun litebike_protocol_id_matches_taxonomy() {
        val mapping = protocolTaxonomy()
        assertEquals(9, mapping.size, "Must have 9 protocols")
        assertEquals("Http", mapping[1u])
        assertEquals("WebSocket", mapping[7u])
        assertEquals("Upnp", mapping[9u])
    }

    /** WamBlock = Join<WamElement, WamKey> — session × transform. */
    @Test
    fun wamBlock_is_join_session_transform() {
        val element = "session-state"
        val key = "transform-code"
        val block: BusWamBlock = element j key
        assertEquals("session-state", block.a)
        assertEquals("transform-code", block.b)
    }
}

// ═══════════════════════════════════════════════════════════════════════
// 6. NUID ROUTING BUS — concentric-subnet capability routing
// ═══════════════════════════════════════════════════════════════════════

class NuidRoutingTest {

    /** pyo3 isomorph: module path (pyo3) ≅ capability path (NUID). */
    @Test
    fun pyo3_modulePath_matches_capability() {
        val cap = BusCapability("cas", "put")
        assertEquals("cas", cap.family)
        assertEquals("put", cap.verb)
    }

    /** fastmcpp isomorph: URI + method ≅ NUID routing (tree containment). */
    @Test
    fun fastmcpp_uri_matches_subnet() {
        val lan = BusSubnet.of("lan")
        val lanHost = BusSubnet.of("lan.localhost")
        assertEquals(1, lan.level)
        assertEquals(2, lanHost.level)
        assertTrue(lan contains lanHost,
            "lan scope must contain lan.localhost (prefix match, deeper)")
        assertTrue(lan contains lanHost,
            "Exact match: lan.localhost contains lan.localhost")
    }

    /** Concentric: inner contains outer is false. */
    @Test
    fun concentric_inner_does_not_contain_outer() {
        val inner = BusSubnet.of("core")
        val outer = BusSubnet.of("lan.localhost")
        assertFalse(outer contains inner,
            "outer scope must NOT contain inner scope (authority flows inward)")
    }
}

// ═══════════════════════════════════════════════════════════════════════
// 7. CAS REPLICATION BUS — durable event propagation
// ═══════════════════════════════════════════════════════════════════════

class CasReplicationTest {

    /** Every CAS put fans out to registered hooks. */
    @Test
    fun cas_put_fans_out_to_hooks() {
        val bus = CasReplicationBus()
        var hookFired = false
        bus.registerHook { _ -> hookFired = true }
        bus.replicate(ByteArray(8))
        assertTrue(hookFired, "Hook must fire on replicate")
    }

    /** Multiple hooks all fire. */
    @Test
    fun cas_replication_multicast() {
        val bus = CasReplicationBus()
        var count = 0
        bus.registerHook { _ -> count++ }
        bus.registerHook { _ -> count++ }
        bus.registerHook { _ -> count++ }
        bus.replicate(ByteArray(4))
        assertEquals(3, count, "All 3 hooks must fire")
    }
}

// ═══════════════════════════════════════════════════════════════════════
// 8. MUX REACTOR BUS — credential pool + model dispatch
// ═══════════════════════════════════════════════════════════════════════

class MuxReactorTest {

    /** Lease acquisition follows capacity bounds. */
    @Test
    fun mux_lease_respects_capacity() {
        val bus = MuxReactorBus(maxKeys = 2)
        val k1 = bus.registerKey("key-1", "openai")
        val k2 = bus.registerKey("key-2", "anthropic")
        val k3 = bus.registerKey("key-3", "openai")
        val lease1 = bus.acquireLease("agent-1")
        val lease2 = bus.acquireLease("agent-2")
        val lease3 = bus.acquireLease("agent-3") // no keys left
        assertEquals(true, lease1)
        assertEquals(true, lease2)
        assertEquals(false, lease3)
    }

    /** Cache hit/miss tracking. */
    @Test
    fun mux_cache_hit_miss() {
        val bus = MuxReactorBus()
        bus.cachePut("openai", "gpt-4", "payload")
        val hit = bus.cacheLookup("openai", "gpt-4")
        val miss = bus.cacheLookup("anthropic", "claude")
        assertEquals(true, hit)
        assertEquals(false, miss)
    }
}

// ═══════════════════════════════════════════════════════════════════════
// 9. HTX TRANSPORT BUS — HTTP exchange over TLS
// ═══════════════════════════════════════════════════════════════════════

class HtxTransportTest {

    /** HTX exchange lifecycle: REQUEST → RESPONSE. */
    @Test
    fun htx_exchange_lifecycle() {
        val exchange = HtxExchangeBus()
        exchange.begin("req-42", "https://api.example.com/v1/chat")
        assertEquals(BusLifecycleFSM.ACTIVE, exchange.state("req-42"))
        exchange.complete("req-42", 200)
        assertEquals(BusLifecycleFSM.CLOSED, exchange.state("req-42"))
    }

    /** Content-Length framing. */
    @Test
    fun htx_content_length_framing() {
        val body = ByteArray(1024)
        val head = htxRenderHead("POST", "https://api.example.com", body.size)
        assertTrue(head.contains("Content-Length: 1024"),
            "Head must contain Content-Length for body")
    }
}

// ═══════════════════════════════════════════════════════════════════════
// 10. CROSS-BUS ISOMORPH — the fabric unifies all busses
// ═══════════════════════════════════════════════════════════════════════

class CrossBusIsomorphTest {

    /** A WireUnit can carry any bus payload — Join<Tag, Join<Id, Payload>>. */
    @Test
    fun wireUnit_carries_any_bus_payload() {
        val toolPayload = WireTag.Tool j ("t-1" j "view path".encodeToByteArray())
        val resourcePayload = WireTag.Resource j ("r-1" j ByteArray(64))
        val notifPayload = WireTag.Notification j ("n-1" j ByteArray(0))
        assertEquals(WireTag.Tool, toolPayload.a)
        assertEquals(WireTag.Resource, resourcePayload.a)
        assertEquals(WireTag.Notification, notifPayload.a)
    }

    /** The bus fabric typecheck: all busses compose into one Series<WireUnit>. */
    @Test
    fun bus_fabric_unifies_into_series() {
        val fabric: Series<WireUnit> = 3 j { i ->
            when (i) {
                0 -> WireTag.Tool j ("t-0" j ByteArray(1))
                1 -> WireTag.Resource j ("r-0" j ByteArray(2))
                else -> WireTag.Notification j ("n-0" j ByteArray(3))
            }
        }
        assertEquals(3, fabric.size)
        assertEquals(WireTag.Tool, fabric[0].a)
        assertEquals(WireTag.Resource, fabric[1].a)
        assertEquals(WireTag.Notification, fabric[2].a)
    }

    /** Isomorph chain: ACP tool call → WireUnit → BusEnvelope → Lifecycle FSM.
     *  Proves the round-trip preserves identity. */
    @Test
    fun acp_roundtrip_preserves_identity() {
        val original = AcpToolCall(
            toolId = "memory/view",
            params = mapOf("path" to "docs/README.md"),
            requestId = "req-roundtrip",
        )
        val wire = original.toWireUnit()
        val envelope = wire.toBusEnvelope()
        assertEquals(BusVerb.ToolCall, envelope.verb)
        assertEquals("req-roundtrip", envelope.requestId)
    }
}

// ═══════════════════════════════════════════════════════════════════════
// Helper — assertTrue without message overload
// ═══════════════════════════════════════════════════════════════════════
private fun assertTrue(value: Boolean, message: String) {
    kotlin.test.assertTrue(value, message)
}

private fun assertFalse(value: Boolean, message: String) {
    kotlin.test.assertFalse(value, message)
}
