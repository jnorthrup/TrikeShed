package borg.trikeshed.reactor

import borg.trikeshed.lib.*
import borg.trikeshed.userspace.nio.file.spi.JvmFileOperations
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream
import java.io.File
import java.net.InetAddress
import java.net.Socket
import java.security.KeyFactory
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import javax.net.ssl.*
import kotlin.concurrent.thread
import kotlin.test.*

/**
 * The TLS 1.3 client through [Tls13CodecBackend] and a [TlsElement] endpoint against the JDK's TLS 1.3 server
 * (SSLServerSocket on loopback) serving [X509Test]'s keytool PKI: each cipher suite, each group (secp256r1 and
 * secp384r1 through HelloRetryRequest), each CertificateVerify scheme, a requested client certificate, a server
 * KeyUpdate, an IP literal, and the refused certificates; OpenSSL's s_server for ed25519 and a second oracle.
 */
class Tls13ClientJvmTest {
    val fixtures = X509Test()

    /** PKCS#8 private keys of the served leaves, exported once from the keytool keystore. */
    val keys: Map<String, ByteArray> = mapOf(
        "leaf-p256" to "308187020100301306072a8648ce3d020106082a8648ce3d030107046d306b0201010420769f2571b613bc349c67848d79b2c28633d586ccc140241c" +
            "82314e49bc3135f2a14403420004c4cbb8fa0bd120be15165c4f6152f78eb0f83f44ea3b487923765969eda008180fbb92d684bcf8006b65237711e2" +
            "6a5beb2834a3ef5771ebeb011eb455ccab52",
        "leaf-p384" to "3081b6020100301006072a8648ce3d020106052b8104002204819e30819b020101043005540dec44264fb0ffb14a9d9f872d6f35b69ab74ebef5f945" +
            "9cd71f4b1178cefc2190d11dee1bbad25fab9950c8b8b3a1640362000415efa62890c60079f0d986baa377496559bbf7e46b3797c3c3c1cf8826d4dc" +
            "dfe2c2f5adfface54ede16d29c4ba3b5c539494d209cf2b365d9bfbc72f102016318c23818fda518be4955b72f227afba2d2ec7891ec8d81b2a5c38c" +
            "1358e45bb4",
        "leaf-rsa" to "308204bc020100300d06092a864886f70d0101010500048204a6308204a20201000282010100a1f06ac03d387a98c7e182cfef5e8dfba7dfde2128ba" +
            "0da92fe099b0d8f54f2bf2d413c88a51b397ec933a41cfdfac35d6f9e858831252f809600ffb2e002055df6c337dcd8eee2aa5cdb5a25c7a6cd20511" +
            "23859f39c966d94670b9141666767566c5ea89664e23e0a714abead6963f625c63970e3d8b75d38c18e3d82e4c9db55fb1a143f10fdaf7ce50e413a4" +
            "6109b59ffbc21681b861d9c83567256206a9d5c20ae3024065fae5a617e4717ae224e65b8749fd9c6577609372a3e3fcd6e4f71d79b8289c9050e5a8" +
            "c62eef27a5e9251392648fa9b93fdf6d3b036ad97dcc8f60410010b036bae5fb5b051be51dcaed1315a6cf95f6d0e8637b3004bab3b7020301000102" +
            "82010019ced36c7cb53e10903657df24a25895652d2fdceffd2ac52e5ca891bf19e53ab751e62f3d200533ae9fce1683f6fd1e7d7ac97525ba6b5f9d" +
            "3ae28120d523521c2fc5cb04520591e567b1cd44d8bbf4029bb72209fcaf24c576cceb2a10e1d67a225d6361ef5c7faa43666f7997264dc6b2bbaa6c" +
            "ba113a3f7321da59ef1305845ca4c2425fb600f0c5767ab28a5218179de16e88345031a38675bc781d3f2874a243fec2d3f5220aba8be960a0f10a15" +
            "3f99cb6ef8ae255260f4c589f57e5261c2dc45c2cfdd06e529e976f41b28f4b6c9dfdef74f14dc81a4106868d0c6f8b5b4bb01679ab404a42a75c70a" +
            "b2add5990d91f82864ee7f6fdd316202528d3902818100d2dec352c36c7d8838c02f63c67360b4ed322e1c1fcc27753f86dc8531b41cf4fd474003aa" +
            "623f6a5219888d9fe13be55fa03ba33dc0dc56f5f0578ca3a1674201abb6e8016f68ad18a4269a3d3e59504b94825a94a128c4d96f698a32fe86ddc4" +
            "e0435418ab0146f22d0b68774f0e491588d9ace0aaf16bd809bbbc11846ebf02818100c498cd18e81352e2e5eb422e9863a2ff4f430b25b58a22cfdb" +
            "ce62f2283014e071525f195d2b3fd8ec2a2594021c65870d9bc2fe0015a5ae6ae6622be23302e937529b78837dfe94129badb09f7a051d81b8d79ea5" +
            "0984200730d5ef60bbf9ec6277719d8aad23b930a697d50163c9ea426d1a5de718d577bb64db79ca6ff10902818042fe3cc5cd8a69335efc47d20619" +
            "5fcefb50a6ef8e7698ce9a392954339f4a57d723ccc59d5b0b54a6f2afbc76dfe2dc938ddf849438f287ba28fa579720cf225c4f86939ce91d336911" +
            "b5a5e8f12704e88e2cffc41a934e3eda3388917ce5b20d3b4688b0319bfd4331665f71102fb4ae5fc4f0348c7a98cd910210d4ebe3170281806be3e7" +
            "7aa9b22124c04511b6fc8e819f576255b5c6211ee5af9d5e13c3164ffeb4721e1a8f54f2df1b0c2e3f41c14fbe842b5d2183ee5d5490fa0a1a4732f3" +
            "80058a62240b159426b2e798b47b70bd69ea0d04d7dd7e9171fa4044c7eff07941ceeff9fc1964d75c046508a22ef712926b0df1106388ef6d9bfbc7" +
            "aed8f801f10281803217e914856a00a69710a3d29f40a43431aea1bcdd7ee4ac548af492f4eae4c058d830c4ff9f8ad233173100987c032409ab48be" +
            "cf771a1c54d0a8634d6a58ddb247374d7d1d539ba5895230890bcfd4684c273720e85ccba99586496aebcf535e61cb02b5bec22d78a9be32380819bd" +
            "0b1baef3fa841a0d52b49629fa33fd81",
        "leaf-ed25519" to "302e020100300506032b657004220420ba6b747c78e19f1d538ab924396439118966203c45a34852beb7d79612f727dc",
        "leaf-expired" to "308187020100301306072a8648ce3d020106082a8648ce3d030107046d306b020101042005f308d44d77b31acadb161aaff2397a60e7e9945693fecb" +
            "94ed89d7e88e4d86a14403420004ef3a94fc6b333b01e1b35333971babf22a3a4728f0f5801827e37e23517b23c184cf95df450c1855ce078867ad23" +
            "6ee73ccd61e7f855dcf601c8428cb9ffe265",
    ).mapValues { it.value.hexToByteArray() }

    /** RFC 7468 textual encoding of [der] under [label]. */
    fun pem(label: String, der: ByteArray): String = "-----BEGIN $label-----\n" + Base64.getMimeEncoder().encodeToString(der) + "\n-----END $label-----\n"

    fun pemFile(text: String): File = File.createTempFile("trikeshed-test", ".pem").apply {
        deleteOnExit()
        writeText(text)
    }

    val roots: File = pemFile(listOf("root-ec", "root-rsa").joinToString("") { pem("CERTIFICATE", fixtures.certificates.getValue(it)) })

    fun serverContext(alias: String): SSLContext {
        val factory = CertificateFactory.getInstance("X.509")
        val chain = fixtures.jdkChain(alias).map { factory.generateCertificate(ByteArrayInputStream(it)) }.toTypedArray()
        val spec = PKCS8EncodedKeySpec(keys.getValue(alias))
        val key = listOf("EC", "RSA", "Ed25519").firstNotNullOf { runCatching { KeyFactory.getInstance(it).generatePrivate(spec) }.getOrNull() }
        val password = "test".toCharArray()
        val store = KeyStore.getInstance("PKCS12").apply { load(null, null); setKeyEntry(alias, key, password, chain) }
        val managers = KeyManagerFactory.getInstance("PKIX").apply { init(store, password) }.keyManagers
        return SSLContext.getInstance("TLSv1.3").apply { init(managers, null, null) }
    }

    /**
     * One connection answering each line with "pong <line>" until "bye"; with [keyUpdate] the JDK's startHandshake()
     * sends a KeyUpdate (update_requested) before the first answer.
     */
    class Server(context: SSLContext, val keyUpdate: Boolean = false, configure: SSLParameters.() -> Unit) : AutoCloseable {
        val socket = context.serverSocketFactory.createServerSocket(0, 1, InetAddress.getLoopbackAddress()) as SSLServerSocket
        var failure: Throwable? = null
        val worker: Thread

        init {
            socket.sslParameters = socket.sslParameters.apply { protocols = arrayOf("TLSv1.3"); configure() }
            worker = thread {
                try {
                    (socket.accept() as SSLSocket).use { connection ->
                        val input = connection.inputStream.bufferedReader()
                        val output = connection.outputStream
                        var line = input.readLine()
                        if (keyUpdate) connection.startHandshake()
                        while (line != null && line != "bye") {
                            output.write("pong $line\n".toByteArray())
                            output.flush()
                            line = input.readLine()
                        }
                    }
                } catch (e: Throwable) {
                    failure = e
                }
            }
        }

        val port: Int get() = socket.localPort

        override fun close() {
            socket.close()
            worker.join(10_000)
        }
    }

    /** Handshake as [host], send each of [lines] and read its answer; returns the session, the client and the answers. */
    fun exchange(port: Int, lines: List<String>, host: String = "localhost", trustStore: String? = roots.path): Triple<TlsSession, Tls13Client, List<String>> = runBlocking {
        val backend = Tls13CodecBackend(JvmFileOperations())
        val tls = openTlsElement(TlsConfig(trustStore = trustStore), backend)
        val endpoint = tls.clientEndpoint(host, port)
        Socket(InetAddress.getLoopbackAddress(), port).use { socket ->
            socket.soTimeout = 10_000
            val input = socket.getInputStream()
            val output = socket.getOutputStream()
            val received = StringBuilder()
            fun flush(frames: TlsFrames) {
                frames.forEach {
                    if (it.stage == TlsFlowStage.UPSTREAM_CIPHERTEXT || it.stage == TlsFlowStage.CLOSE_NOTIFY) output.write(it.payload.toArray())
                    if (it.stage == TlsFlowStage.DOWNSTREAM_PLAINTEXT) received.append(it.payload.toArray().decodeToString())
                }
                output.flush()
            }
            val buffer = ByteArray(16384)
            suspend fun read() {
                val n = input.read(buffer)
                check(n > 0) { "server closed" }
                flush(endpoint.downstream(ByteSeries(buffer.copyOf(n))))
            }
            flush(endpoint.handshake())
            while (!endpoint.isHandshakeComplete) read()
            val client = backend.clients.values.single()
            val answers = lines.map { line ->
                flush(endpoint.upstream(ByteSeries("$line\n".encodeToByteArray())))
                while ('\n' !in received) read()
                received.substring(0, received.indexOf('\n')).also { received.deleteRange(0, received.indexOf('\n') + 1) }
            }
            val session = endpoint.session!!
            flush(endpoint.upstream(ByteSeries("bye\n".encodeToByteArray())))
            flush(endpoint.close())
            tls.close()
            Triple(session, client, answers)
        }
    }

    fun serve(alias: String, keyUpdate: Boolean = false, lines: List<String> = listOf("ping"), configure: SSLParameters.() -> Unit = {}): Triple<TlsSession, Tls13Client, List<String>> =
        Server(serverContext(alias), keyUpdate, configure).use { server ->
            val result = exchange(server.port, lines)
            server.close()
            server.failure?.let { throw it }
            assertEquals(lines.map { "pong $it" }, result.third)
            result
        }

    @Test fun cipherSuites() {
        for (suite in listOf(TlsCipherSuite.TLS_AES_128_GCM_SHA256, TlsCipherSuite.TLS_AES_256_GCM_SHA384, TlsCipherSuite.TLS_CHACHA20_POLY1305_SHA256)) {
            val (session, _, _) = serve("leaf-p256") { cipherSuites = arrayOf(suite.name) }
            assertEquals(suite, session.cipherSuite)
            assertEquals(TlsProtocol.TLS13, session.protocol)
            assertEquals("CN=localhost", session.peerCertificate?.subject)
            assertEquals("CN=TrikeShed Test Intermediate EC", session.peerCertificate?.issuer)
        }
    }

    @Test fun groupsWithHelloRetryRequest() {
        for ((group, name) in listOf(TlsSupportedGroup.X25519 to "x25519", TlsSupportedGroup.P256 to "secp256r1", TlsSupportedGroup.P384 to "secp384r1")) {
            val (_, client, _) = serve("leaf-p256") { namedGroups = arrayOf(name) }
            assertEquals(group, client.keyShare.group)
            assertEquals(group != TlsSupportedGroup.X25519, client.retried)
        }
    }

    /**
     * Each scheme the JDK signs CertificateVerify with, offered alone in signature_algorithms (every scheme stays in
     * signature_algorithms_cert, since the JDK holds its whole chain to the offered schemes). JSSE refuses ed25519.
     */
    @Test fun signatureSchemes() {
        for ((scheme, alias) in listOf(
            SignatureScheme.ecdsa_secp256r1_sha256 to "leaf-p256", SignatureScheme.ecdsa_secp384r1_sha384 to "leaf-p384",
            SignatureScheme.rsa_pss_rsae_sha256 to "leaf-rsa", SignatureScheme.rsa_pss_rsae_sha384 to "leaf-rsa",
            SignatureScheme.rsa_pss_rsae_sha512 to "leaf-rsa",
        )) Server(serverContext(alias)) {}.use { server ->
            Socket(InetAddress.getLoopbackAddress(), server.port).use { socket ->
                socket.soTimeout = 10_000
                val client = Tls13Client(
                    "localhost", true, fixtures.jdkRoots, listOf(TlsCipherSuite.TLS_AES_128_GCM_SHA256), listOf(TlsSupportedGroup.X25519),
                    emptyList(), { fixtures.captured }, listOf(scheme),
                )
                val buffer = ByteArray(16384)
                var pending = ByteArray(0)
                socket.getOutputStream().write(client.clientHello())
                while (client.state != Tls13Client.State.CONNECTED) {
                    val n = socket.getInputStream().read(buffer)
                    check(n > 0) { "server closed" }
                    pending += buffer.copyOf(n)
                    pending = pending.copyOfRange(client.receive(pending), pending.size)
                    client.outgoing.forEach { socket.getOutputStream().write(it) }
                    client.outgoing.clear()
                }
                socket.getOutputStream().write(client.seal("bye\n".encodeToByteArray()) + client.closeNotify())
            }
            server.close()
            server.failure?.let { throw it }
        }
    }

    @Test fun requestedClientCertificateAnsweredEmpty() {
        val (_, client, _) = serve("leaf-p256") { wantClientAuth = true }
        assertNotNull(client.certificateRequestContext)
    }

    /**
     * OpenSSL 3's s_server (`openssl` on the PATH) as the second oracle, restricted by -sigalgs to one CertificateVerify
     * scheme: ed25519, which JSSE will not sign with, and one RSA-PSS and one ECDSA leaf. -rev answers each line reversed.
     */
    @Test fun openSslSignatureSchemes() {
        for ((scheme, alias) in listOf(
            SignatureScheme.ed25519 to "leaf-ed25519", SignatureScheme.rsa_pss_rsae_sha256 to "leaf-rsa",
            SignatureScheme.ecdsa_secp256r1_sha256 to "leaf-p256",
        )) {
            val chain = fixtures.jdkChain(alias)
            val server = ProcessBuilder(
                "openssl", "s_server", "-accept", "127.0.0.1:0", "-naccept", "1", "-tls1_3", "-rev", "-sigalgs", scheme.name,
                "-cert", pemFile(pem("CERTIFICATE", chain.first())).path,
                "-cert_chain", pemFile(chain.drop(1).joinToString("") { pem("CERTIFICATE", it) }).path,
                "-key", pemFile(pem("PRIVATE KEY", keys.getValue(alias))).path,
            ).redirectErrorStream(true).start()
            try {
                val output = server.inputStream.bufferedReader()
                val accept = generateSequence { output.readLine() }.firstOrNull { it.startsWith("ACCEPT ") }
                    ?: fail("openssl s_server did not listen")
                val (session, _, answers) = exchange(accept.substringAfterLast(':').toInt(), listOf("ping"))
                assertEquals(listOf("gnip"), answers, "$scheme")
                assertEquals("CN=localhost", session.peerCertificate?.subject)
            } finally {
                server.destroy()
                server.waitFor()
            }
        }
    }

    @Test fun serverKeyUpdate() {
        val (_, client, _) = serve("leaf-rsa", keyUpdate = true, lines = listOf("before", "after"))
        // under the updated keys: the server's two answers, and our "after", "bye" and close_notify
        assertEquals(2L, client.read!!.sequence)
        assertEquals(3L, client.write!!.sequence)
    }

    @Test fun ipLiteralNamesTheIpAddressSan() {
        Server(serverContext("leaf-p256")) {}.use { server -> exchange(server.port, listOf("ping"), host = "127.0.0.1") }
    }

    fun refused(alias: String, host: String = "localhost", trustStore: String? = roots.path): TlsAlert =
        Server(serverContext(alias)) {}.use { server -> assertFailsWith<TlsAlert> { exchange(server.port, emptyList(), host, trustStore) } }

    @Test fun refusedCertificates() {
        refused("leaf-p256", host = "wrong.example").let {
            assertEquals(AlertDescription.bad_certificate.code, it.description)
            assertEquals(CertificateError.CertNotValidForName, (it.cause as CertificateException).error)
        }
        refused("leaf-p256", trustStore = null).let {
            assertEquals(AlertDescription.unknown_ca.code, it.description)
            assertEquals(CertificateError.UnknownIssuer, (it.cause as CertificateException).error)
        }
        refused("leaf-expired").let {
            assertEquals(AlertDescription.certificate_expired.code, it.description)
            assertEquals(CertificateError.CertExpired, (it.cause as CertificateException).error)
        }
    }
}
