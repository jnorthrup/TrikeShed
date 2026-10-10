package borg.trikeshed.loom

import borg.trikeshed.lib.*

/*
 * cocaine-rats crates/loom-mesh/src/lease.rs, the receipt: non-secret evidence of one time-bounded
 * provider credential, what the key inventory consumes. Leases themselves hold secrets and never travel
 * in ledger records.
 */
const val RECEIPT_FORMAT: String = "loom-lease-receipt/v1"

/** serde snake_case on the wire. */
enum class Provider {
    Google, Cloudflare, Vast, Runpod;

    fun enforcement(): Enforcement = when (this) {
        Google, Cloudflare -> Enforcement.Provider
        Vast -> Enforcement.Revocation
        Runpod -> Enforcement.Consumer
    }

    fun purpose(): Purpose = when (this) {
        Google, Cloudflare -> Purpose.Archive
        Runpod -> Purpose.Invocation
        Vast -> Purpose.Management
    }
}

/** What the credential may be used for inside LOOM. */
enum class Purpose {
    /** Offsite settlement-evidence archive writes. */
    Archive,

    /** Provider edge authorization for calling an enrolled mesh endpoint. */
    Invocation,

    /** Provider management API (provisioning); never accepted by the mesh. */
    Management,
}

enum class Enforcement {
    /** The provider rejects the credential after expiry. */
    Provider,

    /** The provider key never expires; the keeper must delete it at expiry. */
    Revocation,

    /** No provider expiry or revocation API; only LOOM consumers refuse it. */
    Consumer,
}

class LeaseReceipt(
    val format: String,
    val provider: Provider,
    val purpose: Purpose,
    val enforcement: Enforcement,
    val scope: String,
    val issued_at: ULong,
    val not_after: ULong,
    /** Public provider key identity (Vast key id, R2 access key id). */
    val key_id: String? = null,
) {
    companion object {
        /** serde_json with deny_unknown_fields: `key_id` defaults to null. */
        fun from_json(bytes: ByteArray): LeaseReceipt {
            val fields = s_["format", "provider", "purpose", "enforcement", "scope", "issued_at", "not_after", "key_id"]
            val values = deserialize_struct(bytes, fields)
            fun value(at: Int) = values[at] ?: error("missing field `${fields[at]}`")
            return LeaseReceipt(
                value(0).deserialize_string("format"),
                value(1).deserialize_enum("provider", Provider.entries),
                value(2).deserialize_enum("purpose", Purpose.entries),
                value(3).deserialize_enum("enforcement", Enforcement.entries),
                value(4).deserialize_string("scope"),
                value(5).deserialize_u64("issued_at"),
                value(6).deserialize_u64("not_after"),
                values[7]?.let { if (it == Null) null else it.deserialize_string("key_id") },
            )
        }
    }
}
