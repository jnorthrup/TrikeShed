package borg.trikeshed.jules

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/**
 * JVM actual of [JulesHttpClient] backed by java.net.http.HttpClient.
 *
 * All blocking calls run on Dispatchers.IO so the coroutine scheduler
 * is never parked by network I/O. 15s connect timeout, 30s request timeout.
 */
class JvmJulesHttpClient(
    private val apiKey: String,
    private val base: String = "https://jules.googleapis.com/v1alpha",
) : JulesHttpClient {

    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(java.time.Duration.ofSeconds(15))
        .build()

    override suspend fun get(path: String): String = withContext(Dispatchers.IO) {
        request("GET", path, null)
    }

    override suspend fun post(path: String, json: String): String = withContext(Dispatchers.IO) {
        request("POST", path, json)
    }

    override suspend fun delete(path: String): String = withContext(Dispatchers.IO) {
        request("DELETE", path, null)
    }

    private fun request(method: String, path: String, json: String?): String {
        val builder = HttpRequest.newBuilder()
            .uri(URI.create("$base$path"))
            .timeout(java.time.Duration.ofSeconds(30))
            .header("x-goog-api-key", apiKey)
            .header("Content-Type", "application/json")
        when (method) {
            "GET" -> builder.GET()
            "DELETE" -> builder.DELETE()
            else -> builder.POST(HttpRequest.BodyPublishers.ofString(json ?: "{}"))
        }
        val resp = http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
        if (resp.statusCode() >= 400) error("Jules API ${resp.statusCode()}: ${resp.body().take(300)}")
        return resp.body()
    }
}
