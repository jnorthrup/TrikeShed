package borg.trikeshed.ipns

import borg.trikeshed.lib.Series
import borg.trikeshed.lib.j
import borg.trikeshed.lib.size
import borg.trikeshed.lib.toSeries
import borg.trikeshed.lib.view
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlin.math.min
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/** Deterministic protocol/lifecycle tests; these in-memory peers are not public-network evidence. */
class IpnsDhtTest {
    val now = Instant.parse("2026-09-11T00:00:00Z")
    val expiry = Instant.parse("2026-09-12T00:00:00Z")
    val crypto = CodecCrypto()
    val identity = crypto.generate()
    val cid = IpnsCid.raw("fixture".encodeToByteArray())

    fun peer(number: Int): Libp2pPeer {
        val id = IpnsName.fromPublicKey(ByteArray(32) { (it + number).toByte() }).multihash
        val address = IpnsDhtAddresses.encode("/ip4/127.0.0.1/tcp/${10000 + number}")
        return Libp2pPeer(id, 1 j { address })
    }

    fun record(sequence: ULong) = IpnsRecord.create(identity, "/ipfs/$cid", sequence, expiry, 60_000_000_000u, crypto)

    @Test fun protobufWireAndDefaultPutType() {
        val wire = byteArrayOf(8, 1, 18, 3, 97, 98, 99) // Go protobuf: type=GET_VALUE, key="abc".
        val message = IpnsDhtCodec.decode(wire)
        assertEquals(IpnsDhtType.GET_VALUE, message.type)
        assertContentEquals("abc".encodeToByteArray(), message.key)
        assertContentEquals(wire, IpnsDhtCodec.encode(message))
        assertEquals(IpnsDhtType.PUT_VALUE, IpnsDhtCodec.decode(IpnsProtobuf.bytes(2, byteArrayOf(1))).type)
        assertFailsWith<IllegalArgumentException> { IpnsDhtCodec.decode(wire + byteArrayOf(8, 1)) }
        assertFailsWith<IllegalArgumentException> { IpnsDhtCodec.decode(byteArrayOf(8, 127)) }
        assertFailsWith<IllegalArgumentException> { IpnsDhtCodec.validatePeerId(byteArrayOf(0, 0)) }
    }

    @Test fun recordAndCloserPeerRoundTrip() {
        val key = identity.name.routingKey
        val original = IpnsDhtMessage(IpnsDhtType.GET_VALUE, key, IpnsDhtRecord(key, record(1u).bytes), 1 j { peer(3) })
        val decoded = IpnsDhtCodec.decode(IpnsDhtCodec.encode(original))
        assertContentEquals(original.record!!.value, decoded.record!!.value)
        assertEquals(1, decoded.closerPeers.size)
        assertContentEquals(peer(3).id, decoded.closerPeers.b(0).id)
        assertContentEquals(peer(3).addresses.b(0), decoded.closerPeers.b(0).addresses.b(0))
    }

    @Test fun publicMultiTransportPeersDoNotDiscardRecordOrOtherPeers() {
        val key = identity.name.routingKey
        val signed = record(1u)
        val envelope = IpnsDhtCodec.encode(IpnsDhtMessage(IpnsDhtType.GET_VALUE, key, IpnsDhtRecord(key, signed.bytes)))
        val advertisements = mutableListOf<ByteArray>()
        repeat(24) { index ->
            advertisements += IpnsEncoding.varint(4u) + byteArrayOf(8, 8, 8, (index + 1).toByte()) +
                IpnsEncoding.varint(273u) + byteArrayOf(15, 161.toByte()) + IpnsEncoding.varint(461u)
        }
        advertisements += peer(1).addresses.b(0) // TCP remains usable after more than 16 alien addresses.
        fun advertised(id: ByteArray, addresses: List<ByteArray>): ByteArray = IpnsProtobuf.bytes(1, id) +
            IpnsDhtCodec.concatenate(addresses.map { IpnsProtobuf.bytes(2, it) })
        val crowded = advertised(peer(1).id, advertisements)
        val malformed = IpnsProtobuf.bytes(1, byteArrayOf(0, 0))
        val oversized = advertised(peer(2).id, listOf(ByteArray(IpnsDhtCodec.MAX_ADDRESS + 1)))
        val tooMany = advertised(peer(4).id, List(IpnsDhtCodec.MAX_ADDRESSES + 1) { peer(4).addresses.b(0) })
        val other = advertised(peer(3).id, listOf(peer(3).addresses.b(0)))
        val wire = envelope + IpnsDhtCodec.concatenate(listOf(crowded, malformed, oversized, tooMany, other).map { IpnsProtobuf.bytes(8, it) })
        val decoded = IpnsDhtCodec.decode(wire)
        assertEquals(2, decoded.closerPeers.size)
        assertEquals(3, decoded.rejectedPeers)
        assertEquals(25, decoded.closerPeers.b(0).addresses.size)
        val usable = assertNotNull(IpnsDhtAddresses.filter(decoded.closerPeers.b(0), true))
        assertEquals(1, usable.addresses.size)
        assertContentEquals(peer(1).addresses.b(0), usable.addresses.b(0))
        assertContentEquals(peer(3).id, decoded.closerPeers.b(1).id)
        val verified = IpnsRecord.verify(identity.name, decoded.record!!.value, now, crypto)
        assertContentEquals(signed.bytes, verified.bytes)
        // Record/envelope validity is still strict even when peer advertisements are recoverable.
        assertFailsWith<IllegalArgumentException> {
            IpnsDhtCodec.decode(IpnsProtobuf.uint(1, 1u) + IpnsProtobuf.bytes(3, IpnsProtobuf.bytes(1, key)))
        }
        assertFailsWith<IllegalArgumentException> { IpnsDhtCodec.decode(ByteArray(IpnsDhtCodec.MAX_MESSAGE + 1)) }
    }

    @Test fun multiaddrPolicyAndUnsignedDistance() {
        val local = peer(1)
        assertNull(IpnsDhtAddresses.filter(local, false))
        assertNotNull(IpnsDhtAddresses.filter(local, true))
        val seed = IpnsDhtAddresses.bootstrap(1 j { IpnsDhtAddresses.IPFS_BOOTSTRAP_VA1 })
        assertEquals(1, seed.size)
        val parsed = IpnsDhtAddresses.decode(seed.b(0).addresses.b(0))
        assertContentEquals(byteArrayOf(40, 160.toByte(), 9, 115), parsed.ip)
        assertEquals(4001, parsed.port)
        val secondAddress = "/ip6/2604:2dc0:101:100::138f/tcp/4001/p2p/${IpnsEncoding.base58(seed.b(0).id)}"
        val merged = IpnsDhtAddresses.bootstrap(arrayOf(IpnsDhtAddresses.IPFS_BOOTSTRAP_VA1, secondAddress).toSeries())
        assertEquals(1, merged.size)
        assertEquals(2, merged.b(0).addresses.size)
        assertFailsWith<IllegalArgumentException> { IpnsDhtAddresses.decode(local.addresses.b(0) + IpnsEncoding.varint(477u)) }
        assertFailsWith<IllegalArgumentException> { IpnsDhtAddresses.encode("/dnsaddr/bootstrap.libp2p.io/tcp/4001") }
        assertFalse(IpnsDhtAddresses.isPublic(IpnsDhtAddresses.ipv6("::1")))
        assertTrue(IpnsDhtAddresses.isPublic(IpnsDhtAddresses.ipv6("2604:2dc0:101:100::138f")))
        assertTrue(IpnsDht.compareDistance(byteArrayOf(127), byteArrayOf(128.toByte())) < 0)
        assertContentEquals(ByteArray(32), IpnsDht.distance(IpnsEncoding.sha256(local.id), IpnsEncoding.sha256(local.id)))
        // Published IPFS Kademlia keyspace example, independent of this implementation's encoder.
        val example = IpnsEncoding.unbase58("12D3KooWLU2znyJMtDiHArqAGbZn8CgUGp92kxDBtefftEEaHSZS")
        assertEquals("e43d28f0996557c0d5571d75c62a57a59d7ac1d30a51ecedcdb9d5e4afa56100",
            IpnsEncoding.sha256(example).joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') })
    }

    @Test fun iterativeLookupDiscoversAndDrainsBoundedRounds() = runTest {
        val peers = (1..5).map(::peer).toSeries()
        val dialer = CodecDialer { _, request ->
            delay(1)
            IpnsDhtMessage(request.type, closerPeers = peers)
        }
        val dht = IpnsDht(dialer, crypto, 1 j { peer(1) }, limits(replication = 5, parallelism = 2))
        val result = dht.findClosest(identity.name.routingKey)
        assertEquals(5, result.replies.size)
        assertTrue(result.exhausted)
        assertEquals(5, result.closest.size)
        assertEquals(0, dialer.active)
        assertEquals(5, dialer.closed)
        assertTrue(dialer.peak in 1..2)
        val distances = result.closest.view.map { IpnsDht.distance(IpnsEncoding.sha256(it.id), IpnsEncoding.sha256(identity.name.routingKey)) }
        assertTrue(distances.zipWithNext().all { (a, b) -> IpnsDht.compareDistance(a, b) <= 0 })
    }

    @Test fun newestMinorityWinsAndRepairsStalePeers() = runTest {
        val peers = (1..4).map(::peer).toSeries()
        val stale = record(1u); val newest = record(2u)
        val dialer = CodecDialer { peer, request ->
            if (request.type == IpnsDhtType.PUT_VALUE) request
            else IpnsDhtMessage(request.type, request.key,
                IpnsDhtRecord(request.key, if (peer.id.contentEquals(peers.b(3).id)) newest.bytes else stale.bytes))
        }
        val dht = IpnsDht(dialer, crypto, peers, limits(replication = 4, parallelism = 4, quorum = 3))
        val report = dht.resolveReport(identity.name, now)
        assertTrue(report.quorumReached)
        assertEquals(4, report.valid.size) // A whole admitted round is observed, even after quorum.
        assertEquals(2uL, report.selected!!.sequence)
        assertEquals(3, report.repairs.size)
        assertTrue(report.repairs.view.all { it.failure == null })
        assertEquals(0, dialer.active)
    }

    @Test fun queryLimitLeavesExplicitIncompleteLookup() = runTest {
        val target = IpnsEncoding.sha256(identity.name.routingKey)
        val peers = (1..8).map(::peer).sortedWith { a, b -> IpnsDht.compareDistance(
            IpnsDht.distance(IpnsEncoding.sha256(a.id), target), IpnsDht.distance(IpnsEncoding.sha256(b.id), target)) }.toSeries()
        val dialer = CodecDialer { _, request -> IpnsDhtMessage(request.type, closerPeers = peers) }
        val dht = IpnsDht(dialer, crypto, 1 j { peers.b(7) },
            limits(replication = 2).copy(maxQueries = 2, maxCandidates = 8))
        val report = dht.findClosest(identity.name.routingKey)
        assertEquals(2, report.replies.size)
        assertTrue(report.queryLimitReached)
        assertFalse(report.exhausted)
        assertEquals(0, dialer.active)
    }

    @Test fun exactPutAcknowledgementRequired() = runTest {
        val dialer = CodecDialer { _, request ->
            if (request.type == IpnsDhtType.FIND_NODE) IpnsDhtMessage(request.type)
            else request.copy(record = request.record!!.copy(value = byteArrayOf(99)))
        }
        val dht = IpnsDht(dialer, crypto, 1 j { peer(1) }, limits())
        val report = dht.publish(identity.name, record(1u).bytes, now)
        assertEquals(0, report.acknowledgements.size)
        assertFalse(report.complete)
        assertEquals(1, report.failures.size)
        assertTrue(report.failures.b(0).reason.contains("exact key/value"))
        assertEquals(0, dialer.active)
    }

    @Test fun storageRejectionsUseNearestQueriedReplacementsWithoutDuplicatePuts() = runTest {
        val target = IpnsEncoding.sha256(identity.name.routingKey)
        val peers = (1..5).map(::peer).sortedWith { a, b -> IpnsDht.compareDistance(
            IpnsDht.distance(IpnsEncoding.sha256(a.id), target), IpnsDht.distance(IpnsEncoding.sha256(b.id), target)) }.toSeries()
        val signed = record(1u)
        val puts = mutableListOf<String>()
        val dialer = CodecDialer { peer, request ->
            if (request.type == IpnsDhtType.FIND_NODE) IpnsDhtMessage(request.type, closerPeers = peers)
            else {
                puts += IpnsEncoding.base58(peer.id)
                assertContentEquals(signed.bytes, request.record!!.value)
                if (peer.id.contentEquals(peers.b(0).id) || peer.id.contentEquals(peers.b(3).id))
                    error("Routing succeeds but storage is rejected")
                request
            }
        }
        // Both distant bootstrap peers were queried before the two nearest peers were discovered.
        val dht = IpnsDht(dialer, crypto, arrayOf(peers.b(3), peers.b(4)).toSeries(), limits(replication = 2, parallelism = 2))
        val report = dht.publish(identity.name, signed.bytes, now)
        assertTrue(report.complete)
        assertEquals(2, report.replicaTarget)
        assertEquals(2, report.acknowledgements.size)
        assertEquals(1, report.primaryAcknowledgements.size)
        assertEquals(1, report.replacementAcknowledgements.size)
        assertContentEquals(peers.b(4).id, report.replacementAcknowledgements.b(0).id)
        assertEquals(2, report.failures.size)
        assertEquals(4, report.putAttempts)
        assertEquals(puts.size, puts.toSet().size)
        assertEquals(IpnsEncoding.base58(peers.b(3).id), puts[2])
        assertEquals(IpnsEncoding.base58(peers.b(4).id), puts[3])
        assertFalse(puts.contains(IpnsEncoding.base58(peers.b(2).id))) // Never queried, so not an eligible replacement.
        assertEquals(0, dialer.active)
    }

    @Test fun transportFailureIsNotAbsenceAndTimeoutClosesStreams() = runTest {
        val dialer = CodecDialer { _, _ -> delay(100); error("unreachable") }
        val dht = IpnsDht(dialer, crypto, 1 j { peer(1) }, limits().copy(rpcTimeoutMillis = 10))
        val report = dht.resolveReport(identity.name, now)
        assertFalse(report.absent)
        assertEquals(1, report.failures.size)
        assertEquals(0, dialer.active)
        assertFailsWith<IpnsDhtResolutionException> { dht.resolve(identity.name, now) }
        assertEquals(0, dialer.active)
    }

    @Test fun validAbsenceAndInvalidSignatureAreDistinct() = runTest {
        val empty = CodecDialer { _, request -> IpnsDhtMessage(request.type) }
        assertNull(IpnsDht(empty, crypto, 1 j { peer(1) }, limits()).resolve(identity.name, now))
        val damaged = record(1u).bytes.also { it[5] = (it[5].toInt() xor 1).toByte() }
        val invalid = CodecDialer { _, request -> IpnsDhtMessage(request.type, request.key, IpnsDhtRecord(request.key, damaged)) }
        val report = IpnsDht(invalid, crypto, 1 j { peer(1) }, limits()).resolveReport(identity.name, now)
        assertFalse(report.absent)
        assertEquals(0, report.valid.size)
        assertEquals(1, report.failures.size)
    }

    fun limits(replication: Int = 1, parallelism: Int = 1, quorum: Int = 1) = IpnsDhtLimits(
        replication = replication, parallelism = parallelism, minValidResponses = quorum,
        allowPrivateAddresses = true, rpcTimeoutMillis = 1000, operationTimeoutMillis = 10_000)

    /** Test-only signature oracle; cryptographic interoperability is covered by JvmIpnsCrypto/Go tests. */
    class CodecCrypto : IpnsCrypto {
        override fun generate() = ByteArray(32) { (it + 37).toByte() }.let { IpnsKeyPair(it, it) }
        override fun sign(privateKey: ByteArray, payload: ByteArray): ByteArray = IpnsEncoding.sha256(privateKey + payload).let { it + it }
        override fun verify(publicKey: ByteArray, payload: ByteArray, signature: ByteArray) = sign(publicKey, payload).contentEquals(signature)
    }

    class CodecDialer(val reply: suspend (Libp2pPeer, IpnsDhtMessage) -> IpnsDhtMessage) : Libp2pDialer {
        var active = 0
        var peak = 0
        var closed = 0
        override suspend fun open(peer: Libp2pPeer, protocol: String): Libp2pStream {
            assertEquals(IpnsDhtCodec.PROTOCOL, protocol)
            active++; peak = maxOf(peak, active)
            return object : Libp2pStream {
                var bytes = byteArrayOf()
                var at = 0
                var isClosed = false
                override suspend fun write(bytes: ByteArray) {
                    val reader = IpnsVarint(bytes)
                    val count = reader.read().toInt()
                    val request = IpnsDhtCodec.decode(reader.take(count))
                    assertEquals(reader.position, bytes.size)
                    this.bytes = IpnsDhtCodec.frame(reply(peer, request))
                }
                override suspend fun read(maxBytes: Int): ByteArray {
                    val count = min(min(maxBytes, 3), bytes.size - at)
                    return bytes.copyOfRange(at, at + count).also { at += count }
                }
                override suspend fun close() { if (!isClosed) { isClosed = true; active--; closed++ } }
            }
        }
    }
}
