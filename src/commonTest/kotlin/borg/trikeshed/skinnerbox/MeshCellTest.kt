package borg.trikeshed.skinnerbox

import borg.trikeshed.context.nuid.Subnet
import borg.trikeshed.ipns.Ed25519
import borg.trikeshed.job.CanonicalCbor
import borg.trikeshed.job.ContentId
import borg.trikeshed.lib.*
import kotlin.test.*

class MeshCellTest {
    private fun policy(value: Long = 1) = CanonicalCbor.encodeMap(mapOf("decision" to value))
    private fun newCell() = MeshCell("tenant", s_[meshPeer(1), meshPeer(2), meshPeer(3)], policy())
    private val record = ContentId.of("immutable-event".encodeToByteArray())

    @Test fun epochIsCanonicalAndDefensiveAndBindsEveryPlacementInput() {
        val members = mutableListOf(meshPeer(1), meshPeer(2), meshPeer(3))
        val decision = policy()
        val cell = MeshCell("tenant", 3 j { members[it] }, decision)
        val original = cell.bytes()
        members[0] = meshPeer(4)
        decision.fill(0)
        cell.decision.fill(0)
        cell.bytes().fill(0)
        cell.members[0].publicKey.fill(0)
        assertContentEquals(original, cell.bytes())
        assertEquals(ContentId.of(original), cell.epoch)
        assertEquals(newCell().epoch, cell.epoch)
        assertEquals(meshPeer(1).id, cell.owner.id)
        assertEquals(meshPeer(2).id, cell.replicas.a.id)
        assertEquals(meshPeer(3).id, cell.replicas.b.id)
        val decoded = MeshPersistenceCodec.decode(original)
        assertContentEquals(original, CanonicalCbor.encodeMap(decoded))
        assertNotEquals(cell.epoch, MeshCell("tenant", s_[meshPeer(1), meshPeer(2), meshPeer(3)], policy(2)).epoch)
        assertNotEquals(cell.epoch, MeshCell("tenant.child", s_[meshPeer(1), meshPeer(2), meshPeer(3)], policy()).epoch)
        assertNotEquals(cell.epoch, MeshCell("tenant", s_[meshPeer(2), meshPeer(1), meshPeer(3)], policy()).epoch)
    }

    @Test fun membershipRequiresThreeDistinctIdentitiesDomainsAndNamespaceGrants() {
        assertFails { MeshCell("tenant", s_[meshPeer(1), meshPeer(2)], policy()) }
        assertFails { MeshCell("tenant", s_[meshPeer(1), meshPeer(1), meshPeer(3)], policy()) }
        val sameDomain = MeshPeer(meshIdentity(2).publicKey, meshPeer(1).failureDomain, "https://second.example", Subnet.parse("tenant"))
        assertFails { MeshCell("tenant", s_[meshPeer(1), sameDomain, meshPeer(3)], policy()) }
        assertFails { MeshCell("tenant", s_[meshPeer(1), meshPeer(2), meshPeer(3, "other")], policy()) }
        assertFails { MeshCell("tenant", s_[meshPeer(1), meshPeer(2), meshPeer(3)], ByteArray(64 * 1024 + 1)) }
        assertFails { MeshCell("tenant", s_[meshPeer(1), meshPeer(2), meshPeer(3)], policy() + 0) }
        val largeDecision = CanonicalCbor.encodeMap(mapOf("inputs" to List(2200) { it.toLong() }))
        assertNotNull(MeshCell("tenant", s_[meshPeer(1), meshPeer(2), meshPeer(3)], largeDecision).epoch)
    }

    @Test fun freshCustodyRequiresEveryDistinctMemberAndCannotCountDuplicates() {
        val cell = newCell()
        val proofs = (1..3).map { MeshCustody.issue(meshPeer(it), meshIdentity(it), cell, record, 100_000).bytes() }
        assertEquals(3, MeshCustody.verifyAll(proofs.toSeries(), cell, record, 100_001).size)
        assertEquals(1, MeshCustody.verifyAll(s_[proofs[0]], cell, record, 100_001).size)
        assertFails { MeshCustody.verifyAll(s_[proofs[0], proofs[0]], cell, record, 100_001) }
        assertFails { MeshCustody.verifyAll((proofs + proofs[0]).toSeries(), cell, record, 100_001) }
        assertFails { MeshCustody.issue(meshPeer(4), meshIdentity(4), cell, record, 100_000) }
        assertFails { MeshCustody.issue(meshPeer(1), meshIdentity(2), cell, record, 100_000) }
    }

    @Test fun custodyBindsRecordEpochNamespaceAndImmutableBytes() {
        val cell = newCell()
        val proof = MeshCustody.issue(meshPeer(1), meshIdentity(1), cell, record, 100_000)
        val bytes = proof.bytes()
        val restored = MeshCustody.verify(bytes, cell, record, 100_000)
        bytes.fill(0)
        restored.bytes().fill(0)
        assertContentEquals(proof.bytes(), restored.bytes())
        assertFails { MeshCustody.verify(proof.bytes(), cell, ContentId.of(byteArrayOf(1)), 100_000) }
        assertFails { MeshCustody.verify(proof.bytes(), MeshCell("tenant", cell.members, policy(2)), record, 100_000) }
        assertFails { MeshCustody.verify(proof.bytes(), MeshCell("tenant.child", cell.members, policy()), record, 100_000) }
        assertFails { MeshCustody.verify(proof.bytes() + 0, cell, record, 100_000) }
    }

    @Test fun custodyRejectsSignedWrongTypesAndExtraOrChangedFields() {
        val cell = newCell()
        val proof = MeshCustody.issue(meshPeer(1), meshIdentity(1), cell, record, 100_000)
        val outer = MeshPersistenceCodec.decode(proof.bytes())
        val fields = MeshPersistenceCodec.decode(outer["body"] as ByteArray)
        fun signed(value: Map<String, Any?>): ByteArray {
            val body = CanonicalCbor.encodeMap(value)
            return CanonicalCbor.encodeMap(mapOf("body" to body, "signature" to Ed25519.sign(meshIdentity(1).privateKey, body)))
        }
        for (changed in listOf(fields + ("durable" to "true"), fields + ("atMillis" to "100000"),
            fields + ("peer" to meshPeer(2).id), fields + ("extra" to 1L), fields - "namespace")) {
            assertFails { MeshCustody.verify(signed(changed), cell, record, 100_000) }
        }
        val signature = (outer["signature"] as ByteArray).copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
        assertFails { MeshCustody.verify(CanonicalCbor.encodeMap(outer + ("signature" to signature)), cell, record, 100_000) }
    }

    @Test fun custodyFreshnessHandlesTimeBoundariesWithoutOverflow() {
        val cell = newCell()
        for (time in listOf(0L, Long.MAX_VALUE)) {
            val proof = MeshCustody.issue(meshPeer(1), meshIdentity(1), cell, record, time)
            assertEquals(time, MeshCustody.verify(proof.bytes(), cell, record, time).atMillis)
        }
        val proof = MeshCustody.issue(meshPeer(1), meshIdentity(1), cell, record, 100_000)
        MeshCustody.verify(proof.bytes(), cell, record, 130_000)
        MeshCustody.verify(proof.bytes(), cell, record, 70_000)
        assertFails { MeshCustody.verify(proof.bytes(), cell, record, 130_001) }
        assertFails { MeshCustody.verify(proof.bytes(), cell, record, 69_999) }
        assertFails { MeshCustody.verify(proof.bytes(), cell, record, -1) }
    }
}
