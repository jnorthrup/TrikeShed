package borg.trikeshed.jules

/**
 * Platform HTTP client abstraction for Jules REST calls.
 *
 * commonMain interface so the flywheel can compile against it on any target.
 * jvmMain actual: [JvmJulesHttpClient] backed by java.net.http.HttpClient.
 * Future actual: userspace NIO (ChannelOperations-based) for io_uring.
 *
 * All calls are suspend-friendly — the jvmMain impl dispatches to
 * Dispatchers.IO so blocking I/O doesn't park the calling coroutine.
 */
interface JulesHttpClient {
    /** GET a path, return the response body. Throws on transport error. */
    suspend fun get(path: String): String
    /** POST a JSON body to a path, return the response body. */
    suspend fun post(path: String, json: String): String
    /** DELETE a resource. */
    suspend fun delete(path: String): String
}
