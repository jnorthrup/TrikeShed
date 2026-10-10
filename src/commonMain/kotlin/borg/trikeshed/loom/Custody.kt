package borg.trikeshed.loom

import borg.trikeshed.collections.associative.*
import borg.trikeshed.ipns.IpnsCrypto
import borg.trikeshed.lib.*

/**
 * cocaine-rats crates/loom-mesh/src/custody.rs: canonical signed possession evidence, independent of
 * clocks, entropy and storage. `*_at` callers supply independently trusted execution time.
 */
class Custody(
    val id: Id,
    val nodes: Series<String>,
    val degraded: Boolean,
    val challenge: ByteArray,
    val receipts: Series<Receipt>,
    val domains: Series<String>,
) {
    fun to_bytes(): ByteArray = Cbor.encode(
        itemArrayOf(
            Item.Str("loom-custody/v1"),
            Item.Bin(id),
            Item.Bin(challenge),
            Item.Arr(receipts α { Item.Bin(it.to_bytes()) }),
        )
    )

    companion object {
        fun verified_at(id: Id, challenge: ByteArray, receipts: Series<Receipt>, members: Series<Member>, now: ULong, crypto: IpnsCrypto): Custody {
            trusted_time(now)
            if (receipts.size > 64) error("custody bounds")
            validate_custody_members(members)
            val nodes = mutableSetOf<String>()
            val domains = mutableSetOf<String>()
            for (receipt in receipts.view) {
                val peer = members.view.firstOrNull { it.id == receipt.signer } ?: error("unknown receipt signer")
                receipt.verify_fresh_at(peer, id, challenge, now, crypto)
                if (receipt.archival || !nodes.add(peer.id) || !domains.add(peer.domain)) error("duplicate or archival custody")
            }
            return Custody(id, nodes.sorted().toSeries(), nodes.size < 3, challenge, receipts, domains.sorted().toSeries())
        }

        fun from_bytes_at(data: ByteArray, id: Id, challenge: ByteArray, members: Series<Member>, now: ULong, crypto: IpnsCrypto): Custody {
            trusted_time(now)
            val a = array(decode(data), 4)
            tag(a[0], "loom-custody/v1")
            if (!fixed(a[1], 32).contentEquals(id) || !fixed(a[2], 32).contentEquals(challenge)) error("custody binding")
            val items = a[3] as? Item.Arr ?: error("custody schema")
            if (items.size > 64) error("custody bounds")
            val receipts = Array(items.size) { Receipt.from_bytes(bytes(items[it])) }.toSeries()
            return verified_at(id, challenge, receipts, members, now, crypto)
        }
    }
}
