package borg.trikeshed.reactor

import borg.trikeshed.userspace.nio.ByteBuffer
import borg.trikeshed.userspace.nio.channels.spi.ProcessOperations
import borg.trikeshed.userspace.nio.channels.spi.ProcessResult
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class TlsEndpointTest {
    @Test
    fun opensslExecFallbackUsesProcessSpiAndRepoByteBuffer() = runTest {
        val processOperations = FakeProcessOperations()
        val tls = openTlsElement(processOperations = processOperations)
        val endpoint = tls.clientEndpoint("example.com", 443)

        val request = ByteBuffer("GET / HTTP/1.1\r\nHost: example.com\r\n\r\n".encodeToByteArray())
        assertEquals(request.remaining(), endpoint.write(request))

        endpoint.handshake()

        val response = ByteBuffer(128)
        val read = endpoint.read(response)

        assertTrue(endpoint.isHandshakeComplete)
        assertEquals("TLSv1.3", endpoint.protocol)
        assertEquals("TLS_AES_128_GCM_SHA256", endpoint.cipherSuite)
        assertEquals(1, processOperations.calls.size)
        assertTrue(read > 0)

        response.flip()
        val bytes = ByteArray(response.remaining())
        response.get(bytes)
        assertEquals("HTTP/1.1 200 OK\r\n\r\nhello", bytes.decodeToString())
        assertNotNull(endpoint.peerCertificate)
        assertEquals("CN=example.com", endpoint.peerCertificate?.subject)
    }
}

private class FakeProcessOperations : ProcessOperations {
    override val key: CoroutineContext.Key<*> get() = ProcessOperations.Key

    val calls = mutableListOf<Triple<String, List<String>, ByteArray?>>()

    override suspend fun exec(
        command: String,
        args: List<String>,
        stdin: ByteArray?,
        env: Map<String, String>,
    ): ProcessResult {
        calls += Triple(command, args, stdin)
        return ProcessResult(
            exitCode = 0,
            stdout = "HTTP/1.1 200 OK\r\n\r\nhello".encodeToByteArray(),
            stderr = """
                Protocol  : TLSv1.3
                Cipher    : TLS_AES_128_GCM_SHA256
                subject=CN=example.com
                issuer=CN=Test CA
                Verify return code: 0 (ok)
            """.trimIndent().encodeToByteArray(),
        )
    }
}
