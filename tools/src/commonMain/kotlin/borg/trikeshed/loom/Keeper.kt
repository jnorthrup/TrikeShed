package borg.trikeshed.loom

import borg.trikeshed.htx.*
import borg.trikeshed.lib.*
import borg.trikeshed.reactor.*
import borg.trikeshed.userspace.nio.*
import borg.trikeshed.userspace.nio.channels.*
import borg.trikeshed.userspace.nio.file.*
import borg.trikeshed.userspace.nio.file.attribute.*

/**
 * cocaine-rats crates/loom-mesh/src/lease.rs `keeper`: mints leases. Runs in trusted custody (the off-laptop
 * wakeup caller or an operator), never inside a mesh node, worker image or model process.
 */
object Keeper {
    const val GOOGLE_IAM = "https://iamcredentials.googleapis.com/"
    const val CLOUDFLARE_API = "https://api.cloudflare.com/"
    const val VAST_API = "https://console.vast.ai/"
    const val CLOUD_PLATFORM = "https://www.googleapis.com/auth/cloud-platform"
    const val MAX_BODY = 64 * 1024

    /** reqwest with no proxy and no redirects, a 30 s timeout, and https only unless built [for_loopback_test]. */
    class Http(val client: HtxClientReactorElement, val https_only: Boolean) {
        /** reqwest refuses an http URL on an https-only client before any exchange. */
        suspend fun send(method: HtxMethod, url: String, headers: HtxHeaders, body: ByteArray = ByteArray(0)): HtxResponse? =
            if (https_only && !url.startsWith("https://")) null else client.send(method, url, headers, body, 30_000uL)

        suspend fun close() = client.close()

        companion object {
            suspend fun new(routeService: HtxRouteService): Http = build(routeService, true)

            /** Loopback HTTP fixture transport only. */
            suspend fun for_loopback_test(routeService: HtxRouteService): Http = build(routeService, false)

            suspend fun build(routeService: HtxRouteService, https_only: Boolean): Http {
                if (https_only && routeService is HtxReactorElement && routeService.tlsBackend is StubTlsCodecBackend) {
                    TODO("lease provider https on this target waits on a TLS 1.3 client codec behind TlsCodecBackend; the platform registers StubTlsCodecBackend")
                }
                return Http(HtxClientReactorElement(routeService, HtxClientOptions(maxRedirects = 0)).also { it.open() }, https_only)
            }
        }
    }

    /** The origin requests are built on: [fixed], or for fixtures a literal loopback http origin as [GcsArchive.loopback_origin] reads it. */
    fun origin(base: String, fixed: String): String =
        if (base == fixed) base else GcsArchive.loopback_origin(base) ?: error("lease provider origin")

    /** url's `path_segments_mut().extend` on a special scheme: `.` and `..` are skipped, each other segment percent-encoded (SPECIAL_PATH_SEGMENT). */
    fun path_segments(vararg segments: String): String = segments.filter { it != "." && it != ".." }.joinToString("/") { segment ->
        StringBuilder().apply {
            for (byte in segment.encodeToByteArray()) {
                val c = byte.toInt() and 0xFF
                if (c <= 0x20 || c >= 0x7F || c.toChar() in "\"#<>?`{}/%\\") append('%').append("0123456789ABCDEF"[c shr 4]).append("0123456789ABCDEF"[c and 15])
                else append(c.toChar())
            }
        }.toString()
    }

    /** The JSON of a 2xx reply read within [MAX_BODY]. */
    fun json(response: HtxResponse?): Any {
        response ?: error("lease provider unavailable")
        val body = response.body.toArray()
        if (body.size > MAX_BODY) error("lease provider response too large")
        if (response.status !in 200..299) error("lease provider refused")
        return json(body, unique = false) ?: error("lease provider response invalid")
    }

    fun text(value: Any?, key: String): String = (value as? Map<*, *>)?.get(key) as? String ?: error("lease provider response invalid")

    /** IAM Credentials generateAccessToken for [target], storage scope only. [source] authorizes the call and needs `iam.serviceAccounts.getAccessToken`. */
    suspend fun mint_google(http: Http, base: String, source: String, target: String, secs: ULong, extended: Boolean, now: ULong): Lease {
        val origin = origin(base, GOOGLE_IAM)
        val secs = selectable(Provider.Google, secs, extended)
        if (!service_account_email(target)) error("invalid service account")
        val body = jsonText(mapOf("scope" to listOf(GOOGLE_STORAGE_SCOPE), "lifetime" to "${secs}s"))
        val url = origin + path_segments("v1", "projects", "-", "serviceAccounts", "$target:generateAccessToken")
        val reply = json(http.send(HtxMethod.POST, url, htxHeaders("authorization" j source, "content-type" j "application/json"), body.encodeToByteArray()))
        val expires = rfc3339_utc(text(reply, "expireTime"))
        val lease = Lease(FORMAT, target, now, minOf(expires, now + secs), Credential.Google(text(reply, "accessToken")))
        lease.check_usable(now)
        return lease
    }

    /** R2 temporary credentials: object read/write on one bucket prefix. */
    suspend fun mint_cloudflare(
        http: Http, base: String, api_token: String, account_id: String, bucket: String, prefix: String, parent_access_key_id: String,
        secs: ULong, now: ULong,
    ): Lease {
        val origin = origin(base, CLOUDFLARE_API)
        val secs = selectable(Provider.Cloudflare, secs, false)
        if (!charset(api_token, 1, 256, "-_") || !charset(parent_access_key_id, 1, 128, "")) error("invalid Cloudflare source credential")
        val url = origin + path_segments("client", "v4", "accounts", account_id, "r2", "temp-access-credentials")
        val body = jsonText(
            mapOf(
                "bucket" to bucket, "parentAccessKeyId" to parent_access_key_id, "permission" to "object-read-write",
                "ttlSeconds" to secs, "prefixes" to listOf("$prefix/"),
            ),
        )
        val reply = json(http.send(HtxMethod.POST, url, htxHeaders("authorization" j "Bearer $api_token", "content-type" j "application/json"), body.encodeToByteArray()))
            as? Map<*, *> ?: error("lease provider refused")
        if (reply["success"] != true) error("lease provider refused")
        val result = reply["result"] ?: error("lease provider response invalid")
        val lease = Lease(
            FORMAT, "$bucket/$prefix", now, now + secs,
            Credential.Cloudflare(account_id, bucket, text(result, "accessKeyId"), text(result, "secretAccessKey"), text(result, "sessionToken")),
        )
        lease.check_usable(now)
        return lease
    }

    /** Scoped Vast key named with its deadline; `sweep` must revoke it. */
    suspend fun mint_vast(http: Http, base: String, master_key: String, name: String, permissions: Any, secs: ULong, now: ULong): Lease {
        val origin = origin(base, VAST_API)
        val secs = selectable(Provider.Vast, secs, false)
        label(name)
        if (permissions !is Map<*, *>) error("Vast permissions must be an object")
        val authorization = vast_auth(master_key)
        val body = jsonText(mapOf("name" to name, "permissions" to permissions))
        val reply = json(http.send(HtxMethod.POST, origin + "api/v0/auth/apikeys", htxHeaders("authorization" j authorization, "content-type" j "application/json"), body.encodeToByteArray()))
        val key_id = asU64((reply as? Map<*, *>)?.get("id")) ?: error("lease provider response invalid")
        val lease = Lease(FORMAT, name, now, now + secs, Credential.Vast(key_id, text(reply, "key")))
        lease.check_usable(now)
        return lease
    }

    /** Delete one Vast key; false when it was already absent. */
    suspend fun revoke_vast(http: Http, base: String, master_key: String, key_id: ULong): Boolean {
        val origin = origin(base, VAST_API)
        val response = http.send(HtxMethod.DELETE, origin + "api/v0/auth/apikeys/$key_id", htxHeaders("authorization" j vast_auth(master_key)))
            ?: error("lease provider unavailable")
        return when {
            response.status in 200..299 -> true
            response.status == 404 -> false
            else -> error("lease provider refused")
        }
    }

    fun vast_auth(master_key: String): String =
        if (charset(master_key, 16, 512, "-_")) "Bearer $master_key" else error("invalid Vast source credential")

    /** Runpod has no key-creation or expiry API: stamp an operator-created restricted key with a declared window that LOOM consumers enforce. */
    fun wrap_runpod(origin: String, api_key: String, secs: ULong, now: ULong): Lease {
        val secs = selectable(Provider.Runpod, secs, false)
        val lease = Lease(FORMAT, origin, now, now + secs, Credential.Runpod(api_key))
        lease.check_usable(now)
        return lease
    }

    /** Revoke every Vast lease whose window has closed, renaming each `*.receipt.json` to `*.revoked.json`; the number revoked. */
    suspend fun sweep_vast(http: Http, base: String, master_key: String, receipts: String, now: ULong): Int =
        TODO("sweep_vast waits on opendir/readdir and rename in the NIO facade (Files.list, Files.move) and on config.rs protected_file for each receipt")

    /** Create an owner-only file that did not exist before. O_CREAT|O_EXCL never follows a final symlink, which is what O_NOFOLLOW adds in Rust. */
    fun write_new(path: String, bytes: ByteArray) {
        val channel = try {
            FileChannel.open(
                path, setOf(StandardOpenOption.WRITE, StandardOpenOption.CREATE_NEW),
                PosixFilePermissions.asFileAttribute(setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)),
            )
        } catch (failure: Exception) {
            error("cannot create lease output")
        }
        try {
            val buffer = ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) channel.write(buffer)
            channel.force(true)
        } catch (failure: Exception) {
            error("cannot write lease output")
        } finally {
            channel.close()
        }
    }

    /** config.rs protected_file: at most [max] bytes of a regular file owned by the effective uid, mode & 0o077 == 0, one link, opened O_NOFOLLOW. */
    fun protected_file(path: String, max: Int): ByteArray =
        TODO(
            "config.rs protected_file needs the opened file's type, owner, mode and link count, which the NIO facade's STATX does not report " +
                "(UserspaceIO.jvm.kt statx: size, mtime, type) and Files.kt refuses by policy, and an O_NOFOLLOW open, which FileChannel.open does not pass",
        )

    fun secret_file(path: String): String {
        val bytes = protected_file(path, 16 * 1024)
        try {
            val text = bytes.decodeToString(throwOnInvalidSequence = true)
            return text.removeSuffix("\r\n").let { if (it.length < text.length) it else text.removeSuffix("\n") }
        } catch (malformed: CharacterCodingException) {
            error("secret file encoding")
        } finally {
            bytes.fill(0)
        }
    }

    suspend fun google_source(flags: Flags): String {
        flags.get("source-token-file")?.let { path ->
            val token = secret_file(path)
            if (!bearer_token(token, 16 * 1024)) error("invalid Google source token")
            return "Bearer $token"
        }
        flags.need("source")
        TODO(
            "google_source --source waits on gcs.rs source_credentials: protected_credentials (fstat owner and mode, rejected by the NIO facade's policy) " +
                "and google-cloud-auth's authorized_user, impersonated_service_account and external_account token exchanges",
        )
    }

    /** `--name value` flags, each at most once, and the repeatable `--extended` switch. Arguments arrive decoded, so Rust's `str` is [need]. */
    class Flags(val values: Map<String, String>, val switches: List<String>) {
        fun get(name: String): String? = values[name]

        fun need(name: String): String = values[name] ?: error("required lease flag missing")

        fun switch(name: String): Boolean = name in switches

        companion object {
            fun parse(args: Iterator<String>): Flags {
                val values = mutableMapOf<String, String>()
                val switches = mutableListOf<String>()
                while (args.hasNext()) {
                    val arg = args.next()
                    if (!arg.startsWith("--")) error("invalid lease arguments")
                    val name = arg.substring(2)
                    if (name == "extended") {
                        switches += name
                        continue
                    }
                    if (!args.hasNext()) error("lease flag value missing")
                    if (values.put(name, args.next()) != null) error("duplicate lease flag")
                }
                return Flags(values, switches)
            }
        }
    }

    const val USAGE = """usage:
  loom-lease mint google     --target SA_EMAIL (--source ADC_FILE | --source-token-file FILE) --duration D [--extended] --out FILE
  loom-lease mint cloudflare --account ID --bucket B --prefix P --parent-access-key-id K --api-token-file FILE --duration D --out FILE
  loom-lease mint vast       --master-key-file FILE --name LABEL --permissions-file FILE --duration D --out FILE
  loom-lease mint runpod     --origin https://ID.api.runpod.ai/ --key-file FILE --duration D --out FILE
  loom-lease sweep vast      --master-key-file FILE --receipts DIR
  loom-lease install google|runpod --lease FILE --mesh-config CONFIG --admin-id ID --admin-key-file KEY
             [--invocation-lease RUNPOD_LEASE] [--node ID ...all Replica members]
D: seconds or 90s/30m/1h/7d. Writes the lease (0600, new file) to --out and its
non-secret receipt to --out.receipt.json; prints the receipt."""

    /** Keeper command line over [routeService]. Prints only non-secret receipts. */
    suspend fun cli(args: List<String>, routeService: HtxRouteService) {
        val args = args.iterator()
        val verb = if (args.hasNext()) args.next() else error(USAGE)
        val provider = Provider.parse(if (args.hasNext()) args.next() else error(USAGE))
        val flags = Flags.parse(args)
        val now = now()
        val http = Http.new(routeService)
        try {
            when {
                verb == "sweep" && provider == Provider.Vast -> {
                    val key = secret_file(flags.need("master-key-file"))
                    println("{\"revoked\":${sweep_vast(http, VAST_API, key, flags.need("receipts"), now)}}")
                    return
                }
                verb == "install" -> {
                    val lease = Lease.from_json(protected_file(flags.need("lease"), 32 * 1024))
                    if (lease.provider() != provider) error("lease provider mismatch")
                    lease.check_usable(now)
                    TODO("loom-lease install waits on config.rs Config::read and client.rs Client (call over the signed Admin route, install_invocation_lease)")
                }
                verb != "mint" -> error(USAGE)
            }
            val secs = parse_duration(flags.need("duration"))
            val out = flags.need("out")
            val lease = when (provider) {
                Provider.Google -> mint_google(http, GOOGLE_IAM, google_source(flags), flags.need("target"), secs, flags.switch("extended"), now)
                Provider.Cloudflare -> {
                    val token = secret_file(flags.need("api-token-file"))
                    mint_cloudflare(
                        http, CLOUDFLARE_API, token, flags.need("account"), flags.need("bucket"), flags.need("prefix"),
                        flags.need("parent-access-key-id"), secs, now,
                    )
                }
                Provider.Vast -> {
                    val key = secret_file(flags.need("master-key-file"))
                    val permissions = json(protected_file(flags.need("permissions-file"), 16 * 1024), unique = false) ?: error("invalid Vast permissions")
                    mint_vast(http, VAST_API, key, flags.need("name"), permissions, secs, now)
                }
                Provider.Runpod -> {
                    val key = secret_file(flags.need("key-file"))
                    wrap_runpod(flags.need("origin"), key, secs, now)
                }
            }
            val body = lease.to_json()
            val receipt = lease.receipt().to_json()
            try {
                // The receipt first: a Vast key must always be sweepable.
                write_new("$out.receipt.json", receipt)
                write_new(out, body)
            } finally {
                body.fill(0)
            }
            println(receipt.decodeToString())
        } finally {
            http.close()
        }
    }
}
