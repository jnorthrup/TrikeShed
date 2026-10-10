package borg.trikeshed.loom

import borg.trikeshed.collections.associative.*
import borg.trikeshed.ipns.IpnsCrypto
import borg.trikeshed.job.sha256
import borg.trikeshed.lib.*

/** cocaine-rats crates/loom-mesh/src/completion.rs: the settlement authority's signed financial completion. */
class Completion(
    val signer: String,
    val id: Id,
    val reference: String,
    val completed_at: ULong,
    var signature: ByteArray = ByteArray(64),
) {
    fun validate() {
        label(signer)
        if (reference.isEmpty() || reference.encodeToByteArray().size > 256 || reference.any { it.isISOControl() } ||
            completed_at == 0uL || completed_at > Long.MAX_VALUE.toULong()
        ) error("completion bounds")
    }

    fun unsigned(): Item = itemArrayOf(
        Item.Str("loom-settlement-completed/v1"),
        Item.Str(signer),
        Item.Bin(id),
        Item.Str(reference),
        Item.Num(completed_at.toLong()),
    )

    fun to_bytes(): ByteArray = Cbor.encode(itemArrayOf(unsigned(), Item.Bin(signature)))

    fun verify(authority: Member, id: Id, crypto: IpnsCrypto) {
        validate()
        if (signer != authority.id || !this.id.contentEquals(id)) error("completion authority or binding")
        if (!crypto.verify(authority.key(), Cbor.encode(unsigned()), signature)) error("completion signature")
    }

    fun digest(): Id = sha256(to_bytes())

    companion object {
        fun from_bytes(data: ByteArray): Completion {
            val outer = array(decode(data), 2)
            val a = array(outer[0], 5)
            tag(a[0], "loom-settlement-completed/v1")
            return Completion(text(a[1]), fixed(a[2], 32), text(a[3]), number(a[4]), fixed(outer[1], 64)).also { it.validate() }
        }
    }
}
