package borg.trikeshed.loom

import borg.trikeshed.collections.associative.*
import borg.trikeshed.ipns.IpnsCrypto
import borg.trikeshed.job.sha256
import borg.trikeshed.lib.*

/*
 * cocaine-rats crates/loom-mesh/src/settlement.rs: authenticated external completion intake, not a
 * financial settlement engine.
 */

/** Exact serialized upper bound of the lifecycle fields of one record, from unsigned sizing templates. */
fun lifecycle_headroom_bound(members: Int): ULong {
    val max = Long.MAX_VALUE.toULong()
    val receipt = Receipt.capacity_template()
    val admission = Settlement(
        Completion("a".repeat(64), ByteArray(32), "a".repeat(256), max),
        "a".repeat(64),
        max,
        members.coerceIn(3, 64) j { _: Int -> receipt },
    )
    val handoff = Handoff(
        "a".repeat(64),
        ByteArray(32),
        "a".repeat(64) j (max j max),
        ByteArray(32),
        GcsReceipt("a".repeat(63), "a".repeat(256), max, max, ByteArray(32)),
    )
    // v1/v2 tags and array heads have equal sizes; v2 appends two byte strings, and an optional
    // historical archive receipt replaces one empty bin.
    return (Cbor.encode(Item.Bin(admission.to_bytes())).size + Cbor.encode(Item.Bin(handoff.to_bytes())).size +
        Cbor.encode(Item.Bin(receipt.to_bytes())).size - 1).toULong()
}

/** A replica's persisted initial N3 admission; historical custody is bound to the admission time. */
class Settlement(
    val completion: Completion,
    val signer: String,
    val admitted_at: ULong,
    val receipts: Series<Receipt>,
    var signature: ByteArray = ByteArray(64),
) {
    fun unsigned(): Item = itemArrayOf(
        Item.Str("loom-settlement-admission/v1"),
        Item.Bin(completion.to_bytes()),
        Item.Str(signer),
        Item.Num(admitted_at.toLong()),
        Item.Arr(receipts α { Item.Bin(it.to_bytes()) }),
    )

    fun to_bytes(): ByteArray = Cbor.encode(itemArrayOf(unsigned(), Item.Bin(signature)))

    fun verify(config: Config, id: Id, crypto: IpnsCrypto) {
        val authority = config.member(config.settlement_authority ?: error("settlement authority missing"))
        completion.verify(authority, id, crypto)
        val signer = config.member(this.signer)
        if (!signer.has(Role.Replica) || admitted_at < completion.completed_at ||
            admitted_at > Long.MAX_VALUE.toULong() || receipts.size !in 3..64
        ) error("admission binding")
        if (!crypto.verify(signer.key(), Cbor.encode(unsigned()), signature)) error("admission signature")
        val nodes = mutableSetOf<String>()
        val domains = mutableSetOf<String>()
        for (r in receipts.view) {
            val peer = config.member(r.signer)
            r.verify(peer, id, crypto)
            if (r.archival || !r.nonce.contentEquals(receipts[0].nonce) || r.time.abs_diff(admitted_at) > CLOCK_SKEW ||
                !nodes.add(peer.id) || !domains.add(peer.domain)
            ) error("admission custody")
        }
        if (this.signer !in nodes) error("admitter custody missing")
    }

    companion object {
        /** Rust's admit reads the wall clock for [admitted_at]; here the trusted runtime supplies it. */
        fun admit_at(completion: Completion, signer: String, receipts: Series<Receipt>, key: ByteArray, admitted_at: ULong, crypto: IpnsCrypto): Settlement =
            Settlement(completion, signer, admitted_at, receipts).also { it.signature = crypto.sign(key, Cbor.encode(it.unsigned())) }

        fun from_bytes(data: ByteArray): Settlement {
            val outer = array(decode(data), 2)
            val a = array(outer[0], 5)
            tag(a[0], "loom-settlement-admission/v1")
            val rows = a[4] as? Item.Arr ?: error("admission receipts")
            if (rows.size !in 3..64) error("admission quorum")
            return Settlement(
                Completion.from_bytes(bytes(a[1])),
                text(a[2]),
                number(a[3]),
                Array(rows.size) { Receipt.from_bytes(bytes(rows[it])) }.toSeries(),
                fixed(outer[1], 64),
            )
        }
    }
}

/**
 * The stable provider payload: no node-local admission time or custody nonce, so retries across
 * replicas have identical bytes. Bounded below [MAX_WIRE].
 */
fun settlement_bundle(segment: Segment, completion: Completion): ByteArray {
    if (!segment.id().contentEquals(completion.id)) error("bundle binding")
    val data = Cbor.encode(
        itemArrayOf(Item.Str("loom-settled-ledger/v1"), Item.Bin(segment.to_bytes()), Item.Bin(completion.to_bytes()))
    )
    if (data.size > MAX_WIRE) error("bundle size")
    return data
}

fun bundle_name(data: ByteArray): String = "${sha256(data).toHexString()}.cbor"

/**
 * Local signed attestation of a verified, exactly held GCS generation; not a Google signature. It keeps
 * the content and slot bindings after the encrypted local bytes are reclaimed.
 */
class Handoff(
    val signer: String,
    val id: Id,
    /** producer, epoch, sequence */
    val slot: Join<String, Twin<ULong>>,
    val completion_hash: Id,
    val receipt: GcsReceipt,
    var signature: ByteArray = ByteArray(64),
) {
    fun unsigned(): Item = itemArrayOf(
        Item.Str("loom-gcs-handoff/v1"),
        Item.Str(signer),
        Item.Bin(id),
        Item.Str(slot.a),
        Item.Num(slot.b.a.toLong()),
        Item.Num(slot.b.b.toLong()),
        Item.Bin(completion_hash),
        Item.Str(receipt.bucket),
        Item.Str(receipt.`object`),
        Item.Num(receipt.generation.toLong()),
        Item.Num(receipt.size.toLong()),
        Item.Bin(receipt.sha256),
    )

    fun to_bytes(): ByteArray = Cbor.encode(itemArrayOf(unsigned(), Item.Bin(signature)))

    fun verify(config: Config, completion: Completion, segment: Segment?, crypto: IpnsCrypto) {
        receipt.validate()
        val gcs = config.gcs ?: error("GCS trust missing")
        if (signer != config.id || !id.contentEquals(completion.id) || !completion_hash.contentEquals(completion.digest()) ||
            receipt.bucket != gcs.bucket || receipt.`object` != "${gcs.prefix}/${receipt.sha256.toHexString()}.cbor"
        ) error("handoff binding")
        if (!crypto.verify(config.member(signer).key(), Cbor.encode(unsigned()), signature)) error("handoff signature")
        if (segment != null) {
            val data = settlement_bundle(segment, completion)
            val slot = segment.slot()
            if (this.slot.a != slot.a || this.slot.b.a != slot.b.a || this.slot.b.b != slot.b.b ||
                receipt.size != data.size.toULong() || !receipt.sha256.contentEquals(sha256(data))
            ) error("handoff content binding")
        }
    }

    companion object {
        fun issue(config: Config, segment: Segment, completion: Completion, receipt: GcsReceipt, key: ByteArray, crypto: IpnsCrypto): Handoff =
            Handoff(config.id, segment.id(), segment.slot(), completion.digest(), receipt).also {
                it.signature = crypto.sign(key, Cbor.encode(it.unsigned()))
                it.verify(config, completion, segment, crypto)
            }

        fun from_bytes(data: ByteArray): Handoff {
            val outer = array(decode(data), 2)
            val a = array(outer[0], 12)
            tag(a[0], "loom-gcs-handoff/v1")
            return Handoff(
                text(a[1]),
                fixed(a[2], 32),
                text(a[3]) j (number(a[4]) j number(a[5])),
                fixed(a[6], 32),
                GcsReceipt(text(a[7]), text(a[8]), number(a[9]), number(a[10]), fixed(a[11], 32)),
                fixed(outer[1], 64),
            ).also { it.receipt.validate() }
        }
    }
}
