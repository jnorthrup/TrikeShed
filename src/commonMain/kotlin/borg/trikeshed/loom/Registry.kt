package borg.trikeshed.loom

import borg.trikeshed.htx.*
import borg.trikeshed.job.*
import borg.trikeshed.lib.*
import borg.trikeshed.reactor.*
import borg.trikeshed.userspace.nio.*
import borg.trikeshed.userspace.nio.channels.*
import borg.trikeshed.userspace.nio.file.*
import borg.trikeshed.util.*
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

/**
 * loom-mesh `registry`: the read-only OCI registry role over public object-storage origins
 * (DEC-083). Each served repository names its own operator's public HTTPS origin holding a plain
 * OCI image layout at `<origin>blobs/sha256/<hex>`; [Registry.load] fetches and SHA-256-verifies
 * every manifest its default tags reach. Also the local layout check run before an upload
 * ([verifyLayout]). The OCI routes wait on the HTTP listener.
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
 * fetched and verified it from its own origin), and the repositories served.
 */
class Registry(val manifests: Map<String, Manifest>, val repositories: Map<String, Repository>) {
    class Manifest(val media_type: String, val bytes: ByteArray)

    class Repository(val origin: Url, val tags: Map<String, String>, val manifests: MutableSet<String>, val blobs: MutableMap<String, ULong>)

    companion object {
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
    }

    /** Defaults actually served: (repository j origin) j (tag j digest), in repository then tag order. */
    fun defaults(): Series<Join<Twin<String>, Twin<String>>> =
        repositories.entries.flatMap { (r, repo) -> repo.tags.entries.map { (t, d) -> (r j repo.origin.serialization) j (t j d) } }.toSeries()
}
