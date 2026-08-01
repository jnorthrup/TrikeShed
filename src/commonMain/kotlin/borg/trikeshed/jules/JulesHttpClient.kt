package borg.trikeshed.jules

import borg.trikeshed.htx.htxHeaders
import borg.trikeshed.lib.j

/**
 * Common HTTP shape for Jules REST calls.
 *
 * Its production implementation [TrikeHtxHttpClient] is commonMain and routes
 * through the reactor HTX transport. Targets supply only their actual NIO/TLS
 * providers to the route service; they do not supply HTTP clients.
 */
interface JulesHttpClient {
    /** GET a path, return the response body. Throws on transport or HTTP error. */
    suspend fun get(path: String): String
    /** POST a JSON body to a path, return the response body. */
    suspend fun post(path: String, json: String): String
    /** DELETE a resource. */
    suspend fun delete(path: String): String
}

fun julesHtxClient(apiKey: String, base: String): JulesHttpClient =
    TrikeHtxHttpClient(
        base = base,
        defaultHeaders = htxHeaders("x-goog-api-key" j apiKey),
    )
