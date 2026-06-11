package borg.trikeshed.htx.client

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import borg.trikeshed.htx.client.spi.NetworkConnection
import borg.trikeshed.htx.client.spi.NetworkTransportSpi

class MockNetworkConnection(val responseData: String) : NetworkConnection {
    val writtenData = mutableListOf<ByteArray>()

    override suspend fun write(data: ByteArray) {
        writtenData.add(data)
    }

    override fun read(): Flow<ByteArray> = flowOf(responseData.encodeToByteArray())

    override suspend fun close() {}
}

class MockNetworkTransport(val responseData: String) : NetworkTransportSpi {
    lateinit var lastConnection: MockNetworkConnection
    override suspend fun connect(host: String, port: Int): NetworkConnection {
        lastConnection = MockNetworkConnection(responseData)
        return lastConnection
    }
}

class HtxClientGetTest {

    @Test
    fun testSimpleHttpGet() = runTest {
        val mockResponse = "HTTP/1.1 200 OK\r\nContent-Length: 13\r\n\r\nHello, World!"
        val transport = MockNetworkTransport(mockResponse)
        val client = HtxClient(transport)

        val response = client.get("http://example.com/test")

        assertEquals(200, response.status)
        assertEquals("Hello, World!", response.body)

        // Verify request was written correctly
        val written = transport.lastConnection.writtenData.joinToString("") { it.decodeToString() }
        assertTrue(written.startsWith("GET /test HTTP/1.1\r\nHost: example.com\r\n"))
    }
}
