package borg.trikeshed.reactor

import borg.trikeshed.lib.*
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyStore
import java.util.Base64
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLEngineResult
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSession
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Record sessions from one shared SSLContext per identity: handshake, data both ways and close_notify over whole
 * and split records, the partial-record retention between calls, and explicit context reload.
 */
class JvmTlsRecordSessionTest {
    private class Wire : TlsRecordSink {
        val plain = ByteArrayOutputStream()
        val cipher = ByteArrayOutputStream()
        override fun plaintext(bytes: ByteArray, from: Int, to: Int) = plain.write(bytes, from, to - from)
        override fun ciphertext(bytes: ByteArray, from: Int, to: Int) = cipher.write(bytes, from, to - from)
        fun takeCipher(): ByteArray = cipher.toByteArray().also { cipher.reset() }
        fun takePlain(): ByteArray = plain.toByteArray().also { plain.reset() }
    }

    /** Hands [bytes] to [session] in [chunk]-byte pieces; the result of the last piece. */
    private fun feed(session: TlsRecordSession, bytes: ByteArray, wire: Wire, chunk: Int): Boolean {
        var at = 0
        var open = true
        while (at < bytes.size && open) {
            val end = minOf(bytes.size, at + chunk)
            open = session.receive(bytes, at, end, wire)
            at = end
        }
        return open
    }

    private fun handshake(client: TlsRecordSession, server: TlsRecordSession, c: Wire, s: Wire, chunk: Int) {
        client.begin(c)
        server.begin(s)
        repeat(16) {
            val toServer = c.takeCipher()
            val toClient = s.takeCipher()
            if (toServer.isEmpty() && toClient.isEmpty()) return
            assertTrue(feed(server, toServer, s, chunk))
            assertTrue(feed(client, toClient, c, chunk))
        }
        error("handshake did not settle")
    }

    @Test fun handshakeDataAndCloseOverWholeAndSplitRecords() {
        val directory = Files.createTempDirectory("tls-record-")
        try {
            val (cert, key) = writeIdentity(directory, "a")
            val backend = JvmTlsCodecBackend()
            val serverConfig = TlsConfig(certificateFile = cert.toString(), privateKeyFile = key.toString(), hostnameVerification = false)
            val clientConfig = TlsConfig(trustStore = cert.toString(), hostnameVerification = false)
            for (chunk in intArrayOf(Int.MAX_VALUE, 1, 7, 1000)) {
                val server = backend.recordSession(serverConfig, TlsRole.SERVER j ("" j 0))
                val client = backend.recordSession(clientConfig, TlsRole.CLIENT j ("" j 443))
                val c = Wire()
                val s = Wire()
                handshake(client, server, c, s, chunk)
                assertTrue(client.established && server.established, "chunk $chunk")
                assertEquals(TlsProtocol.TLS13, assertNotNull(server.session).protocol)
                assertEquals(0, server.pendingCiphertext)
                val request = "GET /feed HTTP/1.1\r\nHost: x\r\n\r\n".encodeToByteArray()
                assertEquals(TlsSendOutcome.ACCEPTED, client.send(request, 0, request.size, c))
                val sent = c.takeCipher()
                if (chunk < sent.size) {
                    // Half a record waits in the session, copied to its exact length, until the rest arrives.
                    assertTrue(server.receive(sent, 0, sent.size / 2, s))
                    assertEquals(sent.size / 2, server.pendingCiphertext)
                    assertEquals(0, s.plain.size())
                    assertTrue(feed(server, sent.copyOfRange(sent.size / 2, sent.size), s, chunk))
                } else assertTrue(feed(server, sent, s, chunk))
                assertEquals(0, server.pendingCiphertext)
                assertContentEquals(request, s.takePlain())
                // 40,000 bytes is three records.
                val response = ByteArray(40_000) { (it % 251).toByte() }
                assertEquals(TlsSendOutcome.ACCEPTED, server.send(response, 0, response.size, s))
                assertTrue(feed(client, s.takeCipher(), c, chunk))
                assertContentEquals(response, c.takePlain())
                client.close(c)
                assertTrue(client.closed)
                assertEquals(TlsSendOutcome.CLOSED, client.send(request, 0, request.size, c))
                assertFalse(feed(server, c.takeCipher(), s, chunk), "server sees close_notify, chunk $chunk")
                assertTrue(server.closed)
                assertTrue(s.takeCipher().isNotEmpty(), "server answers close_notify")
            }
            // One context per identity, whatever the number of connections.
            assertEquals(2L, backend.contexts.builds)
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test fun reloadRotatesNewEnginesAndAFailedReloadChangesNothing() {
        val directory = Files.createTempDirectory("tls-reload-")
        try {
            val (cert, key) = writeIdentity(directory, "a")
            val backend = JvmTlsCodecBackend()
            val serverConfig = TlsConfig(certificateFile = cert.toString(), privateKeyFile = key.toString(), hostnameVerification = false)
            val clientConfig = TlsConfig(trustStore = cert.toString(), hostnameVerification = false)
            fun connect(): Pair<TlsRecordSession, TlsRecordSession> {
                val server = backend.recordSession(serverConfig, TlsRole.SERVER j ("" j 0))
                val client = backend.recordSession(clientConfig, TlsRole.CLIENT j ("" j 443))
                handshake(client, server, Wire(), Wire(), Int.MAX_VALUE)
                assertTrue(client.established && server.established)
                return client to server
            }
            val before = connect()
            assertEquals(2L, backend.contexts.builds)
            // Rotate both files in place: the reload re-reads them; the open sessions are untouched.
            val (cert2, key2) = writeIdentity(directory, "b")
            Files.copy(cert2, cert, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            Files.copy(key2, key, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            backend.contexts.reload()
            assertEquals(4L, backend.contexts.builds)
            assertTrue(before.first.established && !before.first.closed)
            connect()
            // A reload that cannot read its files throws and keeps the contexts in use.
            Files.delete(key)
            assertFailsWith<java.nio.file.NoSuchFileException> { backend.contexts.reload() }
            connect()
            assertEquals(4L, backend.contexts.builds)
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    /** The suspend codec's engines come from the same shared contexts: three connections, two identities built. */
    @Test fun suspendCodecEnginesShareTheContexts() = kotlinx.coroutines.runBlocking {
        val directory = Files.createTempDirectory("tls-codec-")
        try {
            val (cert, key) = writeIdentity(directory, "a")
            val backend = JvmTlsCodecBackend()
            val serverConfig = TlsConfig(certificateFile = cert.toString(), privateKeyFile = key.toString(), hostnameVerification = false)
            val clientConfig = TlsConfig(trustStore = cert.toString(), hostnameVerification = false)
            for (n in 0 until 3) {
                var client = backend.handshake(clientConfig, TlsFlowState(TlsRole.CLIENT j ("localhost" j 443), 2L * n + 1)).state
                var server = backend.handshake(serverConfig, TlsFlowState(TlsRole.SERVER j ("0.0.0.0" j 443), 2L * n + 2)).state
                var toServer = client.pendingUpstreamCiphertext.toArray()
                var toClient = server.pendingUpstreamCiphertext.toArray()
                for (round in 0 until 16) {
                    if (toServer.isEmpty() && toClient.isEmpty()) break
                    server = backend.downstream(serverConfig, server, ByteSeries(toServer)).state
                    client = backend.downstream(clientConfig, client, ByteSeries(toClient)).state
                    toServer = client.pendingUpstreamCiphertext.toArray()
                    toClient = server.pendingUpstreamCiphertext.toArray()
                }
                assertEquals(TlsConnectionState.OPEN, client.lifecycle)
                assertEquals(TlsConnectionState.OPEN, server.lifecycle)
                val message = "ping $n".encodeToByteArray()
                client = backend.upstream(clientConfig, client, ByteSeries(message)).state
                server = backend.downstream(serverConfig, server, client.pendingUpstreamCiphertext).state
                assertContentEquals(message, server.pendingDownstreamPlaintext.toArray())
                backend.close(clientConfig, client)
                backend.close(serverConfig, server)
            }
            assertEquals(2L, backend.contexts.builds)
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    /** A TLS 1.2 pair from [backend], established over whole records: (client, server, client wire, server wire). */
    private fun tls12Pair(backend: JvmTlsCodecBackend, cert: Path, key: Path): List<Any> {
        val tls12 = listOf(TlsProtocol.TLS12).toSeries()
        val server = backend.recordSession(TlsConfig(certificateFile = cert.toString(), privateKeyFile = key.toString(),
            hostnameVerification = false, protocols = tls12), TlsRole.SERVER j ("" j 0)) as JvmTlsRecordSession
        val client = backend.recordSession(TlsConfig(trustStore = cert.toString(), hostnameVerification = false, protocols = tls12),
            TlsRole.CLIENT j ("" j 443)) as JvmTlsRecordSession
        val c = Wire()
        val s = Wire()
        handshake(client, server, c, s, Int.MAX_VALUE)
        assertTrue(client.established && server.established)
        assertEquals(TlsProtocol.TLS12, assertNotNull(server.session).protocol)
        return listOf(client, server, c, s)
    }

    /**
     * Takes an established TLS 1.2 pair into the middle of a server-initiated renegotiation: HelloRequest out, the
     * client's ClientHello in, the server's flight out, and the server engine waiting for the client's reply.
     */
    private fun midRenegotiation(client: JvmTlsRecordSession, server: JvmTlsRecordSession, c: Wire, s: Wire) {
        server.engine.beginHandshake()
        assertTrue(server.receive(ByteArray(0), 0, 0, s)) // flushes what the handshake owes the peer: HelloRequest
        val hello = s.takeCipher()
        assertTrue(hello.isNotEmpty(), "HelloRequest")
        assertTrue(client.receive(hello, 0, hello.size, c))
        val clientHello = c.takeCipher()
        assertTrue(clientHello.isNotEmpty(), "the client answers with a ClientHello")
        assertTrue(server.receive(clientHello, 0, clientHello.size, s))
        assertEquals(javax.net.ssl.SSLEngineResult.HandshakeStatus.NEED_UNWRAP, server.engine.handshakeStatus)
    }

    /** Moves ciphertext both ways until neither side has any; the client's last receive result. */
    private fun shuttle(client: TlsRecordSession, server: TlsRecordSession, c: Wire, s: Wire): Boolean {
        var clientOpen = true
        for (round in 0 until 32) {
            val toClient = s.takeCipher()
            val toServer = c.takeCipher()
            if (toClient.isEmpty() && toServer.isEmpty()) return clientOpen
            if (toClient.isNotEmpty() && clientOpen) clientOpen = client.receive(toClient, 0, toClient.size, c)
            if (toServer.isNotEmpty() && !server.closed) server.receive(toServer, 0, toServer.size, s)
        }
        error("records still moving after 32 rounds")
    }

    @Test fun dataSentDuringARealRenegotiationArrivesInOrderAndCloseFollows() {
        val directory = Files.createTempDirectory("tls-reneg-")
        try {
            val (cert, key) = writeIdentity(directory, "a")
            val (client, server, c, s) = tls12Pair(JvmTlsCodecBackend(), cert, key)
            client as JvmTlsRecordSession; server as JvmTlsRecordSession; c as Wire; s as Wire
            val first = server.session
            assertSame(first, server.session, "the negotiated metadata is derived once per SSLSession")
            midRenegotiation(client, server, c, s)
            // JSSE still takes application data here (the current keys protect it); whatever the engine does, the
            // bytes must arrive whole and in order once the handshake finishes.
            val message = ByteArray(5000) { (it % 199).toByte() }
            assertEquals(TlsSendOutcome.ACCEPTED, server.send(message, 0, message.size, s))
            val more = "and then this".encodeToByteArray()
            assertEquals(TlsSendOutcome.ACCEPTED, server.send(more, 0, more.size, s))
            assertTrue(shuttle(client, server, c, s))
            assertEquals(javax.net.ssl.SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING, server.engine.handshakeStatus)
            assertEquals(0, server.pendingPlaintext)
            assertContentEquals(message + more, c.takePlain())
            assertEquals(TlsProtocol.TLS12, assertNotNull(server.session).protocol)
            server.close(s)
            assertTrue(server.closed)
            assertFalse(shuttle(client, server, c, s), "the client saw close_notify")
            assertTrue(client.closed)
            assertNull(server.failure)
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    /**
     * Delegates to a real engine. While [stall] is set it reports NEED_UNWRAP and answers every wrap with the result
     * an engine gives while it waits for the peer's handshake data: OK, nothing consumed, nothing produced.
     */
    private class StallingEngine(val real: SSLEngine) : SSLEngine(real.peerHost, real.peerPort) {
        var stall = false
        var wraps = 0
        var stalledWraps = 0

        override fun wrap(srcs: Array<out ByteBuffer>, offset: Int, length: Int, dst: ByteBuffer): SSLEngineResult {
            wraps++
            if (!stall) return real.wrap(srcs, offset, length, dst)
            stalledWraps++
            check(stalledWraps < 10_000) { "wrap asked again and again without progress" }
            return SSLEngineResult(SSLEngineResult.Status.OK, SSLEngineResult.HandshakeStatus.NEED_UNWRAP, 0, 0)
        }

        override fun unwrap(src: ByteBuffer, dsts: Array<out ByteBuffer>, offset: Int, length: Int): SSLEngineResult =
            real.unwrap(src, dsts, offset, length)
        override fun getHandshakeStatus(): SSLEngineResult.HandshakeStatus =
            if (stall) SSLEngineResult.HandshakeStatus.NEED_UNWRAP else real.handshakeStatus
        override fun getDelegatedTask(): Runnable? = real.delegatedTask
        override fun closeInbound() = real.closeInbound()
        override fun isInboundDone(): Boolean = real.isInboundDone
        override fun closeOutbound() = real.closeOutbound()
        override fun isOutboundDone(): Boolean = real.isOutboundDone
        override fun getSupportedCipherSuites(): Array<String> = real.supportedCipherSuites
        override fun getEnabledCipherSuites(): Array<String> = real.enabledCipherSuites
        override fun setEnabledCipherSuites(suites: Array<String>) { real.enabledCipherSuites = suites }
        override fun getSupportedProtocols(): Array<String> = real.supportedProtocols
        override fun getEnabledProtocols(): Array<String> = real.enabledProtocols
        override fun setEnabledProtocols(protocols: Array<String>) { real.enabledProtocols = protocols }
        override fun getSession(): SSLSession = real.session
        override fun getHandshakeSession(): SSLSession? = real.handshakeSession
        override fun beginHandshake() = real.beginHandshake()
        override fun setUseClientMode(mode: Boolean) { real.useClientMode = mode }
        override fun getUseClientMode(): Boolean = real.useClientMode
        override fun setNeedClientAuth(need: Boolean) { real.needClientAuth = need }
        override fun getNeedClientAuth(): Boolean = real.needClientAuth
        override fun setWantClientAuth(want: Boolean) { real.wantClientAuth = want }
        override fun getWantClientAuth(): Boolean = real.wantClientAuth
        override fun setEnableSessionCreation(flag: Boolean) { real.enableSessionCreation = flag }
        override fun getEnableSessionCreation(): Boolean = real.enableSessionCreation
        override fun getApplicationProtocol(): String? = real.applicationProtocol
        override fun getHandshakeApplicationProtocol(): String? = real.handshakeApplicationProtocol
        override fun getSSLParameters(): SSLParameters = real.sslParameters
        override fun setSSLParameters(params: SSLParameters) { real.sslParameters = params }
    }

    /** An established pair over real engines, the server's behind a [StallingEngine]: (client, server, engine, c, s). */
    private fun stallingPair(directory: Path, limit: Int): List<Any> {
        val (cert, key) = writeIdentity(directory, "a")
        val backend = JvmTlsCodecBackend()
        val real = (backend.recordSession(TlsConfig(certificateFile = cert.toString(), privateKeyFile = key.toString(),
            hostnameVerification = false), TlsRole.SERVER j ("" j 0)) as JvmTlsRecordSession).engine
        val engine = StallingEngine(real)
        val server = JvmTlsRecordSession(engine, pendingLimit = limit)
        val client = backend.recordSession(TlsConfig(trustStore = cert.toString(), hostnameVerification = false),
            TlsRole.CLIENT j ("" j 443)) as JvmTlsRecordSession
        val c = Wire()
        val s = Wire()
        handshake(client, server, c, s, Int.MAX_VALUE)
        assertTrue(client.established && server.established)
        return listOf(client, server, engine, c, s)
    }

    @Test fun aSendTheEngineCannotTakeIsHeldNotSpunBoundedAndDrainsInOrder() {
        val directory = Files.createTempDirectory("tls-stall-")
        try {
            val (client, server, engine, c, s) = stallingPair(directory, 16 * 1024)
            client as JvmTlsRecordSession; server as JvmTlsRecordSession; engine as StallingEngine; c as Wire; s as Wire
            engine.stall = true
            val chunks = (0 until 5).map { i -> ByteArray(4096) { (i * 31 + it).toByte() } }
            for (i in 0 until 4) {
                val before = engine.wraps
                assertEquals(TlsSendOutcome.ACCEPTED, server.send(chunks[i], 0, 4096, s), "send $i while input is withheld")
                assertTrue(engine.wraps - before <= 1, "one no-progress wrap per send, not a spin")
                assertEquals(4096 * (i + 1), server.pendingPlaintext)
            }
            assertTrue(engine.stalledWraps > 0, "the engine answered OK with nothing consumed and nothing produced")
            assertEquals(0, s.takeCipher().size, "nothing reached the wire while the engine waited")
            // A fifth would pass the 16 KiB limit: refused whole, before any of it is taken.
            assertEquals(TlsSendOutcome.BACKPRESSURE, server.send(chunks[4], 0, 4096, s))
            assertEquals(16 * 1024, server.pendingPlaintext)
            assertEquals(TlsSendOutcome.BACKPRESSURE, server.send(ByteArray(16 * 1024 + 1), 0, 16 * 1024 + 1, s))
            // The peer's data arrives and the engine takes plaintext again: the held bytes leave first, in order.
            engine.stall = false
            val ping = "ping".encodeToByteArray()
            assertEquals(TlsSendOutcome.ACCEPTED, client.send(ping, 0, ping.size, c))
            val wire = c.takeCipher()
            assertTrue(server.receive(wire, 0, wire.size, s))
            assertContentEquals(ping, s.takePlain())
            assertEquals(0, server.pendingPlaintext)
            assertEquals(TlsSendOutcome.ACCEPTED, server.send(chunks[4], 0, 4096, s), "the refused send, retried")
            assertTrue(shuttle(client, server, c, s))
            assertContentEquals(chunks[0] + chunks[1] + chunks[2] + chunks[3] + chunks[4], c.takePlain())
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test fun closeWaitsBehindHeldPlaintextThenSendsCloseNotify() {
        val directory = Files.createTempDirectory("tls-stall-close-")
        try {
            val (client, server, engine, c, s) = stallingPair(directory, 16 * 1024)
            client as JvmTlsRecordSession; server as JvmTlsRecordSession; engine as StallingEngine; c as Wire; s as Wire
            engine.stall = true
            val message = ByteArray(3000) { (it % 97).toByte() }
            assertEquals(TlsSendOutcome.ACCEPTED, server.send(message, 0, message.size, s))
            assertEquals(3000, server.pendingPlaintext)
            server.close(s)
            assertFalse(server.closed, "close_notify waits behind the held plaintext")
            assertEquals(TlsSendOutcome.CLOSED, server.send(message, 0, 1, s), "nothing more is accepted once close was asked")
            assertEquals(0, s.takeCipher().size)
            engine.stall = false
            val ping = "ping".encodeToByteArray()
            assertEquals(TlsSendOutcome.ACCEPTED, client.send(ping, 0, ping.size, c))
            val wire = c.takeCipher()
            assertFalse(server.receive(wire, 0, wire.size, s), "the receive that let the plaintext go also finished the close")
            assertTrue(server.closed)
            assertNull(server.failure)
            assertEquals(0, server.pendingPlaintext)
            assertFalse(shuttle(client, server, c, s), "the client saw close_notify")
            assertContentEquals(message, c.takePlain(), "all of the plaintext came first")
            assertTrue(client.closed)
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test fun aSinkMayNotDriveASessionOnItsOwnThread() {
        val directory = Files.createTempDirectory("tls-reentry-")
        try {
            val (cert, key) = writeIdentity(directory, "a")
            val backend = JvmTlsCodecBackend()
            val server = backend.recordSession(TlsConfig(certificateFile = cert.toString(), privateKeyFile = key.toString(),
                hostnameVerification = false), TlsRole.SERVER j ("" j 0))
            val client = backend.recordSession(TlsConfig(trustStore = cert.toString(), hostnameVerification = false), TlsRole.CLIENT j ("" j 443))
            val c = Wire()
            val s = Wire()
            handshake(client, server, c, s, Int.MAX_VALUE)
            val request = "GET / HTTP/1.1\r\n\r\n".encodeToByteArray()
            assertEquals(TlsSendOutcome.ACCEPTED, client.send(request, 0, request.size, c))
            val sent = c.takeCipher()
            val reentrant = object : TlsRecordSink {
                // Drives a session while the server's windows still hold these bytes: refused.
                override fun plaintext(bytes: ByteArray, from: Int, to: Int) { client.send(bytes, from, to, c) }
                override fun ciphertext(bytes: ByteArray, from: Int, to: Int) {}
            }
            assertFailsWith<IllegalStateException> { server.receive(sent, 0, sent.size, reentrant) }
            // The refusal releases the thread's windows: ordinary calls work again.
            assertEquals(TlsSendOutcome.ACCEPTED, client.send(request, 0, request.size, c))
            val again = c.takeCipher()
            assertTrue(server.receive(again, 0, again.size, s))
            assertContentEquals(request, s.takePlain())
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    companion object {
        /** A fresh self-signed P-256 identity for 127.0.0.1 from the JDK's keytool, written as PEM: (cert, key). */
        fun writeIdentity(directory: Path, name: String): Pair<Path, Path> {
            val store = directory.resolve("$name.p12")
            val keytool = Path.of(java.lang.System.getProperty("java.home"), "bin", "keytool").toString()
            val run = ProcessBuilder(keytool, "-genkeypair", "-alias", name, "-keyalg", "EC", "-groupname", "secp256r1",
                "-sigalg", "SHA256withECDSA", "-dname", "CN=127.0.0.1", "-ext", "SAN=IP:127.0.0.1", "-validity", "2",
                "-storetype", "PKCS12", "-keystore", store.toString(), "-storepass", "changeit", "-keypass", "changeit")
                .redirectErrorStream(true).start()
            val output = run.inputStream.readAllBytes().decodeToString()
            check(run.waitFor(60, TimeUnit.SECONDS) && run.exitValue() == 0) { output }
            val keyStore = KeyStore.getInstance("PKCS12")
            Files.newInputStream(store).use { keyStore.load(it, "changeit".toCharArray()) }
            val mime = Base64.getMimeEncoder(64, "\n".toByteArray())
            fun pem(label: String, der: ByteArray) = "-----BEGIN $label-----\n${mime.encodeToString(der)}\n-----END $label-----\n"
            val cert = directory.resolve("$name-cert.pem")
            Files.writeString(cert, pem("CERTIFICATE", keyStore.getCertificate(name).encoded))
            val key = directory.resolve("$name-key.pem")
            Files.writeString(key, pem("PRIVATE" + " KEY", keyStore.getKey(name, "changeit".toCharArray()).encoded))
            return cert to key
        }
    }
}
