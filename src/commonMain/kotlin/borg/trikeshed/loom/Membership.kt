package borg.trikeshed.loom

import borg.trikeshed.cursor.*
import borg.trikeshed.lib.*
import borg.trikeshed.parse.confix.*
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

    /**
     * The 32-byte Ed25519 key. Like ed25519-dalek's VerifyingKey::is_weak, the small-order points
     * are refused in every encoding; ed25519-dalek's point decompression ("public key") is not here,
     * so an encoding off the curve passes this check and fails at [IpnsCrypto.verify].
     */
    fun key(): ByteArray {
        if (public_key.length != 64 || !public_key.all { it in '0'..'9' || it in 'a'..'f' }) error("public key encoding")
        val key = public_key.hexToByteArray()
        if (SMALL_ORDER.view.any { point -> (0 until 32).all { i -> (key[i].toInt() xor point[i].toInt()) and (if (i == 31) 0x7f else 0xff) == 0 } })
            error("weak public key")
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
                url = url?.let { if (it.row.tag == IOMemento.IoNothing) null else it.deserialize_string("url") },
                roles = (roles ?: error("missing field `roles`")).let { cell ->
                    if (cell.row.tag != IOMemento.IoArray) error("invalid type: `roles`, expected a sequence")
                    val kids = cell.row.kids
                    Array(kids.size) { (kids[it] j cell.src).deserialize_enum("roles", Role.entries) }.toSeries()
                },
            )
        }
    }
}

/**
 * The small-order Ed25519 points as encoded y with the sign bit clear (libsodium's
 * ge25519_has_small_order list): 0, 1, the two order-8 points, p - 1, p and p + 1.
 */
val SMALL_ORDER: Series<ByteArray> = s_[
    "0000000000000000000000000000000000000000000000000000000000000000",
    "0100000000000000000000000000000000000000000000000000000000000000",
    "26e8958fc2b227b045c3f489f2ef98f0d5dfac05d3c63339b13802886d53fc05",
    "c7176a703d4dd84fba3c0b760d10670f2a2053fa2c39ccc64ec7fd7792ac037a",
    "ecffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff7f",
    "edffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff7f",
    "eeffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff7f",
] α { it.hexToByteArray() }

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
 * serde_json's struct with deny_unknown_fields over the Confix JSON scan: one object whose keys are
 * each one of [fields] at most once. The value cells return in [fields] order, null where absent.
 */
fun deserialize_struct(bytes: ByteArray, fields: Series<String>): Array<ConfixCell?> {
    val doc = confixDoc(bytes, Syntax.JSON)
    val root = doc.root
    if (root == null || root.tag != IOMemento.IoObject || root.kids.size % 2 != 0) error("invalid type: expected a struct")
    val cells = arrayOfNulls<ConfixCell>(fields.size)
    for (k in 0 until root.kids.size step 2) {
        val name = (root.kids[k] j doc.src).deserialize_string("key")
        val at = (0 until fields.size).firstOrNull { fields[it] == name } ?: error("unknown field `$name`")
        if (cells[at] != null) error("duplicate field `$name`")
        cells[at] = root.kids[k + 1] j doc.src
    }
    return cells
}

fun ConfixCell.deserialize_string(field: String): String =
    if (row.tag == IOMemento.IoString) reify() as String else error("invalid type: `$field`, expected a string")

/** A unit variant by its serde snake_case name; every variant here is one word. */
fun <E : Enum<E>> ConfixCell.deserialize_enum(field: String, entries: EnumEntries<E>): E {
    val name = deserialize_string(field)
    return entries.firstOrNull { it.name.lowercase() == name } ?: error("unknown variant `$name`")
}

/** A JSON integer 0..=u64::MAX, digits only, as serde_json reads u64. */
fun ConfixCell.deserialize_u64(field: String): ULong {
    val digits = ByteArray(row.close - row.open + 1) { src[row.open + it] }.decodeToString()
    if (row.tag != IOMemento.IoDouble && row.tag != IOMemento.IoLong && row.tag != IOMemento.IoInt ||
        digits.isEmpty() || !digits.all { it in '0'..'9' } || (digits.length > 1 && digits[0] == '0')
    ) error("invalid type: `$field`, expected u64")
    return digits.toULongOrNull() ?: error("invalid value: `$field`, expected u64")
}
