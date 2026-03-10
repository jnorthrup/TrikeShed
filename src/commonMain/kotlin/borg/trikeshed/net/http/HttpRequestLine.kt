package borg.trikeshed.net.http

/** RFC 7230 request-line: METHOD SP request-target SP HTTP-version */
interface HttpRequestLineSpec {
    val method: String
    val requestTarget: String
    val httpVersion: String
}

data class HttpRequestLine(
    override val method: String,
    override val requestTarget: String,
    override val httpVersion: String,
) : HttpRequestLineSpec

/**
 * Parse an RFC 7230 request line: "METHOD SP request-target SP HTTP-version"
 * Returns null when the line has fewer than 3 space-delimited parts.
 */
fun parseHttpRequestLine(line: String): HttpRequestLineSpec? {
    val parts = line.split(" ", limit = 3)
    if (parts.size != 3) return null
    return HttpRequestLine(
        method = parts[0],
        requestTarget = parts[1],
        httpVersion = parts[2],
    )
}

/**
 * Parse HTTP header lines (name: value) into a list of pairs.
 * Stops at blank line or end of list. Lines without ':' are skipped.
 * Value leading/trailing whitespace is trimmed.
 */
fun parseHttpHeaders(lines: List<String>): List<Pair<String, String>> {
    val result = mutableListOf<Pair<String, String>>()
    for (line in lines) {
        if (line.isBlank()) break
        val colonIndex = line.indexOf(':')
        if (colonIndex <= 0) continue
        val name = line.substring(0, colonIndex).trim()
        val value = line.substring(colonIndex + 1).trim()
        result.add(name to value)
    }
    return result
}
