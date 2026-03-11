package rxf.server

import borg.trikeshed.lib.Join as Pair
import java.io.Serializable
import java.net.URLDecoder
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.EnumMap
import java.util.Locale
import java.util.TimeZone

/**
 * This enum defines the HTTP Cookie and Set-Cookie header fields.
 * Using the Set-Cookie header field, an HTTP server can pass name/value
 * pairs and associated metadata (called cookies) to a user agent.  When
 * the user agent makes subsequent requests to the server, the user
 * agent uses the metadata and other information to determine whether to
 * return the name/value pairs in the Cookie header.
 *
 *
 * Although simple on their surface, cookies have a number of
 * complexities.  For example, the server indicates a scope for each
 * cookie when sending it to the user agent.  The scope indicates the
 * maximum amount of time in which the user agent should return the
 * cookie, the servers to which the user agent should return the cookie,
 * and the URI schemes for which the cookie is applicable.
 *
 *
 * For historical reasons, cookies contain a number of security and
 * privacy infelicities.  For example, a server can indicate that a
 * given cookie is intended for "secure" connections, but the Secure
 * attribute does not provide integrity in the presence of an active
 * network attacker.  Similarly, cookies for a given host are shared
 * across all the ports on that host, even though the usual "same-origin
 * policy" used by web browsers isolates content retrieved via different
 * ports.
 *
 *
 * There are two audiences for this specification: developers of cookie-
 * generating servers and developers of cookie-consuming user agents.
 *
 *
 * To maximize interoperability with user agents, servers SHOULD limit
 * themselves to the well-behaved profile defined in Section 4 when
 * generating cookies.
 *
 *
 * User agents MUST implement the more liberal processing rules defined
 * in Section 5, in order to maximize interoperability with existing
 * servers that do not conform to the well-behaved profile defined in
 * Section 4.
 *
 *
 * This document specifies the syntax and semantics of these headers as
 * they are actually used on the Internet.  In particular, this document
 * does not create new syntax or semantics beyond those in use today.
 * The recommendations for cookie generation provided in Section 4
 * represent a preferred subset of current server behavior, and even the
 * more liberal cookie processing algorithm provided in Section 5 does
 * not recommend all of the syntactic and semantic variations in use
 * today.  Where some existing software differs from the recommended
 * protocol in significant ways, the document contains a note explaining
 * the difference.
 *
 *
 * Prior to this document, there were at least three descriptions of
 * cookies: the so-called "Netscape cookie specification" [Netscape],
 * RFC 2109 [RFC2109], and RFC 2965 [RFC2965].  However, none of these
 * documents describe how the Cookie and Set-Cookie headers are actually
 * used on the Internet (see [Kri2001] for historical context).  In
 * relation to previous IETF specifications of HTTP state management
 * mechanisms, this document requests the following actions:
 *   1.  Change the status of [RFC2109] to Historic (it has already been obsoleted by [RFC2965]).
 *  1.  Change the status of [RFC2965] to Historic.
 *  1.  Indicate that [RFC2965] has been obsoleted by this document.
 *
 *
 * In particular, in moving RFC 2965 to Historic and obsoleting it, this
 * document deprecates the use of the Cookie2 and Set-Cookie2 header
 * fields.
 */
enum class CookieRfc6265Util {
    Name {
        init {
            token = null
        }

        override fun value(token: ByteBuffer): Serializable? =
            splitNameValue(token)?.a?.let(::byteBufferToArray)
    },

    Value {
        init {
            token = null
        }

        override fun value(token: ByteBuffer): Serializable? =
            splitNameValue(token)?.b?.let(::byteBufferToArray)
    },

    Expires {
        override fun value(input: ByteBuffer): Serializable? =
            attributeValueString(input, key)?.let(::parseCookieDate)
    },

    `Max$2dAge` {
        override fun value(input: ByteBuffer): Serializable? =
            attributeValueString(input, key)?.trim()?.toLongOrNull()
    },

    Domain {
        override fun value(input: ByteBuffer): Serializable? =
            attributeValueBuffer(input, key)?.let(::byteBufferToArray)
    },

    Path {
        override fun value(input: ByteBuffer): Serializable? =
            attributeValueBuffer(input, key)?.let(::byteBufferToArray)
    },

    Secure {
        override fun value(input: ByteBuffer): Serializable? =
            if (matchesFlagAttribute(input, key)) java.lang.Boolean.TRUE else null
    },

    HttpOnly {
        override fun value(input: ByteBuffer): Serializable? =
            if (matchesFlagAttribute(input, key)) java.lang.Boolean.TRUE else null
    };

    val key: String =
        URLDecoder.decode(name.replace('$', '%'), StandardCharsets.UTF_8.name()).lowercase(Locale.ROOT)

    var token: ByteBuffer? = ByteBuffer.wrap(key.toByteArray(StandardCharsets.UTF_8)).asReadOnlyBuffer()

    abstract fun value(token: ByteBuffer): Serializable?

    companion object {
        fun parseSetCookie(input: ByteBuffer): EnumMap<CookieRfc6265Util, Serializable> {
            val result = EnumMap<CookieRfc6265Util, Serializable>(CookieRfc6265Util::class.java)
            val segments = splitSegments(input)
            if (segments.isEmpty()) {
                return result
            }

            Name.value(segments.first())?.let { result[Name] = it }
            Value.value(segments.first())?.let { result[Value] = it }

            for (segment in segments.drop(1)) {
                for (attribute in entries) {
                    if (attribute == Name || attribute == Value || result.containsKey(attribute)) {
                        continue
                    }

                    attribute.value(segment)?.let { result[attribute] = it }
                }
            }

            return result
        }

        fun parseCookie(
            input: ByteBuffer,
            vararg filter: ByteBuffer
        ): Pair<Pair<ByteBuffer, ByteBuffer>, Pair<*, *>?>? {
            var result: Pair<Pair<ByteBuffer, ByteBuffer>, Pair<*, *>?>? = null

            for (segment in splitSegments(input)) {
                val cookie = splitNameValue(segment) ?: continue
                val cookieName = cookie.a
                val cookieValue = cookie.b

                if (filter.isNotEmpty() && filter.none { byteBufferEquals(it, cookieName) }) {
                    continue
                }

                result = Pair(Pair(cookieName, cookieValue), result)
            }

            return result
        }
    }
}

private val emptyByteBuffer: ByteBuffer = ByteBuffer.wrap(ByteArray(0)).asReadOnlyBuffer()

private val cookieDateFormats = arrayOf(
    "EEE, dd MMM yyyy HH:mm:ss zzz",
    "EEE, dd-MMM-yyyy HH:mm:ss zzz",
    "EEE, dd MMM yy HH:mm:ss zzz",
)

private fun splitSegments(input: ByteBuffer): List<ByteBuffer> {
    val source = input.asReadOnlyBuffer()
    val segments = ArrayList<ByteBuffer>()
    var start = source.position()

    for (index in start until source.limit()) {
        if (source.get(index) == ';'.code.toByte()) {
            trimSlice(source, start, index)?.let(segments::add)
            start = index + 1
        }
    }

    trimSlice(source, start, source.limit())?.let(segments::add)
    return segments
}

private fun splitNameValue(segment: ByteBuffer): Pair<ByteBuffer, ByteBuffer>? {
    val separator = indexOf(segment, '='.code.toByte())
    if (separator < 0) {
        return null
    }

    val name = trimSlice(segment, segment.position(), separator) ?: return null
    val value = trimSlice(segment, separator + 1, segment.limit()) ?: emptyByteBuffer.duplicate()
    return Pair(name, value)
}

private fun attributeValueBuffer(segment: ByteBuffer, expectedName: String): ByteBuffer? {
    val separator = indexOf(segment, '='.code.toByte())
    if (separator < 0) {
        return null
    }

    val attributeName = trimSlice(segment, segment.position(), separator)?.let(::byteBufferToString) ?: return null
    if (!attributeName.equals(expectedName, ignoreCase = true)) {
        return null
    }

    return trimSlice(segment, separator + 1, segment.limit()) ?: emptyByteBuffer.duplicate()
}

private fun attributeValueString(segment: ByteBuffer, expectedName: String): String? =
    attributeValueBuffer(segment, expectedName)?.let(::byteBufferToString)

private fun matchesFlagAttribute(segment: ByteBuffer, expectedName: String): Boolean {
    val separator = indexOf(segment, '='.code.toByte())
    if (separator >= 0) {
        val attributeName = trimSlice(segment, segment.position(), separator)?.let(::byteBufferToString) ?: return false
        val attributeValue = trimSlice(segment, separator + 1, segment.limit())
        return attributeName.equals(expectedName, ignoreCase = true) && attributeValue == null
    }

    val attributeName = trimSlice(segment, segment.position(), segment.limit())?.let(::byteBufferToString) ?: return false
    return attributeName.equals(expectedName, ignoreCase = true)
}

private fun trimSlice(source: ByteBuffer, rawStart: Int, rawEnd: Int): ByteBuffer? {
    var start = rawStart
    var end = rawEnd

    while (start < end && source.get(start).toInt().toChar().isWhitespace()) {
        start++
    }
    while (end > start && source.get(end - 1).toInt().toChar().isWhitespace()) {
        end--
    }

    if (start >= end) {
        return null
    }

    val slice = source.asReadOnlyBuffer()
    slice.position(start)
    slice.limit(end)
    return slice.slice().asReadOnlyBuffer()
}

private fun indexOf(source: ByteBuffer, target: Byte): Int {
    for (index in source.position() until source.limit()) {
        if (source.get(index) == target) {
            return index
        }
    }
    return -1
}

private fun byteBufferEquals(left: ByteBuffer, right: ByteBuffer): Boolean {
    val leftCopy = left.asReadOnlyBuffer()
    val rightCopy = right.asReadOnlyBuffer()
    if (leftCopy.remaining() != rightCopy.remaining()) {
        return false
    }

    while (leftCopy.hasRemaining()) {
        if (leftCopy.get() != rightCopy.get()) {
            return false
        }
    }

    return true
}

private fun byteBufferToArray(buffer: ByteBuffer): ByteArray {
    val copy = buffer.asReadOnlyBuffer()
    val bytes = ByteArray(copy.remaining())
    copy.get(bytes)
    return bytes
}

private fun byteBufferToString(buffer: ByteBuffer): String =
    String(byteBufferToArray(buffer), StandardCharsets.UTF_8)

private fun parseCookieDate(value: String): Date? {
    val candidate = value.trim()
    for (pattern in cookieDateFormats) {
        val format = SimpleDateFormat(pattern, Locale.US)
        format.isLenient = true
        format.timeZone = TimeZone.getTimeZone("GMT")
        try {
            return format.parse(candidate)
        } catch (_: Exception) {
        }
    }
    return null
}
