package borg.trikeshed.skinnerbox

import borg.trikeshed.context.nuid.Subnet
import borg.trikeshed.ipns.Ed25519
import borg.trikeshed.job.CanonicalCbor
import borg.trikeshed.job.ContentId
import borg.trikeshed.lib.*
import borg.trikeshed.userspace.nio.platform.spi.CacheTopology
import kotlin.test.*

class MeshPlacementTest {
    private val task = MeshTask("mesh.eu.tenant", "epoch", byteArrayOf(1))
    private val policy = MeshPlacementPolicy(Subnet.parse("mesh"), setOf("DE", "FR"))
    private fun cid(label: String) = ContentId.of(label.encodeToByteArray())
    private fun peer(n: Int, domain: String = "domain-$n", namespace: String = "mesh.eu") =
        MeshPeer(Ed25519.publicKey(ByteArray(32) { (n + it).toByte() }), domain,
            "https://peer$n.example", Subnet.parse(namespace))
    private fun host(peer: MeshPeer, country: String = "DE", until: Long = 1000, qualified: Boolean = true) =
        MeshHost(peer, country, cid(peer.id), MeshHostQuote(0, until, 10, MeshStorageRate(10, 100, 10)), qualified)
    private fun links(peers: Series<MeshPeer>, price: (MeshPeer, MeshPeer) -> Long = { _, _ -> 1L }): Series<MeshLinkQuote> {
        val result = mutableListOf<MeshLinkQuote>()
        for (a in peers.view) for (b in peers.view) if (a.id != b.id) result.add(
            MeshLinkQuote(a.id, b.id, cid(a.id + b.id), 0, 1000,
                MeshByteRate(price(a, b), 1), MeshByteRate(2 * price(a, b), 1)))
        return result.toTypedArray().let { array -> array.size j { array[it] } }
    }

    @Test fun exactFractionsPreserveSubMicroPricesAndProviderUnits() {
        assertEquals(MeshPrice.of(1, 2), MeshPrice.of(1, 3) + MeshPrice.of(1, 6))
        assertEquals(MeshPrice.ZERO, MeshPrice.ZERO + MeshPrice.ZERO)
        val decimal = MeshByteRate(1, 1_000_000_000).cost(1)
        val binary = MeshByteRate(1, 1_073_741_824).cost(1)
        assertTrue(binary < decimal)
        assertEquals(1L, binary.ceilingMicroUsd())
        assertEquals(1L, decimal.ceilingMicroUsd())
        assertTrue(MeshPrice.of(Long.MAX_VALUE - 1, Long.MAX_VALUE) < MeshPrice.of(1))
        assertTrue(MeshPrice.of(Long.MAX_VALUE, Long.MAX_VALUE - 1) > MeshPrice.of(1))
        assertFailsWith<IllegalArgumentException> { MeshPrice.of(Long.MAX_VALUE) + MeshPrice.of(1) }
        assertFailsWith<IllegalArgumentException> { MeshPrice.of(Long.MAX_VALUE) * 2 }
        assertEquals(133L, MeshPrice.of(100).postedMicroUsd())
    }

    @Test fun oneIngressTwoReplicaEdgesAndAllChargesAreIncluded() {
        val source = peer(9)
        val peers = s_[peer(1), peer(2), peer(3)]
        val plan = MeshPlacement.select(task, source, peers α { host(it) }, links(s_[source, peers[0], peers[1], peers[2]]),
            policy, MeshLoad(100, 200, 40, 10, 3, 2, 30), 1)
        assertEquals(3, plan.members.size)
        assertEquals(3, plan.members.view.map { it.peer.failureDomain }.toSet().size)
        assertEquals(6, plan.transfers.size)
        assertEquals(1, plan.transfers.view.count { it.kind == MeshTransferKind.REQUEST })
        assertEquals(2, plan.transfers.view.count { it.kind == MeshTransferKind.RETAIN })
        assertEquals(MeshPrice.of(70), plan.cost.processing)
        assertEquals(MeshPrice.of(180), plan.cost.storage)
        assertEquals(MeshPrice.of(560), plan.cost.ingress)
        assertEquals(MeshPrice.of(1120), plan.cost.egress)
        assertEquals(MeshPrice.of(1930), plan.cost.total)
        assertEquals(3, plan.evaluatedPlans)
        assertTrue(plan.fresh(999))
        assertFalse(plan.fresh(1000))
        assertFalse(plan.fresh(0))
    }

    @Test fun exhaustivePlacementBeatsChoosingOnlyACheapHost() {
        val source = peer(9)
        val peers = s_[peer(1), peer(2), peer(3), peer(4)]
        val gate = peers[1]
        val free = setOf(source.id, peers[2].id, peers[3].id)
        val tariffs = links(s_[source, peers[0], peers[1], peers[2], peers[3]]) { a, b ->
            if (a.id == gate.id && b.id in free || b.id == gate.id && a.id in free) 0 else 1
        }
        val plan = MeshPlacement.select(task, source, peers α { host(it) }, tariffs, policy,
            MeshLoad(10, 10, 10, 10, 0, 0, 0), 1)
        assertEquals(gate.id, plan.executor.peer.id)
        assertEquals(setOf(peers[2].id, peers[3].id), setOf(plan.replicas.a.peer.id, plan.replicas.b.peer.id))
        assertEquals(MeshPrice.ZERO, plan.cost.total)
        assertEquals(12, plan.evaluatedPlans)
        val reversed: Series<MeshPeer> = peers.size j { index: Int -> peers[peers.size - index - 1] }
        val again = MeshPlacement.select(task, source, reversed α { host(it) }, tariffs, policy,
            MeshLoad(10, 10, 10, 10, 0, 0, 0), 1)
        assertEquals(plan.executor.peer.id, again.executor.peer.id)
        assertEquals(plan.replicas.a.peer.id, again.replicas.a.peer.id)
        assertContentEquals(plan.decisionBytes(), again.decisionBytes())
        val decision = CanonicalCbor.decodeMap(plan.decisionBytes())
        assertEquals("skinnerbox-placement/v1", decision["format"])
        assertEquals(12L, decision["evaluated"])
        assertEquals(7, (decision["load"] as List<*>).size)
        assertEquals(4, (decision["hosts"] as List<*>).size)
        assertEquals(20, (decision["links"] as List<*>).size)
        assertContentEquals(plan.decisionBytes(), CanonicalCbor.encodeMap(decision))
        val changed = plan.decisionBytes()
        changed[0] = 0
        assertNotEquals(changed[0], plan.decisionBytes()[0])
    }

    @Test fun missingQuotesAndDuplicateFailureDomainsDoNotDegradeN3() {
        val source = peer(9)
        val peers = s_[peer(1), peer(2), peer(3)]
        val load = MeshLoad(1, 1, 1, 1, 1, 1, 1)
        assertFailsWith<IllegalArgumentException> {
            MeshPlacement.select(task, source, peers α { host(it) }, emptySeries(), policy, load, 1)
        }
        val domains = s_[peer(1, "same"), peer(2, "same"), peer(3)]
        assertFailsWith<IllegalArgumentException> {
            MeshPlacement.select(task, source, domains α { host(it) }, links(s_[source, domains[0], domains[1], domains[2]]), policy, load, 1)
        }
        assertFailsWith<IllegalArgumentException> {
            MeshPlacement.select(task, source, s_[host(peers[0]), host(peers[0]), host(peers[2])],
                links(s_[source, peers[0], peers[1], peers[2]]), policy, load, 1)
        }
    }

    @Test fun freshQuotesQualificationAndCountryAreHardConstraints() {
        val source = peer(9)
        val peers = s_[peer(1), peer(2), peer(3), peer(4), peer(5), peer(6)]
        val hosts = s_[host(peers[0]), host(peers[1]), host(peers[2]),
            host(peers[3], country = "US"), host(peers[4], until = 1), host(peers[5], qualified = false)]
        val plan = MeshPlacement.select(task, source, hosts, links(s_[source, peers[0], peers[1], peers[2]]),
            policy, MeshLoad(1, 1, 1, 1, 0, 0, 0), 1)
        assertEquals(setOf(MeshExclusion.COUNTRY, MeshExclusion.STALE, MeshExclusion.UNQUALIFIED), plan.excluded.view.map { it.reason }.toSet())
        val staleLinks = links(s_[source, peers[0], peers[1], peers[2]]) α { it.copy(validUntilMillis = 1) }
        assertFailsWith<IllegalArgumentException> {
            MeshPlacement.select(task, source, hosts, staleLinks, policy, MeshLoad(1, 1, 1, 1, 0, 0, 0), 1)
        }
    }

    @Test fun hostTwinBindsPeerAndRejectsInexactRates() {
        val p = peer(1)
        val fields = mapOf("country_code" to "DE", "valid_until_ms" to 1000L,
            "processing_micro_usd_per_unit" to 3L, "storage_micro_usd" to 10L,
            "storage_bytes_per_unit" to 1_000_000_000L, "storage_seconds_per_unit" to 3600L,
            "qualified" to true)
        val observation = TwinObservation.create(TwinKey("host", p.id), TwinSource.CURATED, "quote", 1, fields)
        val parsed = MeshHost.fromTwin(p, observation)
        assertEquals(observation.id, parsed.twin)
        assertTrue(parsed.qualified)
        assertFailsWith<IllegalArgumentException> { MeshHost.fromTwin(peer(2), observation) }
        val inexact = TwinObservation.create(TwinKey("host", p.id), TwinSource.CURATED, "quote", 1,
            fields + ("processing_micro_usd_per_unit" to TwinNumber.of("1.5")))
        assertFailsWith<IllegalArgumentException> { MeshHost.fromTwin(p, inexact) }
    }

    @Test fun numericXorIsNotHammingDistanceAndScopesDoNotLeak() {
        val target = ContentId("sha256:" + "0".repeat(64))
        val highSingleBit = "8" + "0".repeat(63)
        val lowerManyBits = "7" + "f".repeat(63)
        assertTrue(MeshRouting.compareDistance(target, lowerManyBits, highSingleBit) < 0)
        val a = peer(1, namespace = "mesh.eu")
        val b = peer(2, namespace = "mesh.us")
        val result = MeshRouting.closest(target, task.subnet, Subnet.parse("mesh"), s_[a, b])
        assertEquals(1, result.size)
        assertEquals(a.id, result[0].id)
        assertEquals(0, MeshRouting.closest(target, Subnet.local, Subnet.local,
            s_[peer(3, namespace = "local")]).size)
        assertFailsWith<IllegalArgumentException> {
            MeshRouting.closest(target, task.subnet, Subnet.parse("mesh.us"), s_[a, b])
        }
    }

    @Test fun strokesAndRetryJitterHaveExplicitBoundaries() {
        val stroke = MeshStroke.from(CacheTopology(32L * 1024, l2Bytes = 512L * 1024))
        assertEquals(128L * 1024, stroke.maxBytes)
        assertTrue(stroke.admits(2, 100, 100))
        assertFalse(stroke.admits(3, 100, 100))
        assertFalse(stroke.admits(1, Long.MAX_VALUE, 1))
        assertNull(MeshStroke.from(CacheTopology.UNKNOWN).observedCacheBytes)
        val p = peer(1)
        val delay = meshRetryDelay(p.id, task.id, 3, 100, 10_000)
        assertEquals(delay, meshRetryDelay(p.id, task.id, 3, 100, 10_000))
        assertTrue(delay in 0..800)
        for (attempt in 0..63) assertTrue(meshRetryDelay(p.id, task.id, attempt, 100, 10_000) in 0..10_000)
        assertFailsWith<IllegalArgumentException> { meshRetryDelay(p.id, task.id, 64, 100, 10_000) }
    }
}
