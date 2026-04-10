package borg.literbike.curl_h2

/**
 * HTTP/2 client error types
 */
sealed class H2Error : Exception() {
    data class Curl(val message: String) : H2Error()
    data class H2Protocol(val message: String) : H2Error()
    data class Io(override val cause: Throwable) : H2Error()
    data class InvalidUrl(val url: String) : H2Error()
    data class Connection(val message: String) : H2Error()
    object Timeout : H2Error()
    data class InvalidResponse(val message: String) : H2Error()

    override val message: String
        get() = when (this) {
            is Curl -> "Curl error: $message"
            is H2Protocol -> "HTTP/2 protocol error: $message"
            is Io -> "IO error: ${cause.message}"
            is InvalidUrl -> "Invalid URL: $url"
            is Connection -> "Connection error: $message"
            Timeout -> "Request timeout"
            is InvalidResponse -> "Invalid response: $message"
        }
}

/**
 * Result type for H2 operations
 */
typealias H2Result<T> = Result<T>
