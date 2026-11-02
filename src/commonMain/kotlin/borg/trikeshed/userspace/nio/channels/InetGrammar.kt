package borg.trikeshed.userspace.nio.channels

import borg.trikeshed.lib.*

typealias Port = Int
typealias PortRange = IntRange
/** An address's octets in network order: 4 for IPv4, 16 for IPv6. */
typealias Inet = ByteArray
typealias Ipv4 = Inet
typealias Ipv6 = Inet
typealias Endpoint = Join<Inet, Port>

/**
 * Network address grammar over [CharSeries]: each production consumes its text and returns the value, or leaves the
 * position where it was and returns null.
 *
 *     port       = digit{1,5}                      (0..65535)
 *     portRange  = port [ '-' port ]               (lo <= hi)
 *     octet      = digit{1,3}                      (0..255)
 *     ipv4       = octet '.' octet '.' octet '.' octet
 *     ipv4Range  = ipv4 [ '-' octet ]              (the last octet runs to the second; 192.168.50.201-208)
 *     hex16      = hexdigit{1,4}
 *     ipv6       = RFC 4291 text: eight hex16 joined by ':', one '::' standing for one or more zero groups,
 *                  an ipv4 in place of the last two groups (::ffff:192.168.50.1)
 *     inet       = ipv4 | ipv6
 *     hostInet   = ipv4 | '[' ipv6 ']'
 *     endpoint   = hostInet ':' port
 *     sctpEndpoint = hostInet { ',' hostInet } ':' port      (multihomed: the addresses share the port)
 *     list(p)    = p { ',' p }
 */
fun CharSeries.decimal(maxDigits: Int, max: Int): Int? {
    val start = pos
    var value = 0
    while (hasRemaining && pos - start < maxDigits && this[pos] in '0'..'9') value = value * 10 + (get - '0')
    if (pos == start || value > max || (hasRemaining && this[pos] in '0'..'9')) { pos = start; return null }
    return value
}

fun CharSeries.consume(c: Char): Boolean = if (hasRemaining && this[pos] == c) { pos++; true } else false

fun CharSeries.consume(text: CharSequence): Boolean {
    if (rem < text.length || text.indices.any { this[pos + it] != text[it] }) return false
    pos += text.length
    return true
}

fun CharSeries.port(): Port? = decimal(5, 0xFFFF)

fun CharSeries.portRange(): PortRange? {
    val start = pos
    val lo = port() ?: return null
    if (!consume('-')) return lo..lo
    val hi = port()
    if (hi == null || hi < lo) { pos = start; return null }
    return lo..hi
}

fun CharSeries.octet(): Int? = decimal(3, 255)

fun CharSeries.ipv4(): Ipv4? {
    val start = pos
    val octets = Ipv4(4)
    for (i in octets.indices) {
        if (i > 0 && !consume('.')) { pos = start; return null }
        octets[i] = (octet() ?: run { pos = start; return null }).toByte()
    }
    return octets
}

fun CharSeries.ipv4Range(): Series<Ipv4>? {
    val start = pos
    val first = ipv4() ?: return null
    if (!consume('-')) return s_[first]
    val lo = first[3].toInt() and 0xFF
    val hi = octet()
    if (hi == null || hi < lo) { pos = start; return null }
    return (lo..hi).toSeries() α { last -> first.copyOf().also { it[3] = last.toByte() } }
}

fun CharSeries.hex16(): Int? {
    val start = pos
    var value = 0
    while (hasRemaining && pos - start < 4) value = value * 16 + (this[pos].digitToIntOrNull(16) ?: break).also { pos++ }
    if (pos == start || (hasRemaining && this[pos].digitToIntOrNull(16) != null)) { pos = start; return null }
    return value
}

fun CharSeries.ipv6(): Ipv6? {
    val start = pos
    val head = mutableListOf<Int>()
    val tail = mutableListOf<Int>()
    var compressed = consume("::")
    var needed = false
    while (true) {
        val groups = if (compressed) tail else head
        val dotted = if (head.size + tail.size <= 6) ipv4() else null
        if (dotted != null) {
            groups.add((dotted[0].toInt() and 0xFF shl 8) or (dotted[1].toInt() and 0xFF))
            groups.add((dotted[2].toInt() and 0xFF shl 8) or (dotted[3].toInt() and 0xFF))
            break
        }
        val group = hex16()
        if (group == null) { if (needed) { pos = start; return null }; break }
        groups.add(group)
        needed = false
        if (consume("::")) { if (compressed) { pos = start; return null }; compressed = true }
        else if (consume(':')) needed = true
        else break
    }
    val count = head.size + tail.size
    if (if (compressed) count > 7 else count != 8) { pos = start; return null }
    // head groups from the front, tail groups from the back; the '::' gap stays zero.
    val octets = Ipv6(16)
    fun word(at: Int, value: Int) { octets[2 * at] = (value shr 8).toByte(); octets[2 * at + 1] = value.toByte() }
    for ((at, value) in head.withIndex()) word(at, value)
    for ((at, value) in tail.withIndex()) word(8 - tail.size + at, value)
    return octets
}

fun CharSeries.inet(): Inet? = ipv4() ?: ipv6()

/** An address where a port may follow: ipv4, or ipv6 in brackets. */
fun CharSeries.hostInet(): Inet? {
    val start = pos
    if (!consume('[')) return ipv4()
    val address = ipv6()
    if (address == null || !consume(']')) { pos = start; return null }
    return address
}

fun CharSeries.endpoint(): Endpoint? {
    val start = pos
    val address = hostInet()
    if (address == null || !consume(':')) { pos = start; return null }
    val port = port() ?: run { pos = start; return null }
    return address j port
}

/** unixHost = 'unix:' name      (an AF_UNIX socket name as [unixName] reads it; the rest of the text) */
fun CharSeries.unixHost(): UnixName? {
    val start = pos
    for (c in "unix:") if (!consume(c)) { pos = start; return null }
    val text = StringBuilder()
    while (hasRemaining) text.append(get)
    val name = unixNameOrNull(text.toString())?.takeIf { it.isNotEmpty() }
    if (name == null) pos = start
    return name
}

/** SCTP endpoint (RFC 9260): every transport address of a multihomed association shares one port. */
typealias SctpEndpoint = Join<Series<Inet>, Port>

fun CharSeries.sctpEndpoint(): SctpEndpoint? {
    val start = pos
    val addresses = list { hostInet() }
    if (addresses == null || !consume(':')) { pos = start; return null }
    val port = port() ?: run { pos = start; return null }
    return addresses j port
}

/** The SQE sockaddr of an [Endpoint]: sockaddr_in for 4 octets, sockaddr_in6 for 16. */
val Endpoint.sockaddr: ByteArray get() = if (a.size == 4) sockaddrIpv4(a, b) else sockaddrIpv6(a, b)

fun <T> CharSeries.list(item: CharSeries.() -> T?): Series<T>? {
    val start = pos
    val items = mutableListOf(item() ?: return null)
    while (consume(',')) items.add(item() ?: run { pos = start; return null })
    return items.toSeries()
}

/** [production] over the whole of [text] (surrounding whitespace allowed), or null when any text is left over. */
fun <T> parseWhole(text: CharSequence, production: CharSeries.() -> T?): T? {
    val input = CharSeries(text).trim
    val value = input.production() ?: return null
    return if (input.hasRemaining) null else value
}
