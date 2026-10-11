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

/**
 * A replica's signed statement that it read back the exact version bytes at a held GCS generation.
 * Peers adopt it; it is not a Google signature.
 */
class LedgerSettled(
    val signer: String,
    val stream: String,
    val version: ULong,
    val digest: Id,
    val settled_at: ULong,
    val receipt: GcsReceipt,
    var signature: ByteArray = ByteArray(64),
) {
    fun unsigned(): Item = itemArrayOf(
        Item.Str("loom-ledger-settled/v1"),
        Item.Str(signer),
        Item.Str(stream),
        Item.Num(version.toLong()),
        Item.Bin(digest),
        Item.Num(settled_at.toLong()),
        Item.Str(receipt.bucket),
        Item.Str(receipt.`object`),
        Item.Num(receipt.generation.toLong()),
        Item.Num(receipt.size.toLong()),
        Item.Bin(receipt.sha256),
    )

    fun to_bytes(): ByteArray = Cbor.encode(itemArrayOf(unsigned(), Item.Bin(signature)))

    fun verify(config: Config, version: LedgerVersion, crypto: IpnsCrypto) {
        receipt.validate()
        val signer = config.member(this.signer)
        val `object` = version.object_name()
        val bytes = version.to_bytes()
        val gcs = config.gcs
        if (!signer.has(Role.Replica) || stream != version.stream || this.version != version.version ||
            !digest.contentEquals(version.digest()) || !receipt.sha256.contentEquals(digest) ||
            receipt.size != bytes.size.toULong() || settled_at < version.issued_at || settled_at > Long.MAX_VALUE.toULong() ||
            !receipt.`object`.endsWith("/$`object`") ||
            (gcs != null && (receipt.bucket != gcs.bucket || receipt.`object` != "${gcs.prefix}/$`object`"))
        ) error("ledger settlement binding")
        if (!crypto.verify(signer.key(), Cbor.encode(unsigned()), signature)) error("ledger settlement signature")
    }

    companion object {
        fun issue(config: Config, version: LedgerVersion, receipt: GcsReceipt, settled_at: ULong, key: ByteArray, crypto: IpnsCrypto): LedgerSettled =
            LedgerSettled(config.id, version.stream, version.version, version.digest(), settled_at, receipt).also {
                it.signature = crypto.sign(key, Cbor.encode(it.unsigned()))
                it.verify(config, version, crypto)
            }

        fun from_bytes(data: ByteArray): LedgerSettled {
            val outer = array(decode(data), 2)
            val a = array(outer[0], 11)
            tag(a[0], "loom-ledger-settled/v1")
            return LedgerSettled(
                text(a[1]), text(a[2]), number(a[3]), fixed(a[4], 32), number(a[5]),
                GcsReceipt(text(a[6]), text(a[7]), number(a[8]), number(a[9]), fixed(a[10], 32)),
                fixed(outer[1], 64),
            ).also { it.receipt.validate() }
        }
    }
}

enum class Accepted {
    New,
    Duplicate,

    /** Older than the settled version and chain-consistent: nothing to hold. */
    Stale,
}

/**
 * One stream's replica-held state: the most recent settled version plus the latest unsettled
 * version of each not-yet-settled epoch.
 */
class LedgerSlots(
    var settled: Join<LedgerVersion, LedgerSettled>? = null,
    var pending: Series<LedgerVersion> = 0 j { _: Int -> error("empty series") },
) {
    fun held(): Series<LedgerVersion> = settled?.let { (v, _) -> (pending.size + 1) j { i: Int -> if (i == 0) v else pending[i - 1] } } ?: pending

    /** Serial-version conflict resolution against everything held. Callers verify the authority signature first. */
    fun check(v: LedgerVersion): Accepted? {
        val digest = v.digest()
        for (h in held().view) {
            if (h.stream != v.stream) error("ledger stream binding")
            if (h.version == v.version) return if (h.digest().contentEquals(digest)) Accepted.Duplicate else error("ledger version conflict")
            if ((h.version < v.version && h.issued_at > v.issued_at) || (h.version > v.version && h.issued_at < v.issued_at))
                error("ledger version order")
            if ((h.version + 1uL == v.version && !v.previous.contentEquals(h.digest())) ||
                (v.version + 1uL == h.version && !h.previous.contentEquals(digest))
            ) error("ledger chain fork")
        }
        if (settled?.a?.let { it.version > v.version } == true) return Accepted.Stale
        return null
    }

    fun accept(v: LedgerVersion): Accepted {
        check(v)?.let { return it }
        val next = pending.view.toMutableList()
        next.add(next.count { it.version < v.version }, v)
        // Cumulative versions: the latest of an epoch supersedes earlier ones.
        val kept = ArrayList<LedgerVersion>()
        for (p in next.asReversed()) if (kept.none { it.epoch_start() == p.epoch_start() }) kept.add(p)
        kept.reverse()
        if (kept.size > MAX_LEDGER_PENDING) error("ledger backlog")
        pending = kept.toSeries()
        return Accepted.New
    }

    /** The latest version of a closed epoch (minute 15 has passed). */
    fun candidate(now: ULong): LedgerVersion? = pending.view.lastOrNull { it.window_open() <= now }

    /** False for a duplicate or older settlement (the first is kept). */
    fun record_settled(v: LedgerVersion, s: LedgerSettled): Boolean {
        settled?.let { (held, _) ->
            if (held.version >= v.version) return when (check(v)) {
                Accepted.Duplicate, Accepted.Stale -> false
                else -> error("ledger settlement order")
            }
        }
        check(v)
        pending = pending.view.filter { it.version > v.version }.toList().toSeries()
        settled = v j s
        return true
    }

    fun stream(): String? = held().view.firstOrNull()?.stream

    fun top(): ULong = held().view.maxOfOrNull { it.version } ?: 0uL

    fun settled_version(): ULong = settled?.a?.version ?: 0uL

    fun body(stream: String): Item = itemArrayOf(
        Item.Str("loom-ledger-slots/v1"),
        Item.Str(stream),
        Item.Bin(settled?.a?.to_bytes() ?: ByteArray(0)),
        Item.Bin(settled?.b?.to_bytes() ?: ByteArray(0)),
        Item.Arr(pending α { Item.Bin(it.to_bytes()) }),
    )

    fun encode(stream: String): ByteArray {
        val body = body(stream)
        return Cbor.encode(itemArrayOf(body, Item.Bin(sha256(Cbor.encode(body)))))
    }

    companion object {
        /**
         * Decode and rebuild through the same acceptance rules: a stored file is valid only if it is
         * exactly what those rules would have produced.
         */
        fun decode(data: ByteArray, config: Config, crypto: IpnsCrypto): Join<String, LedgerSlots> {
            val outer = array(decode(data), 2)
            if (!fixed(outer[1], 32).contentEquals(sha256(Cbor.encode(outer[0])))) error("ledger checksum")
            val a = array(outer[0], 5)
            tag(a[0], "loom-ledger-slots/v1")
            val stream = text(a[1])
            label(stream)
            val authority = ledger_authority(config)
            val rebuilt = LedgerSlots()
            val sv = bytes(a[2])
            val ss = bytes(a[3])
            if (sv.isEmpty() != ss.isEmpty()) error("ledger settled pair")
            if (sv.isNotEmpty()) {
                val v = LedgerVersion.from_bytes(sv)
                val s = LedgerSettled.from_bytes(ss)
                v.verify(authority, crypto)
                s.verify(config, v, crypto)
                rebuilt.record_settled(v, s)
            }
            val rows = a[4] as? Item.Arr ?: error("ledger pending")
            for (row in rows.items.view) {
                val v = LedgerVersion.from_bytes(bytes(row))
                v.verify(authority, crypto)
                if (rebuilt.accept(v) != Accepted.New) error("ledger pending not canonical")
            }
            if (rebuilt.stream()?.let { it != stream } == true || !rebuilt.encode(stream).contentEquals(data))
                error("ledger slots not canonical")
            return stream j rebuilt
        }
    }
}

fun ledger_authority(config: Config): Member =
    config.member(config.settlement_authority ?: error("settlement authority missing"))

/**
 * Wire view for the desk/UI: the most recent settled ledger (GCS-verified bytes) plus the
 * provisional unsettled versions every replica holds.
 */
class LedgerHead(
    val stream: String,
    val settled: Join<LedgerVersion, LedgerSettled>?,
    val pending: Series<LedgerVersion>,
    /** Replicas (including the responder) confirmed to hold the update. */
    val holders: ULong,
    /** This call moved a closed epoch upstream. */
    val uploaded: Boolean,
) {
    fun to_bytes(): ByteArray = Cbor.encode(
        itemArrayOf(
            Item.Str("loom-ledger-head/v1"),
            Item.Str(stream),
            Item.Bin(settled?.a?.to_bytes() ?: ByteArray(0)),
            Item.Bin(settled?.b?.to_bytes() ?: ByteArray(0)),
            Item.Arr(pending α { Item.Bin(it.to_bytes()) }),
            Item.Num(holders.toLong()),
            Item.Num(if (uploaded) 1 else 0),
        )
    )

    companion object {
        fun of(stream: String, slots: LedgerSlots, holders: ULong, uploaded: Boolean): LedgerHead =
            LedgerHead(stream, slots.settled, slots.pending, holders, uploaded)

        /** Structural decode; callers verify against their own trust config. */
        fun from_bytes(data: ByteArray): LedgerHead {
            val a = array(decode(data), 7)
            tag(a[0], "loom-ledger-head/v1")
            val sv = bytes(a[2])
            val ss = bytes(a[3])
            val settled = if (sv.isEmpty() && ss.isEmpty()) null else LedgerVersion.from_bytes(sv) j LedgerSettled.from_bytes(ss)
            val rows = a[4] as? Item.Arr ?: error("ledger head pending")
            return LedgerHead(
                text(a[1]),
                settled,
                Array(rows.size) { LedgerVersion.from_bytes(bytes(rows[it])) }.toSeries(),
                number(a[5]),
                number(a[6]) == 1uL,
            )
        }
    }
}
