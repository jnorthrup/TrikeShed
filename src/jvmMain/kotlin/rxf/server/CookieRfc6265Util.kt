package rxf.server

import borg.trikeshed.lib.ByteSeries
import borg.trikeshed.common.BFrag as ByteBuffer

import java.io.Serializable
import java.net.URLDecoder
import java.nio.Buffer

import java.util.*
import borg.trikeshed.lib.Join as Pair



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
    /**
     * returns an array of bytes
     */
    Name {
        init {
            token = null
        }

        override fun value(token: ByteBuffer): Serializable {
            var input = token
            input = input.duplicate().rewind() as ByteBuffer
            do {
                while (input.hasRemaining() && Character.isWhitespace((input.mark() as ByteBuffer).get().toInt()));
                val begin = input.reset().position()
                while (input.hasRemaining() && '='.code.toByte() != (input.mark() as ByteBuffer).get());

                return ByteBuffer.allocate(
                    (input.reset().flip().position(begin) as ByteBuffer).slice().limit()
                ).put(input).array()
            } while (input.hasRemaining())
        }
    },
    Value {
        init {
            token = null
        }

        override fun value(input: ByteBuffer): Serializable {
            var input = input
            input = input.duplicate().rewind() as ByteBuffer
            do {
                while (input.hasRemaining() && '='.code.toByte() != input.get());
                while (input.hasRemaining() && Character.isWhitespace((input.mark() as ByteBuffer).get().toInt()));
                val begin = input.reset().position()
                while (input.hasRemaining() && ';'.code.toByte() != (input.mark() as ByteBuffer).get());

                return ByteBuffer.allocate(
                    (input.reset().flip().position(begin) as ByteBuffer).slice().limit()
                ).put(input).array()
            } while (input.hasRemaining())
        }
    },

    /**
     * 5.2.1.  The Expires Attribute
     *
     *
     * If the attribute-name case-insensitively matches the string
     * "Expires", the user agent MUST process the cookie-av as follows.
     *
     *
     * Let the expiry-time be the result of parsing the attribute-value as
     * cookie-date (see Section 5.1.1).
     *
     *
     * If the attribute-value failed to parse as a cookie date, ignore the
     * cookie-av.
     *
     *
     * If the expiry-time is later than the last date the user agent can
     * represent, the user agent MAY replace the expiry-time with the last
     * representable date.
     *
     *
     * If the expiry-time is earlier than the earliest date the user agent
     * can represent, the user agent MAY replace the expiry-time with the
     * earliest representable date.
     *
     *
     * Append an attribute to the cookie-attribute-list with an attribute-
     * name of Expires and an attribute-value of expiry-time.
     */
    Expires {
        override fun value(input: ByteBuffer): Serializable? {
            var input = input
            input = input.slice()
            while (input.hasRemaining() && Character.isWhitespace((input.mark() as ByteBuffer).get().toInt()));
            input = (input.reset() as ByteBuffer).slice()
            var b: Byte
            while (input.hasRemaining() && !Character.isWhitespace((input.mark() as ByteBuffer).get().also { b = it }
                    .toInt())
                && '='.code.toByte() != b
            );

            val position = input.reset().position()
            val limit = token!!.limit()

            if (position == limit) {
                while (input.hasRemaining() && '='.code.toByte() != input.get());

                val parseme = HttpMethod.UTF8.decode(input.slice())
                var date: Date? = null
                try {
                    date = DateHeaderParser.parseDate(parseme.toString().trim { it <= ' ' })
                } catch (e: Exception) {
                }
                return date
            }

            return null
        }
    },

    /**
     * 5.2.2.  The Max-Age Attribute
     *
     *
     * If the attribute-name case-insensitively matches the string "Max-
     * Age", the user agent MUST process the cookie-av as follows.
     *
     *
     * If the first character of the attribute-value is not a DIGIT or a "-"
     * character, ignore the cookie-av.
     *
     *
     * If the remainder of attribute-value contains a non-DIGIT character,
     * ignore the cookie-av.
     *
     *
     * Let delta-seconds be the attribute-value converted to an integer.
     *
     *
     * If delta-seconds is less than or equal to zero (0), let expiry-time
     * be the earliest representable date and time.  Otherwise, let the
     * expiry-time be the current date and time plus delta-seconds seconds.
     *
     *
     * Append an attribute to the cookie-attribute-list with an attribute-
     * name of Max-Age and an attribute-value of expiry-time.
     */
    `Max$2dAge` {
        override fun value(input: ByteSeries): Serializable? {
            var input = input
            input = input.slice()
            while (input.hasRemaining() && Character.isWhitespace((input.mark() as ByteBuffer).get().toInt()));
            input = (input.reset() as ByteBuffer).slice()
            var b: Byte
            while (input.hasRemaining() && !Character.isWhitespace((input.mark() as ByteBuffer).get().also { b = it }
                    .toInt())
                && '='.code.toByte() != b
            );

            val position = input.reset().position()
            val limit = token!!.limit()

            if (position == limit) {
                while (input.hasRemaining() && '='.code.toByte() != input.get());

                val parseme = HttpMethod.UTF8.decode(input.slice())
                var l: Long? = null
                try {
                    l = parseme.toString().trim { it <= ' ' }.toLong()
                } catch (e: NumberFormatException) {
                }
                return l
            }

            return null
        }
    },

    /**
     * 5.2.3.  The Domain Attribute
     *
     *
     * If the attribute-name case-insensitively matches the string "Domain",
     * the user agent MUST process the cookie-av as follows.
     *
     *
     * If the attribute-value is empty, the behavior is undefined.  However,
     * the user agent SHOULD ignore the cookie-av entirely.
     *
     *
     * If the first character of the attribute-value string is %x2E ("."):
     *
     *
     * Let cookie-domain be the attribute-value without the leading %x2E
     * (".") character.
     *
     *
     * Otherwise:
     *
     *
     * Let cookie-domain be the entire attribute-value.
     *
     *
     * Convert the cookie-domain to lower case.
     *
     *
     * Append an attribute to the cookie-attribute-list with an attribute-
     * name of Domain and an attribute-value of cookie-domain.
     */
    Domain {
        override fun value(input: ByteBuffer): Serializable? {
            var input = input
            input = input.slice()
            while (input.hasRemaining() && Character.isWhitespace((input.mark() as ByteBuffer).get().toInt()));
            input = (input.reset() as ByteBuffer).slice()
            var b: Byte
            while (input.hasRemaining() && !Character.isWhitespace((input.mark() as ByteBuffer).get().also { b = it }
                    .toInt())
                && '='.code.toByte() != b
            );

            val position = input.reset().position()
            val limit = token!!.limit()

            if (position == limit) {
                while (input.hasRemaining() && '='.code.toByte() != input.get());

                return ByteBuffer.allocate((input.slice().also { input = it }).limit()).put(input).array()
            }

            return null
        }
    },

    /**
     * 5.2.4.  The Path Attribute
     *
     *
     * If the attribute-name case-insensitively matches the string "Path",
     * the user agent MUST process the cookie-av as follows.
     *
     *
     * If the attribute-value is empty or if the first character of the
     * attribute-value is not %x2F ("/"):
     *
     *
     * Let cookie-path be the default-path.
     *
     *
     * Otherwise:
     *
     *
     * Let cookie-path be the attribute-value.
     *
     *
     * Append an attribute to the cookie-attribute-list with an attribute-
     * name of Path and an attribute-value of cookie-path.
     */
    Path {
        override fun value(input: ByteBuffer): Serializable? {
            var input = input
            input = input.slice()
            while (input.hasRemaining() && Character.isWhitespace((input.mark() as ByteBuffer).get().toInt()));
            input = (input.reset() as ByteBuffer).slice()
            var b: Byte
            while (input.hasRemaining() && !Character.isWhitespace((input.mark() as ByteBuffer).get().also { b = it }
                    .toInt())
                && '='.code.toByte() != b
            );

            val position = input.reset().position()
            val limit = token!!.limit()

            if (position == limit) {
                while (input.hasRemaining() && '='.code.toByte() != input.get());

                return ByteBuffer.allocate((input.slice().also { input = it }).limit()).put(input).array()
            }

            return null
        }
    },

    /**
     * 5.2.5.  The Secure Attribute
     *
     *
     * If the attribute-name case-insensitively matches the string "Secure",
     * the user agent MUST append an attribute to the cookie-attribute-list
     * with an attribute-name of Secure and an empty attribute-value.
     */
    Secure {
        override fun value(input: ByteBuffer): Serializable? {
            input.rewind()
            val tok = token!!.duplicate()
            var b: Byte
            do {
                while (input.hasRemaining() && Character.isWhitespace((input.mark() as ByteBuffer).get().toInt()));
                tok.rewind()
                while (tok.hasRemaining() && input.hasRemaining()
                    && tok.get() == input.get().lowercaseChar()
                );
                if (!tok.hasRemaining()) {
                    var keep = false
                    while (input.hasRemaining() && ';'.code.toByte() != ((input.mark() as ByteBuffer).get()
                            .also { b = it }) && (Character.isWhitespace(b.toInt()).also { keep = it })
                    );
                    if (keep) return java.lang.Boolean.TRUE
                }
            } while (input.hasRemaining())

            return null
        }
    },

    /**
     * 5.2.6.  The HttpOnly Attribute
     *
     *
     * If the attribute-name case-insensitively matches the string
     * "HttpOnly", the user agent MUST append an attribute to the cookie-
     * attribute-list with an attribute-name of HttpOnly and an empty
     * attribute-value.
     */
    HttpOnly {
        override fun value(input: ByteBuffer): Serializable? {
            input.rewind()
            val tok = token!!.duplicate()
            var b: Byte
            do {
                while (input.hasRemaining() && Character.isWhitespace((input.mark() as ByteBuffer).get().toInt()));
                tok.rewind()
                while (tok.hasRemaining() && input.hasRemaining()
                    && tok.get() == input.get().lowercaseChar()
                );
                if (!tok.hasRemaining()) {
                    var keep = false
                    while (input.hasRemaining() && ';'.code.toByte() != ((input.mark() as ByteBuffer).get()
                            .also { b = it }) && (Character.isWhitespace(b.toInt()).also { keep = it })
                    );
                    if (keep) {
                        return java.lang.Boolean.TRUE
                    }
                }
            } while (input.hasRemaining())

            return null
        }
    };

    val key: String = URLDecoder.decode(name.replace('$', '%')).lowercase(Locale.getDefault())
    var token: ByteBuffer? = HttpMethod.UTF8.encode(key)

    abstract fun value(token: ByteBuffer): Serializable?

    companion object {
        fun parseSetCookie(input: ByteBuffer): EnumMap<CookieRfc6265Util, Serializable> {
            val a = ArrayList<ByteBuffer>()
            while (input.hasRemaining()) {
                val begin = input.position()
                var b: Byte = 0
                while (input.hasRemaining() && ';'.code.toByte() != ((input.mark() as ByteBuffer).get()
                        .also { b = it })
                );
                a.add(
                    ((if (b == ';'.code.toByte()) input.duplicate().reset() else input.duplicate()).flip()
                        .position(begin) as ByteBuffer).slice()
                )
            }
            val res =
                EnumMap<CookieRfc6265Util, Serializable>(CookieRfc6265Util::class.java)
            val iterator: Iterator<ByteBuffer> = a.iterator()
            val next = iterator.next()
            val n = Name.value(next)!!
            res[Name] = n
            val v = Value.value(next)!!
            res[Value] = v

            while (iterator.hasNext()) {
                val byteBuffer = iterator.next()
                val values = entries.toTypedArray()
                for (i in 2 until values.size) {
                    val cookieRfc6265Util = values[i]
                    if (!res.containsKey(cookieRfc6265Util)) {
                        val value = cookieRfc6265Util.value(byteBuffer.rewind() as ByteBuffer)
                        if (null != value) {
                            res[cookieRfc6265Util] = value
                        }
                    }
                }
            }
            return res
        }

        /**
         * @param filter ByteBuffers as keys for cookies
         * @param input  unescaped bytes split by ';'
         * @return slist of cookies
         */
        fun parseCookie(
            input: ByteBuffer,
            vararg filter: ByteBuffer
        ): Pair<Pair<ByteBuffer, ByteBuffer>, Pair?>? {
            var ret: Pair<*, *>? = null
            val buf = input.duplicate().slice()

            while (buf.hasRemaining()) {
                while (buf.hasRemaining() && Character.isWhitespace((buf.mark() as ByteBuffer).get().toInt()));
                val keyBegin = buf.reset().position()

                while (buf.hasRemaining() && '='.code.toByte() != (buf.mark() as ByteBuffer).get());
                val ckey = (buf.duplicate().reset().flip().position(keyBegin) as ByteBuffer).slice()
                while (buf.hasRemaining() && Character.isWhitespace((buf.mark() as ByteBuffer).get().toInt()));
                val vBegin = buf.reset().position()

                while (buf.hasRemaining()) {
                    when ((buf.mark() as ByteBuffer).get()) {
                        ';', '\r', '\n' -> {}
                        else -> continue
                    }
                    break
                }
                if (filter.size > 0) {
                    for (filt in filter) {
                        if (ckey.limit() == filt.limit()) {
                            ckey.mark()
                            filt.rewind()
                            while (filt.hasRemaining() && ckey.hasRemaining() && filt.get() == ckey.get());
                            if (!filt.hasRemaining() && !ckey.hasRemaining()) {
                                ret =
                                    Pair<Pair<Buffer, ByteBuffer>, Pair<*, *>?>(
                                        Pair(
                                            ckey.reset(), (buf.duplicate().reset().flip()
                                                .position(vBegin) as ByteBuffer).slice()
                                        ), ret
                                    )
                                break
                            }
                        }
                    }
                } else ret =
                    Pair(
                        Pair(
                            ckey, (buf.duplicate().reset().flip().position(vBegin) as ByteBuffer)
                                .slice()
                        ), ret
                    )
            }
            return ret as Pair<Pair<ByteBuffer, ByteBuffer>, Pair?>?
        }
    }
}