@file:OptIn(ExperimentalEncodingApi::class)

package borg.trikeshed.loom

import borg.trikeshed.htx.*
import borg.trikeshed.job.*
import borg.trikeshed.lib.*
import borg.trikeshed.platform.*
import borg.trikeshed.userspace.*
import borg.trikeshed.util.*
import kotlin.io.encoding.*
import kotlin.time.*
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.*

/*
 * google-cloud-auth 1.16 credentials/external_account.rs from a JSON file, its STS exchange (internal/sts_exchange.rs)
 * and the subject-token sources such a file selects (external_account_sources: file, url, executable, aws).
 */

const val IAM_SCOPE: String = "https://www.googleapis.com/auth/iam"
const val TOKEN_EXCHANGE_GRANT_TYPE: String = "urn:ietf:params:oauth:grant-type:token-exchange"
const val ACCESS_TOKEN_TYPE: String = "urn:ietf:params:oauth:token-type:access_token"
const val JWT_TOKEN_TYPE: String = "urn:ietf:params:oauth:token-type:jwt"
const val SAML2_TOKEN_TYPE: String = "urn:ietf:params:oauth:token-type:saml2"
const val ID_TOKEN_TYPE: String = "urn:ietf:params:oauth:token-type:id_token"

/** subject_token.rs SubjectTokenProvider. */
fun interface SubjectTokenProvider {
    suspend fun subject_token(): String
}

/** external_account.rs is_valid_workforce_pool_audience: `[//iam.googleapis.com/]locations/L/workforcePools/P/providers/R`. */
fun is_valid_workforce_pool_audience(audience: String): Boolean {
    val s = audience.removePrefix("//iam.googleapis.com/").split('/')
    return s.size == 6 && s[0] == "locations" && s[2] == "workforcePools" && s[4] == "providers" && s[3].isNotEmpty() && s[5].isNotEmpty()
}

/**
 * credentials::external_account::Builder::new(json).with_scopes(scopes).build(): ExternalAccountFile (audience,
 * subject_token_type, token_url and credential_source required), [scopes] over the file's, an empty set as cloud-platform,
 * a workforce user project only for a workforce audience; no quota project. Each fetch reads the subject token, exchanges
 * it at `token_url` and, with an impersonation URL, trades the STS token through [generate_access_token].
 */
suspend fun external_account_credentials(json: Any?, scopes: List<String>?, http: HtxClientReactorElement): Credentials {
    val file = try {
        serde_struct(
            json, listOf("audience", "subject_token_type", "service_account_impersonation_url", "token_url", "client_id", "client_secret", "scopes",
                "credential_source", "workforce_pool_user_project", "universe_domain"),
        ).also {
            it[0].serde_string()
            it[1].serde_string()
            it[2].serde_option_string()
            it[3].serde_string()
            it[4].serde_option_string()
            it[5].serde_option_string()
            it[6].serde_option_strings()
            it[8].serde_option_string()
            it[9].serde_option_string()
        }
    } catch (refused: IllegalArgumentException) {
        throw BuilderError("external_account")
    }
    val audience = file[0].serde_string()
    val source = credential_source(file[7], audience, http)
    val workforce_pool_user_project = file[8].serde_option_string()
    if (workforce_pool_user_project != null && !is_valid_workforce_pool_audience(audience)) {
        throw BuilderError("workforce_pool_user_project should not be set for non-workforce pool credentials")
    }
    val scope = (scopes ?: file[6].serde_option_strings() ?: emptyList()).ifEmpty { listOf(DEFAULT_SCOPE) }
    val subject_token_type = file[1].serde_string()
    val impersonation_url = file[2].serde_option_string()
    val token_url = file[3].serde_string()
    val client_id = file[4].serde_option_string()
    val client_secret = file[5].serde_option_string()
    val cache = TokenCache.new {
        val subject_token = source.subject_token()
        val extra_options = if (client_id == null && client_secret == null) {
            workforce_pool_user_project?.let { StringBuilder("{\"userProject\":").apply { quoted(it); append('}') }.toString() }
        } else null
        val sts_scope = if (impersonation_url != null) listOf(IAM_SCOPE) else scope
        val token_res = exchange_token(http, token_url, audience, subject_token, subject_token_type, sts_scope, client_id, client_secret, extra_options)
        if (impersonation_url != null) {
            val bearer = "Bearer ${token_res.a}"
            if (!header_value(bearer)) throw CredentialsError(false, "invalid header value")
            generate_access_token(http, htxHeaders("authorization" j bearer), null, scope, impersonation_url)
        } else Token(token_res.a, token_res.b.a, expires_in(token_res.b.b))
    }
    return Credentials(cache, null)
}

/**
 * sts_exchange.rs STSHandler::exchange_token, form-encoded: grant_type, requested_token_type, subject_token,
 * subject_token_type, scope (space-joined, when any), audience and options, after an `authorization: Basic` of
 * `id:secret` (base64url, no padding) when both are set. Rust orders the pairs by a randomly seeded HashMap; these keep
 * the crate's insertion order. The access token j (token_type j expires_in).
 */
suspend fun exchange_token(
    http: HtxClientReactorElement, url: String, audience: String, subject_token: String, subject_token_type: String, scope: List<String>,
    client_id: String?, client_secret: String?, options: String?,
): Join<String, Join<String, ULong>> {
    val params = listOfNotNull(
        "grant_type" j TOKEN_EXCHANGE_GRANT_TYPE,
        "requested_token_type" j ACCESS_TOKEN_TYPE,
        "subject_token" j subject_token,
        "subject_token_type" j subject_token_type,
        if (scope.isNotEmpty()) "scope" j scope.joinToString(" ") else null,
        "audience" j audience,
        options?.let { "options" j it },
    )
    val headers = listOfNotNull(
        if (client_id != null && client_secret != null) {
            ("authorization" j "Basic " + ServiceAccountKey.JWS.encode("$client_id:$client_secret".encodeToByteArray())).takeIf { header_value(it.b) }
        } else null,
        "content-type" j "application/x-www-form-urlencoded",
    )
    val response = try {
        http.token_request(HtxMethod.POST, url, headers, form_urlencoded(params).encodeToByteArray())
    } catch (e: SendError) {
        throw CredentialsError(!e.is_builder, "failed to exchange token")
    }
    if (response.status !in 200..299) throw CredentialsError(is_retryable_code(response.status), "failed to exchange token")
    return decode(response, listOf("access_token", "issued_token_type", "token_type", "expires_in", "scope", "refresh_token")) {
        it[1].serde_string()
        it[4].serde_option_string()
        it[5].serde_option_string()
        it[0].serde_string() j (it[2].serde_string() j it[3].serde_u64())
    }
}

/** external_account.rs CredentialSourceFile, `#[serde(untagged)]`: the first of Aws, Executable, Url and File it reads as. */
fun credential_source(value: Any?, audience: String, http: HtxClientReactorElement): SubjectTokenProvider {
    fun <T> variant(read: () -> T): T? = try {
        read()
    } catch (refused: IllegalArgumentException) {
        null
    }
    fun format(value: Any?): Join<String, String> = if (value == null || value == Null) "text" j "" else serde_struct(value, listOf("type", "subject_token_field_name")).let {
        it[0].serde_string() j (it[1].serde_option_string() ?: "")
    }
    variant {
        val f = serde_struct(value, listOf("environment_id", "region_url", "url", "regional_cred_verification_url", "imdsv2_session_token_url"))
        f[0].serde_string()
        AwsSourcedCredentials(f[1].serde_option_string(), f[2].serde_option_string(), f[3].serde_option_string(), f[4].serde_option_string(), audience, http)
    }?.let { return it }
    variant {
        val e = serde_struct(serde_struct(value, listOf("executable"))[0], listOf("command", "timeout_millis", "output_file"))
        val command = e[0].serde_string()
        val timeout = if (e[1] == null || e[1] == Null) 30.seconds else (asU64(e[1])?.takeIf { it <= UInt.MAX_VALUE.toULong() } ?: serde_refusal()).toLong().milliseconds
        // split_command (`split_whitespace`): the first word, then the rest as arguments.
        val words = mutableListOf<String>().apply {
            val word = StringBuilder()
            for (c in command + " ") if (c.isWhitespace()) {
                if (word.isNotEmpty()) add(word.toString())
                word.clear()
            } else word.append(c)
        }
        ExecutableSourcedCredentials(words.firstOrNull() ?: command, words.drop(1), timeout, e[2].serde_option_string())
    }?.let { return it }
    variant {
        val f = serde_struct(value, listOf("url", "headers", "format"))
        val headers = when (val h = f[1]) {
            null, Null -> emptyList()
            is Map<*, *> -> h.entries.map { (k, v) -> (k as String).lowercase() j v.serde_string() }
            else -> serde_refusal()
        }
        val (format, field) = format(f[2]).let { it.a to it.b }
        UrlSourcedCredentials(f[0].serde_string(), headers, format, field, http)
    }?.let { return it }
    variant {
        val f = serde_struct(value, listOf("file", "format"))
        val (format, field) = format(f[1]).let { it.a to it.b }
        FileSourcedCredentials(f[0].serde_string(), format, field)
    }?.let { return it }
    throw BuilderError("data did not match any variant of untagged enum CredentialSourceFile")
}

/** std::fs::read_to_string through the ring: the whole file, symlinks followed, as UTF-8; null where it fails. */
fun read_to_string(path: String): String? {
    val ring = Config.ring()
    try {
        val fd = try {
            ring.open(path, UringOp.O_RDONLY, 0)
        } catch (failed: IllegalStateException) {
            return null
        }
        try {
            return Bootstrap.utf8(ring.read_to_end(fd, Int.MAX_VALUE))
        } catch (failed: IllegalStateException) {
            return null
        } finally {
            ring.close(fd)
        }
    } finally {
        ring.closeNow()
    }
}

/** A JSON-format subject token: the string at [field] of the parsed `Value`. */
fun json_subject_token(text: String, field: String): String =
    ((json(text.encodeToByteArray(), unique = false) ?: throw CredentialsError(false, "subject token JSON")) as? Map<*, *>)?.get(field) as? String
        ?: throw CredentialsError(false, "failed to read subject token field `$field` as string")

/** file_sourced.rs: the file's text, or its JSON's [subject_token_field_name] when [format] is `json`. */
class FileSourcedCredentials(val file: String, val format: String, val subject_token_field_name: String) : SubjectTokenProvider {
    override suspend fun subject_token(): String {
        val content = read_to_string(file) ?: throw CredentialsError(false, "subject token file")
        return if (format == "json") json_subject_token(content, subject_token_field_name) else content
    }
}

/** url_sourced.rs: GET [url] with [headers] (a 10 s timeout); the body as text, or its JSON's field when [format] is `json`. */
class UrlSourcedCredentials(val url: String, val headers: List<HtxHeader>, val format: String, val subject_token_field_name: String, val http: HtxClientReactorElement) :
    SubjectTokenProvider {
    override suspend fun subject_token(): String {
        val response = try {
            http.token_request(HtxMethod.GET, url, headers, timeout = 10.seconds)
        } catch (e: SendError) {
            throw CredentialsError(!e.is_builder, "failed to request subject token")
        }
        if (response.status !in 200..299) throw CredentialsError(is_retryable_code(response.status), "failed to request subject token")
        val text = response.body.toArray().decodeToString()
        return if (format == "json") json_subject_token(text, subject_token_field_name) else text
    }
}

/**
 * executable_sourced.rs: a valid response in [output_file] first, else [command] with [args] when
 * GOOGLE_EXTERNAL_ACCOUNT_ALLOW_EXECUTABLES is `1`, within [timeout]; its stdout is the executable response JSON.
 */
class ExecutableSourcedCredentials(val command: String, val args: List<String>, val timeout: Duration, val output_file: String?) : SubjectTokenProvider {
    override suspend fun subject_token(): String {
        if (output_file != null) {
            val content = read_to_string(output_file)
            if (content != null) try {
                return parse_token(content)
            } catch (invalid: CredentialsError) {
            }
        }
        val token = from_command()
        if (token.isEmpty()) throw CredentialsError(false, "failed to read subject token, subject token is empty")
        return token
    }

    suspend fun from_command(): String {
        if ((env_var("GOOGLE_EXTERNAL_ACCOUNT_ALLOW_EXECUTABLES") ?: "0") != "1") {
            throw CredentialsError(false, "executables need to be explicitly allowed (set GOOGLE_EXTERNAL_ACCOUNT_ALLOW_EXECUTABLES to '1') to run")
        }
        val output = try {
            withTimeoutOrNull(timeout) { process { it.exec(command, args, ByteArray(0)) } }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failed: Exception) {
            throw CredentialsError(true, "failed to read subject token")
        } ?: throw CredentialsError(true, "failed to read subject token, timeout")
        if (output.exitCode != 0) {
            Bootstrap.utf8(output.stderr) ?: throw CredentialsError(false, "failed to read subject token, stderr")
            throw CredentialsError(true, "failed to read subject token, command execution failed")
        }
        return parse_token(Bootstrap.utf8(output.stdout) ?: throw CredentialsError(true, "failed to read subject token, stdout"))
    }

    /** ExecutableResponse: version, success and token_type required; an unexpired id_token or saml_response by type. */
    fun parse_token(output: String): String {
        val res = serde_struct(output.encodeToByteArray(), listOf("version", "success", "token_type", "expiration_time", "id_token", "saml_response", "code", "message"))
        val fields = try {
            res ?: serde_refusal()
            (res[0] as? Long)?.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE } ?: serde_refusal()
            res[1].serde_bool()
            res[2].serde_string()
            if (res[3] != null && res[3] != Null && res[3] !is Long) serde_refusal()
            for (i in 4..7) res[i].serde_option_string()
            res
        } catch (refused: IllegalArgumentException) {
            throw CredentialsError(false, "executable response")
        }
        if (!fields[1].serde_bool()) throw CredentialsError(true, "failed to read subject token, response contains unsuccessful response")
        (fields[3] as? Long)?.let { if (it < now().toLong()) throw CredentialsError(true, "the token returned by the executable is expired") }
        return when (fields[2].serde_string()) {
            JWT_TOKEN_TYPE, ACCESS_TOKEN_TYPE, ID_TOKEN_TYPE -> fields[4].serde_option_string() ?: throw CredentialsError(false, "missing `id_token` field")
            SAML2_TOKEN_TYPE -> fields[5].serde_option_string() ?: throw CredentialsError(false, "missing `saml_response` field")
            else -> throw CredentialsError(false, "contains unsupported token type")
        }
    }
}

/**
 * aws_sourced.rs: the IMDSv2 session token (when configured), the region (AWS_REGION, AWS_DEFAULT_REGION or the zone at
 * [region_url]) and credentials (AWS_ACCESS_KEY_ID with AWS_SECRET_ACCESS_KEY, else the role at [role_url]), then a
 * SigV4-signed GetCallerIdentity request as the subject token ([signed]).
 */
class AwsSourcedCredentials(
    val region_url: String?,
    val role_url: String?,
    val regional_cred_verification_url: String?,
    val imdsv2_session_token_url: String?,
    val audience: String,
    val http: HtxClientReactorElement,
) : SubjectTokenProvider {
    override suspend fun subject_token(): String {
        val imdsv2_token = resolve_imdsv2_token()
        val region = resolve_region(imdsv2_token)
        val creds = resolve_credentials(imdsv2_token)
        return signed(region, creds, now())
    }

    suspend fun request(method: HtxMethod, url: String, headers: List<HtxHeader>): HtxResponse {
        val response = try {
            http.token_request(method, url, headers)
        } catch (e: SendError) {
            throw CredentialsError(!e.is_builder, "failed to fetch AWS credentials for subject token")
        }
        if (response.status !in 200..299) throw CredentialsError(is_retryable_code(response.status), "failed to fetch AWS credentials for subject token")
        return response
    }

    suspend fun resolve_imdsv2_token(): String? = imdsv2_session_token_url?.let {
        request(HtxMethod.PUT, it, listOf("x-aws-ec2-metadata-token-ttl-seconds" j "21600")).body.toArray().decodeToString()
    }

    suspend fun get_with_imdsv2_token(url: String, imdsv2_token: String?): HtxResponse =
        request(HtxMethod.GET, url, listOfNotNull(imdsv2_token?.let { "x-aws-ec2-metadata-token" j it }))

    suspend fun resolve_region(imdsv2_token: String?): String {
        env_var("AWS_REGION")?.let { return it }
        env_var("AWS_DEFAULT_REGION")?.let { return it }
        if (region_url != null) {
            parse_region_from_zone(get_with_imdsv2_token(region_url, imdsv2_token).body.toArray().decodeToString())?.let { return it }
        }
        throw CredentialsError(false, "could not resolve AWS region")
    }

    /** access key id j (secret access key j session token). */
    suspend fun resolve_credentials(imdsv2_token: String?): Join<String, Join<String, String?>> {
        val ak = env_var("AWS_ACCESS_KEY_ID")
        val sk = env_var("AWS_SECRET_ACCESS_KEY")
        if (ak != null && sk != null) return ak j (sk j env_var("AWS_SESSION_TOKEN"))
        val url = role_url ?: throw CredentialsError(false, "unable to determine the AWS metadata server security credentials endpoint")
        val role_name = get_with_imdsv2_token(url, imdsv2_token).body.toArray().decodeToString().trim()
        val response = get_with_imdsv2_token(url.trimEnd('/') + "/" + role_name.trim(), imdsv2_token)
        return decode(response, listOf("AccessKeyId", "SecretAccessKey", "Token")) {
            it[0].serde_string() j (it[1].serde_string() j it[2].serde_option_string())
        }
    }

    /** The subject token at Unix second [now]: the form-encoded JSON of the signed GetCallerIdentity request. */
    fun signed(region: String, creds: Join<String, Join<String, String?>>, now: ULong): String {
        val civil = InstantShim.ofEpochMilli(now.toLong() * 1000)
        val date_stamp = civil.substring(0, 4) + civil.substring(5, 7) + civil.substring(8, 10)
        val amz_date = date_stamp + "T" + civil.substring(11, 13) + civil.substring(14, 16) + civil.substring(17, 19) + "Z"
        val sts = (regional_cred_verification_url ?: "https://sts.{region}.amazonaws.com?Action=GetCallerIdentity&Version=2011-06-15").replace("{region}", region)
        val url = Url.parse(if (sts.startsWith("http")) sts else "https://$sts") ?: throw CredentialsError(false, "invalid AWS STS URL")
        val host = url.host ?: throw CredentialsError(false, "invalid AWS STS URL: missing host")
        // BTreeMaps: keys in byte order, a repeated query key's last value.
        val query = LinkedHashMap<String, String>().apply { url.query?.let { for ((k, v) in query_pairs(it)) put(k, v) } }
            .entries.sortedWith { x, y -> codePointOrder(x.key, y.key) }
        val canonical_query = form_urlencoded(query.map { it.key j it.value })
        val headers = listOfNotNull("host" to host, "x-amz-date" to amz_date, creds.b.b?.let { "x-amz-security-token" to it }, "x-goog-cloud-target-resource" to audience)
        val signed_headers = headers.joinToString(";") { it.first }
        val canonical_headers = headers.joinToString("") { "${it.first}:${it.second.trim()}\n" }
        val canonical_request = "POST\n/\n$canonical_query\n$canonical_headers\n$signed_headers\n${hex(sha256(ByteArray(0)))}"
        val credential_scope = "$date_stamp/$region/sts/aws4_request"
        val string_to_sign = "AWS4-HMAC-SHA256\n$amz_date\n$credential_scope\n${hex(sha256(canonical_request.encodeToByteArray()))}"
        var key = ("AWS4" + creds.b.a).encodeToByteArray()
        for (part in listOf(date_stamp, region, "sts", "aws4_request")) key = hmacSha256(key, part.encodeToByteArray())
        val signature = hex(hmacSha256(key, string_to_sign.encodeToByteArray()))
        val authorization = "AWS4-HMAC-SHA256 Credential=${creds.a}/$credential_scope, SignedHeaders=$signed_headers, Signature=$signature"
        val json = StringBuilder("{\"url\":").apply {
            quoted(url.serialization)
            append(",\"method\":\"POST\",\"headers\":[")
            (headers + ("Authorization" to authorization)).forEachIndexed { i, (k, v) ->
                if (i > 0) append(',')
                append("{\"key\":")
                quoted(k)
                append(",\"value\":")
                quoted(v)
                append('}')
            }
            append("],\"body\":\"\"}")
        }.toString()
        return byte_serialize(json)
    }

    companion object {
        fun hex(bytes: ByteArray): String = bytes.toLowerHex()

        /** aws_sourced.rs parse_region_from_zone: a trailing letter after a digit dropped; empty is none. */
        fun parse_region_from_zone(text: String): String? {
            val zone = text.trim()
            if (zone.isEmpty()) return null
            val last = zone.last()
            if ((last in 'a'..'z' || last in 'A'..'Z') && zone.encodeToByteArray().size > 1) {
                val potential = zone.dropLast(1)
                if (potential.lastOrNull()?.let { it in '0'..'9' } == true) return potential
            }
            return zone
        }

        /** url form_urlencoded::parse: `&`-separated pairs, `+` as space, percent-decoded, UTF-8 with replacement. */
        fun query_pairs(query: String): List<Pair<String, String>> = query.split('&').filter { it.isNotEmpty() }.map { piece ->
            val eq = piece.indexOf('=')
            fun decode(text: String): String {
                val bytes = text.encodeToByteArray()
                val out = ArrayList<Byte>(bytes.size)
                var i = 0
                while (i < bytes.size) {
                    val b = bytes[i]
                    val hi = if (i + 2 < bytes.size) hexval(bytes[i + 1]) else -1
                    val lo = if (i + 2 < bytes.size) hexval(bytes[i + 2]) else -1
                    when {
                        b == '+'.code.toByte() -> out += ' '.code.toByte()
                        b == '%'.code.toByte() && hi >= 0 && lo >= 0 -> {
                            out += (hi * 16 + lo).toByte()
                            i += 2
                        }
                        else -> out += b
                    }
                    i++
                }
                return out.toByteArray().decodeToString()
            }
            if (eq < 0) decode(piece) to "" else decode(piece.substring(0, eq)) to decode(piece.substring(eq + 1))
        }

        fun hexval(b: Byte): Int = when (val c = b.toInt().toChar()) {
            in '0'..'9' -> c - '0'
            in 'a'..'f' -> c - 'a' + 10
            in 'A'..'F' -> c - 'A' + 10
            else -> -1
        }
    }
}
