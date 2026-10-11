package borg.trikeshed.loom

import borg.trikeshed.htx.*
import borg.trikeshed.lib.*
import borg.trikeshed.platform.*
import borg.trikeshed.userspace.*
import borg.trikeshed.userspace.nio.channels.spi.*
import borg.trikeshed.userspace.nio.platform.spi.*
import borg.trikeshed.userspace.nio.spi.*
import kotlin.random.Random
import kotlin.test.*
import kotlin.time.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.*

/**
 * gcs.rs load_credentials and source_credentials against what google-cloud-auth 1.16 did for the same inputs
 * ([GcsAdcVectors], printed by a scratch Rust harness compiling gcs.rs verbatim under a loopback-only sandbox): each
 * case scripts [Routes] with the replies the Rust fake gave and must send the same requests in the same order, at
 * the same times where the crate schedules them, and reach the same result.
 */
class LoomCredentialsTest {
    val STORAGE = STORAGE_SCOPE
    val CLOUD = DEFAULT_SCOPE
    val SA = "loom-handoff@operator-a.iam.gserviceaccount.com"

    /** std::env as the harness set it for a case. */
    class Environment(val values: Map<String, String>) : SystemOperations {
        override fun getenv(name: String, defaultVal: String?): String? = values[name] ?: defaultVal
        override fun getProperty(name: String, defaultVal: String?): String? = defaultVal
        override val homedir: String get() = values["HOME"] ?: ""
    }

    /**
     * The harness's loopback fake as a route service: requests and their arrival (ms) in order, each answered by the
     * next template (`status|content-type|body`, `EXPIRE_TIME` as now + 1 h, `HANG` never), unscripted ones by a 500.
     * Storage requests are the sandbox's refused connection and are kept apart.
     */
    class Routes(templates: Series<String>) : HtxRouteService {
        val replies = ArrayDeque(templates.toList())
        val log = mutableListOf<HtxRequest>()
        val at = mutableListOf<Long>()
        val storage = mutableListOf<HtxRequest>()
        val start = TimeSource.Monotonic.markNow()
        val lock = Mutex()

        override suspend fun exchange(state: HtxExchangeState, request: HtxRequest): HtxExchangeResult {
            if (request.target.host == "storage.googleapis.com") {
                lock.withLock { storage += request }
                return HtxExchangeResult(state.copy(lifecycle = HtxExchangeLifecycle.FAILED, failure = "connection refused"))
            }
            val template = lock.withLock {
                log += request
                at += start.elapsedNow().inWholeMilliseconds
                replies.removeFirstOrNull()
            } ?: "500|text/plain|unscripted request"
            if (template == "HANG") awaitCancellation()
            val (status, type, body) = template.split('|', limit = 3)
            val bytes = body.replace("EXPIRE_TIME", InstantShim.ofEpochMilli((now().toLong() + 3600) * 1000).substring(0, 19) + "Z").encodeToByteArray()
            val headers = htxHeaders("content-type" j type, "content-length" j bytes.size.toString(), "connection" j "close")
            return HtxExchangeResult(state.copy(lifecycle = HtxExchangeLifecycle.RESPONDED, response = HtxResponse(status.toInt(), ByteSeries(bytes), headers)))
        }
    }

    lateinit var nio: NioSupervisor
    lateinit var platform: ProcessOperations
    val root = (HostSystem.getenv("TMPDIR") ?: "/tmp").trimEnd('/') + "/loom-credentials-" + Random.nextLong().toULong().toString(16)

    fun <T> session(block: suspend CoroutineScope.() -> T): T = runBlocking {
        nio = NioSupervisor().also { it.open() }
        try {
            platform = nio.service<ProcessOperations>()!!
            withContext(nio) { block() }
        } finally {
            nio.close()
        }
    }

    suspend fun run(command: String, vararg args: String) = assertEquals(0, platform.exec(command, args.toList(), ByteArray(0)).exitCode, "$command ${args.toList()}")

    /** A fresh directory for [case], as the harness's `dir`. */
    suspend fun dir(case: String): String = "$root/$case".also {
        run("/bin/rm", "-rf", it)
        run("/bin/mkdir", "-p", it)
    }

    suspend fun write(path: String, bytes: ByteArray, mode: String) {
        val ring = Config.ring()
        try {
            val fd = ring.open(path, UringOp.O_WRONLY or UringOp.O_CREAT or UringOp.O_TRUNC, 0x180)
            try {
                ring.write_all(fd, bytes)
            } finally {
                ring.close(fd)
            }
        } finally {
            ring.closeNow()
        }
        run("/bin/chmod", mode, path)
    }

    fun fill(template: String, files: String) = template.replace("BASE/", "http://127.0.0.1:9/").replace("FILES/", "$files/")

    /** The harness's `headers()`: sorted `name: value` lines, or the error's retry class. */
    suspend fun headers(credentials: Credentials): String = try {
        "headers:" + credentials.headers().toList().map { "${it.a}: ${it.b}" }.sorted().joinToString("\n")
    } catch (e: CredentialsError) {
        "error:" + if (e.is_transient) "transient" else "permanent"
    }

    fun parse(raw: ByteArray): Triple<String, List<Pair<String, String>>, ByteArray> {
        val end = (0..raw.size - 4).first { raw[it] == 13.toByte() && raw[it + 1] == 10.toByte() && raw[it + 2] == 13.toByte() && raw[it + 3] == 10.toByte() }
        val lines = raw.decodeToString(0, end).split("\r\n")
        return Triple(lines[0], lines.drop(1).map { it.substringBefore(':').lowercase() to it.substringAfter(':').trim() }, raw.copyOfRange(end + 4, raw.size))
    }

    fun form(body: ByteArray): List<String> = body.decodeToString().split('&').sorted()

    /**
     * The request reqwest sent equals ours: request line, the headers in order once reqwest's own (`host`, its default `accept`,
     * `content-length`) are set aside, and the body ([body] compares it; STS bodies as their pairs, which Rust orders by a
     * randomly seeded HashMap).
     */
    fun assertSameRequest(rust: String, ours: HtxRequest, name: String, body: (ByteArray, ByteArray) -> Unit = { r, o -> assertEquals(r.decodeToString(), o.decodeToString(), name) }) {
        val (line, headers, rustBody) = parse(rust.hexToByteArray())
        assertEquals(line, "${ours.method} ${ours.target.requestPath} HTTP/1.1", name)
        assertEquals(headers.filterNot { it.first == "host" || it.first == "content-length" || it == ("accept" to "*/*") }, ours.headers.toList().map { it.a to it.b }, name)
        val oursBody = ours.body.toArray()
        if (oursBody.isNotEmpty() && oursBody[0] != '{'.code.toByte() && rustBody.decodeToString().contains("grant_type=urn")) {
            assertEquals(form(rustBody), form(oursBody), name)
        } else body(rustBody, oursBody)
    }

    /** Requests, result and arrival times against the Rust case; [slack] ms per arrival, null where the crate's delay is random. */
    fun assertCase(name: String, rust: Join<Series<String>, String>, rustAt: Series<Long>, routes: Routes, result: String, slack: Long? = 400) {
        assertEquals(rust.b, result, name)
        assertEquals(rust.a.size, routes.log.size, "$name requests")
        for (i in 0 until rust.a.size) assertSameRequest(rust.a[i], routes.log[i], "$name request $i")
        if (slack != null) for (i in 0 until rustAt.size) assertTrue(kotlin.math.abs(rustAt[i] - routes.at[i]) <= slack, "$name request $i at ${routes.at[i]} ms, Rust ${rustAt[i]} ms")
    }

    /** The harness's `source`: [template] as a 0600 credentials.json beside subject.jwt, source_credentials, one headers(), 300 ms. */
    suspend fun source(name: String, template: String, scope: String, replies: Series<String>, env: Map<String, String> = emptyMap(), extra: suspend (String) -> Unit = {}): Pair<Routes, String> {
        val files = dir(name)
        write("$files/subject.jwt", "eyJ.subject-capture.jwt\n".encodeToByteArray(), "600")
        extra(files)
        val routes = Routes(replies)
        val reactor = HtxClientReactorElement(routes, HtxClientOptions(maxRedirects = 0)).also { it.open() }
        write("$files/credentials.json", fill(template, files).encodeToByteArray(), "600")
        val result = withContext(Environment(env + ("HOME" to "$root/home"))) {
            val credentials = try {
                source_credentials("$files/credentials.json", scope, reactor)
            } catch (refused: IllegalStateException) {
                return@withContext "error:${refused.message}"
            }
            try {
                headers(credentials).also { delay(300) }
            } finally {
                credentials.close()
            }
        }
        reactor.close()
        return routes to result
    }

    fun vectors(name: String): Triple<Join<Series<String>, String>, Series<Long>, Series<String>> = GcsAdcVectors.cases.getValue(name)

    suspend fun sourceCase(name: String, scope: String = STORAGE, slack: Long? = 400, env: Map<String, String> = emptyMap(), extra: suspend (String) -> Unit = {}) {
        val (rust, at, replies) = vectors(name)
        val (routes, result) = source(name, GcsAdcVectors.inputs.getValue("${name}Input"), scope, replies, env, extra)
        assertCase(name, rust, at, routes, result, slack)
    }

    @Test
    fun metadataServer() = session {
        suspend fun mds(name: String, wait: Long) {
            val (rust, at, replies) = vectors(name)
            val routes = Routes(replies)
            val reactor = HtxClientReactorElement(routes, HtxClientOptions(maxRedirects = 0)).also { it.open() }
            val result = withContext(Environment(mapOf(GCE_METADATA_HOST_ENV_VAR to "127.0.0.1:9"))) {
                val credentials = mds_credentials(listOf(STORAGE), reactor)
                try {
                    val first = headers(credentials)
                    delay(wait)
                    "$first\n--\n${headers(credentials)}"
                } finally {
                    credentials.close()
                }
            }
            reactor.close()
            assertCase(name, rust, at, routes, result)
        }
        for (name in listOf("mdsToken", "mdsForbidden", "mdsUnavailable", "mdsNotJson", "mdsNoTokenType", "mdsNoExpiry", "mdsNullExpiry", "mdsSequence",
            "mdsDuplicate", "mdsUnknownDuplicate", "mdsNegativeExpiry", "mdsFloatExpiry", "mdsMacToken", "mdsBadHeaderValue", "mdsTrailing")) mds(name, 0)
        // The refresh schedule: 240 s before expiry, every 10 s within it, at once once expired, 10 s after a transient failure.
        coroutineScope {
            for ((name, wait) in listOf("mdsRefresh" to 6_500L, "mdsShortRefresh" to 11_500L, "mdsExpiredRefresh" to 1_000L, "mdsTransientRetry" to 11_500L,
                "mdsForbiddenAfterToken" to 6_500L)) launch { mds(name, wait) }
        }
    }

    @Test
    fun authorizedUser() = session {
        sourceCase("userToken")
        sourceCase("userCloudPlatform", CLOUD)
        for (name in listOf("userNoExpiry", "userRefused", "userServerError", "userTooMany", "userNotJson", "userScopeNumber", "userUniverse", "userUniverseDefault",
            "userNoSecret", "userTokenUriNumber", "userQuotaNull", "userUntyped", "userUnknownType", "userTypeNumber", "userArray")) sourceCase(name)
    }

    /** The bearer of a "headers:" result or request header is [key]'s JWT for [scope] at a second within [window]. */
    fun assertJwt(bearer: String, key: ServiceAccountKey, scope: String, window: LongRange, name: String) =
        assertTrue(window.any { bearer == "Bearer " + key.generate(scope, it.toULong()) }, "$name: $bearer")

    fun key(input: String): ServiceAccountKey = ServiceAccountKey.from_value(json(input.encodeToByteArray(), unique = false))

    @Test
    fun serviceAccount() = session {
        for ((name, scope) in listOf("saStorage" to STORAGE, "saCloudPlatform" to CLOUD, "saQuotaIgnored" to STORAGE)) {
            val (rust, _, replies) = vectors(name)
            val input = GcsAdcVectors.inputs.getValue("${name}Input")
            val before = now().toLong()
            val (routes, result) = source(name, input, scope, replies)
            assertEquals(0, routes.log.size, name)
            val rustBefore = GcsAdcVectors.times["${name}Before"] ?: GcsAdcVectors.times.getValue("saStorageBefore")
            assertJwt(rust.b.removePrefix("headers:authorization: "), key(input), scope, rustBefore - 1..rustBefore + 30, "$name Rust")
            assertJwt(result.removePrefix("headers:authorization: "), key(input), scope, before - 1..now().toLong() + 1, name)
        }
        sourceCase("saNoProject")
        sourceCase("saGarbageKey")
    }

    @Test
    fun impersonatedServiceAccount() = session {
        sourceCase("impUser")
        sourceCase("impUserQuotaSource", CLOUD)
        for (name in listOf("impDelegates", "impExternal", "impRefused", "impServerError", "impBadExpireTime", "impSourceRefused", "impNested",
            "impSourceUnknown", "impSourceUntyped", "impSourceInvalid", "impBadUrl", "impTwoAccounts", "impUrlWithoutMethod")) sourceCase(name)
        // A 503 and a 429 are retried after the crate's jittered, throttled backoff.
        sourceCase("impRetryUnavailable", slack = null)
        sourceCase("impRetryTooMany", slack = null)
        // The source is a service account: its self-signed cloud-platform JWT authorizes generateAccessToken.
        val (rust, _, replies) = vectors("impServiceAccount")
        val input = GcsAdcVectors.inputs.getValue("impServiceAccountInput")
        val before = now().toLong()
        val (routes, result) = source("impServiceAccount", input, STORAGE, replies)
        assertEquals(rust.b, result)
        assertEquals(1, routes.log.size)
        val source = ServiceAccountKey.from_value((json(input.encodeToByteArray(), unique = false) as Map<*, *>)["source_credentials"])
        val rustBefore = GcsAdcVectors.times.getValue("impServiceAccountBefore")
        val (_, rustHeaders, _) = parse(rust.a[0].hexToByteArray())
        assertJwt(rustHeaders.single { it.first == "authorization" }.second, source, CLOUD, rustBefore - 1..rustBefore + 3, "impServiceAccount Rust")
        assertJwt(routes.log[0].headerValue("authorization")!!, source, CLOUD, before - 1..now().toLong() + 1, "impServiceAccount")
        val bearer = rustHeaders.single { it.first == "authorization" }.second
        assertSameRequest(rust.a[0], routes.log[0].withHeader("authorization", bearer), "impServiceAccount request") { r, o ->
            assertEquals(r.decodeToString(), o.decodeToString())
        }
    }

    @Test
    fun externalAccount() = session {
        sourceCase("extFile")
        sourceCase("extFileCloudPlatform", CLOUD)
        val json = mapOf("extFileJson" to """{"id_token":"eyJ.subject-json.jwt","other":1}""", "extFileJsonMissing" to """{"id_token":"eyJ.subject-json.jwt"}""")
        for (name in listOf("extFileJson", "extFileJsonMissing", "extFileMissing", "extUrl", "extUrlText", "extUrlRefused", "extUrlAndFile", "extImpersonation",
            "extClientAuth", "extWorkforce", "extWorkforceClient", "extWorkforceInvalid", "extStsRefused", "extStsServerError", "extStsNoExpiry",
            "extNoTokenUrl", "extNoSource", "extScopesEmpty")) sourceCase(name) { files ->
            json[name]?.let { write("$files/subject.json", it.encodeToByteArray(), "600") }
        }
    }

    @Test
    fun executableSourced() = session {
        for ((name, allow) in listOf("extExecutable" to true, "extExecutableDisallowed" to false, "extExecutableFailed" to true, "extExecutableSaml" to true,
            "extExecutableExpired" to true, "extExecutableOutputFile" to true)) {
            val output = GcsAdcVectors.inputs.getValue("${name}Output")
            var files = ""
            sourceCase(name, env = if (allow) mapOf("GOOGLE_EXTERNAL_ACCOUNT_ALLOW_EXECUTABLES" to "1") else emptyMap()) {
                files = it
                write("$it/subject.sh", "#!/bin/sh\nprintf '%s\\n' \"\$*\" > \"\$0.args\"\nprintf '%s' '$output'\n".encodeToByteArray(), "700")
                if (name == "extExecutableOutputFile") write("$it/cached.json",
                    """{"version":1,"success":true,"token_type":"urn:ietf:params:oauth:token-type:jwt","id_token":"eyJ.subject-cached.jwt"}""".encodeToByteArray(), "600")
            }
            assertEquals(GcsAdcVectors.inputs.getValue("${name}Args"), read_to_string("$files/subject.sh.args") ?: "", "$name arguments")
        }
    }

    @Test
    fun awsSourced() = session {
        val envs = mapOf(
            "extAws" to emptyMap(),
            "extAwsEnv" to mapOf("AWS_REGION" to "eu-west-2", "AWS_ACCESS_KEY_ID" to "AKIDENV0123", "AWS_SECRET_ACCESS_KEY" to "env/secret+key", "AWS_SESSION_TOKEN" to "env-session"),
            "extAwsEnvNoSession" to mapOf("AWS_DEFAULT_REGION" to "ap-south-1", "AWS_ACCESS_KEY_ID" to "AKIDENV0123", "AWS_SECRET_ACCESS_KEY" to "env/secret+key"),
            "extAwsNoRegion" to emptyMap(),
        )
        val signing = mapOf(
            "extAws" to ("us-east-1" to ("ASIACAPTURE0123" j ("capture/secret+key0123" j "capture-session-token"))),
            "extAwsEnv" to ("eu-west-2" to ("AKIDENV0123" j ("env/secret+key" j "env-session"))),
            "extAwsEnvNoSession" to ("ap-south-1" to ("AKIDENV0123" j ("env/secret+key" j null as String?))),
        )
        for ((name, env) in envs) {
            val (rust, at, replies) = vectors(name)
            val (routes, result) = source(name, GcsAdcVectors.inputs.getValue("${name}Input"), STORAGE, replies, env)
            assertEquals(rust.b, result, name)
            assertEquals(rust.a.size, routes.log.size, "$name requests")
            for (i in 0 until rust.a.size - 1) assertSameRequest(rust.a[i], routes.log[i], "$name request $i")
            val (region, creds) = signing[name] ?: continue
            // The subject token is the signed request at its x-amz-date: ours re-signs Rust's at Rust's second.
            val aws = credential_source(fill(GcsAdcVectors.inputs.getValue("${name}Input"), "").let { (json(it.encodeToByteArray(), unique = false) as Map<*, *>)["credential_source"] },
                "//iam.googleapis.com/projects/123456/locations/global/workloadIdentityPools/loom-pool/providers/loom-provider", HtxClientReactorElement(routes)) as AwsSourcedCredentials
            fun subject(body: ByteArray): String = AwsSourcedCredentials.query_pairs(body.decodeToString()).single { it.first == "subject_token" }.second
            fun second(token: String): ULong {
                val date = Regex("x-amz-date%22%2C%22value%22%3A%22(\\d{8}T\\d{6}Z)").find(token)!!.groupValues[1]
                val iso = "${date.substring(0, 4)}-${date.substring(4, 6)}-${date.substring(6, 8)}T${date.substring(9, 11)}:${date.substring(11, 13)}:${date.substring(13, 15)}Z"
                return rfc3339(iso)!!.a.toULong()
            }
            val rustBody = parse(rust.a.last().hexToByteArray()).third
            val rustToken = subject(rustBody)
            assertEquals(rustToken, aws.signed(region, creds, second(rustToken)), "$name subject token")
            val oursToken = subject(routes.log.last().body.toArray())
            assertEquals(aws.signed(region, creds, second(oursToken)), oursToken, "$name our subject token")
            assertEquals(form(rustBody).filterNot { it.startsWith("subject_token=") }, form(routes.log.last().body.toArray()).filterNot { it.startsWith("subject_token=") }, name)
            assertEquals(at.size, routes.at.size)
        }
    }

    @AfterTest
    fun clean() = runBlocking {
        NioSupervisor().also { it.open() }.let { nio ->
            try {
                nio.service<ProcessOperations>()!!.exec("/bin/rm", listOf("-rf", root), ByteArray(0))
            } finally {
                nio.close()
            }
        }
        Unit
    }
}
