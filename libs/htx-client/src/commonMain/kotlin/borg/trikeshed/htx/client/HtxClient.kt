package borg.trikeshed.htx.client

import borg.trikeshed.htx.client.spi.NetworkTransportSpi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

class HttpResponse(val status: Int, val body: String)

class HtxClient(
    private val transport: NetworkTransportSpi,
    private val config: HtxClientConfig = HtxClientConfig(),
) {
    suspend fun get(url: String): HttpResponse {
        var status = 200
        var responseData = byteArrayOf()
        var isHeaderParsed = false

        val connection = streamConnection(url, null, null)
        connection.read().collect { chunk ->
             if (!isHeaderParsed) {
                 val currentData = responseData + chunk
                 val bodyIndex = findHeaderEnd(currentData)

                 if (bodyIndex != -1) {
                     isHeaderParsed = true
                     val headerStr = currentData.copyOfRange(0, bodyIndex).decodeToString()
                     val lines = headerStr.split("\r\n")
                     val statusLine = lines.firstOrNull() ?: ""
                     val statusParts = statusLine.split(" ")
                     status = if (statusParts.size > 1) statusParts[1].toIntOrNull() ?: 500 else 500

                     responseData = currentData.copyOfRange(bodyIndex + 4, currentData.size)
                 } else {
                     responseData = currentData
                 }
             } else {
                 responseData += chunk
             }
        }
        return HttpResponse(status, responseData.decodeToString())
    }

    suspend fun getRange(url: String, start: Long?, end: Long?): HttpResponse {
        var responseData = byteArrayOf()
        streamRange(url, start, end).collect { chunk ->
            responseData += chunk
        }
        return HttpResponse(206, responseData.decodeToString())
    }

    suspend fun streamConnection(url: String, start: Long?, end: Long?): borg.trikeshed.htx.client.spi.NetworkConnection {
        val request = parseRequest(
            url = url,
            config = config,
            range = if (start != null && end != null) HtxRange(start, end) else null,
        )
        val connection = transport.connect(request.target)
        connection.write(request.renderWireRequest().encodeToByteArray())
        return connection
    }

    suspend fun streamRange(url: String, start: Long?, end: Long?): Flow<ByteArray> = flow {
        val connection = streamConnection(url, start, end)

        var headerParsed = false
        var leftover = byteArrayOf()

        connection.read().collect { chunk ->
            if (!headerParsed) {
                leftover += chunk
                val bodyIndex = findHeaderEnd(leftover)
                if (bodyIndex != -1) {
                    headerParsed = true
                    val bodyData = leftover.copyOfRange(bodyIndex + 4, leftover.size)
                    if (bodyData.isNotEmpty()) {
                        emit(bodyData)
                    }
                }
            } else {
                emit(chunk)
            }
        }

        connection.close()
    }

    private fun findHeaderEnd(data: ByteArray): Int {
        for (i in 0..data.size - 4) {
            if (data[i] == '\r'.code.toByte() &&
                data[i + 1] == '\n'.code.toByte() &&
                data[i + 2] == '\r'.code.toByte() &&
                data[i + 3] == '\n'.code.toByte()) {
                return i
            }
        }
        return -1
    }
}
