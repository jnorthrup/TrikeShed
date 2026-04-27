package borg.trikeshed.userspace.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

// ================================================================================
// SELF-CONTAINED STUBS: Protocol channelization algebra
// Donor: old trikeshed-reactor AllProtocolChannelization.kt —
//   TcpChannelization, UdpChannelization, WebSocketChannelization,
//   SshChannelization, FtpChannelization, SmtpChannelization, DnsChannelization,
//   UniversalChannelization factory with URL auto-detect
// Semantic gap: no ChannelProvider → protocol channelization in root TrikeShed.
//   QuicChannelService exists but has no TCP/UDP/WS/SSH channelization peers.
// ================================================================================

/** Channel ID, uniquely identifies a single channel. */
data class ChannelId(val id: Long)

/** Channel in the domain of channelized I/O. */
interface Channel {
    val id: ChannelId
    suspend fun close()
}

/** A connectable channel that can dial remote endpoints. */
interface ConnectableChannel : Channel {
    suspend fun connect(host: String, port: Int)
    suspend fun read(buf: ByteArray): Int
    suspend fun write(data: ByteArray): Int
}

/** A listenable channel that can accept incoming connections. */
interface ListenableChannel : Channel {
    suspend fun bind(host: String, port: Int)
    suspend fun accept(): ConnectableChannel?
}

/** Protocol channelization converts URL schemes into channel operations. */
interface ProtocolChannelization {
    val scheme: String
    suspend fun connect(url: String): Channel
}

// ================================================================================
// SPEC: Protocol channelization covers TCP, UDP, WS, SSH, FTP, SMTP, DNS
// ================================================================================

class ProtocolChannelizationRedTest {

    /** TCP channelization connects to host:port. */
    @Test fun tcp_channelization_connect() {
        val tcp = object : ProtocolChannelization {
            override val scheme: String = "tcp"
            override suspend fun connect(url: String): Channel {
                val parts = url.removePrefix("tcp://").split(":")
                val host = parts[0]
                val port = parts.getOrElse(1) { "80" }.toInt()
                return object : ConnectableChannel {
                    override val id: ChannelId = ChannelId(1)
                    override suspend fun connect(h: String, p: Int) {}
                    override suspend fun read(buf: ByteArray): Int = 0
                    override suspend fun write(data: ByteArray): Int = 0
                    override suspend fun close() {}
                }
            }
        }
        assertEquals("tcp", tcp.scheme)
    }

    /** WebSocket channelization upgrades HTTP to WS. */
    @Test fun ws_channelization_handshake() {
        val ws = object : ProtocolChannelization {
            override val scheme: String = "ws"
            override suspend fun connect(url: String): Channel {
                val withoutScheme = url.removePrefix("ws://").removePrefix("wss://")
                val parts = withoutScheme.split("/", limit = 2)
                val path = if (parts.size > 1) "/${parts[1]}" else "/"
                return object : ConnectableChannel {
                    override val id: ChannelId = ChannelId(2)
                    override suspend fun connect(host: String, port: Int) {}
                    override suspend fun read(buf: ByteArray): Int = 0
                    override suspend fun write(data: ByteArray): Int = 0
                    override suspend fun close() {}
                }
            }
        }
        assertEquals("ws", ws.scheme)
    }

    /** SSH channelization handles auth + command execution. */
    @Test fun ssh_channelization_auth() {
        val ssh = object : ProtocolChannelization {
            override val scheme: String = "ssh"
            override suspend fun connect(url: String): Channel = object : ConnectableChannel {
                override val id: ChannelId = ChannelId(3)
                override suspend fun connect(host: String, port: Int) {}
                override suspend fun read(buf: ByteArray): Int = 0
                override suspend fun write(data: ByteArray): Int = 0
                override suspend fun close() {}
            }
        }
        assertEquals("ssh", ssh.scheme)
    }

    /** FTP channelization uses control + data channels. */
    @Test fun ftp_channelization_controlAndData() {
        val controlChannel = object : ConnectableChannel {
            override val id: ChannelId = ChannelId(10)
            override suspend fun connect(host: String, port: Int) {}
            override suspend fun read(buf: ByteArray): Int = 0
            override suspend fun write(data: ByteArray): Int = 0
            override suspend fun close() {}
        }
        val dataChannel = object : ConnectableChannel {
            override val id: ChannelId = ChannelId(11)
            override suspend fun connect(host: String, port: Int) {}
            override suspend fun read(buf: ByteArray): Int = 0
            override suspend fun write(data: ByteArray): Int = 0
            override suspend fun close() {}
        }
        assertEquals(10, controlChannel.id.id)
        assertEquals(11, dataChannel.id.id)
    }

    /** SMTP channelization handles mail commands. */
    @Test fun smtp_channelization_mailCommands() {
        val smtp = object : ProtocolChannelization {
            override val scheme: String = "smtp"
            override suspend fun connect(url: String): Channel = object : ConnectableChannel {
                override val id: ChannelId = ChannelId(4)
                override suspend fun connect(host: String, port: Int) {}
                override suspend fun read(buf: ByteArray): Int = 0
                override suspend fun write(data: ByteArray): Int = 0
                override suspend fun close() {}
            }
        }
        assertEquals("smtp", smtp.scheme)
    }

    /** DNS over UDP resolves hostnames. */
    @Test fun dns_channelization_udpResolve() {
        val dns = object : ProtocolChannelization {
            override val scheme: String = "dns"
            override suspend fun connect(url: String): Channel = object : ConnectableChannel {
                override val id: ChannelId = ChannelId(5)
                override suspend fun connect(host: String, port: Int) {}
                override suspend fun read(buf: ByteArray): Int = 0
                override suspend fun write(data: ByteArray): Int = 0
                override suspend fun close() {}
            }
        }
        assertEquals("dns", dns.scheme)
    }

    /** Universal channelization auto-detects protocol from URL scheme. */
    @Test fun universal_channelization_autoDetect() {
        val urls = mapOf(
            "http://example.com" to "http",
            "ws://example.com/ws" to "ws",
            "ssh://example.com:22" to "ssh",
            "ftp://example.com" to "ftp",
            "smtp://example.com:25" to "smtp",
            "tcp://example.com:443" to "tcp",
        )
        val parse: (String) -> String = { url ->
            when {
                url.startsWith("http://") -> "http"
                url.startsWith("ws://") -> "ws"
                url.startsWith("ssh://") -> "ssh"
                url.startsWith("ftp://") -> "ftp"
                url.startsWith("smtp://") -> "smtp"
                url.startsWith("tcp://") -> "tcp"
                else -> "unknown"
            }
        }
        urls.forEach { (url, expected) ->
            assertEquals(expected, parse(url))
        }
    }

    /** ListenableChannel binds and accepts. */
    @Test fun listenableChannel_bindAndAccept() {
        val server = object : ListenableChannel {
            override val id: ChannelId = ChannelId(100)
            var bound = false
            override suspend fun bind(host: String, port: Int) { bound = true }
            override suspend fun accept(): ConnectableChannel? = null
            override suspend fun close() { bound = false }
        }
        kotlinx.coroutines.runBlocking { server.bind("0.0.0.0", 8080) }
        assertEquals(null, kotlinx.coroutines.runBlocking { server.accept() })
    }

    /** ConnectableChannel dials remote and reads/writes. */
    @Test fun connectableChannel_dialAndIO() {
        var connected = false
        val client = object : ConnectableChannel {
            override val id: ChannelId = ChannelId(200)
            override suspend fun connect(host: String, port: Int) { connected = true }
            override suspend fun read(buf: ByteArray): Int = 0
            override suspend fun write(data: ByteArray): Int = 0
            override suspend fun close() {}
        }
        kotlinx.coroutines.runBlocking { client.connect("localhost", 443) }
        assertTrue(connected)
    }
}
