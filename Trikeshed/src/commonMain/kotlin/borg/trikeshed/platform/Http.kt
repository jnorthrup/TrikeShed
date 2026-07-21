package borg.trikeshed.platform

data class HttpResponse(
    val statusCode: Int,
    val body: String,
    val headers: Map<String, List<String>> = emptyMap(),
    val errorBody: String? = null
)

/**
 * Performs a basic HTTP GET request.
 *
 * @param urlString The URL to fetch.
 * @param headers A map of request headers.
 * @return An [HttpResponse] object.
 */
expect suspend fun httpGet(urlString: String, headers: Map<String, String> = emptyMap()): HttpResponse
