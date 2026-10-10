package borg.trikeshed.loom

import borg.trikeshed.collections.associative.*
import borg.trikeshed.ipns.IpnsCrypto
import borg.trikeshed.job.sha256
import borg.trikeshed.lib.*
import kotlin.test.*

/** The capture harness's config: replica-a, settled by the authority, archiving to loom-archive. */
fun config(id: String = "replica-a", gcs: GcsConfig = GcsConfig("loom-archive")): Config = Config(id, members(), "authority", gcs)

/** completion.rs, settlement.rs (Settlement, Handoff, bundle) and gcs.rs GcsReceipt against LoomVectors. */
abstract class LoomSettlementTest(val crypto: IpnsCrypto) {
    val id = LoomVectors.SEGMENT_ID.hexToByteArray()
    val nonce = LoomVectors.NONCE_3.hexToByteArray()
    val t = LoomVectors.T
    val authority = members()[0]
    val replicaA = members()[1]
    val segment = Segment.from_bytes(LoomVectors.SEGMENT.hexToByteArray())

    /** The authority signs completions outside loom-mesh; the harness signed this one the same way. */
    fun completion() = Completion("authority", id, "pi_capture_0001", t + 10uL).also {
        it.signature = crypto.sign(LoomVectors.SEED_AUTHORITY.hexToByteArray(), Cbor.encode(it.unsigned()))
    }

    fun receipts(late: ULong = 0uL): Series<Receipt> = s_[
        Receipt.issue_at("replica-a", id, nonce, false, LoomVectors.SEED_REPLICA_A.hexToByteArray(), t + 20uL, crypto),
        Receipt.issue_at("replica-b", id, nonce, false, LoomVectors.SEED_REPLICA_B.hexToByteArray(), t + 20uL, crypto),
        Receipt.issue_at("replica-c", id, nonce, false, LoomVectors.SEED_REPLICA_C.hexToByteArray(), t + 20uL + late, crypto),
    ]

    fun gcsReceipt(bucket: String = "loom-archive") = LoomVectors.BUNDLE.hexToByteArray().let { bundle ->
        GcsReceipt(bucket, "loom-settlement/v1/${sha256(bundle).toHexString()}.cbor", 1_790_000_123_456_789uL, bundle.size.toULong(), sha256(bundle))
    }

    @Test
    fun completionSignsToTheCapturedBytes() {
        val completion = completion()
        assertEquals(LoomVectors.COMPLETION, completion.to_bytes().toHexString())
        assertEquals(LoomVectors.COMPLETION_DIGEST, completion.digest().toHexString())
        Completion.from_bytes(LoomVectors.COMPLETION.hexToByteArray()).verify(authority, id, crypto)
    }

    @Test
    fun settlementAdmitsToTheCapturedBytes() {
        val settlement = Settlement.admit_at(completion(), "replica-a", receipts(), LoomVectors.SEED_REPLICA_A.hexToByteArray(), t + 20uL, crypto)
        assertEquals(LoomVectors.SETTLEMENT, settlement.to_bytes().toHexString())
        val captured = Settlement.from_bytes(LoomVectors.SETTLEMENT.hexToByteArray())
        assertEquals(LoomVectors.SETTLEMENT, captured.to_bytes().toHexString())
        captured.verify(config(), id, crypto)
    }

    @Test
    fun bundleAndHandoffMatchTheCapturedBytes() {
        val bundle = settlement_bundle(segment, completion())
        assertEquals(LoomVectors.BUNDLE, bundle.toHexString())
        assertEquals(LoomVectors.BUNDLE_NAME, bundle_name(bundle))
        val handoff = Handoff.issue(config(), segment, completion(), gcsReceipt(), LoomVectors.SEED_REPLICA_A.hexToByteArray(), crypto)
        assertEquals(LoomVectors.HANDOFF, handoff.to_bytes().toHexString())
        val captured = Handoff.from_bytes(LoomVectors.HANDOFF.hexToByteArray())
        assertEquals(LoomVectors.HANDOFF, captured.to_bytes().toHexString())
        captured.verify(config(), completion(), segment, crypto)
    }

    @Test
    fun lifecycleHeadroomMatches() {
        assertEquals(LoomVectors.LIFECYCLE_HEADROOM_3, lifecycle_headroom_bound(3))
        assertEquals(LoomVectors.LIFECYCLE_HEADROOM_64, lifecycle_headroom_bound(64))
    }

    @Test
    fun settlementRejectionsAgree() {
        val completion = completion()
        val settlement = Settlement.from_bytes(LoomVectors.SETTLEMENT.hexToByteArray())
        val handoff = Handoff.from_bytes(LoomVectors.HANDOFF.hexToByteArray())
        val seed = LoomVectors.SEED_REPLICA_A.hexToByteArray()
        val cases: Map<String, () -> Unit> = mapOf(
            "admission_quorum" to {
                Settlement.from_bytes(Settlement(completion, "replica-a", t + 20uL, settlement.receipts[0 until 2], settlement.signature).to_bytes())
            },
            "admission_custody" to {
                Settlement.from_bytes(Settlement.admit_at(completion, "replica-a", receipts(late = 31uL), seed, t + 20uL, crypto).to_bytes())
                    .verify(config(), id, crypto)
            },
            "admission_wrong_id" to { settlement.verify(config(), pattern(32, 1, 1), crypto) },
            "handoff_other_bucket" to { handoff.verify(config(gcs = GcsConfig("other-archive")), completion, segment, crypto) },
            "handoff_other_node" to { handoff.verify(config(id = "replica-b"), completion, segment, crypto) },
            "handoff_invalid_receipt" to { Handoff.issue(config(), segment, completion, gcsReceipt(bucket = "Loom"), seed, crypto) },
            "completion_wrong_authority" to { completion.verify(replicaA, id, crypto) },
            "completion_reference_control" to {
                Completion.from_bytes(Completion("authority", id, "a\nb", t + 10uL, completion.signature).to_bytes())
            },
        )
        for ((name, expected) in LoomVectors.settlementErrors.view) assertEquals(expected, outcome(cases.getValue(name)), name)
    }

    @Test
    fun gcsReceiptValidationAgrees() {
        val r = gcsReceipt()
        fun edit(bucket: String = r.bucket, name: String = r.`object`, generation: ULong = r.generation, size: ULong = r.size) =
            GcsReceipt(bucket, name, generation, size, r.sha256)
        val cases = mapOf(
            "valid" to r,
            "bucket_short" to edit(bucket = "ab"),
            "bucket_upper" to edit(bucket = "Loom-archive"),
            "bucket_dash" to edit(bucket = "loom-archive-"),
            "bucket_64" to edit(bucket = "a".repeat(64)),
            "object_dotdot" to edit(name = "loom/../x"),
            "object_empty_part" to edit(name = "loom//x"),
            "object_257" to edit(name = "a".repeat(257)),
            "object_256" to edit(name = "a".repeat(256)),
            "generation_0" to edit(generation = 0uL),
            "generation_range" to edit(generation = 1uL shl 63),
            "size_0" to edit(size = 0uL),
            "size_over" to edit(size = MAX_WIRE.toULong() + 1uL),
        )
        for ((name, expected) in LoomVectors.gcsReceipt.view) assertEquals(expected, outcome { cases.getValue(name).validate() }, name)
    }
}
