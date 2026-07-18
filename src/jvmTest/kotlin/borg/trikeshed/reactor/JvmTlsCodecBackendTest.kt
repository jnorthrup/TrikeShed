package borg.trikeshed.reactor

import borg.trikeshed.lib.ByteSeries
import borg.trikeshed.lib.s_
import borg.trikeshed.lib.toList
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.runTest

class JvmTlsCodecBackendTest {
    @Test
    fun ccekJvmTlsBackendHandshakesAndRelaysPlaintext() = runTest {
        val tempDir = Files.createTempDirectory("ccek-jvm-tls")
        val certPath = writePem(tempDir, "localhost-cert.pem", TEST_CERTIFICATE_PEM)
        val keyPath = writePem(tempDir, "localhost-key.pem", TEST_PRIVATE_KEY_PEM)

        val clientElement = openTlsElement(
            config = TlsConfig(
                trustStore = certPath.toString(),
                hostnameVerification = false,
            ),
            backend = JvmTlsCodecBackend(),
        )
        val serverElement = openTlsElement(
            config = TlsConfig(
                trustStore = certPath.toString(),
                certificateFile = certPath.toString(),
                privateKeyFile = keyPath.toString(),
                hostnameVerification = false,
            ),
            backend = JvmTlsCodecBackend(),
        )

        val client = clientElement.clientEndpoint("localhost", 443)
        val server = serverElement.serverEndpoint("0.0.0.0", 443)

        var clientFrames: List<TlsChannelFrame> = client.handshake().toList()
        server.handshake()

        repeat(16) {
            if (client.isHandshakeComplete && server.isHandshakeComplete) {
                return@repeat
            }

            val serverFrames = relayCiphertext(clientFrames, server)
            clientFrames = relayCiphertext(serverFrames, client)
        }

        assertTrue(client.isHandshakeComplete)
        assertTrue(server.isHandshakeComplete)
        assertEquals(TlsProtocol.TLS13, client.session?.protocol)
        assertEquals(TlsProtocol.TLS13, server.session?.protocol)

        val serverPayloadFrames = relayCiphertext(
            client.upstream(ByteSeries("ping over tls")).toList(),
            server,
        )
        val serverPlaintext = serverPayloadFrames
            .first { it.stage == TlsFlowStage.DOWNSTREAM_PLAINTEXT }
            .payload
            .asString()
        assertEquals("ping over tls", serverPlaintext)

        val clientPayloadFrames = relayCiphertext(
            server.upstream(ByteSeries("pong over tls")).toList(),
            client,
        )
        val clientPlaintext = clientPayloadFrames
            .first { it.stage == TlsFlowStage.DOWNSTREAM_PLAINTEXT }
            .payload
            .asString()
        assertEquals("pong over tls", clientPlaintext)
    }

    @Test
    fun supervisorJobCanProveRemoteHttpsSite() = runTest {
        if (System.getProperty("trikeshed.liveTls") != "true") return@runTest

        val host = System.getProperty("trikeshed.liveTlsHost") ?: "example.com"
        val supervisor = SupervisorJob()
        val tls = openTlsElement(
            config = TlsConfig(
                hostnameVerification = true,
                alpnProtocols = s_[TlsApplicationProtocol.HTTP_1_1],
            ),
            backend = JvmTlsCodecBackend(),
            parentJob = supervisor,
        )
        val endpoint = tls.clientEndpoint(host, 443)

        java.net.Socket(host, 443).use { socket ->
            socket.soTimeout = 10_000
            val input = socket.getInputStream()
            val output = socket.getOutputStream()

            var frames = endpoint.handshake().toList()
            repeat(32) {
                writeCiphertext(frames, output)
                if (endpoint.isHandshakeComplete) {
                    return@repeat
                }
                val inbound = readChunk(input)
                frames = endpoint.downstream(ByteSeries(inbound)).toList()
            }

            assertTrue(endpoint.isHandshakeComplete)
            assertTrue(endpoint.session?.protocol != null)
            assertTrue(endpoint.session?.cipherSuite != null)

            writeCiphertext(
                endpoint.upstream(
                    ByteSeries(
                        "GET / HTTP/1.1\r\nHost: $host\r\nConnection: close\r\n\r\n",
                    ),
                ).toList(),
                output,
            )

            val plaintext = ByteArrayOutputStream()
            repeat(64) {
                val inbound = readChunk(input)
                val responseFrames = endpoint.downstream(ByteSeries(inbound)).toList()
                responseFrames
                    .filter { it.stage == TlsFlowStage.DOWNSTREAM_PLAINTEXT }
                    .forEach { plaintext.write(it.payload.toArray()) }
                writeCiphertext(responseFrames, output)
                if (plaintext.size() >= 12 && plaintext.toByteArray().decodeToString().startsWith("HTTP/1.1 ")) {
                    return@repeat
                }
            }

            assertTrue(plaintext.toByteArray().decodeToString().startsWith("HTTP/1.1 "))
        }

        supervisor.cancel()
    }

    private suspend fun relayCiphertext(
        frames: List<TlsChannelFrame>,
        endpoint: TlsEndpoint,
    ): List<TlsChannelFrame> {
        val emitted = mutableListOf<TlsChannelFrame>()
        frames.filter { it.stage == TlsFlowStage.UPSTREAM_CIPHERTEXT && !it.payload.isEmpty }
            .forEach { emitted += endpoint.downstream(it.payload).toList() }
        return emitted
    }

    private fun writePem(
        directory: Path,
        fileName: String,
        contents: String,
    ): Path =
        directory.resolve(fileName).also { Files.writeString(it, contents) }

    private fun writeCiphertext(
        frames: List<TlsChannelFrame>,
        output: java.io.OutputStream,
    ) {
        frames.filter { it.stage == TlsFlowStage.UPSTREAM_CIPHERTEXT && !it.payload.isEmpty }
            .forEach { frame -> output.write(frame.payload.toArray()) }
        output.flush()
    }

    private fun readChunk(input: java.io.InputStream): ByteArray {
        val buffer = ByteArray(32 * 1024)
        val count = input.read(buffer)
        check(count > 0) { "Expected remote TLS bytes" }
        return buffer.copyOf(count)
    }

    private companion object {
        const val TEST_CERTIFICATE_PEM = """
-----BEGIN CERTIFICATE-----
MIIDCTCCAfGgAwIBAgIUHXfIkLXh2NqIZgZaWD/WlAiz7SYwDQYJKoZIhvcNAQEL
BQAwFDESMBAGA1UEAwwJbG9jYWxob3N0MB4XDTI2MDYxOTAwMzM0M1oXDTI3MDYx
OTAwMzM0M1owFDESMBAGA1UEAwwJbG9jYWxob3N0MIIBIjANBgkqhkiG9w0BAQEF
AAOCAQ8AMIIBCgKCAQEAuUPE8wIUm6r///mNnBBRVFVN/67owYI5zSR2Ozt2O0JL
Bt8uzC3V9GUdWyzC26rBNl73/iYvdDJiUfFr4YZk6BNrZwsNUxstgTo29oDfaVD1
VN1FV4qO+qYueq0Yq2jQw7PbnRPIlAzwGuvdBzbly7BdKoqSRUB+w7hg31SvlMc0
A7MZ3Kh0n5JrZuSk1zxvG4XZcDivfd+63GDB+5TmzgBVUdWDW5jePXXubsYCwFMV
/aODMTO+n2hdkWnm8lJZka4hiKdW9/izgttmzFrqN3BlyOXktnKzzyJWZ8zqIjXj
EJeDCbH98y8XYlPMYRVbku+nmrFehrxdv1YHEeIGkQIDAQABo1MwUTAdBgNVHQ4E
FgQU/+zy4kZ9bp2VyxtKSJIwyag/uYYwHwYDVR0jBBgwFoAU/+zy4kZ9bp2VyxtK
SJIwyag/uYYwDwYDVR0TAQH/BAUwAwEB/zANBgkqhkiG9w0BAQsFAAOCAQEAUIe6
gNQxP3YuvKtTFzRiDgvyek0lUh1igLGuljkbUh3ztfMl+ZV9ig6JVyEsIwMs7uR5
9QBGjZ5/kC9hK4NoPIV50Xfe7BsrxhYFyerf5rHlehKjCMsAMGYkQIAzoU4VnEN9
hp6wO90GzIKJmAwRNN9M6XiAL+bgqqqP6d0PkmsDSbPjIf+2LfXVDwSqJX4c6t4X
kLO6UMLV4ivzOkm80n5kX7UK636iKx3T+bOrt9FhjK4yON6x788y4q9S17d8HNs0
pv4eW2eC5UAl4RbQTkFpNmw3OEgv1hpKNmH05TtGEt2dbFGyQjFysaJ5c9ebqFn/
tJ1kylnpMtPlpPqQMg==
-----END CERTIFICATE-----
"""

        const val TEST_PRIVATE_KEY_PEM = """
-----BEGIN PRIVATE KEY-----
MIIEvQIBADANBgkqhkiG9w0BAQEFAASCBKcwggSjAgEAAoIBAQC5Q8TzAhSbqv//
+Y2cEFFUVU3/rujBgjnNJHY7O3Y7QksG3y7MLdX0ZR1bLMLbqsE2Xvf+Ji90MmJR
8WvhhmToE2tnCw1TGy2BOjb2gN9pUPVU3UVXio76pi56rRiraNDDs9udE8iUDPAa
690HNuXLsF0qipJFQH7DuGDfVK+UxzQDsxncqHSfkmtm5KTXPG8bhdlwOK9937rc
YMH7lObOAFVR1YNbmN49de5uxgLAUxX9o4MxM76faF2RaebyUlmRriGIp1b3+LOC
22bMWuo3cGXI5eS2crPPIlZnzOoiNeMQl4MJsf3zLxdiU8xhFVuS76easV6GvF2/
VgcR4gaRAgMBAAECggEACTldkffVjNabvmPvcAjD8933bzaHMjNIq3UYSeSxA0xO
rf3ot5PEplFuf76fyQ4cZped41KFZFSp3oiVpXnqhj+JRm0fcbbVsixy1h0egoDc
ZihKLoygh8xEXilGJCqo0kWfNpfoka5/OefqblSGZnjWeqrBk58dcKM6d4Qg5dhb
XCre8ZVeDqKmLlTIysi1ge/r2esLQynEuCT9o7gd0+LUaJRmXXd5zUq1opX7t1lS
UpWMJoM/D/TgqfhK+CUtSWu/WppMwh9XQBIGeRK+EM6jJi0yCaJ14uEMRjsR8B2H
W4fVv7nwDFDIjw191ETvdwEh5SF/3OP7stCCudQ4TQKBgQD251d/5MkvQaQ6m1wR
3f9pVGFDQvoWcOtO07Cpy1XR0tMDE8xYh6adt4L/ahCJ0wPovqdmF8G/pK5tHLpg
s72po4Oq/cOcAmrM0siic6DOHaToz23KImXQMNq4YPq8+l0duAhZ5NPYl2NdA7we
uq6ALEH6qF5Nm3abSg0aeT46uwKBgQDAFxVL8qtwrJDX1AJn22LQsPwex7s/1J8S
XcGbY383MAEgtHCngQgKpIGmWHNjo7cT1Xj1AQO4lsdiirwpFWFX71cLHA8qkW/6
+bioVI1NA4luobqeASSKs5/yL/uqg0oF/RCrLdYVLUjz4+JgjA8qLM9XQKukiqCk
qTS4ASWNIwKBgHmkQFeiP/woOuEk6Zdj6BHcoI6i0NN9jnCnmsIMi5t0YsGBX4u+
STgq0T9E4iEE9UgVpiOGUM46NDVNkgVSiH6rMJNtspGKH1QKBQ8EKJVjxXtttol/
qOmJKDjujpnMP1QE9xhSxIxRCYMp8+Sv1IkRRPBZQxy5GxYmxY3lbeNBAoGBAI+J
sDRMZaeQi7t7hUCuQyzG0978SQPtkeBbhuzicJGAoZcavoOse6HYQ7lVpwPxtkBv
6C7MT0eEBAGywd/BRjg7dMOsd/jLLO5R5JaEeAHwfqXY3GZtXCz5BLApAtnruUi4
TdhcK/kvGbCFvQeAIWTWjykX/iq6HEhu2CIXWUQZAoGAEeADjLYB5x8PL9xL/eDi
HEMsBukml1B5QueIJuBl9FJqO3O5SrvP6JnNwTYMlX36Z5LTYhxYWiEU521SiolN
eT+5dX2mSULFUt1OrkVQyH5qqhWuIf7spBZPTJVPxCvfnlNVqwkBVkf3SojVNfTN
5iXYtbXM6MXJSQWelmfey4I=
-----END PRIVATE KEY-----
"""
    }
}
