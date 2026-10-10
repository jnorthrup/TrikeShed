package borg.trikeshed.loom

import borg.trikeshed.collections.associative.*
import borg.trikeshed.ipns.IpnsCrypto
import borg.trikeshed.lib.*
import kotlin.test.*

/** The harness's byte patterns: byte i is i * mul + add. */
fun pattern(length: Int, mul: Int, add: Int): ByteArray = ByteArray(length) { (it * mul + add).toByte() }

/** segment.rs and custody.rs (with auth.rs Receipt) against LoomVectors. */
abstract class LoomCustodyTest(val crypto: IpnsCrypto) {
    val id = LoomVectors.SEGMENT_ID.hexToByteArray()
    val challenge = LoomVectors.CHALLENGE.hexToByteArray()
    val t = LoomVectors.T
    val replicaA = members()[1]
    val producers = mapOf("producer" to LoomVectors.PUBLIC_PRODUCER.hexToByteArray())

    fun receipt(signer: String, seed: String, nonce: ByteArray, time: ULong) =
        Receipt.issue_at(signer, id, nonce, false, seed.hexToByteArray(), time, crypto)

    fun receipts(): Series<Receipt> = s_[
        receipt("replica-a", LoomVectors.SEED_REPLICA_A, challenge, t + 3uL),
        receipt("replica-b", LoomVectors.SEED_REPLICA_B, challenge, t + 3uL),
        receipt("replica-c", LoomVectors.SEED_REPLICA_C, challenge, t + 3uL),
    ]

    @Test
    fun segmentSignsToTheCapturedBytes() {
        val segment = Segment.sign("producer", 7uL, 42uL, pattern(48, 7, 3), LoomVectors.SEED_PRODUCER.hexToByteArray(), crypto)
        assertEquals(LoomVectors.SEGMENT, segment.to_bytes().toHexString())
        assertEquals(LoomVectors.SEGMENT_ID, segment.id().toHexString())
        val captured = Segment.from_bytes(LoomVectors.SEGMENT.hexToByteArray())
        assertEquals(LoomVectors.SEGMENT, captured.to_bytes().toHexString())
        captured.verify(producers, crypto)
    }

    @Test
    fun segmentRejectionsAgree() {
        val segment = Segment.from_bytes(LoomVectors.SEGMENT.hexToByteArray())
        val seed = LoomVectors.SEED_PRODUCER.hexToByteArray()
        val tampered = LoomVectors.SEGMENT.hexToByteArray().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
        val wrongTag = Cbor.encode(
            itemArrayOf(
                itemArrayOf(Item.Str("loom-segment/v2"), Item.Str("producer"), Item.Num(7), Item.Num(42), Item.Bin(pattern(48, 7, 3))),
                Item.Bin(ByteArray(64)),
            )
        )
        val cases: Map<String, () -> Unit> = mapOf(
            "short_ciphertext" to { Segment.sign("producer", 7uL, 42uL, pattern(15, 7, 3), seed, crypto) },
            "bad_producer" to { Segment.sign("pro ducer", 7uL, 42uL, pattern(48, 7, 3), seed, crypto) },
            "epoch_range" to { Segment.sign("producer", 1uL shl 63, 42uL, pattern(48, 7, 3), seed, crypto) },
            "tampered_signature" to { Segment.from_bytes(tampered).verify(producers, crypto) },
            "unknown_producer" to { segment.verify(emptyMap(), crypto) },
            "wrong_tag" to { Segment.from_bytes(wrongTag) },
        )
        for ((name, expected) in LoomVectors.segmentErrors.view) assertEquals(expected, outcome(cases.getValue(name)), name)
    }

    @Test
    fun custodyAssemblesToTheCapturedBytes() {
        val receipts = receipts()
        assertEquals(LoomVectors.RECEIPT_A, receipts[0].to_bytes().toHexString())
        assertEquals(LoomVectors.RECEIPT_B, receipts[1].to_bytes().toHexString())
        assertEquals(LoomVectors.RECEIPT_C, receipts[2].to_bytes().toHexString())
        val custody = Custody.verified_at(id, challenge, receipts, members(), t + 4uL, crypto)
        assertEquals(LoomVectors.CUSTODY, custody.to_bytes().toHexString())
        assertFalse(custody.degraded)
        assertEquals(listOf("replica-a", "replica-b", "replica-c"), custody.nodes.view.toList())
        assertEquals(listOf("dc-a", "dc-b", "dc-c"), custody.domains.view.toList())
        val captured = Custody.from_bytes_at(LoomVectors.CUSTODY.hexToByteArray(), id, challenge, members(), t + 4uL, crypto)
        assertEquals(LoomVectors.CUSTODY, captured.to_bytes().toHexString())
        val degraded = Custody.verified_at(id, challenge, receipts[0 until 2], members(), t + 4uL, crypto)
        assertTrue(degraded.degraded)
        assertEquals(LoomVectors.CUSTODY_DEGRADED, degraded.to_bytes().toHexString())
    }

    @Test
    fun custodyRejectionsAgree() {
        val receipts = receipts()
        val other = pattern(32, 1, 1)
        val archival = LoomVectors.RECEIPT_A.hexToByteArray().also { it[it.size - 64 - 2 - 1] = 2 }
        val cases: Map<String, () -> Unit> = mapOf(
            "duplicate_receipt" to { Custody.verified_at(id, challenge, s_[receipts[0], receipts[0]], members(), t + 4uL, crypto) },
            "unknown_signer" to { Custody.verified_at(id, challenge, receipts, s_[members()[0], members()[1], members()[2], members()[4]], t + 4uL, crypto) },
            "stale_receipt" to { Custody.verified_at(id, challenge, receipts, members(), t + 34uL, crypto) },
            "wrong_challenge" to { Custody.from_bytes_at(LoomVectors.CUSTODY.hexToByteArray(), id, other, members(), t + 4uL, crypto) },
            "wrong_id" to { Custody.from_bytes_at(LoomVectors.CUSTODY.hexToByteArray(), other, challenge, members(), t + 4uL, crypto) },
            "receipt_kind" to { Receipt.from_bytes(archival) },
            "receipt_wrong_id" to { receipts[0].verify(replicaA, other, crypto) },
            "receipt_wrong_role" to { receipts[0].verify(replicaA.copy(roles = s_[Role.Reader]), id, crypto) },
            "receipt_wrong_nonce" to { receipts[0].verify_fresh_at(replicaA, id, other, t + 3uL, crypto) },
            "receipt_wrong_key" to { receipts[0].verify(replicaA.copy(public_key = LoomVectors.PUBLIC_REPLICA_B), id, crypto) },
        )
        for ((name, expected) in LoomVectors.custodyErrors.view) assertEquals(expected, outcome(cases.getValue(name)), name)
    }
}
