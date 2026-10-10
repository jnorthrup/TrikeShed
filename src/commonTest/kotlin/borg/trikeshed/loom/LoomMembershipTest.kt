package borg.trikeshed.loom

import borg.trikeshed.lib.*
import kotlin.test.*

/** The capture harness's enrolled members, seeds 1..5. */
fun members(): Series<Member> = s_[
    Member("authority", LoomVectors.PUBLIC_AUTHORITY, roles = s_[Role.Admin]),
    Member("replica-a", LoomVectors.PUBLIC_REPLICA_A, "dc-a", "https://replica-a.example/", s_[Role.Replica]),
    Member("replica-b", LoomVectors.PUBLIC_REPLICA_B, "dc-b", "https://replica-b.example/", s_[Role.Replica]),
    Member("replica-c", LoomVectors.PUBLIC_REPLICA_C, "dc-c", "https://replica-c.example/", s_[Role.Replica]),
    Member("producer", LoomVectors.PUBLIC_PRODUCER, roles = s_[Role.Producer]),
]

fun Member.copy(id: String = this.id, public_key: String = this.public_key, domain: String = this.domain, roles: Series<Role> = this.roles) =
    Member(id, public_key, domain, url, roles)

fun Series<Member>.edit(at: Int, edit: (Member) -> Member): Series<Member> = size j { if (it == at) edit(this[it]) else this[it] }

/** serde_json's rendering of a struct field value. */
fun json(value: String?): String = if (value == null) "null" else "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

/** membership.rs against LoomVectors: serde JSON, Member::key, validate_custody_members, RunPod origins. */
class LoomMembershipTest {
    @Test
    fun memberJsonAgreesWithSerde() {
        for ((name, case) in LoomVectors.memberJson.view) {
            val (input, expected) = case
            val result = try {
                val m = Member.from_json(input.encodeToByteArray())
                "ok:{\"id\":${json(m.id)},\"public_key\":${json(m.public_key)},\"domain\":${json(m.domain)},\"url\":${json(m.url)}," +
                    "\"roles\":[${m.roles.view.joinToString(",") { json(it.name.lowercase()) }}]}"
            } catch (e: IllegalStateException) {
                "error"
            }
            assertEquals(expected, result, name)
        }
    }

    @Test
    fun keyAgreesWithEd25519Dalek() {
        for ((name, case) in LoomVectors.memberKey.view) {
            // point decompression ("public key") is the hermetic Ed25519's; see Member.key
            if (name == "off_curve") continue
            val (public_key, expected) = case
            val result = try {
                assertEquals(public_key, Member("x", public_key, roles = s_[Role.Admin]).key().toHexString(), name)
                "ok"
            } catch (e: IllegalStateException) {
                e.message
            }
            assertEquals(expected, result, name)
        }
    }

    @Test
    fun custodyMembersAgree() {
        val m = members()
        val cases = mapOf(
            "valid" to m,
            "duplicate_key" to m.edit(4) { it.copy(public_key = LoomVectors.PUBLIC_REPLICA_C) },
            "duplicate_id" to m.edit(4) { it.copy(id = "replica-c") },
            "duplicate_domain" to m.edit(3) { it.copy(domain = "dc-b") },
            "replica_and_archive" to m.edit(1) { it.copy(roles = s_[Role.Replica, Role.Archive]) },
            "no_roles" to m.edit(4) { it.copy(roles = emptySeriesOf()) },
            "six_roles" to m.edit(0) { it.copy(roles = s_[Role.Admin, Role.Reader, Role.Producer, Role.Reader, Role.Admin, Role.Reader]) },
            "bad_id" to m.edit(0) { it.copy(id = "auth ority") },
            "replica_without_domain" to m.edit(1) { it.copy(domain = "") },
            "archive_without_domain" to m.edit(4) { it.copy(roles = s_[Role.Archive]) },
            // the bound precedes every key check, so one key serves all 65
            "sixty_five" to (65 j { i: Int -> Member("m$i", LoomVectors.PUBLIC_PRODUCER, roles = s_[Role.Reader]) }),
        )
        for ((name, expected) in LoomVectors.custodyMembers.view) {
            val result = try {
                validate_custody_members(cases.getValue(name))
                "ok"
            } catch (e: IllegalStateException) {
                e.message
            }
            assertEquals(expected, result, name)
        }
    }

    @Test
    fun runpodOriginsAgree() {
        for ((origin, expected) in LoomVectors.runpodOrigin.view) assertEquals(expected, valid_runpod_endpoint_origin(origin), origin)
    }
}
