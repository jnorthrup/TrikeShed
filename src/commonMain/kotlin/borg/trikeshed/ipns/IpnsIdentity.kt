package borg.trikeshed.ipns

import kotlin.coroutines.CoroutineContext

interface IpnsCrypto : CoroutineContext.Element {
    companion object Key : CoroutineContext.Key<IpnsCrypto>
    override val key: CoroutineContext.Key<*> get() = Key
    fun generate(): IpnsKeyPair
    fun sign(privateKey: ByteArray, payload: ByteArray): ByteArray
    fun verify(publicKey: ByteArray, payload: ByteArray, signature: ByteArray): Boolean
}

class IpnsKeyPair(publicKey: ByteArray, privateKey: ByteArray) {
    private val publicBytes = publicKey.copyOf()
    private val seed = privateKey.copyOf()
    init { require(publicBytes.size == 32 && seed.size == 32) { "Ed25519 public32/private seed32 required" } }
    val publicKey: ByteArray get() = publicBytes.copyOf()
    val privateKey: ByteArray get() = seed.copyOf()
    val name: IpnsName get() = IpnsName.fromPublicKey(publicBytes)
}

/** Binary peer multihash; equality never depends on mutable byte-array identity. */
class IpnsName(multihash: ByteArray) {
    private val hash = multihash.copyOf()
    init {
        val (code, digest) = IpnsEncoding.multihashParts(hash)
        require(code == 0uL && digest.isNotEmpty() && digest.size <= 42 || code == 0x12uL && digest.size == 32) { "Unsupported peer multihash" }
    }
    val multihash: ByteArray get() = hash.copyOf()
    val routingKey: ByteArray get() = "/ipns/".encodeToByteArray() + hash
    val publicKey: ByteArray get() {
        val (code, digest) = IpnsEncoding.multihashParts(hash)
        require(code == 0uL) { "Public key is not inlined" }
        return IpnsEncoding.publicKeyRaw(digest)
    }
    fun matches(publicKey: ByteArray): Boolean {
        val encoded = IpnsEncoding.publicKey(publicKey)
        val (code, digest) = IpnsEncoding.multihashParts(hash)
        return digest.contentEquals(if (code == 0uL) encoded else IpnsEncoding.sha256(encoded))
    }
    override fun toString(): String = IpnsEncoding.base36(IpnsCid(0x72u, hash).bytes)
    fun peerId(): String = IpnsEncoding.base58(hash)
    override fun equals(other: Any?): Boolean = other is IpnsName && hash.contentEquals(other.hash)
    override fun hashCode(): Int = hash.contentHashCode()
    companion object {
        fun fromPublicKey(publicKey: ByteArray): IpnsName = IpnsName(IpnsEncoding.multihash(0u, IpnsEncoding.publicKey(publicKey)))
        fun parse(text: String): IpnsName {
            val name = text.removePrefix("/ipns/")
            return if (name.startsWith("Qm") || name.startsWith("1")) IpnsName(IpnsEncoding.unbase58(name))
            else IpnsCid.parse(name).let { require(it.codec == 0x72uL) { "Expected libp2p-key CID" }; IpnsName(it.multihash) }
        }
    }
}
