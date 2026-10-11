package borg.trikeshed.loom

import borg.trikeshed.lib.*

/*
 * cocaine-rats crates/loom-mesh/src/lease.rs: time-bounded provider credentials. A lease is one provider
 * credential plus the window in which LOOM may use it; every consumer refuses it once now + CLOCK_SKEW
 * reaches not_after. Leases hold secrets and never travel in ledger records; the receipt is the
 * non-secret evidence the key inventory consumes. The keeper that mints them is tools/ Keeper.kt.
 */
const val FORMAT: String = "loom-lease/v1"
const val RECEIPT_FORMAT: String = "loom-lease-receipt/v1"

/** Shortest selectable lease. Shorter windows cannot survive one cold start. */
const val MIN_SECS: ULong = 60uL
const val GOOGLE_STORAGE_SCOPE: String = "https://www.googleapis.com/auth/devstorage.read_write"

/** serde snake_case on the wire. */
enum class Provider {
    Google, Cloudflare, Vast, Runpod;

    fun enforcement(): Enforcement = when (this) {
        Google, Cloudflare -> Enforcement.Provider
        Vast -> Enforcement.Revocation
        Runpod -> Enforcement.Consumer
    }

    fun purpose(): Purpose = when (this) {
        Google, Cloudflare -> Purpose.Archive
        Runpod -> Purpose.Invocation
        Vast -> Purpose.Management
    }

    /**
     * Longest selectable window. Google's 12 h requires the service account to be allowed by
     * `constraints/iam.allowServiceAccountCredentialLifetimeExtension`.
     */
    fun max_secs(extended: Boolean): ULong = when (this) {
        Google -> if (extended) 43_200uL else 3_600uL
        Cloudflare -> 604_800uL
        Vast, Runpod -> 2_592_000uL
    }

    companion object {
        fun parse(value: String): Provider = entries.firstOrNull { it.name.lowercase() == value } ?: error("unknown lease provider")
    }
}

/** What the credential may be used for inside LOOM. */
enum class Purpose {
    /** Offsite settlement-evidence archive writes. */
    Archive,

    /** Provider edge authorization for calling an enrolled mesh endpoint. */
    Invocation,

    /** Provider management API (provisioning); never accepted by the mesh. */
    Management,
}

enum class Enforcement {
    /** The provider rejects the credential after expiry. */
    Provider,

    /** The provider key never expires; the keeper must delete it at expiry. */
    Revocation,

    /** No provider expiry or revocation API; only LOOM consumers refuse it. */
    Consumer,
}

/** Provider-shaped secret material, serde internally tagged by `provider` with deny_unknown_fields. */
sealed class Credential {
    class Google(val access_token: String) : Credential()

    class Cloudflare(
        val account_id: String,
        val bucket: String,
        val access_key_id: String,
        val secret_access_key: String,
        val session_token: String,
    ) : Credential()

    class Vast(val key_id: ULong, val api_key: String) : Credential()

    class Runpod(val api_key: String) : Credential()

    fun provider(): Provider = when (this) {
        is Google -> Provider.Google
        is Cloudflare -> Provider.Cloudflare
        is Vast -> Provider.Vast
        is Runpod -> Provider.Runpod
    }

    companion object {
        /**
         * serde's internally tagged enum: a map holding `provider` and exactly the variant's fields, or a
         * sequence of the tag then the fields in order. The tag is a variant name or its index.
         */
        fun deserialize(value: Any?): Credential {
            val tag = when (value) {
                is Map<*, *> -> value["provider"] ?: error("missing field `provider`")
                is List<*> -> value.firstOrNull() ?: error("missing field `provider`")
                else -> error("invalid type: `credential`, expected an internally tagged enum")
            }
            val provider = when (tag) {
                is String -> Provider.entries.firstOrNull { it.name.lowercase() == tag }
                is Long -> Provider.entries.getOrNull(if (tag in 0..3) tag.toInt() else -1)
                else -> null
            } ?: error("unknown variant `$tag`")
            val fields = when (provider) {
                Provider.Google -> s_["access_token"]
                Provider.Cloudflare -> s_["account_id", "bucket", "access_key_id", "secret_access_key", "session_token"]
                Provider.Vast -> s_["key_id", "api_key"]
                Provider.Runpod -> s_["api_key"]
            }
            val values = arrayOfNulls<Any>(fields.size)
            if (value is Map<*, *>) {
                for ((name, field) in value) if (name != "provider") {
                    val at = (0 until fields.size).firstOrNull { fields[it] == name } ?: error("unknown field `$name`")
                    values[at] = field
                }
            } else {
                val items = (value as List<*>).drop(1)
                if (items.size != fields.size) error("invalid length ${items.size}, expected ${fields.size} elements")
                items.forEachIndexed { at, field -> values[at] = field }
            }
            fun text(at: Int) = (values[at] ?: error("missing field `${fields[at]}`")).deserialize_string(fields[at])
            return when (provider) {
                Provider.Google -> Google(text(0))
                Provider.Cloudflare -> Cloudflare(text(0), text(1), text(2), text(3), text(4))
                Provider.Vast -> Vast((values[0] ?: error("missing field `key_id`")).deserialize_u64("key_id"), text(1))
                Provider.Runpod -> Runpod(text(0))
            }
        }
    }
}

class Lease(
    val format: String,
    /** Google: service-account email. Cloudflare: `bucket/prefix`. Vast: key name. Runpod: exact endpoint origin. */
    val scope: String,
    val issued_at: ULong,
    val not_after: ULong,
    val credential: Credential,
) {
    fun provider(): Provider = credential.provider()

    fun purpose(): Purpose = provider().purpose()

    fun enforcement(): Enforcement = provider().enforcement()

    /** Structure, scope, secret shape and window bounds. Does not consult the clock: an expired lease is well-formed but not usable. */
    fun validate() {
        val provider = provider()
        val span = if (not_after > issued_at) not_after - issued_at else 0uL
        if (format != FORMAT || not_after > Long.MAX_VALUE.toULong() || span < MIN_SECS || span > provider.max_secs(true)) {
            error("invalid lease window")
        }
        val ok = when (val c = credential) {
            is Credential.Google -> service_account_email(scope) && bearer_token(c.access_token, 16 * 1024)
            is Credential.Cloudflare ->
                c.account_id.length == 32 && c.account_id.all { it in '0'..'9' || it in 'a'..'f' } && r2_bucket(c.bucket) &&
                    scope.startsWith(c.bucket + "/") && object_prefix(scope.substring(c.bucket.length + 1)) &&
                    charset(c.access_key_id, 1, 128, "") && charset(c.secret_access_key, 1, 256, "+/=") &&
                    charset(c.session_token, 1, 8192, "+/=-_.")
            is Credential.Vast -> c.key_id > 0uL && runCatching { label(scope) }.isSuccess && charset(c.api_key, 16, 512, "-_")
            is Credential.Runpod -> valid_runpod_endpoint_origin(scope) && charset(c.api_key, 32, 512, "-_")
        }
        if (!ok) error("invalid lease credential")
    }

    /** Usable now: well-formed, not from the future and not within the skew margin of expiry. */
    fun check_usable(now: ULong) {
        validate()
        val skewed = if (now > ULong.MAX_VALUE - CLOCK_SKEW) ULong.MAX_VALUE else now + CLOCK_SKEW
        if (issued_at > skewed) error("lease issued in the future")
        if (skewed >= not_after) error("lease expired")
    }

    /** The `Authorization` value for bearer-token providers, refused where http's HeaderValue would refuse it. */
    fun bearer(): String {
        val token = when (val c = credential) {
            is Credential.Google -> c.access_token
            is Credential.Vast -> c.api_key
            is Credential.Runpod -> c.api_key
            is Credential.Cloudflare -> error("R2 leases are not bearer tokens")
        }
        val value = "Bearer $token"
        if (!value.encodeToByteArray().all { (it.toInt() and 0xFF).let { b -> b >= 32 && b != 127 || b == 9 } }) error("invalid lease credential")
        return value
    }

    fun receipt(): LeaseReceipt = LeaseReceipt(
        RECEIPT_FORMAT, provider(), purpose(), enforcement(), scope, issued_at, not_after,
        when (val c = credential) {
            is Credential.Vast -> c.key_id.toString()
            is Credential.Cloudflare -> c.access_key_id
            else -> null
        },
    )

    /** serde_json::to_vec: the struct's fields in order, the credential tagged first. */
    fun to_json(): ByteArray = StringBuilder("{\"format\":").apply {
        quoted(format)
        append(",\"scope\":")
        quoted(scope)
        append(",\"issued_at\":").append(issued_at).append(",\"not_after\":").append(not_after)
        append(",\"credential\":{\"provider\":")
        quoted(provider().name.lowercase())
        fun field(name: String, value: String) {
            append(",\"").append(name).append("\":")
            quoted(value)
        }
        when (val c = credential) {
            is Credential.Google -> field("access_token", c.access_token)
            is Credential.Cloudflare -> {
                field("account_id", c.account_id)
                field("bucket", c.bucket)
                field("access_key_id", c.access_key_id)
                field("secret_access_key", c.secret_access_key)
                field("session_token", c.session_token)
            }
            is Credential.Vast -> {
                append(",\"key_id\":").append(c.key_id)
                field("api_key", c.api_key)
            }
            is Credential.Runpod -> field("api_key", c.api_key)
        }
        append("}}")
    }.toString().encodeToByteArray()

    companion object {
        /** serde_json with deny_unknown_fields, then [validate]; every serde refusal is "invalid lease". */
        fun from_json(bytes: ByteArray): Lease {
            if (bytes.size > 32 * 1024) error("lease size")
            val lease = try {
                val fields = s_["format", "scope", "issued_at", "not_after", "credential"]
                val values = deserialize_struct(bytes, fields)
                fun value(at: Int) = values[at] ?: error("missing field `${fields[at]}`")
                Lease(
                    value(0).deserialize_string("format"),
                    value(1).deserialize_string("scope"),
                    value(2).deserialize_u64("issued_at"),
                    value(3).deserialize_u64("not_after"),
                    Credential.deserialize(value(4)),
                )
            } catch (refused: IllegalStateException) {
                error("invalid lease")
            }
            lease.validate()
            return lease
        }
    }
}

class LeaseReceipt(
    val format: String,
    val provider: Provider,
    val purpose: Purpose,
    val enforcement: Enforcement,
    val scope: String,
    val issued_at: ULong,
    val not_after: ULong,
    /** Public provider key identity (Vast key id, R2 access key id). */
    val key_id: String? = null,
) {
    /** serde_json::to_vec: fields in order, `key_id` written as null when absent. */
    fun to_json(): ByteArray = StringBuilder("{\"format\":").apply {
        quoted(format)
        append(",\"provider\":")
        quoted(provider.name.lowercase())
        append(",\"purpose\":")
        quoted(purpose.name.lowercase())
        append(",\"enforcement\":")
        quoted(enforcement.name.lowercase())
        append(",\"scope\":")
        quoted(scope)
        append(",\"issued_at\":").append(issued_at).append(",\"not_after\":").append(not_after).append(",\"key_id\":")
        if (key_id == null) append("null") else quoted(key_id)
        append('}')
    }.toString().encodeToByteArray()

    companion object {
        /** serde_json with deny_unknown_fields: `key_id` defaults to null. */
        fun from_json(bytes: ByteArray): LeaseReceipt {
            val fields = s_["format", "provider", "purpose", "enforcement", "scope", "issued_at", "not_after", "key_id"]
            val values = deserialize_struct(bytes, fields)
            fun value(at: Int) = values[at] ?: error("missing field `${fields[at]}`")
            return LeaseReceipt(
                value(0).deserialize_string("format"),
                value(1).deserialize_enum("provider", Provider.entries),
                value(2).deserialize_enum("purpose", Purpose.entries),
                value(3).deserialize_enum("enforcement", Enforcement.entries),
                value(4).deserialize_string("scope"),
                value(5).deserialize_u64("issued_at"),
                value(6).deserialize_u64("not_after"),
                values[7]?.let { if (it == Null) null else it.deserialize_string("key_id") },
            )
        }
    }
}

fun charset(value: String, min: Int, max: Int, extra: String): Boolean =
    value.length in min..max && value.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it in extra }

fun bearer_token(value: String, max: Int): Boolean = charset(value, 1, max, "-._~+/=")

fun service_account_email(value: String): Boolean {
    val at = value.indexOf('@')
    if (at < 0) return false
    val local = value.substring(0, at)
    val domain = value.substring(at + 1)
    return value.length <= 128 && local.length in 6..30 && local.all { it in 'a'..'z' || it in '0'..'9' || it == '-' } &&
        domain.endsWith(".gserviceaccount.com") && domain.all { it in 'a'..'z' || it in '0'..'9' || it == '-' || it == '.' }
}

fun r2_bucket(value: String): Boolean =
    value.length in 3..63 && value.all { it in 'a'..'z' || it in '0'..'9' || it == '-' } && !value.startsWith('-') && !value.endsWith('-')

fun object_prefix(value: String): Boolean =
    value.isNotEmpty() && value.length <= 128 && value.split('/').all { part ->
        part.isNotEmpty() && part != "." && part != ".." &&
            part.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '-' || it == '_' || it == '.' }
    }

/** Trusted wall clock for keepers and fixtures. */
fun keeper_now(): ULong = now()

/** `90`, `90s`, `30m`, `1h`, `7d` → seconds. */
fun parse_duration(value: String): ULong {
    val unit = when (value.lastOrNull()) {
        's' -> 1uL
        'm' -> 60uL
        'h' -> 3_600uL
        'd' -> 86_400uL
        else -> 0uL
    }
    val digits = if (unit == 0uL) value else value.dropLast(1)
    if (digits.isEmpty() || digits.length > 9 || !digits.all { it in '0'..'9' }) error("invalid lease duration")
    return digits.toULong() * (if (unit == 0uL) 1uL else unit)
}

/** Validate a requested window for a provider before any provider call. */
fun selectable(provider: Provider, secs: ULong, extended: Boolean): ULong {
    if (extended && provider != Provider.Google) error("extended lifetime applies only to Google")
    if (secs !in MIN_SECS..provider.max_secs(extended)) error("lease duration outside provider bounds")
    return secs
}

/** RFC 3339 UTC (`2026-09-30T18:00:00Z`, optional fraction) → Unix seconds; Howard Hinnant's days_from_civil. */
fun rfc3339_utc(value: String): ULong {
    fun bad(): Nothing = error("invalid provider timestamp")
    if (!value.endsWith('Z')) bad()
    val body = value.dropLast(1)
    val dot = body.indexOf('.')
    val whole = (if (dot < 0) body else body.substring(0, dot)).encodeToByteArray()
    val fraction = if (dot < 0) "0" else body.substring(dot + 1)
    fun at(i: Int, c: Char) = whole[i] == c.code.toByte()
    if (whole.size != 19 || !at(4, '-') || !at(7, '-') || !at(10, 'T') || !at(13, ':') || !at(16, ':') ||
        fraction.isEmpty() || !fraction.all { it in '0'..'9' }
    ) bad()
    fun n(from: Int, to: Int): Long {
        var value = 0L
        for (i in from until to) {
            val digit = whole[i] - '0'.code.toByte()
            if (digit !in 0..9) bad()
            value = value * 10 + digit
        }
        return value
    }
    val year = n(0, 4)
    val mo = n(5, 7)
    val d = n(8, 10)
    val h = n(11, 13)
    val mi = n(14, 16)
    val s = n(17, 19)
    if (mo !in 1L..12L || d !in 1L..31L || h > 23 || mi > 59 || s > 60) bad()
    val y = if (mo <= 2) year - 1 else year
    val era = y.floorDiv(400)
    val yoe = y - era * 400
    val mp = (mo + 9) % 12
    val doy = (153 * mp + 2) / 5 + d - 1
    val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
    val seconds = (era * 146_097 + doe - 719_468) * 86_400 + h * 3_600 + mi * 60 + s
    if (seconds < 0) bad()
    return seconds.toULong()
}
