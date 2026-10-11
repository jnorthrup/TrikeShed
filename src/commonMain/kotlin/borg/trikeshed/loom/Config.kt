package borg.trikeshed.loom

import borg.trikeshed.collections.associative.*
import borg.trikeshed.ipns.*
import borg.trikeshed.lib.*
import borg.trikeshed.userspace.*
import borg.trikeshed.userspace.nio.platform.spi.*

/** cocaine-rats crates/loom-mesh/src/config.rs: the node configuration, serde `default` and `deny_unknown_fields`. */
class Config(
    val id: String = "",
    val listen: SocketAddr = SocketAddr.from_str("127.0.0.1:8787")!!,
    val store: String = "",
    val key_file: String = "",
    val members: Series<Member> = emptySeriesOf(),
    val archive: String? = null,
    /** Compatibility for isolated primitive tests, never production archival. */
    val legacy_archive_test_only: Boolean = false,
    /** Historical receipt verification only; not enrolled or contacted. */
    val legacy_archive_read_trust: Member? = null,
    val settlement_authority: String? = null,
    val gcs: GcsConfig? = null,
    val limits: Limits = Limits(),
    val reconcile_ms: ULong = 30_000uL,
    val request_timeout_ms: ULong = 3000uL,
    val replay_capacity: ULong = 512uL,
    val min_age_secs: ULong = 900uL,
    val allow_delete: Boolean = false,
    val allow_loopback_http: Boolean = false,
    val insecure_local_test_only: Boolean = false,
    /** Explicit container listener; disables background peer wakeups. */
    val runpod_serverless: Boolean = false,
) {
    fun member(id: String): Member = members.view.firstOrNull { it.id == id } ?: error("unknown identity")

    fun validate() {
        limits.validate()
        label(id)
        if (limits.lifecycle_headroom_bytes < required_lifecycle_headroom(members.size)) error("lifecycle headroom below lifecycle growth")
        val unspecified = listen.ip.size == 4 && listen.ip.all { it == 0.toByte() }
        if (store.isEmpty() ||
            !(listen.is_loopback() || (runpod_serverless && unspecified) || (insecure_local_test_only && private_test_ip(listen))) ||
            reconcile_ms !in 100uL..3_600_000uL || request_timeout_ms !in 100uL..10_000uL || replay_capacity !in 16uL..512uL ||
            min_age_secs > 31_536_000uL || members.size !in 1..64
        ) error("configuration bounds")
        if (runpod_serverless) {
            if (!unspecified || listen.port == 0 || !store.startsWith('/') || allow_loopback_http || insecure_local_test_only ||
                members.view.count { it.has(Role.Replica) } != 3 || members.view.any { it.has(Role.Archive) }
            ) error("unsafe serverless configuration")
            for (m in members.view.filter { it.has(Role.Replica) }) runpod_origin(m.url ?: error("missing peer URL"))
        }
        val ids = mutableSetOf<String>()
        val keys = mutableSetOf<String>()
        val domains = mutableSetOf<String>()
        val urls = mutableSetOf<String>()
        for (m in members.view) {
            label(m.id)
            if (!ids.add(m.id) || !keys.add(m.key().toHexString()) || m.roles.size == 0 || m.roles.size > 5)
                error("duplicate or empty identity")
            if (m.has(Role.Replica) || m.has(Role.Archive)) {
                label(m.domain)
                if (!domains.add(m.domain) || (m.has(Role.Replica) && m.has(Role.Archive))) error("duplicate domain or mixed custody role")
                val url = validate_url(m.url ?: error("missing peer URL"), allow_loopback_http, insecure_local_test_only)
                if (!urls.add(url.serialization)) error("duplicate peer URL")
            } else if (m.url != null) error("URL without storage role")
        }
        legacy_archive_read_trust?.let { member ->
            label(member.id)
            label(member.domain)
            member.key()
            if (member.roles.size != 1 || member.roles[0] != Role.Archive || member.url != null || archive != null ||
                members.view.any { it.id == member.id || it.public_key == member.public_key }
            ) error("historical archive trust must be read-only and unenrolled")
        }
        settlement_authority?.let { id ->
            val authority = member(id)
            if (!authority.has(Role.Admin) || authority.has(Role.Replica) || authority.has(Role.Archive) || legacy_archive_test_only)
                error("unsafe settlement authority configuration")
        }
        gcs?.let { gcs ->
            gcs.validate()
            if (settlement_authority == null || legacy_archive_test_only || archive != null) error("GCS requires settlement without legacy archive")
        }
        val local = member(id)
        if ((archive != null || members.view.any { it.has(Role.Archive) }) && !legacy_archive_test_only)
            error("legacy archive requires explicit test opt-in")
        if (legacy_archive_test_only && !(allow_loopback_http || insecure_local_test_only)) error("legacy archive requires test transport")
        if (!local.has(Role.Replica) && !local.has(Role.Archive)) error("local storage role missing")
        archive?.let { if (!member(it).has(Role.Archive) || it == id) error("invalid archive") }
        if (allow_delete && ((archive == null && gcs == null) || local.has(Role.Archive))) error("unsafe deletion configuration")
    }

    companion object {
        val fields = s_["id", "listen", "store", "key_file", "members", "archive", "legacy_archive_test_only",
            "legacy_archive_read_trust", "settlement_authority", "gcs", "limits", "reconcile_ms", "request_timeout_ms",
            "replay_capacity", "min_age_secs", "allow_delete", "allow_loopback_http", "insecure_local_test_only", "runpod_serverless"]

        /** Read only the protected configuration, without reading a signing key. */
        fun read(path: String): Config {
            val ring = ring()
            try {
                return config_json(ring.protected_file(path, 131072)).also { it.validate() }
            } finally {
                ring.closeNow()
            }
        }

        /** The configuration and its local Ed25519 signing key (32 raw bytes) from the protected key file. */
        fun load(path: String): Join<Config, ByteArray> {
            val config = read(path)
            val ring = ring()
            val bytes = try {
                ring.protected_file(config.key_file, 32)
            } finally {
                ring.closeNow()
            }
            if (bytes.size != 32) {
                bytes.fill(0)
                error("signing key must be 32 raw bytes")
            }
            if (!Ed25519.publicKey(bytes).contentEquals(config.member(config.id).key())) {
                bytes.fill(0)
                error("local signing key mismatch")
            }
            return config j bytes
        }

        /** serde's derived `Deserialize` over a JSON value: absent fields take [Config]'s defaults. */
        fun deserialize(value: Any): Config {
            val f = deserialize_struct(value, fields)
            val d = Config()
            return Config(
                id = f[0]?.deserialize_string("id") ?: d.id,
                listen = f[1]?.let { SocketAddr.from_str(it.deserialize_string("listen")) ?: error("invalid socket address") } ?: d.listen,
                store = f[2]?.deserialize_string("store") ?: d.store,
                key_file = f[3]?.deserialize_string("key_file") ?: d.key_file,
                members = f[4]?.let { value ->
                    if (value !is List<*>) error("invalid type: `members`, expected a sequence")
                    Array(value.size) { Member.deserialize(value[it]!!) }.toSeries()
                } ?: d.members,
                archive = f[5]?.deserialize_option { it.deserialize_string("archive") },
                legacy_archive_test_only = f[6]?.deserialize_bool("legacy_archive_test_only") ?: d.legacy_archive_test_only,
                legacy_archive_read_trust = f[7]?.deserialize_option { Member.deserialize(it) },
                settlement_authority = f[8]?.deserialize_option { it.deserialize_string("settlement_authority") },
                gcs = f[9]?.deserialize_option { GcsConfig.deserialize(it) },
                limits = f[10]?.let { Limits.deserialize(it) } ?: d.limits,
                reconcile_ms = f[11]?.deserialize_u64("reconcile_ms") ?: d.reconcile_ms,
                request_timeout_ms = f[12]?.deserialize_u64("request_timeout_ms") ?: d.request_timeout_ms,
                replay_capacity = f[13]?.deserialize_u64("replay_capacity") ?: d.replay_capacity,
                min_age_secs = f[14]?.deserialize_u64("min_age_secs") ?: d.min_age_secs,
                allow_delete = f[15]?.deserialize_bool("allow_delete") ?: d.allow_delete,
                allow_loopback_http = f[16]?.deserialize_bool("allow_loopback_http") ?: d.allow_loopback_http,
                insecure_local_test_only = f[17]?.deserialize_bool("insecure_local_test_only") ?: d.insecure_local_test_only,
                runpod_serverless = f[18]?.deserialize_bool("runpod_serverless") ?: d.runpod_serverless,
            )
        }

        fun ring(): FunctionalUringFacade = FunctionalUringFacade(8, openUserspaceChannelBackend(8))
    }
}

/** preflight.rs `config_json`: the Confix-checked JSON, then serde; every schema refusal reads the same. */
fun config_json(bytes: ByteArray): Config {
    val value = checked(bytes).a
    return try {
        Config.deserialize(value)
    } catch (refused: IllegalStateException) {
        error("invalid configuration schema")
    }
}

fun Any.deserialize_bool(field: String): Boolean = this as? Boolean ?: error("invalid type: `$field`, expected a boolean")

/** `Option<T>`: JSON null is None. */
fun <T> Any.deserialize_option(some: (Any) -> T): T? = if (this == Null) null else some(this)

/**
 * The bytes of a regular file owned by the effective user, without group or other permission bits,
 * with one link and at most [max] bytes, opened without following a symlink.
 */
fun FunctionalUringFacade.protected_file(path: String, max: Int): ByteArray {
    val fd = open(path, UringOp.O_RDONLY or UringOp.O_NOFOLLOW, 0)
    try {
        val meta = metadata(fd)
        if (meta.stx_mode and UringOp.S_IFMT != UringOp.S_IFREG || meta.stx_uid != platformGeteuid() ||
            meta.stx_mode and 0x3f != 0 || meta.stx_nlink != 1u || meta.stx_size > max.toULong()
        ) error("unprotected configuration or key file")
        val bytes = read_to_end(fd, max + 1)
        if (bytes.size > max) {
            bytes.fill(0)
            error("configuration or key size")
        }
        return bytes
    } finally {
        close(fd)
    }
}

/**
 * Encoded upper bound for all lifecycle fields on one live record. Replay is independently reserved
 * once per store, not guessed per business request.
 */
fun required_lifecycle_headroom(members: Int): ULong = lifecycle_headroom_bound(members)

fun required_replay_bytes(config: Config): ULong {
    val source = config.members.view.maxOfOrNull { it.id.encodeToByteArray().size } ?: 64
    val row = itemArrayOf(Item.Str("a".repeat(source)), Item.Bin(ByteArray(32)), Item.Num(Long.MAX_VALUE))
    return Cbor.encode(
        itemArrayOf(
            Item.Str("loom-replay/v1"),
            Item.Arr(config.replay_capacity.toInt() j { _: Int -> row }),
            Item.Bin(ByteArray(32)),
        )
    ).size.toULong()
}

/** Ipv4Addr::is_private or is_loopback; Ipv6Addr::is_unique_local or is_loopback. */
fun private_test_ip(ip: SocketAddr): Boolean = ip.is_loopback() || if (ip.ip.size == 4) {
    val a = ip.ip[0].toInt() and 0xff
    val b = ip.ip[1].toInt() and 0xff
    a == 10 || (a == 172 && b in 16..31) || (a == 192 && b == 168)
} else ip.ip[0].toInt() and 0xfe == 0xfc

/**
 * The url crate's parse of [value], then https anywhere, or http to a loopback IP literal (a private one under
 * [insecure_local_test_only]) when either flag allows it; no credentials, query, fragment or path other than "/".
 */
fun validate_url(value: String, allow_loopback_http: Boolean, insecure_local_test_only: Boolean): Url {
    if (value.encodeToByteArray().size > 512) error("URL size")
    val url = Url.parse(value) ?: error("invalid URL")
    val http_loopback = url.scheme == "http" && (allow_loopback_http || insecure_local_test_only) &&
        url.host?.trim('[', ']')?.let { SocketAddr.ip_from_str(it) }?.let { ip ->
            ip.is_loopback() || (insecure_local_test_only && private_test_ip(ip))
        } == true
    if ((url.scheme != "https" && !http_loopback) || url.host == null || url.username.isNotEmpty() || url.password != null ||
        url.query != null || url.fragment != null || url.path != "/"
    ) error("unsafe URL")
    return url
}

/** bootstrap.rs `runpod_origin`: an exact RunPod serverless endpoint origin, without a port. */
fun runpod_origin(value: String): Url {
    if (!valid_runpod_endpoint_origin(value)) error("invalid RunPod endpoint origin")
    val url = validate_url(value, false, false)
    if (url.serialization != value || url.port != null) error("invalid RunPod endpoint origin")
    return url
}

/**
 * Rust std `SocketAddr` as `FromStr` reads it: `a.b.c.d:port` (decimal octets without a leading
 * zero) or `[ipv6%scope]:port`. [ip] holds 4 or 16 octets in network order.
 */
class SocketAddr(val ip: ByteArray, val port: Int, val scope_id: UInt = 0u) {
    fun is_loopback(): Boolean = if (ip.size == 4) ip[0] == 127.toByte()
    else (0 until 15).all { ip[it] == 0.toByte() } && ip[15] == 1.toByte()

    companion object {
        fun from_str(text: String): SocketAddr? = Parser(text).run { all { socket_addr() } }

        /** `IpAddr::from_str`, as a [SocketAddr] of port 0. */
        fun ip_from_str(text: String): SocketAddr? =
            Parser(text).run { all { (ipv4() ?: ipv6())?.let { SocketAddr(it, 0) } } }
    }

    /** core::net::parser over the ASCII bytes. */
    class Parser(text: String) {
        val bytes = text.encodeToByteArray()
        var at = 0

        fun <T> all(read: () -> T?): T? = read()?.takeIf { at == bytes.size }

        fun <T> atomically(read: () -> T?): T? {
            val start = at
            return read() ?: run { at = start; null }
        }

        fun char(c: Char): Unit? = atomically { if (at < bytes.size && bytes[at] == c.code.toByte()) at++.let { } else null }

        fun number(radix: Int, max_digits: Int?, allow_zero_prefix: Boolean, max: Long): Long? = atomically {
            var result = 0L
            var digits = 0
            val leading_zero = at < bytes.size && bytes[at] == '0'.code.toByte()
            while (at < bytes.size) {
                val b = bytes[at].toInt() and 0xff
                val digit = if (b < 128) b.toChar().digitToIntOrNull(radix) else null
                if (digit == null) break
                at++
                result = result * radix + digit
                if (result > max) return@atomically null
                digits++
                if (max_digits != null && digits > max_digits) return@atomically null
            }
            if (digits == 0 || (!allow_zero_prefix && leading_zero && digits > 1)) null else result
        }

        fun ipv4(): ByteArray? = atomically {
            val groups = ByteArray(4)
            for (i in 0 until 4) {
                if (i > 0 && char('.') == null) return@atomically null
                groups[i] = (number(10, 3, false, 255) ?: return@atomically null).toByte()
            }
            groups
        }

        fun ipv6(): ByteArray? = atomically {
            fun groups(into: IntArray, limit: Int): Join<Int, Boolean> {
                for (i in 0 until limit) {
                    if (i < limit - 1) {
                        val v4 = atomically { if (i > 0 && char(':') == null) null else ipv4() }
                        if (v4 != null) {
                            into[i] = (v4[0].toInt() and 0xff shl 8) or (v4[1].toInt() and 0xff)
                            into[i + 1] = (v4[2].toInt() and 0xff shl 8) or (v4[3].toInt() and 0xff)
                            return (i + 2) j true
                        }
                    }
                    into[i] = (atomically { if (i > 0 && char(':') == null) null else number(16, 4, true, 0xffff) }
                        ?: return i j false).toInt()
                }
                return limit j false
            }
            val head = IntArray(8)
            val (head_size, head_ipv4) = groups(head, 8)
            if (head_size < 8) {
                if (head_ipv4) return@atomically null
                char(':') ?: return@atomically null
                char(':') ?: return@atomically null
                val tail = IntArray(7)
                val (tail_size, _) = groups(tail, 8 - (head_size + 1))
                tail.copyInto(head, 8 - tail_size, 0, tail_size)
            }
            ByteArray(16) { (head[it / 2] shr (if (it % 2 == 0) 8 else 0)).toByte() }
        }

        fun port(): Int? = atomically { char(':')?.let { number(10, null, true, 0xffff)?.toInt() } }

        fun socket_addr(): SocketAddr? = atomically {
            ipv4()?.let { ip -> port()?.let { SocketAddr(ip, it) } }
        } ?: atomically {
            char('[') ?: return@atomically null
            val ip = ipv6() ?: return@atomically null
            val scope = atomically { char('%')?.let { number(10, null, true, 0xffffffffL) } } ?: 0L
            char(']') ?: return@atomically null
            port()?.let { SocketAddr(ip, it, scope.toUInt()) }
        }
    }
}

/**
 * url 2.5.8 `Url` as `Url::parse` builds it without a base URL: the WHATWG URL parser, reduced to the
 * components [validate_url] reads. [serialization] is `as_str()`, [host] is `host_str()`.
 */
class Url(
    val serialization: String,
    val scheme: String,
    val username: String,
    val password: String?,
    val host: String?,
    val port: Int?,
    val path: String,
    val query: String?,
    val fragment: String?,
) {
    /** `Display`: [serialization]. */
    override fun toString(): String = serialization

    companion object {
        /** percent-encoding sets beyond CONTROLS (C0, DEL and every non-ASCII byte). */
        const val CONTROLS = ""
        const val FRAGMENT = " \"<>`"
        const val PATH = "$FRAGMENT#?{}"
        const val USERINFO = "$PATH/:;=@[\\]^|"
        const val QUERY = " \"#<>"
        const val SPECIAL_QUERY = "$QUERY'"

        fun default_port(scheme: String): Int? = when (scheme) {
            "http", "ws" -> 80
            "https", "wss" -> 443
            "ftp" -> 21
            else -> null
        }

        fun utf8_percent_encode(text: String, set: String, into: StringBuilder) {
            for (byte in text.encodeToByteArray()) {
                val b = byte.toInt() and 0xff
                if (b < 0x20 || b >= 0x7f || b.toChar() in set) into.append('%').append(HEX[b shr 4]).append(HEX[b and 15])
                else into.append(b.toChar())
            }
        }

        const val HEX = "0123456789ABCDEF"

        fun parse(text: String): Url? = try {
            Parser(text.trim { it <= ' ' }).parse_url()
        } catch (refused: IllegalArgumentException) {
            null
        }

        /** A refusal: `ParseError`. */
        fun refuse(): Nothing = throw IllegalArgumentException("URL parse error")

        fun starts_with_windows_drive_letter(s: String): Boolean =
            s.length >= 2 && (s[0] in 'a'..'z' || s[0] in 'A'..'Z') && (s[1] == ':' || s[1] == '|') && (s.length == 2 || s[2] in "/\\?#")

        fun path_starts_with_windows_drive_letter(s: String): Boolean =
            s.isNotEmpty() && s[0] in "/\\?#" && starts_with_windows_drive_letter(s.substring(1))
    }

    /** parser.rs `Parser` over `Input`: [next] skips ASCII tab and newline as `Input::next` does. */
    class Parser(val input: String) {
        var at = 0
        val serialization = StringBuilder()

        fun next(): Char? {
            while (at < input.length && input[at].let { it == '\t' || it == '\n' || it == '\r' }) at++
            return if (at < input.length) input[at++] else null
        }

        fun peek(): Char? {
            val saved = at
            return next().also { at = saved }
        }

        /** The next code point as text, a surrogate pair kept whole. */
        fun code_point(c: Char): String = if (c.isHighSurrogate()) "$c${next() ?: ""}" else c.toString()

        fun parse_url(): Url {
            if (input.isEmpty() || !(input[0] in 'a'..'z' || input[0] in 'A'..'Z')) refuse()
            while (true) {
                when (val c = next() ?: refuse()) {
                    in 'a'..'z', in '0'..'9', '+', '-', '.' -> serialization.append(c)
                    in 'A'..'Z' -> serialization.append(c.lowercaseChar())
                    ':' -> break
                    else -> refuse()
                }
            }
            val scheme = serialization.toString()
            serialization.append(':')
            return when (scheme) {
                "http", "https", "ws", "wss", "ftp" -> {
                    while (peek().let { it == '/' || it == '\\' }) next()
                    after_double_slash(scheme, special = true)
                }
                "file" -> parse_file()
                else -> {
                    val saved = at
                    if (next() == '/' && next() == '/') after_double_slash(scheme, special = false)
                    else {
                        at = saved
                        parse_non_special(scheme)
                    }
                }
            }
        }

        fun after_double_slash(scheme: String, special: Boolean): Url {
            serialization.append("//")
            val before_authority = serialization.length
            val username_end = parse_userinfo(special)
            val has_authority = before_authority != serialization.length
            val username = serialization.substring(before_authority, username_end)
            val password = if (username_end < serialization.length && serialization[username_end] == ':')
                serialization.substring(username_end + 1, serialization.length - 1) else null
            val host = parse_host(special)
            serialization.append(host)
            if (host.isEmpty() && (peek() == ':' || special)) refuse()
            var port: Int? = null
            if (peek() == ':') {
                next()
                var value = 0
                var digits = false
                while (true) {
                    val saved = at
                    val c = next() ?: break
                    if (c in '0'..'9') {
                        value = value * 10 + (c - '0')
                        if (value > 65535) refuse()
                        digits = true
                    } else if (c !in "/\\?#") refuse()
                    else {
                        at = saved
                        break
                    }
                }
                if (digits && value != default_port(scheme)) {
                    port = value
                    serialization.append(':').append(value)
                }
            }
            if (host.isEmpty() && has_authority) refuse()
            val path_start = serialization.length
            val first = peek()
            if (special) {
                serialization.append('/')
                if (first == '/' || first == '\\') next()
                parse_path(special, path_start)
            } else if (first != '?' && first != '#') {
                if (first != null && first != '/') serialization.append('/')
                parse_path(special, path_start)
            }
            return with_query_and_fragment(scheme, username, password, host.takeIf { it.isNotEmpty() }, port, path_start)
        }

        /** The userinfo before the last `@` of the authority; the end of the username in [serialization]. */
        fun parse_userinfo(special: Boolean): Int {
            val start = at
            var last_at = -1
            var at_count = 0
            var count = 0
            while (true) {
                val c = next() ?: break
                if (c == '@') {
                    last_at = at
                    at_count = count
                } else if (c == '/' || c == '?' || c == '#' || (special && c == '\\')) break
                count++
            }
            if (last_at < 0) {
                at = start
                return serialization.length
            }
            if (at_count == 0) {
                at = last_at
                if (peek().let { it == '/' || it == '?' || it == '#' || (special && it == '\\') }) refuse()
                return serialization.length
            }
            at = start
            var left = at_count
            var username_end = -1
            var has_password = false
            var has_username = false
            while (left > 0) {
                val c = next()!!
                left--
                if (c == ':' && username_end < 0) {
                    username_end = serialization.length
                    if (left > 0) {
                        serialization.append(':')
                        has_password = true
                    }
                } else {
                    if (!has_password) has_username = true
                    val text = if (c.isHighSurrogate() && left > 0) { left--; "$c${next()}" } else c.toString()
                    utf8_percent_encode(text, USERINFO, serialization)
                }
            }
            if (username_end < 0) username_end = serialization.length
            if (has_username || has_password) serialization.append('@')
            at = last_at
            return username_end
        }

        fun parse_host(special: Boolean): String {
            val text = StringBuilder()
            var inside_square_brackets = false
            while (true) {
                val saved = at
                val c = next() ?: break
                if ((c == ':' && !inside_square_brackets) || (special && c == '\\') || c == '/' || c == '?' || c == '#') {
                    at = saved
                    break
                }
                if (c == '[') inside_square_brackets = true
                if (c == ']') inside_square_brackets = false
                text.append(c)
            }
            val host = text.toString()
            if (special && host.isEmpty()) refuse()
            return if (special) Host.parse(host) else Host.parse_opaque(host)
        }

        fun parse_path(special: Boolean, path_start: Int) {
            while (true) {
                val segment_start = serialization.length
                var ends_with_slash = false
                val segment = StringBuilder()
                while (true) {
                    val saved = at
                    val c = next() ?: break
                    if (c == '/' || (special && c == '\\')) {
                        ends_with_slash = true
                        break
                    }
                    if (c == '?' || c == '#') {
                        at = saved
                        break
                    }
                    segment.append(code_point(c))
                }
                utf8_percent_encode(segment.toString(), PATH, serialization)
                if (ends_with_slash) serialization.append('/')
                val before_slash = serialization.substring(segment_start, serialization.length - if (ends_with_slash) 1 else 0)
                when (before_slash.lowercase()) {
                    "..", "%2e%2e", "%2e.", ".%2e" -> {
                        serialization.setLength(segment_start)
                        if (serialization.endsWith("/") && last_slash_can_be_removed(path_start)) serialization.setLength(serialization.length - 1)
                        if (serialization.length > path_start) {
                            val slash = serialization.substring(path_start).lastIndexOf('/')
                            serialization.setLength(path_start + slash + 1)
                        }
                        if (ends_with_slash && !serialization.endsWith("/")) serialization.append('/')
                    }
                    ".", "%2e" -> {
                        serialization.setLength(segment_start)
                        if (!serialization.endsWith("/")) serialization.append('/')
                    }
                }
                if (!ends_with_slash) break
            }
        }

        fun last_slash_can_be_removed(path_start: Int): Boolean {
            val before = serialization.lastIndexOf("/", serialization.length - 2)
            return before >= path_start && !path_starts_with_windows_drive_letter(serialization.substring(before))
        }

        fun parse_non_special(scheme: String): Url {
            val path_start = serialization.length
            if (peek() == '/') {
                next()
                serialization.append('/')
                parse_path(special = false, path_start = path_start)
            } else while (true) {
                val saved = at
                val c = next() ?: break
                if (c == '?' || c == '#') {
                    at = saved
                    break
                }
                utf8_percent_encode(code_point(c), CONTROLS, serialization)
            }
            return with_query_and_fragment(scheme, "", null, null, null, path_start)
        }

        /** `file:` URLs: only their host can refuse them, and [validate_url] refuses every one that parses. */
        fun parse_file(): Url {
            val saved = at
            if (next().let { it == '/' || it == '\\' } && next().let { it == '/' || it == '\\' }) {
                val text = StringBuilder()
                while (true) {
                    val before = at
                    val c = next() ?: break
                    if (c in "/\\?#") {
                        at = before
                        break
                    }
                    text.append(c)
                }
                val host = text.toString()
                if (host.isNotEmpty() && !(host.length == 2 && starts_with_windows_drive_letter(host))) Host.parse(host)
            } else at = saved
            return Url("file:", "file", "", null, null, null, "", null, null)
        }

        fun with_query_and_fragment(scheme: String, username: String, password: String?, host: String?, port: Int?, path_start: Int): Url {
            val path = serialization.substring(path_start)
            var query: String? = null
            var fragment: String? = null
            when (next()) {
                '?' -> {
                    val text = StringBuilder()
                    while (true) {
                        val c = next() ?: break
                        if (c == '#') {
                            fragment = parse_fragment()
                            break
                        }
                        text.append(c)
                    }
                    query = StringBuilder().also { utf8_percent_encode(text.toString(), if (default_port(scheme) != null) SPECIAL_QUERY else QUERY, it) }.toString()
                }
                '#' -> fragment = parse_fragment()
            }
            query?.let { serialization.append('?').append(it) }
            fragment?.let { serialization.append('#').append(it) }
            return Url(serialization.toString(), scheme, username, password, host, port, path, query, fragment)
        }

        fun parse_fragment(): String {
            val text = StringBuilder()
            while (true) text.append(next() ?: break)
            return StringBuilder().also { utf8_percent_encode(text.toString(), FRAGMENT, it) }.toString()
        }
    }

    /** host.rs `Host::parse` (special schemes) and `Host::parse_opaque`, as `Display` writes them. */
    object Host {
        fun parse(input: String): String {
            if (input.startsWith('[')) {
                if (!input.endsWith(']')) refuse()
                return "[" + ipv6(input.substring(1, input.length - 1)) + "]"
            }
            val bytes = percent_decode(input.encodeToByteArray())
            if (bytes.any { it < 0 }) TODO("UTS 46 mapping of a non-ASCII host (idna 1.1.0 domain_to_ascii)")
            val domain = bytes.decodeToString().lowercase()
            if (domain.split('.').any { it.startsWith("xn--") }) TODO("UTS 46 Punycode label check (idna 1.1.0 domain_to_ascii)")
            if (domain.any { it <= ' ' || it == '\u007f' || it in "%#/:<>?@[\\]^|" }) refuse()
            if (domain.isEmpty()) refuse()
            return if (ends_in_a_number(domain)) ipv4(domain) else domain
        }

        fun parse_opaque(input: String): String {
            if (input.startsWith('[')) {
                if (!input.endsWith(']')) refuse()
                return "[" + ipv6(input.substring(1, input.length - 1)) + "]"
            }
            if (input.any { it in "\u0000\t\n\r #/:<>?@[\\]^|" }) refuse()
            return StringBuilder().also { utf8_percent_encode(input, CONTROLS, it) }.toString()
        }

        fun percent_decode(bytes: ByteArray): ByteArray {
            val out = ArrayList<Byte>(bytes.size)
            var i = 0
            while (i < bytes.size) {
                val hi = if (bytes[i] == '%'.code.toByte() && i + 2 < bytes.size) hex(bytes[i + 1]) else -1
                val lo = if (hi >= 0) hex(bytes[i + 2]) else -1
                if (lo >= 0) {
                    out.add((hi * 16 + lo).toByte())
                    i += 3
                } else out.add(bytes[i++])
            }
            return out.toByteArray()
        }

        fun hex(b: Byte): Int = (b.toInt() and 0xff).let { if (it < 128) it.toChar().digitToIntOrNull(16) ?: -1 else -1 }

        fun ends_in_a_number(input: String): Boolean {
            val parts = input.split('.')
            val last = parts.last().ifEmpty { if (parts.size < 2) return false else parts[parts.size - 2] }
            if (last.isNotEmpty() && last.all { it in '0'..'9' }) return true
            return ipv4_number(last) != -1L
        }

        /** The WHATWG IPv4 number parser: -1 for not a number, -2 for a number past u32. */
        fun ipv4_number(text: String): Long {
            if (text.isEmpty()) return -1
            var input = text
            var radix = 10
            if (input.startsWith("0x") || input.startsWith("0X")) {
                input = input.substring(2)
                radix = 16
            } else if (input.length >= 2 && input.startsWith('0')) {
                input = input.substring(1)
                radix = 8
            }
            if (input.isEmpty()) return 0
            if (!input.all { it.code < 128 && it.digitToIntOrNull(radix) != null }) return -1
            var value = 0L
            for (c in input) {
                value = value * radix + c.digitToInt(radix)
                if (value > 0xffffffffL) return -2
            }
            return value
        }

        fun ipv4(input: String): String {
            val parts = input.split('.').toMutableList()
            if (parts.last().isEmpty()) parts.removeAt(parts.size - 1)
            if (parts.size > 4) refuse()
            val numbers = parts.map { ipv4_number(it).also { n -> if (n < 0) refuse() } }.toMutableList()
            var ipv4 = numbers.removeAt(numbers.size - 1)
            if (ipv4 > (0xffffffffL ushr (8 * numbers.size))) refuse()
            if (numbers.any { it > 255 }) refuse()
            for ((counter, n) in numbers.withIndex()) ipv4 += n shl (8 * (3 - counter))
            return "${ipv4 ushr 24}.${(ipv4 ushr 16) and 255}.${(ipv4 ushr 8) and 255}.${ipv4 and 255}"
        }

        /** `parse_ipv6addr`, written back in `write_ipv6`'s compressed form. */
        fun ipv6(text: String): String {
            val input = text.encodeToByteArray()
            val len = input.size
            val pieces = IntArray(8)
            var piece = 0
            var compress = -1
            var i = 0
            if (len < 2) refuse()
            if (input[0] == ':'.code.toByte()) {
                if (input[1] != ':'.code.toByte()) refuse()
                i = 2
                piece = 1
                compress = 1
            }
            var is_ip_v4 = false
            while (i < len) {
                if (piece == 8) refuse()
                if (input[i] == ':'.code.toByte()) {
                    if (compress >= 0) refuse()
                    i++
                    piece++
                    compress = piece
                    continue
                }
                val start = i
                val end = minOf(len, start + 4)
                var value = 0
                while (i < end) {
                    val digit = hex(input[i])
                    if (digit < 0) break
                    value = value * 16 + digit
                    i++
                }
                if (i < len) when (input[i]) {
                    '.'.code.toByte() -> {
                        if (i == start) refuse()
                        i = start
                        if (piece > 6) refuse()
                        is_ip_v4 = true
                    }
                    ':'.code.toByte() -> {
                        i++
                        if (i == len) refuse()
                    }
                    else -> refuse()
                }
                if (is_ip_v4) break
                pieces[piece++] = value
            }
            if (is_ip_v4) {
                if (piece > 6) refuse()
                var seen = 0
                while (i < len) {
                    if (seen > 0) {
                        if (seen < 4 && input[i] == '.'.code.toByte()) i++ else refuse()
                    }
                    var part = -1
                    while (i < len) {
                        val c = input[i].toInt()
                        if (c < '0'.code || c > '9'.code) break
                        part = when (part) {
                            -1 -> c - '0'.code
                            0 -> refuse()
                            else -> (part * 10 + c - '0'.code).also { if (it > 255) refuse() }
                        }
                        i++
                    }
                    if (part < 0) refuse()
                    pieces[piece] = pieces[piece] * 0x100 + part
                    seen++
                    if (seen == 2 || seen == 4) piece++
                }
                if (seen != 4) refuse()
            }
            if (i < len) refuse()
            if (compress >= 0) {
                var swaps = piece - compress
                piece = 7
                while (swaps > 0) {
                    val t = pieces[piece]
                    pieces[piece] = pieces[compress + swaps - 1]
                    pieces[compress + swaps - 1] = t
                    swaps--
                    piece--
                }
            } else if (piece != 8) refuse()
            var longest = -1
            var longest_length = -1
            var run = -1
            for (p in 0..8) {
                if (p < 8 && pieces[p] == 0) {
                    if (run < 0) run = p
                } else {
                    if (run >= 0 && p - run > longest_length) {
                        longest = run
                        longest_length = p - run
                    }
                    run = -1
                }
            }
            val compress_start = if (longest_length < 2) -1 else longest
            val compress_end = if (longest_length < 2) -2 else longest + longest_length
            val out = StringBuilder()
            var p = 0
            while (p < 8) {
                if (p == compress_start) {
                    out.append(':')
                    if (p == 0) out.append(':')
                    if (compress_end < 8) p = compress_end else break
                }
                out.append(pieces[p].toString(16))
                if (p < 7) out.append(':')
                p++
            }
            return out.toString()
        }
    }
}
