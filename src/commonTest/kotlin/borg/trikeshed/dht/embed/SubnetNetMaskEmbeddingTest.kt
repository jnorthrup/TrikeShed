package borg.trikeshed.dht.embed

import borg.trikeshed.collections.associative.Cbor
import borg.trikeshed.collections.associative.Item
import borg.trikeshed.collections.associative.itemArrayOf
import borg.trikeshed.context.nuid.Capability
import borg.trikeshed.context.nuid.NuidFanoutElement
import borg.trikeshed.context.nuid.Subnet
import borg.trikeshed.dht.id.WorkerNUID
import borg.trikeshed.dht.net.NetMask
import borg.trikeshed.dht.routing.RoutingTable
import borg.trikeshed.job.ContentId
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.get
import borg.trikeshed.lib.j
import borg.trikeshed.lib.size
import borg.trikeshed.util.oroboros.DhtContentGateway
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * M4 — the Subnet ↔ NetMask embedding, and the unclaimed-dispatch seam.
 *
 * Lives under `dht/embed` on purpose: the `util/oroboros` test tree is excluded
 * from commonTest in build.gradle.kts, so a test placed there would never run.
 */
class SubnetNetMaskEmbeddingTest {

    // ── (a) the embedding is monotone and round-trips ───────────────────

    @Test
    fun ladderIsStrictlyMonotoneInLevel() {
        val e = SubnetEmbedding.Default
        for (level in 1 until e.depth) {
            assertTrue(
                e.bitsOf(level) < e.bitsOf(level + 1),
                "deeper subnet must mean more bits: level $level -> ${e.bitsOf(level)}, " +
                    "level ${level + 1} -> ${e.bitsOf(level + 1)}"
            )
        }
    }

    @Test
    fun levelOfIsLeftInverseOfBitsOf() {
        val e = SubnetEmbedding.Default
        for (level in 1..e.depth) assertEquals(level, e.levelOf(e.bitsOf(level)))
        // Past the top rung the embedding retracts rather than bijects.
        assertEquals(e.maxBits, e.bitsOf(e.depth + 4))
        assertEquals(e.depth, e.levelOf(e.bitsOf(e.depth + 4)))
    }

    @Test
    fun concentricSubnetsEmbedMonotonically() {
        val core = Subnet.core                       // level 1
        val lan = Subnet.lanLocalhost                // level 2
        val mesh = Subnet.parse("mesh.worker.a1")    // level 3
        val relay = Subnet.parse("global.relay.eu.1") // level 4

        assertEquals(1, core.level)
        assertEquals(2, lan.level)
        assertEquals(3, mesh.level)
        assertEquals(4, relay.level)

        assertTrue(core.overlayBits < lan.overlayBits)
        assertTrue(lan.overlayBits < mesh.overlayBits)
        assertTrue(mesh.overlayBits < relay.overlayBits)

        // Fanout inherits the monotonicity, floored in-process and clamped at k.
        assertEquals(0, core.overlayFanout, "the innermost scope never leaves the worker")
        assertEquals(0, Subnet.local.overlayFanout)
        assertTrue(core.overlayFanout < lan.overlayFanout)
        assertTrue(lan.overlayFanout < mesh.overlayFanout)
        assertTrue(mesh.overlayFanout <= relay.overlayFanout)
        assertTrue(relay.overlayFanout <= SubnetEmbedding.GATEWAY_K)

        // Same level ⇒ same overlay width, regardless of the segment names.
        assertEquals(Subnet.local.overlayBits, core.overlayBits)
        assertEquals(Subnet.process.overlayBits, lan.overlayBits)
    }

    @Test
    fun equalityIsOverRungValuesNotProjectionIdentity() {
        val a = SubnetEmbedding(2 j { i: Int -> intArrayOf(3, 9)[i] })
        val b = SubnetEmbedding(2 j { i: Int -> listOf(3, 9)[i] })
        assertEquals(a, b, "two ladders with the same rungs must be equal")
        assertEquals(a.hashCode(), b.hashCode())
        assertNotEquals(a, SubnetEmbedding(2 j { i: Int -> intArrayOf(3, 10)[i] }))
    }

    @Test
    fun ladderRejectsNonMonotoneRungs() {
        var threw = false
        try {
            SubnetEmbedding(3 j { i -> intArrayOf(4, 4, 8)[i] })
        } catch (e: IllegalArgumentException) {
            threw = true
        }
        assertTrue(threw, "a non-increasing ladder is not an embedding")
    }

    // ── (b) capability category ↔ trait bits ────────────────────────────

    @Test
    fun wildcardRootSharesTheFamilyBit() {
        assertEquals(
            Capability.ProcessAll.traitMask,
            Capability.Process("spawn").traitMask,
            "a family root and its leaves must occupy one bit"
        )
        assertEquals(Capability.CasAll.traitMask, Capability.Cas("rw").traitMask)
        assertEquals(Capability.WireprotoAll.traitMask, Capability.Wireproto("/x").traitMask)
        assertNotEquals(Capability.Process("spawn").traitMask, Capability.Cas("rw").traitMask)
    }

    @Test
    fun traitMaskRoundTripsThroughCategories() {
        val listed = listOf(
            Capability.Process("spawn"),
            Capability.Sctp,
            Capability.BlackBoard,
        )
        val caps: Series<Capability> = listed.size j { i: Int -> listed[i] }
        val mask = TraitBits.maskOf(caps)
        assertEquals(listOf("process", "sctp", "blackboard"), TraitBits.categoriesOf(mask))
        assertTrue(TraitBits.overlaps(mask, Capability.ProcessAll.traitMask))
        assertTrue(!TraitBits.overlaps(mask, Capability.Model.traitMask))
        assertEquals(0L, mask and TraitBits.universe.inv())
    }

    @Test
    fun unknownCategoryLandsOnCustomRatherThanVanishing() {
        val mask = Capability.Custom("weird", "tok").traitMask
        assertEquals(1L shl TraitBits.customBit, mask)
        assertEquals(listOf("custom"), TraitBits.categoriesOf(mask))
        // customBit is resolved by name, so it tracks the literal entry.
        assertEquals("custom", TraitBits.categories[TraitBits.customBit])
    }

    // ── (c) the seam ────────────────────────────────────────────────────

    /** ConfixWorker's five-item shape: [cap, nonce, subnet, verb, payload]. */
    private fun wireOf(cap: String, subnet: String, verb: String, payload: ByteArray): ByteArray =
        Cbor.encode(
            itemArrayOf(
                Item.Str(cap),
                Item.Str("restored:1,2,3,4"),
                Item.Str(subnet),
                Item.Str(verb),
                Item.Bin(payload),
            )
        )

    /** A routing table stuffed with more peers than any scope may reach. */
    private fun stubTable(routes: Int = 40): RoutingTable<Byte, NetMask.Companion.WarmSz> {
        val agent = WorkerNUID(0)
        val table = RoutingTable<Byte, NetMask.Companion.WarmSz>(agent)
        for (i in 1..routes) table.addRouteSync(WorkerNUID(i.toByte()) j "urn:peer:$i")
        return table
    }

    private fun gatewayOver(table: RoutingTable<Byte, NetMask.Companion.WarmSz>) =
        DhtContentGateway<Byte, NetMask.Companion.WarmSz>(table) { id: ContentId ->
            // Any total map ContentId -> TNum works; XOR-fold the hex nibbles.
            var acc = 0
            for (c in id.hex) acc = acc xor c.code
            (acc and 0x7F).toByte()
        }

    private fun unclaimed(escalated: Int = 3) =
        NuidFanoutElement.DispatchResult(
            claimId = 7L,
            winner = null,
            claimedAtSubnet = null,
            escalatedLevels = escalated,
        )

    @Test
    fun unclaimedDispatchResolvesOverlayCandidates() {
        val table = stubTable()
        val gateway = gatewayOver(table)
        val wire = wireOf("process:spawn", "lan.localhost", "GET", byteArrayOf(9, 8, 7))

        val candidates = resolveOverlayCandidates(unclaimed(), wire, gateway)

        assertTrue(candidates.isNotEmpty(), "an unclaimed action must reach the overlay")
        assertTrue(candidates.size > 1)
        assertEquals(Subnet.lanLocalhost.overlayFanout, candidates.size)
        // Every candidate is a real route out of the stub table.
        for (c in candidates) {
            assertTrue(c.b.startsWith("urn:peer:"), "unexpected address ${c.b}")
            assertTrue(c.a.id != null)
        }
    }

    @Test
    fun fanoutWidthTracksSubnetDepth() {
        val gateway = gatewayOver(stubTable())
        val payload = byteArrayOf(1)

        val core = resolveOverlayCandidates(
            unclaimed(), wireOf("cas:ro", "core", "GET", payload), gateway
        )
        val lan = resolveOverlayCandidates(
            unclaimed(), wireOf("cas:ro", "lan.localhost", "GET", payload), gateway
        )
        val mesh = resolveOverlayCandidates(
            unclaimed(), wireOf("cas:ro", "mesh.worker.a1", "GET", payload), gateway
        )

        assertEquals(emptyList(), core, "a core-scoped action must never leave the worker")
        assertTrue(core.size < lan.size, "core=${core.size} lan=${lan.size}")
        assertTrue(lan.size < mesh.size, "lan=${lan.size} mesh=${mesh.size}")
        assertTrue(mesh.size <= SubnetEmbedding.GATEWAY_K)
    }

    @Test
    fun claimedDispatchNeverReachesTheOverlay() {
        val gateway = gatewayOver(stubTable())
        val wire = wireOf("wireproto:/a", "global.relay.eu.1", "PUT", byteArrayOf(4, 5))
        val claimed = NuidFanoutElement.DispatchResult(
            claimId = 7L,
            winner = "local-worker",
            claimedAtSubnet = Subnet.local,
            escalatedLevels = 0,
        )
        assertEquals(emptyList(), resolveOverlayCandidates(claimed, wire, gateway))
    }

    @Test
    fun wireDecodesBackToTheBearerNuid() {
        val wire = wireOf("modelmux:", "mesh.worker.a1", "POST", byteArrayOf(1, 2, 3))
        val decoded = decodeConfixWire(wire)

        assertEquals(Capability.Model, decoded.capability)
        assertEquals("mesh.worker.a1", decoded.subnet.toString())
        assertEquals(3, decoded.subnet.level)
        assertEquals("POST", decoded.verb)
        assertTrue(byteArrayOf(1, 2, 3).contentEquals(decoded.payload))
        assertEquals(Capability.ModelAll.traitMask, decoded.traitMask)
        assertEquals(4, decoded.nuid.b.a.bytes.size)
        // The reactor-shaped projection is the same join, not a copy.
        assertEquals(decoded.nuid, decoded.action.a)
        assertEquals("POST", decoded.action.b.a)
    }

    @Test
    fun lookupIsKeyedByContentNotByAgent() {
        val gateway = gatewayOver(stubTable())
        val a = resolveOverlayCandidates(
            unclaimed(), wireOf("cas:ro", "mesh.worker.a1", "GET", byteArrayOf(1)), gateway
        )
        val b = resolveOverlayCandidates(
            unclaimed(), wireOf("cas:ro", "mesh.worker.a1", "GET", byteArrayOf(2)), gateway
        )
        assertEquals(a.size, b.size)
        assertNotEquals(
            a.map { it.b }, b.map { it.b },
            "different content must land on different overlay peers"
        )
    }

    @Test
    fun capabilityArgumentsSurviveColonsAndWildcards() {
        // Single-argument families keep everything after the first colon.
        val route = decodeConfixWire(
            wireOf("wireproto:urn:x:y", "lan.localhost", "GET", byteArrayOf(0))
        ).capability
        assertEquals(Capability.Wireproto("urn:x:y"), route)

        // custom is the one two-argument family.
        assertEquals(
            Capability.Custom("kind", "tok"),
            decodeConfixWire(wireOf("custom:kind:tok", "core", "GET", byteArrayOf(0))).capability
        )

        // Wildcard roots round-trip to the root object, preserving the family bit.
        val wild = decodeConfixWire(wireOf("process*:", "core", "GET", byteArrayOf(0)))
        assertEquals(Capability.ProcessAll, wild.capability)
        assertEquals(Capability.Process("spawn").traitMask, wild.traitMask)
    }

    @Test
    fun malformedWireIsRejected() {
        val short = Cbor.encode(itemArrayOf(Item.Str("process:spawn"), Item.Str("core")))
        var threw = false
        try {
            decodeConfixWire(short)
        } catch (e: IllegalArgumentException) {
            threw = true
        }
        assertTrue(threw, "a wire that is not the five-item shape must be rejected")
        assertEquals(2, (Cbor.decode(short) as Item.Arr).items.size)

        // Right arity, wrong item types: still an IllegalArgumentException,
        // never a ClassCastException escaping the decoder.
        val mistyped = Cbor.encode(
            itemArrayOf(
                Item.Str("process:spawn"),
                Item.Str("restored:1"),
                Item.Str("core"),
                Item.Str("GET"),
                Item.Str("not-binary"),
            )
        )
        assertFailsWith<IllegalArgumentException> { decodeConfixWire(mistyped) }

        // An empty nonce is reported with wire context, not from Nonce's init.
        assertFailsWith<IllegalArgumentException> {
            decodeConfixWire(
                Cbor.encode(
                    itemArrayOf(
                        Item.Str("process:spawn"),
                        Item.Str("restored:"),
                        Item.Str("core"),
                        Item.Str("GET"),
                        Item.Bin(byteArrayOf(1)),
                    )
                )
            )
        }
    }
}
