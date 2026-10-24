package borg.trikeshed.cas

import borg.trikeshed.agent.modelmux.ContextBlockException
import borg.trikeshed.agent.modelmux.ContextReadPolicy
import borg.trikeshed.agent.modelmux.ContextRejection
import borg.trikeshed.agent.modelmux.SharedContext
import borg.trikeshed.agent.modelmux.SharedContextMode
import borg.trikeshed.ipns.IpnsCid
import borg.trikeshed.ipns.toIpnsCid
import borg.trikeshed.job.CasStore
import borg.trikeshed.job.ContentId
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Characterizes the identity mapping and the exchange contract. This proves no peer transport: it pins what a transport must agree with. */
class ContextBlockExchangeTest {
    private val raw = 0x55uL

    @Test
    fun localDigestMapsToRawCidWithoutChangingBytes() {
        val bytes = byteArrayOf(0, 1, 2, 127, -1)
        val local = ContentId.of(bytes)
        assertEquals(IpnsCid.raw(bytes), local.toIpnsCid(raw))
        assertEquals(IpnsCid.raw(bytes), IpnsCid.parse(IpnsCid.raw(bytes).toString()))
    }

    @Test
    fun theRawCidCarriesTheRawCodecAndTheFullSha256DigestNotAPrefixRewrite() {
        for (bytes in listOf(ByteArray(0), byteArrayOf(0), "hello\n".encodeToByteArray(), ByteArray(4096) { (it * 31).toByte() })) {
            val local = ContentId.of(bytes)
            val cid = local.toIpnsCid(raw)
            assertEquals(raw, cid.codec)
            val multihash = cid.multihash
            assertEquals(34, multihash.size, "0x12 0x20 and the whole 32-byte digest")
            assertEquals(0x12, multihash[0].toInt())
            assertEquals(0x20, multihash[1].toInt())
            val hex = multihash.drop(2).joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
            assertEquals(local.value.substringAfter(':'), hex, "the digest in the CID is the digest of the ContentId")
        }
        // The well-known identity of the empty raw block.
        assertEquals("bafkreihdwdcefgh4dqkjv67uzcmw7ojee6xedzdetojuzjevtenxquvyku", IpnsCid.raw(ByteArray(0)).toString())
    }

    @Test
    fun binaryRoundTripPreservesBytesThroughVerificationAndTheLocalStore() = runTest {
        val everything = ByteArray(256) { it.toByte() } + byteArrayOf(0, 0, 0, 13, 10, 13, 10, -1, -2)
        val cas = CasStore.inMemory()
        val peer = object : ContextBlockExchange {
            override suspend fun fetch(cid: IpnsCid, maxBytes: Int): ByteArray = everything.copyOf()
            override suspend fun publish(bytes: ByteArray): IpnsCid = error("not a read")
            override suspend fun pin(cid: IpnsCid) = error("not a read")
        }
        val context = SharedContext(cas, peer, ContextReadPolicy(mode = SharedContextMode.PEER, allowedScopes = setOf("team/a")))
        val id = ContentId.of(everything)
        assertContentEquals(everything, context.block(id).bytes)
        assertContentEquals(everything, cas.get(id), "the verified bytes, unmodified, are what the store keeps")
    }

    @Test
    fun aTransportThatStopsAtTheCeilingIsReportedAsOversizeNotAsAFailure() = runTest {
        val peer = object : ContextBlockExchange {
            override suspend fun fetch(cid: IpnsCid, maxBytes: Int): ByteArray = throw BlockTooLarge(maxBytes)
            override suspend fun publish(bytes: ByteArray): IpnsCid = error("not a read")
            override suspend fun pin(cid: IpnsCid) = error("not a read")
        }
        val context = SharedContext(CasStore.inMemory(), peer, ContextReadPolicy(mode = SharedContextMode.PEER, maxBlockBytes = 64, allowedScopes = setOf("team/a")))
        val refused = assertFailsWith<ContextBlockException> { context.block(ContentId.of("x".encodeToByteArray())) }
        assertEquals(ContextRejection.OVERSIZE, refused.reason)
    }

    @Test
    fun aTransportThatReturnsTooManyBytesIsStillRefused() = runTest {
        val peer = object : ContextBlockExchange {
            override suspend fun fetch(cid: IpnsCid, maxBytes: Int): ByteArray = ByteArray(maxBytes + 1)
            override suspend fun publish(bytes: ByteArray): IpnsCid = error("not a read")
            override suspend fun pin(cid: IpnsCid) = error("not a read")
        }
        val context = SharedContext(CasStore.inMemory(), peer, ContextReadPolicy(mode = SharedContextMode.PEER, maxBlockBytes = 64, allowedScopes = setOf("team/a")))
        val refused = assertFailsWith<ContextBlockException> { context.block(ContentId.of("y".encodeToByteArray())) }
        assertEquals(ContextRejection.OVERSIZE, refused.reason)
    }
}
