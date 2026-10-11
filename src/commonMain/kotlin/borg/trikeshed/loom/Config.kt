package borg.trikeshed.loom

import borg.trikeshed.lib.*

/**
 * cocaine-rats crates/loom-mesh/src/config.rs Config: the trust fields the signed objects read. The
 * node's own fields (listen, store, key_file, limits, timers) and its JSON preflight come with the node.
 */
class Config(
    val id: String,
    val members: Series<Member>,
    val settlement_authority: String? = null,
    val gcs: GcsConfig? = null,
) {
    fun member(id: String): Member = members.view.firstOrNull { it.id == id } ?: error("unknown identity")
}

/** RFC 1918 or loopback IPv4, unique-local (fc00::/7) or loopback IPv6; [ip] holds 4 or 16 octets. */
fun private_test_ip(ip: ByteArray): Boolean {
    val o = IntArray(ip.size) { ip[it].toInt() and 0xff }
    return if (o.size == 4) o[0] == 10 || o[0] == 172 && o[1] in 16..31 || o[0] == 192 && o[1] == 168 || o[0] == 127
    else o[0] and 0xfe == 0xfc || (0..14).all { o[it] == 0 } && o[15] == 1
}

/**
 * The url crate's WHATWG parse of [value] as a special URL, then https anywhere, or http to a loopback IP literal (a
 * private one under [insecure_local_test_only]) when either flag allows it; no credentials, query, fragment or path
 * other than "/". Returns the URL's serialization, `scheme://host[:port]/`. A scheme other than http or https is
 * refused as unsafe without being parsed.
 */
fun validate_url(value: String, allow_loopback_http: Boolean, insecure_local_test_only: Boolean): String {
    if (value.encodeToByteArray().size > 512) error("URL size")
    val input = value.trim { it <= ' ' }.filterNot { it == '\t' || it == '\n' || it == '\r' }
    val colon = input.indexOf(':')
    if (colon < 1 || input[0].lowercaseChar() !in 'a'..'z' ||
        !input.substring(0, colon).all { it.lowercaseChar() in 'a'..'z' || it in '0'..'9' || it in "+-." }
    ) error("invalid URL")
    val scheme = input.substring(0, colon).lowercase()
    if (scheme != "http" && scheme != "https") error("unsafe URL")
    val rest = input.substring(colon + 1).trimStart('/', '\\')
    val end = rest.indexOfFirst { it == '/' || it == '\\' || it == '?' || it == '#' }.let { if (it < 0) rest.length else it }
    val authority = rest.substring(0, end)
    val credentials = authority.substringBeforeLast('@', "")
    val hostPort = authority.substringAfterLast('@')
    if (hostPort.startsWith('[')) TODO("validate_url: IPv6 host literals (the HTX reactor connects over AF_INET only)")
    val host = hostPort.substringBefore(':')
    val portText = hostPort.substringAfter(':', "")
    if (host.isEmpty() || !portText.all { it in '0'..'9' } || portText.trimStart('0').length > 5) error("invalid URL")
    val port = if (portText.isEmpty()) null else portText.trimStart('0').ifEmpty { "0" }.toInt()
    if (port != null && port > 65535) error("invalid URL")
    if (host.any { it.code >= 0x80 || it == '%' } || host.split('.').any { it.lowercase().startsWith("xn--") })
        TODO("validate_url: IDNA (UTS #46) mapping of a non-ASCII, percent-encoded or punycode host")
    val domain = host.lowercase()
    if (domain.any { it.code <= 0x20 || it.code == 0x7f || it in "#/:<>?@[\\]^|%" }) error("invalid URL")
    val ip = if (ends_in_a_number(domain)) parse_ipv4addr(domain) ?: error("invalid URL") else null
    val tail = rest.substring(end)
    val pathEnd = tail.indexOfFirst { it == '?' || it == '#' }.let { if (it < 0) tail.length else it }
    val segments = if (pathEnd == 0) listOf("") else tail.substring(1, pathEnd).split('/', '\\')
    val path = mutableListOf<String>()
    for ((i, segment) in segments.withIndex()) when (segment.lowercase()) {
        "..", ".%2e", "%2e.", "%2e%2e" -> {
            path.removeLastOrNull()
            if (i == segments.lastIndex) path += ""
        }
        ".", "%2e" -> if (i == segments.lastIndex) path += ""
        else -> path += segment
    }
    val http_loopback = scheme == "http" && (allow_loopback_http || insecure_local_test_only) && ip != null &&
        (ip[0] == 127.toByte() || insecure_local_test_only && private_test_ip(ip))
    if (scheme != "https" && !http_loopback || credentials.substringBefore(':').isNotEmpty() ||
        credentials.substringAfter(':', "").isNotEmpty() || pathEnd < tail.length || path != listOf("")
    ) error("unsafe URL")
    val hostText = ip?.joinToString(".") { (it.toInt() and 0xff).toString() } ?: domain
    return "$scheme://$hostText${if (port == null || port == (if (scheme == "http") 80 else 443)) "" else ":$port"}/"
}

/** WHATWG ends-in-a-number: the last label, past one trailing dot, is decimal digits or an IPv4 number. */
fun ends_in_a_number(domain: String): Boolean {
    val last = domain.split('.').let { if (it.size > 1 && it.last().isEmpty()) it.dropLast(1) else it }.last()
    return last.isNotEmpty() && last.all { it in '0'..'9' } || parse_ipv4number(last) != null
}

/** WHATWG IPv4 number: 0x hex, leading-zero octal or decimal; a value past 2^32 - 1 saturates, failing the address. */
fun parse_ipv4number(text: String): Long? {
    if (text.isEmpty()) return null
    val (radix, digits) = when {
        text.startsWith("0x") || text.startsWith("0X") -> 16 to text.substring(2)
        text.length > 1 && text[0] == '0' -> 8 to text.substring(1)
        else -> 10 to text
    }
    var value = 0L
    for (c in digits) value = minOf(value * radix + (c.digitToIntOrNull(radix) ?: return null), 1L shl 32)
    return value
}

/** WHATWG IPv4 parser: at most four numbers, the last filling the remaining octets. */
fun parse_ipv4addr(domain: String): ByteArray? {
    val parts = domain.split('.').let { if (it.size > 1 && it.last().isEmpty()) it.dropLast(1) else it }
    if (parts.size > 4) return null
    val numbers = parts.map { parse_ipv4number(it) ?: return null }
    if (numbers.dropLast(1).any { it > 255 } || numbers.last() >= 1L shl (8 * (5 - numbers.size))) return null
    var address = numbers.last()
    for ((i, n) in numbers.dropLast(1).withIndex()) address += n shl (8 * (3 - i))
    return ByteArray(4) { (address shr (8 * (3 - it))).toByte() }
}
