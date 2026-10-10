package borg.trikeshed.loom

import borg.trikeshed.ipns.*
import borg.trikeshed.lib.*
import kotlin.enums.EnumEntries

/** cocaine-rats crates/loom-mesh/src/membership.rs: trust roles, serde snake_case on the wire. */
enum class Role { Replica, Archive, Producer, Reader, Admin }

/** A shared enrolled trust identity; transport admission remains native policy. */
class Member(
    val id: String,
    /** 64 lowercase hex digits of the Ed25519 key. */
    val public_key: String,
    val domain: String = "",
    val url: String? = null,
    val roles: Series<Role>,
) {
    fun has(role: Role): Boolean = role in roles.view

    /** The 32-byte Ed25519 key: ed25519-dalek VerifyingKey::from_bytes ("public key"), then is_weak ("weak public key"). */
    fun key(): ByteArray {
        if (public_key.length != 64 || !public_key.all { it in '0'..'9' || it in 'a'..'f' }) error("public key encoding")
        val key = public_key.hexToByteArray()
        val point = EdwardsPoint()
        if (!point.decode(key)) error("public key")
        if (point.isSmallOrder()) error("weak public key")
        return key
    }

    companion object {
        /** serde_json with deny_unknown_fields: `domain` defaults to "", `url` to null. */
        fun from_json(bytes: ByteArray): Member {
            val (id, public_key, domain, url, roles) = deserialize_struct(bytes, s_["id", "public_key", "domain", "url", "roles"])
            return Member(
                id = (id ?: error("missing field `id`")).deserialize_string("id"),
                public_key = (public_key ?: error("missing field `public_key`")).deserialize_string("public_key"),
                domain = domain?.deserialize_string("domain") ?: "",
                url = url?.let { if (it == Null) null else it.deserialize_string("url") },
                roles = (roles ?: error("missing field `roles`")).let { value ->
                    if (value !is List<*>) error("invalid type: `roles`, expected a sequence")
                    Array(value.size) { value[it].deserialize_enum("roles", Role.entries) }.toSeries()
                },
            )
        }
    }
}

/** Exact RunPod serverless origin grammar: identity syntax only, not DNS, permission or custody. */
fun valid_runpod_endpoint_origin(value: String): Boolean {
    if (!value.startsWith("https://") || !value.endsWith(".api.runpod.ai/") || value.length < 23) return false
    val endpoint = value.substring(8, value.length - 15)
    return endpoint.isNotEmpty() && endpoint.length <= 64 && !endpoint.startsWith('-') && !endpoint.endsWith('-') &&
        endpoint.all { it in 'a'..'z' || it in '0'..'9' || it == '-' }
}

/** Portable trust identities and replica failure-domain labels; not transport URLs or live custody. */
fun validate_custody_members(members: Series<Member>) {
    if (members.size > 64) error("custody bounds")
    val keys = mutableSetOf<String>()
    val ids = mutableSetOf<String>()
    val domains = mutableSetOf<String>()
    for (member in members.view) {
        label(member.id)
        member.key()
        if (!keys.add(member.public_key) || !ids.add(member.id)) error("duplicate trust identity")
        if (member.roles.size == 0 || member.roles.size > 5 || (member.has(Role.Replica) && member.has(Role.Archive)))
            error("invalid trust role")
        if (member.has(Role.Replica) || member.has(Role.Archive)) label(member.domain)
        if (member.has(Role.Replica) && !domains.add(member.domain)) error("duplicate trust domain")
    }
}

/**
 * serde's derived struct with deny_unknown_fields, read as serde_json reads it ([json]): an object whose
 * keys are each one of [fields] at most once, or an array of the fields in declaration order. The values
 * return in [fields] order, null where absent and [Null] where JSON null.
 */
fun deserialize_struct(bytes: ByteArray, fields: Series<String>): Array<Any?> {
    val values = arrayOfNulls<Any>(fields.size)
    when (val value = json(bytes, unique = true) ?: error("invalid JSON")) {
        is Map<*, *> -> for ((name, field) in value) {
            val at = (0 until fields.size).firstOrNull { fields[it] == name } ?: error("unknown field `$name`")
            values[at] = field
        }
        is List<*> -> {
            if (value.size > fields.size) error("invalid length ${value.size}, expected ${fields.size} elements")
            for (at in value.indices) values[at] = value[at]
        }
        else -> error("invalid type: expected a struct")
    }
    return values
}

fun Any.deserialize_string(field: String): String = this as? String ?: error("invalid type: `$field`, expected a string")

/** A unit variant by its serde snake_case name, as a string or an object holding it to null; every variant here is one word. */
fun <E : Enum<E>> Any?.deserialize_enum(field: String, entries: EnumEntries<E>): E {
    val value = this
    val name = when {
        value is String -> value
        value is Map<*, *> && value.size == 1 && value.values.single() == Null -> value.keys.single() as String
        else -> error("invalid type: `$field`, expected a unit variant")
    }
    return entries.firstOrNull { it.name.lowercase() == name } ?: error("unknown variant `$name`")
}

/** A JSON integer 0..=u64::MAX as serde_json reads u64. */
fun Any.deserialize_u64(field: String): ULong = asU64(this) ?: error("invalid type: `$field`, expected u64")
