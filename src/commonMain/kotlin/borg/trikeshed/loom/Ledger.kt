package borg.trikeshed.loom

import borg.trikeshed.collections.associative.*
import borg.trikeshed.ipns.IpnsCrypto
import borg.trikeshed.job.sha256
import borg.trikeshed.lib.*

/*
 * cocaine-rats crates/loom-mesh/src/ledger.rs: the serially versioned settlement ledger (DEC-081).
 * The settlement authority is the single writer of each stream. A version is cumulative (it replaces,
 * never amends, its predecessor), hash-chained to the previous version and signed. Time is cut into
 * aligned 15-minute epochs; an epoch's final version goes upstream from minute 15.
 */
const val LEDGER_EPOCH_SECS: ULong = 900uL
val LEDGER_DEADLINE_SECS: ULong = 2uL * LEDGER_EPOCH_SECS
const val MAX_LEDGER_BODY: Int = 96 * 1024

/** Unsettled epochs retained per stream; exceeding it is backpressure on the writer, never a silent drop. */
const val MAX_LEDGER_PENDING: Int = 8
const val MAX_LEDGER_STREAMS: Int = 1024

class LedgerVersion(
    val signer: String,
    val stream: String,
    val version: ULong,
    val previous: Id,
    val issued_at: ULong,
    /** Opaque cumulative ledger state; the authority encrypts it. */
    val body: ByteArray,
    var signature: ByteArray = ByteArray(64),
) {
    /** The chained successor: version + 1 whose `previous` is this digest. */
    fun next(issued_at: ULong, body: ByteArray, key: ByteArray, crypto: IpnsCrypto): LedgerVersion =
        sign(signer, stream, if (version == ULong.MAX_VALUE) error("ledger version range") else version + 1uL, digest(), issued_at, body, key, crypto)

    fun validate() {
        label(signer)
        label(stream)
        val max = Long.MAX_VALUE.toULong()
        if (version !in 1uL..max || issued_at !in 1uL..max - LEDGER_DEADLINE_SECS || body.size !in 1..MAX_LEDGER_BODY ||
            (version == 1uL) != previous.all { it == 0.toByte() }
        ) error("ledger version bounds")
    }

    fun unsigned(): Item = itemArrayOf(
        Item.Str("loom-ledger-version/v1"),
        Item.Str(signer),
        Item.Str(stream),
        Item.Num(version.toLong()),
        Item.Bin(previous),
        Item.Num(issued_at.toLong()),
        Item.Bin(body),
    )

    fun to_bytes(): ByteArray = Cbor.encode(itemArrayOf(unsigned(), Item.Bin(signature)))

    fun verify(authority: Member, crypto: IpnsCrypto) {
        validate()
        if (signer != authority.id) error("ledger signer")
        if (!crypto.verify(authority.key(), Cbor.encode(unsigned()), signature)) error("ledger signature")
    }

    fun digest(): Id = sha256(to_bytes())

    fun epoch_start(): ULong = issued_at - issued_at % LEDGER_EPOCH_SECS

    /** Minute 15: the epoch is closed and its final version may go upstream. */
    fun window_open(): ULong = epoch_start() + LEDGER_EPOCH_SECS

    /** Relative GCS object: one immutable object per stream version number. */
    fun object_name(): String = "ledger/$stream/${version.toString().padStart(20, '0')}.cbor"

    companion object {
        fun sign(signer: String, stream: String, version: ULong, previous: Id, issued_at: ULong, body: ByteArray, key: ByteArray, crypto: IpnsCrypto): LedgerVersion =
            LedgerVersion(signer, stream, version, previous, issued_at, body).also {
                it.validate()
                it.signature = crypto.sign(key, Cbor.encode(it.unsigned()))
            }

        fun from_bytes(data: ByteArray): LedgerVersion {
            val outer = array(decode(data), 2)
            val a = array(outer[0], 7)
            tag(a[0], "loom-ledger-version/v1")
            return LedgerVersion(text(a[1]), text(a[2]), number(a[3]), fixed(a[4], 32), number(a[5]), bytes(a[6]), fixed(outer[1], 64))
                .also { it.validate() }
        }
    }
}
