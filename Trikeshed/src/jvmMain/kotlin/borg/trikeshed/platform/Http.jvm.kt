package borg.trikeshed.platform

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

actual suspend fun httpGet(urlString: String, headers: Map<String, String>): HttpResponse = withContext(Dispatchers.IO) {
    val url = URL(urlString)
    val connection = url.openConnection() as HttpURLConnection
    connection.requestMethod = "GET"

    headers.forEach { (key, value) ->
        connection.setRequestProperty(key, value)
    }

    try {
        connection.connect() // Implicitly called by getInputStream or getResponseCode

        val statusCode = connection.responseCode
        val responseHeaders = connection.headerFields

        if (statusCode in 200..299) {
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            HttpResponse(statusCode, body, responseHeaders)
        } else {
            val errorBody = connection.errorStream?.bufferedReader()?.use { it.readText() }
            HttpResponse(statusCode, "", responseHeaders, errorBody ?: "No error body.")
        }
    } catch (e: Exception) {
        HttpResponse(0, "", emptyMap(), "Exception: ${e.message}")
    } finally {
        connection.disconnect()
    }
}
