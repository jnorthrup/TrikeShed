package borg.trikeshed.ipns

import borg.trikeshed.lib.Series
import borg.trikeshed.lib.j
import borg.trikeshed.lib.size
import borg.trikeshed.lib.toSeries
import borg.trikeshed.lib.view

data class IpnsDhtTcpAddress(val ip: ByteArray, val port: Int, val peerId: ByteArray?)

/** Only direct numeric TCP multiaddrs accepted by the current uring libp2p transport. */
object IpnsDhtAddresses {
    /** Explicit opt-in seed from go-libp2p-kad-dht/dht_bootstrap.go; reachability is not assumed. */
    const val IPFS_BOOTSTRAP_TCP = "/ip4/104.131.131.82/tcp/4001/p2p/QmaCpDMGvV2BGHeYERUEnRQAwe3N8SzbUtfsmvsqQLuvuJ"
    /** bootstrap.libp2p.io / va1 DNS snapshot supplied explicitly on 2026-09-11; callers may override it. */
    const val IPFS_BOOTSTRAP_VA1 = "/ip4/40.160.9.115/tcp/4001/p2p/12D3KooWKnDdG3iXw9eTFijk3EWSunZcFi54Zka4wmtqtt6rPxc8"

    fun decode(address: ByteArray): IpnsDhtTcpAddress {
        require(address.size in 1..IpnsDhtCodec.MAX_ADDRESS)
        val reader = IpnsVarint(address)
        val ip = when (reader.read()) {
            4uL -> reader.take(4)
            41uL -> reader.take(16)
            else -> throw IllegalArgumentException("Only numeric ip4/ip6 TCP addresses are supported; resolve DNS through an explicit facade resolver")
        }
        require(reader.read() == 6uL) { "Expected TCP multiaddr" }
        val portBytes = reader.take(2)
        val port = ((portBytes[0].toInt() and 255) shl 8) or (portBytes[1].toInt() and 255)
        require(port > 0) { "TCP port zero" }
        val peerId = if (reader.position < address.size) {
            require(reader.read() == 421uL) { "Unsupported multiaddr transport suffix" }
            val length = reader.read()
            require(length in 2u..128u)
            reader.take(length.toInt()).also(IpnsDhtCodec::validatePeerId)
        } else null
        require(reader.position == address.size) { "Unsupported multiaddr suffix" }
        return IpnsDhtTcpAddress(ip, port, peerId)
    }

    fun filter(peer: Libp2pPeer, allowPrivate: Boolean): Libp2pPeer? {
        IpnsDhtCodec.validatePeerId(peer.id)
        val accepted = mutableListOf<ByteArray>()
        for (address in peer.addresses.view) {
            if (accepted.size == IpnsDhtCodec.MAX_ADDRESSES) break
            val parsed = try { decode(address) } catch (_: IllegalArgumentException) { continue }
            if (parsed.peerId != null && !parsed.peerId.contentEquals(peer.id)) continue
            if (!allowPrivate && !isPublic(parsed.ip)) continue
            if (accepted.none { it.contentEquals(address) }) accepted += address.copyOf()
        }
        return if (accepted.isEmpty()) null else Libp2pPeer(peer.id.copyOf(), accepted.toSeries())
    }

    fun bootstrap(addresses: Series<String>, allowPrivate: Boolean = false): Series<Libp2pPeer> {
        val peers = mutableListOf<Libp2pPeer>()
        for (text in addresses.view) {
            val address = encode(text)
            val id = decode(address).peerId ?: throw IllegalArgumentException("Bootstrap requires an authenticated /p2p peer ID")
            val peer = filter(Libp2pPeer(id, 1 j { address }), allowPrivate)
                ?: throw IllegalArgumentException("Bootstrap address is not permitted: $text")
            val index = peers.indexOfFirst { it.id.contentEquals(id) }
            if (index < 0) peers += peer else peers[index] = merge(peers[index], peer, allowPrivate)
        }
        require(peers.isNotEmpty()) { "Explicit bootstrap peers required" }
        return peers.toSeries()
    }

    internal fun merge(left: Libp2pPeer, right: Libp2pPeer, allowPrivate: Boolean): Libp2pPeer {
        require(left.id.contentEquals(right.id))
        val addresses = (left.addresses.size + right.addresses.size) j { index: Int ->
            if (index < left.addresses.size) left.addresses.b(index) else right.addresses.b(index - left.addresses.size)
        }
        return requireNotNull(filter(Libp2pPeer(left.id, addresses), allowPrivate))
    }

    fun encode(text: String): ByteArray {
        require(text.length in 1..2048)
        val parts = text.split('/')
        require(parts.size == 5 || parts.size == 7)
        require(parts[0].isEmpty() && parts[3] == "tcp")
        val ip = when (parts[1]) {
            "ip4" -> ipv4(parts[2])
            "ip6" -> ipv6(parts[2])
            else -> throw IllegalArgumentException("DNS/DNSADDR bootstrap expansion is not implemented; supply explicit numeric TCP multiaddrs")
        }
        val port = parts[4].toIntOrNull() ?: throw IllegalArgumentException("Invalid TCP port")
        require(port in 1..65535)
        var bytes = IpnsEncoding.varint(if (ip.size == 4) 4u else 41u) + ip + byteArrayOf(6, (port ushr 8).toByte(), port.toByte())
        if (parts.size == 7) {
            require(parts[5] == "p2p")
            val id = IpnsName.parse(parts[6]).multihash
            bytes += IpnsEncoding.varint(421u) + IpnsEncoding.varint(id.size.toULong()) + id
        }
        decode(bytes)
        return bytes
    }

    fun isPublic(ip: ByteArray): Boolean {
        fun octet(index: Int) = ip[index].toInt() and 255
        if (ip.size == 4) {
            val a = octet(0); val b = octet(1); val c = octet(2)
            return !(a == 0 || a == 10 || a == 127 || a >= 224 ||
                a == 100 && b in 64..127 || a == 169 && b == 254 || a == 172 && b in 16..31 ||
                a == 192 && (b == 168 || b == 0 && c in 0..2) ||
                a == 198 && (b in 18..19 || b == 51 && c == 100) || a == 203 && b == 0 && c == 113)
        }
        require(ip.size == 16)
        if (ip.take(10).all { it == 0.toByte() } && octet(10) == 255 && octet(11) == 255)
            return isPublic(ip.copyOfRange(12, 16))
        // Global unicast only; reject documentation prefix as well.
        return octet(0) and 0xe0 == 0x20 && !(octet(0) == 0x20 && octet(1) == 1 && octet(2) == 0x0d && octet(3) == 0xb8)
    }

    internal fun ipv4(text: String): ByteArray {
        val parts = text.split('.')
        require(parts.size == 4)
        return ByteArray(4) { i ->
            val part = parts[i]
            require(part.isNotEmpty() && part.all { it in '0'..'9' } && (part.length == 1 || part[0] != '0'))
            val number = part.toIntOrNull() ?: throw IllegalArgumentException("Invalid IPv4 octet")
            require(number in 0..255); number.toByte()
        }
    }

    internal fun ipv6(text: String): ByteArray {
        require('%' !in text && '.' !in text) { "IPv6 zone/embedded IPv4 text is unsupported" }
        val halves = text.split("::")
        require(halves.size <= 2)
        fun words(part: String) = if (part.isEmpty()) emptyList() else part.split(':').map {
            require(it.length in 1..4 && it.all { c -> c in '0'..'9' || c.lowercaseChar() in 'a'..'f' })
            it.toInt(16)
        }
        val left = words(halves[0]); val right = if (halves.size == 2) words(halves[1]) else emptyList()
        val zeroes = 8 - left.size - right.size
        require(if (halves.size == 1) zeroes == 0 else zeroes >= 1)
        val values = left + List(zeroes) { 0 } + right
        return ByteArray(16) { index -> (values[index / 2] ushr if (index % 2 == 0) 8 else 0).toByte() }
    }
}
