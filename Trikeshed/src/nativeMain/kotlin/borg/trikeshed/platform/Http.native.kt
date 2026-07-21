package borg.trikeshed.platform

actual suspend fun httpGet(urlString: String, headers: Map<String, String>): HttpResponse {
    // TODO: Implement for Native. This would typically use a library like Ktor client
    // or platform-specific APIs (e.g., NSURLConnection on macOS/iOS, libcurl on Linux).
    println("Warning: httpGet is not yet implemented for Native. URL: $urlString")
    return HttpResponse(0, "", emptyMap(), "Not implemented on Native platform.")
}
