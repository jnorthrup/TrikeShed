package borg.trikeshed.loom

import borg.trikeshed.context.*
import borg.trikeshed.context.ElementState
import borg.trikeshed.htx.*
import borg.trikeshed.job.*
import borg.trikeshed.lib.*
import borg.trikeshed.litebike.*
import borg.trikeshed.litebike.taxonomy.Protocol
import borg.trikeshed.reactor.*
import borg.trikeshed.userspace.nio.*
import borg.trikeshed.userspace.nio.channels.*
import borg.trikeshed.userspace.nio.file.*
import borg.trikeshed.util.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

/**
 * loom-mesh `registry`: the read-only OCI registry role over public object-storage origins
 * (DEC-083). Each served repository names its own operator's public HTTPS origin holding a plain
 * OCI image layout at `<origin>blobs/sha256/<hex>`; [Registry.load] fetches and SHA-256-verifies
 * every manifest its default tags reach, and [Registry] serves those bytes and answers blob GETs
 * with a 307 to the origin. Also the local layout check run before an upload ([verifyLayout]).
 */

/** `registry::parse_digest`: `sha256:` and 64 lowercase hex digits, the shape of a [ContentId]. */
fun parseDigest(value: String): ContentId? = runCatching { ContentId(value) }.getOrNull()

/** `registry::descriptor`: an OCI descriptor's digest and size. */
fun descriptor(item: Any?): Join<String, ULong> {
    val digest = (item as? Map<*, *>)?.get("digest") as? String
    if (digest == null || parseDigest(digest) == null) error("registry descriptor digest")
    val size = asU64(item["size"])?.takeIf { it <= Long.MAX_VALUE.toULong() } ?: error("registry descriptor size")
    return digest j size
}

/** `registry::descriptors`: the descriptors of [field]; none when absent. */
fun descriptors(json: Any, field: String): List<Join<String, ULong>> =
    when (val items = (json as? Map<*, *>)?.get(field)) {
        null -> emptyList()
        is List<*> -> if (items.size <= 1024) items.map(::descriptor) else error("registry manifest descriptors")
        else -> error("registry manifest descriptors")
    }

/** Rust `Path::join` for a relative [child]. */
fun join(root: String, child: String): String = if (root.isEmpty() || root.endsWith('/')) root + child else "$root/$child"

/**
 * At most [limit] bytes of [path] through the userspace NIO facade (`File::open`, then
 * `take(limit).read_to_end`); [unopened] and [unread] are the failures of the two steps.
 */
fun readFile(path: String, limit: Int, unopened: String, unread: String): ByteArray {
    val channel = try {
        FileChannel.open(path, setOf(StandardOpenOption.READ))
    } catch (failure: Exception) {
        error(unopened)
    }
    try {
        var bytes = ByteArray(minOf(limit, 1 shl 16))
        var size = 0
        while (size < limit) {
            if (size == bytes.size) bytes = bytes.copyOf(minOf(limit.toLong(), size * 2L).toInt())
            val count = try {
                channel.read(ByteBuffer.wrap(bytes, size, bytes.size - size))
            } catch (failure: Exception) {
                error(unread)
            }
            if (count < 0) break
            size += count
        }
        return bytes.copyOf(size)
    } finally {
        channel.close()
    }
}

/**
 * `registry::verify_layout`: every blob reachable from `index.json` of the OCI image layout at
 * [root] holds its stated size and hashes to its name. The top-level manifest digests come back
 * with their `org.opencontainers.image.ref.name` tags, in index order.
 */
fun verifyLayout(root: String): List<Join<String, String>> {
    fun blob(digest: String): String =
        join(join(root, "blobs/sha256"), parseDigest(digest)?.hex ?: error("layout digest"))
    fun readJson(path: String): Any =
        json(readFile(path, Int.MAX_VALUE, "layout read", "layout read"), unique = false) ?: error("layout json")
    fun check(digest: String, size: ULong) {
        val channel = try {
            FileChannel.open(blob(digest), setOf(StandardOpenOption.READ))
        } catch (failure: Exception) {
            error("layout blob missing")
        }
        val hasher = Sha256()
        var total = 0uL
        try {
            val buffer = ByteBuffer.allocate(1 shl 20)
            while (true) {
                buffer.clear()
                val count = try {
                    channel.read(buffer)
                } catch (failure: Exception) {
                    error("layout read")
                }
                if (count < 0) break
                hasher.update(buffer.array(), 0, count)
                total += count.toULong()
            }
        } finally {
            channel.close()
        }
        if (total != size || "sha256:" + hasher.digest().toLowerHex() != digest) error("layout blob mismatch")
    }

    val index = readJson(join(root, "index.json"))
    val tops = ArrayList<Join<String, String>>()
    val queue = ArrayList<Join<Join<String, ULong>, Boolean>>()
    for (item in (index as? Map<*, *>)?.get("manifests") as? List<*> ?: error("layout index")) {
        val top = descriptor(item)
        val annotations = (item as Map<*, *>)["annotations"] as? Map<*, *>
        tops.add(top.a j (annotations?.get("org.opencontainers.image.ref.name") as? String ?: ""))
        queue.add(top j true)
    }
    val seen = HashSet<String>()
    while (queue.isNotEmpty()) {
        val (next, manifest) = queue.removeAt(queue.size - 1)
        val (digest, size) = next
        if (!seen.add(digest)) continue
        check(digest, size)
        if (manifest) {
            val json = readJson(blob(digest))
            for (child in descriptors(json, "manifests")) queue.add(child j true)
            for (child in descriptors(json, "layers")) queue.add(child j false)
            (json as? Map<*, *>)?.get("config")?.let { queue.add(descriptor(it) j false) }
        }
    }
    return tops
}

/** registry.rs RepositorySource: one federated source, an operator's public origin and its default tags. */
class RepositorySource(
    /** Public HTTPS layout root ending in `/`; blobs live at `<origin>blobs/sha256/<hex>`. No credentials, query or fragment. */
    val origin: String,
    /** Tag to `sha256:<hex>` manifest digest, in tag order: the only tags served. */
    val tags: Map<String, String>,
) {
    companion object {
        val fields = s_["origin", "tags"]

        /** serde's derived `Deserialize` with `deny_unknown_fields`; both fields are required. */
        fun deserialize(value: Any, repeated: List<Join<Map<String, Any>, Any>>): RepositorySource {
            if (repeated.any { it.a === value }) error("duplicate field")
            val f = deserialize_struct(value, fields)
            return RepositorySource(
                (f[0] ?: error("missing field `origin`")).deserialize_string("origin"),
                (f[1] ?: error("missing field `tags`")).deserialize_map("tags", repeated) { it.deserialize_string("tags") },
            )
        }
    }
}

/** registry.rs RegistryConfig, serde `default` and `deny_unknown_fields`. */
class RegistryConfig(
    val listen: SocketAddr = SocketAddr.from_str("127.0.0.1:5000")!!,
    /** Repository name to its own origin and defaults, in name order: the only names served. */
    val repositories: Map<String, RepositorySource> = emptyMap(),
    val timeout_ms: ULong = 60_000uL,
) {
    fun validate() = check(false)

    fun check(loopback_http: Boolean) {
        val ok = timeout_ms in 1_000uL..600_000uL && repositories.isNotEmpty() && repositories.all { (repo, source) ->
            Registry.repository(repo) && Registry.origin(source.origin, loopback_http) != null && source.tags.isNotEmpty() &&
                source.tags.all { (t, d) -> Registry.tag(t) && parseDigest(d) != null }
        }
        if (!ok) error("invalid registry configuration")
    }

    companion object {
        val fields = s_["listen", "repositories", "timeout_ms"]

        /** `serde_json::from_slice` into the derived `Deserialize`: a refusal throws. */
        fun from_slice(bytes: ByteArray): RegistryConfig {
            val repeated = ArrayList<Join<Map<String, Any>, Any>>()
            val value = json(bytes, unique = false, repeated) ?: error("invalid JSON")
            if (repeated.any { it.a === value }) error("duplicate field")
            val f = deserialize_struct(value, fields)
            val d = RegistryConfig()
            return RegistryConfig(
                listen = f[0]?.let { SocketAddr.from_str(it.deserialize_string("listen")) ?: error("invalid socket address") } ?: d.listen,
                repositories = f[1]?.deserialize_map("repositories", repeated) { RepositorySource.deserialize(it, repeated) } ?: d.repositories,
                timeout_ms = f[2]?.deserialize_u64("timeout_ms") ?: d.timeout_ms,
            )
        }
    }
}

/** serde's `BTreeMap<String, T>` from a JSON object, in UTF-8 key order; a repeated key's replaced value is deserialized too. */
fun <T> Any.deserialize_map(field: String, repeated: List<Join<Map<String, Any>, Any>>, value: (Any) -> T): Map<String, T> {
    val map = this as? Map<*, *> ?: error("invalid type: `$field`, expected a map")
    for ((replaced_in, replaced) in repeated) if (replaced_in === map) value(replaced)
    return map.keys.map { it as String }.sortedWith { x, y -> codePointOrder(x, y) }.associateWith { value(map[it]!!) }
}

/**
 * registry.rs Registry: manifests by digest, shared across sources (every repository whose defaults name a digest
 * fetched and verified it from its own origin), and the repositories served. As an element it answers the OCI read
 * routes ([router]) on its own [listener], fed one complete HTTP request at a time by the platform bind adapter; drain
 * stops admission at the listener, answers what was admitted, joins, then closes it.
 */
class Registry(
    val manifests: Map<String, Manifest>,
    val repositories: Map<String, Repository>,
    parentJob: Job? = null,
) : AsyncContextElement(ElementState.CREATED, parentJob) {
    class Manifest(val media_type: String, val bytes: ByteArray)

    class Repository(val origin: Url, val tags: Map<String, String>, val manifests: MutableSet<String>, val blobs: MutableMap<String, ULong>)

    companion object Key : AsyncContextKey<Registry>() {
        const val MAX_MANIFEST: Int = 4 * 1024 * 1024
        const val MAX_MANIFESTS: Int = 256
        const val MAX_DEPTH: Int = 3

        /** reqwest `redirect::Policy::limited(3)`. */
        const val MAX_REDIRECTS: Int = 3

        /** The statuses tower-http FollowRedirect follows. */
        val REDIRECTS = setOf(301, 302, 303, 307, 308)

        /** Production: every origin must be public HTTPS. */
        suspend fun load(config: RegistryConfig, routeService: HtxRouteService): Registry {
            config.check(false)
            return load_checked(config, false, routeService)
        }

        /** INSECURE fixture transport: additionally accepts loopback HTTP origins. */
        suspend fun load_for_loopback_test(config: RegistryConfig, routeService: HtxRouteService): Registry {
            config.check(true)
            return load_checked(config, true, routeService)
        }

        /**
         * Every manifest the default tags reach, each fetched from its repository's own origin over [routeService] by
         * the client registry.rs builds: no proxy, https only unless [loopback_http], at most [MAX_REDIRECTS] redirects,
         * `timeout_ms` per manifest.
         */
        suspend fun load_checked(config: RegistryConfig, loopback_http: Boolean, routeService: HtxRouteService): Registry {
            val stub = routeService is HtxReactorElement && routeService.tlsBackend is StubTlsCodecBackend
            val client = HtxClientReactorElement(routeService, HtxClientOptions(maxRedirects = 0)).also { it.open() }
            try {
                val manifests = LinkedHashMap<String, Manifest>()
                val repositories = LinkedHashMap<String, Repository>()
                for ((name, source) in config.repositories) {
                    val repo = Repository(origin(source.origin, loopback_http) ?: error("invalid registry configuration"), source.tags, HashSet(), HashMap())
                    val queue = ArrayList<Join<String, Int>>()
                    for (digest in source.tags.values) queue.add(digest j 0)
                    while (queue.isNotEmpty()) {
                        val (digest, depth) = queue.removeAt(queue.size - 1)
                        if (!repo.manifests.add(digest)) continue
                        if (depth >= MAX_DEPTH || manifests.size >= MAX_MANIFESTS) error("registry manifest graph too large")
                        // Fetch from this repository's own origin even if another source already supplied the digest:
                        // each federated source must prove its origin holds its defaults.
                        val bytes = fetch_manifest(client, repo, digest, config.timeout_ms, !loopback_http, stub)
                        val json = json(bytes, unique = false) ?: error("registry manifest invalid")
                        val media_type = (json as? Map<*, *>)?.get("mediaType") as? String ?: "application/vnd.oci.image.manifest.v1+json"
                        if (!media_type.startsWith("application/vnd.") || media_type.encodeToByteArray().size > 128 || !header_value(media_type))
                            error("registry manifest media type")
                        for (child in descriptors(json, "manifests")) queue.add(child.a j depth + 1)
                        val blobs = descriptors(json, "layers").toMutableList()
                        (json as? Map<*, *>)?.get("config")?.let { blobs.add(descriptor(it)) }
                        for ((blob, size) in blobs) repo.blobs[blob] = size
                        manifests[digest] = Manifest(media_type, bytes)
                    }
                    repositories[name] = repo
                }
                return Registry(manifests, repositories)
            } finally {
                client.close()
            }
        }

        /** Public HTTPS directory URL (or, for fixtures only, loopback HTTP). */
        fun origin(value: String, loopback_http: Boolean): Url? {
            val url = Url.parse(value) ?: return null
            val loopback = url.host?.trim('[', ']')?.let { SocketAddr.ip_from_str(it) }?.is_loopback() == true
            val scheme_ok = url.scheme == "https" || (loopback_http && loopback && url.scheme == "http")
            return url.takeIf {
                scheme_ok && value.encodeToByteArray().size <= 512 && url.host != null && url.username.isEmpty() &&
                    url.password == null && url.query == null && url.fragment == null && url.path.endsWith('/') &&
                    !url.path.contains("/../") && !url.path.contains("/./")
            }
        }

        /** OCI distribution repository name grammar (lowercase path components). */
        fun repository(value: String): Boolean = value.encodeToByteArray().size <= 255 && value.split('/').all { p ->
            p.isNotEmpty() && (p.first() in 'a'..'z' || p.first() in '0'..'9') && (p.last() in 'a'..'z' || p.last() in '0'..'9') &&
                p.all { it in 'a'..'z' || it in '0'..'9' || it in "._-" }
        }

        fun tag(value: String): Boolean = value.encodeToByteArray().size in 1..128 && value[0] != '.' && value[0] != '-' &&
            value.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it in "_.-" }

        /** http `HeaderValue::from_str`: tab, or visible ASCII and space, or any byte above DEL. */
        fun header_value(text: String): Boolean =
            text.encodeToByteArray().all { (it.toInt() and 0xff).let { b -> (b >= 32 && b != 127) || b == 9 } }

        fun blob_url(repo: Repository, digest: String): Url {
            val hex = parseDigest(digest)?.hex ?: error("registry digest")
            return repo.origin.join("blobs/sha256/$hex") ?: error("registry origin")
        }

        /** The manifest [digest] from [repo]'s origin: status 200, at most [MAX_MANIFEST] bytes, hashing to [digest]. */
        suspend fun fetch_manifest(client: HtxClientReactorElement, repo: Repository, digest: String, timeout_ms: ULong, https_only: Boolean, stub: Boolean): ByteArray {
            val response = send(client, blob_url(repo, digest), timeout_ms, https_only, stub)
            val length = content_length(response)
            if (response.status != 200 || (length != null && length > MAX_MANIFEST.toULong())) error("registry manifest fetch rejected")
            val bytes = response.body.toArray()
            // hyper fails a body that ends short of its content-length.
            if (length != null && bytes.size.toULong() < length) error("registry manifest fetch failed")
            if (bytes.size > MAX_MANIFEST) error("registry manifest too large")
            if ("sha256:" + sha256(bytes).toLowerHex() != digest) error("registry manifest digest mismatch")
            return bytes
        }

        /**
         * reqwest's `get(url).send()` on registry.rs's client: tower-http FollowRedirect under reqwest's policy (301, 302,
         * 303, 307 and 308 to a Location that resolves to an http `Uri`, each followed request with a `referer`, the
         * fourth redirect an error, never to a scheme but http(s), nor to http when [https_only]), within [timeout_ms]
         * from the first connect to the end of the last body. Every reqwest error is "registry manifest fetch failed".
         * [stub]: the reactor's TLS backend is StubTlsCodecBackend.
         */
        suspend fun send(client: HtxClientReactorElement, url: Url, timeout_ms: ULong, https_only: Boolean, stub: Boolean): HtxResponse {
            val deadline = TimeSource.Monotonic.markNow() + timeout_ms.toLong().milliseconds
            var current = url
            var referer: String? = null
            var redirects = 0
            while (true) {
                if (current.scheme == "https" && stub) TODO("registry manifests over https on this target, whose platform registers StubTlsCodecBackend")
                if (current.host?.startsWith('[') == true) TODO("registry origins at IPv6 literals (the HTX reactor connects over AF_INET only)")
                val remaining = -deadline.elapsedNow().inWholeMilliseconds
                if (remaining <= 0) error("registry manifest fetch failed")
                val headers = referer?.let { htxHeaders("referer" j it) } ?: emptyHtxHeaders()
                val response = client.send(HtxMethod.GET, target(current), headers, ByteArray(0), remaining.toULong())
                // Status 0: the peer closed or answered no HTTP response.
                if (response == null || response.status == 0) error("registry manifest fetch failed")
                content_length(response)
                if (response.status !in REDIRECTS) return response
                val next = response.headers.headerValue("location")?.let { current.join(it) }?.takeIf { uri(it.serialization) } ?: return response
                if (++redirects > MAX_REDIRECTS || (next.scheme != "http" && next.scheme != "https") || (https_only && next.scheme != "https"))
                    error("registry manifest fetch failed")
                referer = if (next.scheme == "http" && current.scheme == "https") null else target(current)
                current = next
            }
        }

        /** The URL reqwest requests for [url], without userinfo or fragment; also the `referer` of the hop after it. */
        fun target(url: Url): String =
            url.scheme + "://" + url.host + (url.port?.let { ":$it" } ?: "") + url.path + (url.query?.let { "?$it" } ?: "")

        /**
         * hyper's response body length: none for a transfer-coded body, else every `content-length` value (each a
         * comma-separated list) the same decimal u64; any other value fails the request.
         */
        fun content_length(response: HtxResponse): ULong? {
            val headers = response.headers.view
            if (headers.any { it.a.equals("transfer-encoding", ignoreCase = true) }) return null
            var length: ULong? = null
            for ((name, value) in headers) if (name.equals("content-length", ignoreCase = true)) for (part in value.split(',')) {
                val digits = part.trim()
                val n = digits.takeIf { it.isNotEmpty() && it.all { c -> c in '0'..'9' } }?.toULongOrNull()
                if (n == null || (length != null && n != length)) error("registry manifest fetch failed")
                length = n
            }
            return length
        }

        /** http 1.5.0 `Uri::try_from` of [text], a URL serialization: false where tower-http leaves a redirect unfollowed. */
        fun uri(text: String): Boolean {
            val s = text.encodeToByteArray()
            if (s.isEmpty() || s.size > 65534) return false
            val scheme_end = scheme(s)
            if (scheme_end < -1) return false
            val authority_end = authority(s, maxOf(scheme_end, 0)) ?: return false
            return if (scheme_end < 0) authority_end == s.size else authority_end > scheme_end
        }

        /** http's `Scheme2::parse`: the index after `scheme://`, -1 for none, -2 for a scheme longer than 64. */
        fun scheme(s: ByteArray): Int {
            val text = s.decodeToString()
            if (text.startsWith("http://", ignoreCase = true)) return 7
            if (text.startsWith("https://", ignoreCase = true)) return 8
            if (s.size > 3) for (i in s.indices) {
                val c = (s[i].toInt() and 0xff).toChar()
                if (c == ':') {
                    if (s.size < i + 3 || s[i + 1] != '/'.code.toByte() || s[i + 2] != '/'.code.toByte()) return -1
                    return if (i > 64) -2 else i + 3
                }
                if (!(c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c in "+-.~")) return -1
            }
            return -1
        }

        /** http 1.5.0 `validate_authority_bytes` of [s] from [start]: the authority's end, or null where it refuses it. */
        fun authority(s: ByteArray, start: Int): Int? {
            if (start < 0 || start == s.size) return null
            var colons = 0
            var start_bracket = false
            var end_bracket = false
            var has_percent = false
            var end = s.size
            var at_sign = s.size
            for (i in start until s.size) {
                val c = (s[i].toInt() and 0xff).toChar()
                if (c == '/' || c == '?' || c == '#') {
                    end = i
                    break
                }
                when (c) {
                    '%' -> has_percent = true
                    ':' -> if (colons >= 8) return null else colons++
                    '[' -> if (has_percent || start_bracket) return null else start_bracket = true
                    ']' -> {
                        if (!start_bracket || end_bracket) return null
                        end_bracket = true
                        colons = 0
                        has_percent = false
                    }
                    '@' -> {
                        at_sign = i
                        colons = 0
                        has_percent = false
                    }
                    else -> if (!(c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c in "!$&'()*+,-.;=_~")) return null
                }
            }
            return end.takeIf { start_bracket == end_bracket && colons <= 1 && !(end > start && at_sign == end - 1) && !has_percent }
        }

        /** registry.rs oci_error: an OCI distribution error body. */
        fun oci_error(status: Int, code: String): HtxResponse =
            HtxResponse(status, ByteSeries("{\"errors\":[{\"code\":\"$code\",\"message\":\"$code\"}]}".encodeToByteArray()), htxHeaders("content-type" j "application/json"))

        /** hyper's reason phrase of the statuses served. */
        fun reason(status: Int): String = when (status) {
            200 -> "OK"
            307 -> "Temporary Redirect"
            400 -> "Bad Request"
            404 -> "Not Found"
            405 -> "Method Not Allowed"
            414 -> "URI Too Long"
            else -> "Request Header Fields Too Large"
        }

        /** httparse's method and header-name bytes. */
        fun token(b: Byte): Boolean = (b.toInt() and 0xff).let { it < 128 && (it.toChar().let { c -> c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c in "!#$%&'*+-.^_`|~" }) }

        /**
         * The request line and headers as httparse 1.10 reads them (CRLF lines, the listener having framed the request)
         * and hyper 1.11's server checks them: (method j path) j version, the path that of http's `Uri`. A refusal throws
         * the [Parse] hyper answers.
         */
        fun request_head(request: ByteArray): Join<Twin<String>, String> {
            val end = request.indexOfHeaderBoundary()
            if (end < 0) throw Parse(400)
            val lines = ArrayList<ByteArray>()
            var at = 0
            while (at <= end) {
                var i = at
                while (i < end && !(request[i] == '\r'.code.toByte() && request[i + 1] == '\n'.code.toByte())) i++
                lines.add(request.copyOfRange(at, i))
                at = i + 2
            }
            val line = lines[0]
            val method_end = line.indexOfFirst { it == ' '.code.toByte() }
            if (method_end <= 0 || !line.copyOf(method_end).all(::token)) throw Parse(400)
            val target_end = (method_end + 1 until line.size).firstOrNull { line[it] == ' '.code.toByte() } ?: throw Parse(400)
            val target = line.copyOfRange(method_end + 1, target_end)
            if (target.isEmpty() || !target.all { (it.toInt() and 0xff).let { b -> b in 0x21..0x7e || b >= 0x80 } } || !JsonReader.utf8(target, target.size))
                throw Parse(400)
            val version = line.copyOfRange(target_end + 1, line.size).decodeToString()
            if (version != "HTTP/1.0" && version != "HTTP/1.1") throw Parse(400)
            val headers = lines.drop(1)
            for ((count, header) in headers.withIndex()) {
                val colon = header.indexOfFirst { it == ':'.code.toByte() }
                if (colon <= 0 || !header.copyOf(colon).all(::token)) throw Parse(400)
                if (!header.copyOfRange(colon + 1, header.size).all { (it.toInt() and 0xff).let { b -> b == 9 || b in 0x20..0x7e || b >= 0x80 } })
                    throw Parse(400)
                // DEFAULT_MAX_HEADERS
                if (count == 100) throw Parse(431)
            }
            if (target.size > 65534) throw Parse(414)
            val path = path(target) ?: throw Parse(400)
            var te = false
            var chunked = false
            var length: ULong? = null
            for (header in headers) {
                val colon = header.indexOfFirst { it == ':'.code.toByte() }
                val value = header.decodeToString(colon + 1, header.size).trim(' ', '\t')
                when (header.decodeToString(0, colon).lowercase()) {
                    "transfer-encoding" -> {
                        if (version == "HTTP/1.0") throw Parse(400)
                        te = true
                        chunked = value.substringAfterLast(',').trim().let { it.all { c -> c.code < 128 } && it.lowercase() == "chunked" }
                    }
                    "content-length" -> if (!te) {
                        val n = value.takeIf { it.isNotEmpty() && it.all { c -> c in '0'..'9' } }?.toULongOrNull() ?: throw Parse(400)
                        if (length != null && n != length) throw Parse(400)
                        if (n > ULong.MAX_VALUE - 2uL) throw Parse(431)
                        length = n
                    }
                }
            }
            if (te && !chunked) throw Parse(400)
            return (line.decodeToString(0, method_end) j path) j version
        }

        /** http 1.5.0 `Uri::from_shared` of a request target, reduced to `Uri::path`; null where it refuses the target. */
        fun path(s: ByteArray): String? {
            if (s.size > 65534) return null
            if (s.size == 1 && s[0] == '*'.code.toByte()) return "*"
            if (s[0] == '/'.code.toByte()) return path_and_query(s, 0)
            val scheme_end = scheme(s)
            if (scheme_end < -1) return null
            val authority_end = authority(s, maxOf(scheme_end, 0)) ?: return null
            if (scheme_end < 0) return if (authority_end == s.size) "" else null
            if (authority_end == scheme_end) return null
            return if (authority_end == s.size) "/" else path_and_query(s, authority_end)
        }

        /** http's `scan_path_and_query` of [s] from [start]: the path before any query or fragment, `/` when empty. */
        fun path_and_query(s: ByteArray, start: Int): String? {
            if (s[start].toInt().toChar() !in "/?#") return null
            var path_end = s.size
            var i = start
            var query = false
            while (i < s.size) {
                val b = s[i].toInt() and 0xff
                if (b == '#'.code) {
                    if (!query) path_end = i
                    break
                }
                if (!query && b == '?'.code) {
                    path_end = i
                    query = true
                } else if (!(b >= 0x80 || b == 0x21 || b in 0x24..0x3b || b == 0x3d || (if (query) b in 0x3f..0x7e else b in 0x40..0x5f || b in 0x61..0x7e || b == 0x22)))
                    return null
                i++
            }
            return s.decodeToString(start, path_end).ifEmpty { "/" }
        }
    }

    override val key: CoroutineContext.Key<*> get() = Key

    /** The OCI routes' listener, fed one complete HTTP request at a time by the platform bind adapter. */
    val listener = LitebikeListenerElement(parentJob = supervisor)

    /** The serve loop and every admitted request. */
    val serving = SupervisorJob(supervisor)

    override suspend fun open() {
        if (state != ElementState.CREATED) return
        super.open()
        listener.open()
        val requests = listener.register(Protocol.Http)
        val scope = CoroutineScope(currentCoroutineContext() + serving)
        scope.launch {
            while (true) {
                val message = try {
                    requests.consume()
                } catch (_: ClosedReceiveChannelException) {
                    break
                }
                scope.launch { message.respond?.invoke(router(message.payload)) }
            }
        }
        state = ElementState.ACTIVE
    }

    /** Stops admission at the listener, answers what was admitted, joins, then closes the listener. */
    override suspend fun drain() {
        if (state != ElementState.ACTIVE) return
        state = ElementState.DRAINING
        listener.unregister(Protocol.Http)
        serving.complete()
        serving.join()
        listener.close()
        supervisor.complete()
        supervisor.join()
        state = ElementState.CLOSED
    }

    override suspend fun close() {
        listener.close()
        super.close()
    }

    /** Defaults actually served: (repository j origin) j (tag j digest), in repository then tag order. */
    fun defaults(): Series<Join<Twin<String>, Twin<String>>> =
        repositories.entries.flatMap { (r, repo) -> repo.tags.entries.map { (t, d) -> (r j repo.origin.serialization) j (t j d) } }.toSeries()

    /** registry.rs route: the OCI distribution read routes over the defaults. */
    fun route(method: String, path: String): HtxResponse {
        val head = method == "HEAD"
        if (!head && method != "GET") return oci_error(405, "UNSUPPORTED")
        if (path == "/v2/" || path == "/v2") return HtxResponse(200, ByteSeries("{}".encodeToByteArray()), htxHeaders("content-type" j "application/json"))
        if (!path.startsWith("/v2/")) return oci_error(404, "NAME_UNKNOWN")
        val rest = path.substring(4)
        val manifest = rsplit_once(rest, "/manifests/")
        val (name, reference) = manifest ?: rsplit_once(rest, "/blobs/") ?: return oci_error(404, "NAME_UNKNOWN")
        val repo = repositories[name] ?: return oci_error(404, "NAME_UNKNOWN")
        if (manifest != null) {
            val digest = repo.tags[reference] ?: reference.takeIf { it in repo.manifests } ?: return oci_error(404, "MANIFEST_UNKNOWN")
            val bytes = manifests.getValue(digest)
            val headers = htxHeaders("content-type" j bytes.media_type, "content-length" j bytes.bytes.size.toString(), "docker-content-digest" j digest)
            return HtxResponse(200, ByteSeries(if (head) ByteArray(0) else bytes.bytes), headers)
        }
        val size = repo.blobs[reference] ?: manifests[reference]?.takeIf { reference in repo.manifests }?.bytes?.size?.toULong()
        val location = try {
            blob_url(repo, reference)
        } catch (refused: IllegalStateException) {
            null
        }
        if (size == null || location == null) return oci_error(404, "BLOB_UNKNOWN")
        if (head) return HtxResponse(200, emptyHtxBody(), htxHeaders("content-length" j size.toString(), "docker-content-digest" j reference))
        return HtxResponse(307, emptyHtxBody(), htxHeaders("location" j location.serialization, "docker-content-digest" j reference))
    }

    /**
     * registry.rs serve: [route] with `docker-distribution-api-version`, as axum's router answers it: the body's
     * `content-length` where the route set none, and a HEAD answer without its body.
     */
    fun serve(method: String, path: String): HtxResponse {
        val response = route(method, path)
        val headers = response.headers.view.toMutableList()
        headers += "docker-distribution-api-version" j "registry/2.0"
        if (headers.none { it.a == "content-length" }) headers += "content-length" j response.body.rem.toString()
        return HtxResponse(response.status, if (method == "HEAD") emptyHtxBody() else response.body, headers.toSeries())
    }

    /**
     * One complete HTTP/1.x request to the bytes hyper writes for it, but its `date`. The listener closes the connection
     * after the answer, which an HTTP/1.1 answer says where hyper puts `connection: close`; an empty body's
     * `content-length` follows it but on a HEAD answer.
     */
    fun router(request: ByteArray): ByteArray {
        val (line, version) = try {
            request_head(request)
        } catch (refused: Parse) {
            return "HTTP/1.1 ${refused.status} ${reason(refused.status)}\r\nconnection: close\r\ncontent-length: 0\r\n\r\n".encodeToByteArray()
        }
        val response = serve(line.a, line.b)
        val implicit = response.body.rem == 0 && line.a != "HEAD"
        val head = StringBuilder(version).append(' ').append(response.status).append(' ').append(reason(response.status)).append("\r\n")
        for ((name, value) in response.headers.view) if (!(implicit && name == "content-length")) head.append(name).append(": ").append(value).append("\r\n")
        if (version == "HTTP/1.1") head.append("connection: close\r\n")
        if (implicit) head.append("content-length: 0\r\n")
        return head.append("\r\n").toString().encodeToByteArray() + response.body.toArray()
    }

    /** hyper's `Parse` error of a request head, with the status its server answers. */
    class Parse(val status: Int) : Exception()
}

/** Rust `str::rsplit_once`: [text] around the last [delimiter]. */
fun rsplit_once(text: String, delimiter: String): Twin<String>? =
    text.lastIndexOf(delimiter).takeIf { it >= 0 }?.let { text.substring(0, it) j text.substring(it + delimiter.length) }
