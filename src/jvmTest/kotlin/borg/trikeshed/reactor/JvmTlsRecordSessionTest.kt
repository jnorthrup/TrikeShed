package borg.trikeshed.reactor

import borg.trikeshed.lib.*
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyStore
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
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
                assertTrue(client.send(request, 0, request.size, c))
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
                assertTrue(server.send(response, 0, response.size, s))
                assertTrue(feed(client, s.takeCipher(), c, chunk))
                assertContentEquals(response, c.takePlain())
                client.close(c)
                assertTrue(client.closed)
                assertFalse(client.send(request, 0, request.size, c))
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
