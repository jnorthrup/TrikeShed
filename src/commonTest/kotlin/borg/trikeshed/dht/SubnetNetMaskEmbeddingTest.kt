package borg.trikeshed.dht

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
import borg.trikeshed.lib.j
import borg.trikeshed.lib.toList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue

/**
 * M4 — focused tests for the Subnet↔NetMask embedding and the unclaimed-
 * dispatch → overlay-candidate seam. Pure algebra + stub routing table;
 * no transports, no sockets.
 */
class SubnetNetMaskEmbeddingTest {

    @Test
    fun subnetBitsEmbeddingIsMonotoneAndRoundTrips() {
        // innermost → outermost: level 1, 2, 3, 4
        val subnets = listOf(
            Subnet.core,
            Subnet.lanLocalhost,
            Subnet.parse("mesh.worker.7"),
            Subnet.parse("global.relay.a.b"),
        )
        assertEquals(listOf(1, 2, 3, 4), subnets.map { it.level })

        // strictly monotone: deeper subnet ⇒ more bits
        val bits = subnets.map { SubnetNetMaskEmbedding.bitsFor(it) }
        for (i in 1 until bits.size) {
            assertTrue(bits[i] > bits[i - 1], "bits must strictly increase with level: $bits")
        }

        // round trip: ρ ∘ ι = id on levels
        for (s in subnets) {
            assertEquals(s.level, SubnetNetMaskEmbedding.levelFor(SubnetNetMaskEmbedding.bitsFor(s)))
        }

        // the overlay image carries exactly ι(level) bits
        for (s in subnets) {
            assertEquals(
                SubnetNetMaskEmbedding.bitsFor(s),
                SubnetNetMaskEmbedding.overlayNuidFor(s).netmask.bits,
            )
        }

        // embed() pairs the subnet with its bit-width
        val e = SubnetNetMaskEmbedding.embed(Subnet.lanLocalhost)
        assertEquals(Subnet.lanLocalhost, e.a)
        assertEquals(2 * SubnetNetMaskEmbedding.BITS_PER_LEVEL, e.b)
    }

    @Test
    fun capabilityTraitBitsFamilySharedAndDistinctAcrossFamilies() {
        // leaf and wildcard root of the same family share one bit
        assertEquals(
            CapabilityTraitBits.traitBits(Capability.Process("spawn")),
            CapabilityTraitBits.traitBits(Capability.ProcessAll),
        )
        assertEquals(
            CapabilityTraitBits.traitBits(Capability.Cas("ro")),
            CapabilityTraitBits.traitBits(Capability.CasAll),
        )

        // distinct families get distinct bits
        val distinct = listOf(
            Capability.Process("spawn"),
            Capability.Cas("ro"),
            Capability.Wireproto("route"),
            Capability.Sctp,
            Capability.Model,
            Capability.BlackBoard,
            Capability.Custom("kind", "token"),
            Capability.Trajectory,
        ).map { CapabilityTraitBits.traitBits(it) }
        assertEquals(distinct.size, distinct.toSet().size, "each family owns its own bit")

        // unknown category folds to the custom bit
        assertEquals(
            CapabilityTraitBits.traitBits(Capability.Custom("k", "t")),
            1L shl CapabilityTraitBits.bitIndexOf("never-seen-before"),
        )

        // union + retraction round trip
        val word = CapabilityTraitBits.traitBits(Capability.Cas("ro")) or
            CapabilityTraitBits.traitBits(Capability.Sctp)
        val categories = CapabilityTraitBits.categoriesOf(word).toList()
        assertEquals(setOf("cas", "sctp"), categories.toSet())
    }

    @Test
    fun unclaimedDispatchResolvesOverlayCandidatesThroughStubRoutingTable() {
        // stub routing table around a zero-id agent, populated with known routes
        val agent = WorkerNUID(0)
        val table = object : RoutingTable<Byte, NetMask.Companion.WarmSz>(agent) {}
        for (i in 1..10) table.addRouteSync(WorkerNUID(i.toByte()) j "urn:stub:$i")

        // ConfixWorker wire shape: five-item CBOR array (cap, nonce, subnet, verb, payload)
        val wire = Cbor.encode(
            itemArrayOf(
                Item.Str("cas:blob"),
                Item.Str("restored:1,2,3"),
                Item.Str("lan.localhost"),
                Item.Str("put"),
                Item.Bin(byteArrayOf(1, 2, 3)),
            ),
        )

        val toTNum: (ContentId) -> Byte = { it.hex.take(2).toInt(16).toByte() }

        // unclaimed dispatch (winner == null) resolves overlay candidates
        val unclaimed = NuidFanoutElement.DispatchResult(
            claimId = 1L,
            winner = null,
            claimedAtSubnet = null,
            escalatedLevels = 0,
        )
        val candidates = resolveUnclaimedOverlay(unclaimed, wire, table, toTNum)
        assertTrue(candidates.isNotEmpty(), "stub routes must surface as overlay candidates")
        assertTrue(candidates.size <= 20, "gateway hardcodes k=20")
        for (c in candidates) assertTrue(c.b.startsWith("urn:stub:"), "candidate address from stub table")

        // claimed dispatch needs no overlay fanout
        val claimed = NuidFanoutElement.DispatchResult(
            claimId = 2L,
            winner = "some-workgroup",
            claimedAtSubnet = Subnet.local,
            escalatedLevels = 0,
        )
        assertTrue(resolveUnclaimedOverlay(claimed, wire, table, toTNum).isEmpty())

        // malformed wire form is rejected before any lookup
        val notAnArray = Cbor.encode(Item.Str("nope"))
        assertFails { resolveUnclaimedOverlay(unclaimed, notAnArray, table, toTNum) }
        val shortArray = Cbor.encode(itemArrayOf(Item.Str("cas:blob"), Item.Str("restored:1")))
        assertFails { resolveUnclaimedOverlay(unclaimed, shortArray, table, toTNum) }
    }
}
