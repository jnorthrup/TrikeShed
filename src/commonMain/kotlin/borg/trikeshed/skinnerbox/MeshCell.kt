package borg.trikeshed.skinnerbox

import borg.trikeshed.collections.associative.Cbor
import borg.trikeshed.collections.associative.Item
import borg.trikeshed.collections.associative.toAny
import borg.trikeshed.context.nuid.contains
import borg.trikeshed.ipns.Ed25519
import borg.trikeshed.ipns.IpnsCrypto
import borg.trikeshed.ipns.IpnsKeyPair
import borg.trikeshed.job.CanonicalCbor
import borg.trikeshed.job.ContentId
import borg.trikeshed.lib.*

/** One immutable ownership epoch. A placement change is never implicit request routing. */
class MeshCell(val namespace: String, members: Series<MeshPeer>, decision: ByteArray) {
    private val peers = Array(members.also { require(it.size == 3) { "A cell requires exactly three members" } }.size) { members[it] }
    private val policy = decision.also { require(it.size in 1..64 * 1024) { "Placement decision bound" } }.copyOf()
    val members: Series<MeshPeer> get() = peers.size j { peers[it] }
    val owner: MeshPeer get() = peers[0]
    val replicas: Twin<MeshPeer> get() = peers[1] j peers[2]
    val decision: ByteArray get() = policy.copyOf()
    val epoch: ContentId
    private val record: ByteArray

    init {
        val subnet = meshSubnet(namespace)
        require(peers.size == 3 && peers.map { it.id }.toSet().size == 3 &&
            peers.map { it.failureDomain }.toSet().size == 3) { "A cell requires three identities and failure domains" }
        require(peers.all { it.subnet contains subnet }) { "Cell namespace exceeds a member grant" }
        require(policy.size in 1..64 * 1024) { "Placement decision bound" }
        val reader = Cbor.Reader(policy, 0, maxDepth = 16, maxItems = 16_384)
        val item = reader.readItem()
        require(reader.pos == policy.size && item is Item.Map) { "Placement decision shape" }
        @Suppress("UNCHECKED_CAST")
        val fields = item.toAny() as Map<String, Any?>
        require(CanonicalCbor.encodeMap(fields).contentEquals(policy)) { "Placement decision must be canonical" }
        record = CanonicalCbor.encodeMap(mapOf(
            "format" to "skinnerbox.cell/v1", "namespace" to namespace, "decision" to policy,
            "members" to peers.map { mapOf("id" to it.id, "publicKey" to it.publicKey,
                "failureDomain" to it.failureDomain, "origin" to it.origin, "subnet" to it.subnet.toString()) },
        ))
        epoch = ContentId.of(record)
    }

    fun bytes(): ByteArray = record.copyOf()
    fun member(id: String): MeshPeer? = peers.firstOrNull { it.id == id }
}

/** Fresh signed durable custody, distinct from task execution or distributed consensus. */
class MeshCustody private constructor(val peer: String, val epoch: ContentId, val record: ContentId,
    val atMillis: Long, private val encoded: ByteArray) {
    fun bytes(): ByteArray = encoded.copyOf()

    companion object {
        private const val DOMAIN = "skinnerbox.custody/v1"
        const val MAX_AGE_MILLIS = 30_000L

        fun issue(local: MeshPeer, identity: IpnsKeyPair, cell: MeshCell, record: ContentId,
            atMillis: Long, crypto: IpnsCrypto = Ed25519): MeshCustody {
            require(cell.member(local.id) != null && identity.publicKey.contentEquals(local.publicKey))
            require(atMillis >= 0)
            val body = CanonicalCbor.encodeMap(mapOf("format" to DOMAIN, "peer" to local.id,
                "epoch" to cell.epoch.value, "namespace" to cell.namespace, "record" to record.value,
                "atMillis" to atMillis, "durable" to true))
            val wire = CanonicalCbor.encodeMap(mapOf("body" to body, "signature" to crypto.sign(identity.privateKey, body)))
            return verify(wire, cell, record, atMillis, crypto)
        }

        fun verify(bytes: ByteArray, cell: MeshCell, expectedRecord: ContentId, nowMillis: Long,
            crypto: IpnsCrypto = Ed25519): MeshCustody {
            require(bytes.size in 1..2048) { "Custody byte bound" }
            val owned = bytes.copyOf()
            val outer = MeshPersistenceCodec.decode(owned, 2048)
            require(outer.keys == setOf("body", "signature")) { "Custody envelope" }
            val body = outer["body"] as? ByteArray ?: error("Custody body")
            val signature = outer["signature"] as? ByteArray ?: error("Custody signature")
            val fields = MeshPersistenceCodec.decode(body, 1024)
            require(fields.keys == setOf("format", "peer", "epoch", "namespace", "record", "atMillis", "durable") &&
                fields["format"] == DOMAIN && fields["durable"] == true && fields["epoch"] == cell.epoch.value &&
                fields["namespace"] == cell.namespace && fields["record"] == expectedRecord.value) { "Custody binding" }
            val peer = cell.member(fields["peer"] as? String ?: error("Custody peer")) ?: error("Custodian is not a cell member")
            val at = fields["atMillis"] as? Long ?: error("Custody time")
            require(nowMillis >= 0 && at >= 0 &&
                (if (at <= nowMillis) nowMillis - at else at - nowMillis) <= MAX_AGE_MILLIS) { "Custody expired" }
            require(signature.size == 64 && crypto.verify(peer.publicKey, body, signature)) { "Custody signature" }
            return MeshCustody(peer.id, cell.epoch, expectedRecord, at, owned)
        }

        /** Validate the complete bounded proof set; repeated signatures never count as another custodian. */
        fun verifyAll(proofs: Series<ByteArray>, cell: MeshCell, expectedRecord: ContentId, nowMillis: Long,
            crypto: IpnsCrypto = Ed25519): Series<MeshCustody> {
            require(proofs.size in 0..3) { "Custody proof count" }
            val verified = Array(proofs.size) { verify(proofs[it], cell, expectedRecord, nowMillis, crypto) }
            require(verified.map { it.peer }.toSet().size == verified.size) { "Duplicate custody identity" }
            return verified.size j { verified[it] }
        }
    }
}

/** A local commit may already exist. Retry its original request ID to finish custody. */
class MeshCustodyPending(val namespace: String, val epoch: ContentId, val record: ContentId,
    result: ByteArray, val holders: Int) : IllegalStateException(
        if (holders == 3) "Three peers retained the commit; completion metadata is pending. Retry the same request ID"
        else "Local commit retained by $holders of 3 peers; retry the same request ID") {
    private val reply = result.copyOf()
    val result: ByteArray get() = reply.copyOf()
}
