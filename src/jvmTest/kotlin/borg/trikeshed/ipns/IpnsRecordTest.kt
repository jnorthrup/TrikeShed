package borg.trikeshed.ipns

import borg.trikeshed.collections.associative.Cbor
import borg.trikeshed.collections.associative.Item
import kotlin.time.Instant
import kotlin.test.*

class IpnsRecordTest {
    private val crypto = JvmIpnsCrypto()
    private val now = Instant.parse("2026-09-11T00:00:00Z")
    private val eol = Instant.parse("2026-09-12T00:00:00.000000001Z")
    private fun hex(text: String): ByteArray = text.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test fun rfc8032Ed25519Vector() {
        val seed = hex("9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60")
        val public = hex("d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a")
        val signature = hex("e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e065224901555fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b")
        assertContentEquals(signature, crypto.sign(seed, byteArrayOf()))
        assertTrue(crypto.verify(public, byteArrayOf(), signature))
        assertFalse(crypto.verify(public, byteArrayOf(0), signature))
    }

    @Test fun officialIpip0428Fixtures() {
        val fixtures = listOf(
            "k51qzi5uqu5dm4tm0wt8srkg9h9suud4wuiwjimndrkydqm81cqtlb5ak6p7ku_v1" to null,
            "k51qzi5uqu5dlkw8pxuw9qmqayfdeh4kfebhmreauqdc6a7c3y7d5i9fi8mk9w_v1-v2" to "/ipfs/bafkqaddwgevxmmraojswg33smq",
            "k51qzi5uqu5dlmit2tuwdvnx4sbnyqgmvbxftl0eo3f33wwtb9gr7yozae9kpw_v1-v2-broken-v1-value" to null,
            "k51qzi5uqu5diamp7qnnvs1p1gzmku3eijkeijs3418j23j077zrkok63xdm8c_v1-v2-broken-signature-v2" to null,
            "k51qzi5uqu5dilgf7gorsh9vcqqq4myo6jd4zmqkuy9pxyxi5fua3uf7axph4y_v1-v2-broken-signature-v1" to "/ipfs/bafkqahtwgevxmmrao5uxi2bamjzg623fnyqhg2lhnzqxi5lsmuqhmmi",
            "k51qzi5uqu5dit2ku9mutlfgwyz8u730on38kd10m97m36bjt66my99hb6103f_v2" to "/ipfs/bafkqadtwgiww63tmpeqhezldn5zgi",
        )
        for ((fixture, expected) in fixtures) {
            val name = IpnsName.parse(fixture.substringBefore('_'))
            val wire = checkNotNull(javaClass.getResourceAsStream("/ipns/$fixture.ipns-record")).use { it.readBytes() }
            if (expected == null) assertFailsWith<IllegalArgumentException>(fixture) { IpnsRecord.verify(name, wire, now, crypto) }
            else assertEquals(expected, IpnsRecord.verify(name, wire, now, crypto).value, fixture)
        }
    }

    @Test fun namesCidAndIdentityCopies() {
        val identity = crypto.generate()
        val name = identity.name
        assertEquals(name, IpnsName.parse(name.toString()))
        assertEquals(name, IpnsName.parse(name.toString().uppercase()))
        assertEquals(name, IpnsName.parse(name.peerId()))
        assertContentEquals(identity.publicKey, name.publicKey)
        assertContentEquals(byteArrayOf(0, 36, 8, 1, 18, 32), name.multihash.copyOf(6))
        val original = name.toString()
        name.multihash.fill(0); identity.publicKey.fill(0); identity.privateKey.fill(0)
        assertEquals(original, identity.name.toString())
        assertEquals("bafkqaaa", IpnsCid.parse("bafkqaaa").toString())
        assertContentEquals(byteArrayOf(1, 0x55, 0, 0), IpnsCid.parse("bafkqaaa").bytes)
        val empty = IpnsCid.raw(byteArrayOf())
        assertEquals("bafkreihdwdcefgh4dqkjv67uzcmw7ojee6xedzdetojuzjevtenxquvyku", empty.toString())
        assertEquals(empty, IpnsCid.parse(empty.toString()))
        assertFailsWith<IllegalArgumentException> { IpnsName.parse(empty.toString()) }
        val hashedName = IpnsName(IpnsEncoding.multihash(0x12u, IpnsEncoding.sha256(IpnsEncoding.publicKey(identity.publicKey))))
        val record = IpnsRecord.create(identity, "/ipfs/bafkqaaa", 0u, eol, 0u, crypto)
        assertFailsWith<IllegalArgumentException> { IpnsRecord.verify(hashedName, record.bytes, now, crypto) }
        val withPublicKey = record.bytes + IpnsProtobuf.bytes(7, IpnsEncoding.publicKey(identity.publicKey))
        assertEquals(hashedName, IpnsRecord.verify(hashedName, withPublicKey, now, crypto).name)
    }

    @Test fun signedFieldsTamperNameExpiryAndLegacyBinding() {
        val identity = crypto.generate()
        val record = IpnsRecord.create(identity, "/ipfs/bafkqaaa", 7u, eol, 1_000u, crypto)
        assertEquals(record.value, IpnsRecord.verify(identity.name, record.bytes, now, crypto).value)
        assertFailsWith<IllegalArgumentException> { IpnsRecord.verify(crypto.generate().name, record.bytes, now, crypto) }
        assertFailsWith<IllegalArgumentException> { IpnsRecord.verify(identity.name, record.bytes, eol, crypto) }
        assertEquals(7uL, IpnsRecord.verify(identity.name, record.bytes, eol, crypto, allowExpired = true).sequence)
        assertFailsWith<IllegalArgumentException> { IpnsRecord.verify(identity.name, record.bytes + IpnsProtobuf.bytes(1, "other".encodeToByteArray()), now, crypto) }
        val fields = IpnsProtobuf.decode(record.bytes)
        val data = fields.single { it.number == 9 }.bytes
        val signature = fields.single { it.number == 8 }.bytes
        data[data.lastIndex] = 1
        assertFailsWith<IllegalArgumentException> { IpnsRecord.verify(identity.name, IpnsProtobuf.bytes(8, signature) + IpnsProtobuf.bytes(9, data), now, crypto) }
        val embeddedWrongKey = record.bytes + IpnsProtobuf.bytes(7, IpnsEncoding.publicKey(crypto.generate().publicKey))
        assertFailsWith<IllegalArgumentException> { IpnsRecord.verify(identity.name, embeddedWrongKey, now, crypto) }
        assertFailsWith<IllegalArgumentException> { IpnsRecord.create(IpnsKeyPair(identity.publicKey, crypto.generate().privateKey), record.value, 0u, eol, 1u, crypto) }
    }

    @Test fun unsignedOrderCacheAndDeterministicTies() {
        val identity = crypto.generate()
        val first = IpnsRecord.create(identity, "/ipfs/bafkqaaa", Long.MAX_VALUE.toULong(), eol, 1_000_000_001u, crypto)
        val next = IpnsRecord.create(identity, first.value, Long.MAX_VALUE.toULong() + 1u, eol, ULong.MAX_VALUE, crypto)
        assertTrue(next > first)
        assertEquals(now.epochSeconds + 1, first.cacheUntil(now).epochSeconds)
        assertEquals(1, first.cacheUntil(now).nanosecondsOfSecond)
        assertEquals(eol, next.cacheUntil(now))
        val maximum = IpnsRecord.create(identity, first.value, ULong.MAX_VALUE, eol, 0u, crypto)
        assertEquals(ULong.MAX_VALUE, IpnsRecord.verify(identity.name, maximum.bytes, now, crypto).sequence)
        assertEquals(now, maximum.cacheUntil(now))
        val tie = IpnsRecord.create(identity, "/ipns/example.com", first.sequence, eol, first.ttlNanos, crypto)
        val expected = unsignedCompare(first.bytes, tie.bytes)
        assertEquals(expected.compareTo(0), first.compareTo(tie).compareTo(0))
    }

    @Test fun canonicalCborBoundsAndAuthenticatedExtensions() {
        val identity = crypto.generate()
        val data = IpnsDagCbor.encode("/ipfs/bafkqaaa".encodeToByteArray(), 0u, eol.toString().encodeToByteArray(), 0u)
        assertContentEquals(byteArrayOf(0xa5.toByte(), 0x63, 0x54, 0x54, 0x4c, 0), data.copyOf(6))
        fun signed(bytes: ByteArray): ByteArray = IpnsProtobuf.bytes(8, crypto.sign(identity.privateKey, "ipns-signature:".encodeToByteArray() + bytes)) + IpnsProtobuf.bytes(9, bytes)
        val extended = data.copyOf().also { it[0] = 0xa6.toByte() } + Cbor.encode(Item.Str("_extension_key")) + Cbor.encode(Item.Str("retained but ignored"))
        assertEquals("/ipfs/bafkqaaa", IpnsRecord.verify(identity.name, signed(extended), now, crypto).value)
        val nonminimal = data.copyOfRange(0, 5) + byteArrayOf(0x18, 0) + data.copyOfRange(6, data.size)
        assertFailsWith<IllegalArgumentException> { IpnsRecord.verify(identity.name, signed(nonminimal), now, crypto) }
        assertFailsWith<IllegalArgumentException> { IpnsRecord.verify(identity.name, signed(data + byteArrayOf(0)), now, crypto) }
        val malformedText = data.copyOf().also { it[2] = 0xff.toByte() }
        assertFailsWith<IllegalArgumentException> { IpnsRecord.verify(identity.name, signed(malformedText), now, crypto) }
        val duplicate = data.copyOf().also { it[0] = 0xa6.toByte() } + Cbor.encode(Item.Str("ValidityType")) + byteArrayOf(0)
        assertFailsWith<IllegalArgumentException> { IpnsRecord.verify(identity.name, signed(duplicate), now, crypto) }
        assertFailsWith<IllegalArgumentException> { IpnsRecord.verify(identity.name, ByteArray(IpnsRecord.MAX_BYTES + 1), now, crypto) }
        val signatureOnly = IpnsProtobuf.bytes(8, ByteArray(64))
        assertFailsWith<IllegalArgumentException> { IpnsRecord.verify(identity.name, signatureOnly, now, crypto) }
    }

    @Test fun protobufOverflowTruncationAndDuplicateRecordFields() {
        for (bad in listOf(byteArrayOf(0), byteArrayOf(8, 0x80.toByte()), byteArrayOf(8, 0x80.toByte(), 0), byteArrayOf(18, 127), byteArrayOf(11)))
            assertFailsWith<IllegalArgumentException> { IpnsProtobuf.decode(bad) }
        assertFailsWith<IllegalArgumentException> { IpnsProtobuf.decode(byteArrayOf(8) + ByteArray(9) { 0xff.toByte() } + byteArrayOf(2)) }
        assertEquals(ULong.MAX_VALUE, IpnsProtobuf.decode(IpnsProtobuf.uint(1, ULong.MAX_VALUE)).single().integer)
        val id = crypto.generate(); val r = IpnsRecord.create(id, "/ipfs/bafkqaaa", 0u, eol, 0u, crypto)
        assertFailsWith<IllegalArgumentException> { IpnsRecord.verify(id.name, r.bytes + IpnsProtobuf.bytes(8, ByteArray(64)), now, crypto) }
    }

    @Test fun binaryCidAndEmptyValuesNormalizeAfterVerification() {
        val id = crypto.generate()
        for (value in listOf(byteArrayOf(), IpnsCid.parse("bafkqaaa").bytes)) {
            val data = IpnsDagCbor.encode(value, 0u, eol.toString().encodeToByteArray(), 0u)
            val wire = IpnsProtobuf.bytes(8, crypto.sign(id.privateKey, "ipns-signature:".encodeToByteArray() + data)) + IpnsProtobuf.bytes(9, data)
            assertEquals("/ipfs/bafkqaaa", IpnsRecord.verify(id.name, wire, now, crypto).value)
        }
    }

    private fun unsignedCompare(a: ByteArray, b: ByteArray): Int {
        for (i in 0 until minOf(a.size, b.size)) {
            val value = (a[i].toInt() and 255) - (b[i].toInt() and 255)
            if (value != 0) return value
        }
        return a.size.compareTo(b.size)
    }
}
