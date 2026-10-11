package borg.trikeshed.loom

import borg.trikeshed.htx.*
import borg.trikeshed.job.sha256
import borg.trikeshed.lib.*
import borg.trikeshed.userspace.nio.channels.spi.JvmChannelOperations
import borg.trikeshed.util.*
import kotlinx.coroutines.*
import kotlin.test.*

/**
 * gcs.rs against the requests and tokens its Rust build produced for the same inputs ([GcsVectors]): each case
 * scripts a [LoopbackFake] with the replies the Rust harness's fake gave, and the requests it records must equal
 * Rust's ([LoopbackFake.assertSameRequest]) with the same result.
 */
class LoomGcsJvmTest {
    val sa = "loom-handoff@operator-a.iam.gserviceaccount.com"
    val bucket = "operator-a-ledger"
    val relative = "bundle-0001.cbor"
    val objectName = "loom-settlement/v1/bundle-0001.cbor"
    val generation = 1_728_600_000_000_001uL
    val data = ByteArray(37) { (it * 29 + 0x80).toByte() }
    val config = GcsConfig(bucket)

    fun metadata(generation: ULong, hold: Boolean) =
        """{"kind":"storage#object","bucket":"$bucket","name":"$objectName","generation":"$generation","size":"${data.size}","temporaryHold":$hold,"contentType":"application/cbor","cacheControl":"no-store"}"""

    val held = LoopbackFake.json(200, metadata(generation, true))

    fun media(generation: ULong, body: ByteArray) =
        LoopbackFake.reply(200, listOf("content-type" to "application/cbor", "x-goog-generation" to generation.toString()), body)

    fun text(receipt: GcsReceipt) =
        "ok:${receipt.bucket} ${receipt.`object`} ${receipt.generation} ${receipt.size} ${receipt.sha256.toLowerHex()}"

    val loopback: suspend (String, HtxRouteService) -> GcsArchive = { base, routes -> GcsArchive.for_loopback_test(config, base, routes) }

    fun case(
        name: String,
        rust: Join<Series<String>, String>,
        replies: List<ByteArray>,
        archive: suspend (String, HtxRouteService) -> GcsArchive = loopback,
        action: suspend (GcsArchive) -> String,
    ) = runBlocking {
        val fake = LoopbackFake(replies).start()
        val reactor = HtxReactorElement(JvmChannelOperations()).also { it.open() }
        try {
            val gcs = archive(fake.base, reactor)
            val result = try {
                action(gcs)
            } catch (refused: IllegalStateException) {
                "error:${refused.message}"
            } finally {
                gcs.close()
            }
            assertEquals(rust.b, result, name)
            assertEquals(rust.a.size, fake.log.size, name)
            for (i in 0 until rust.a.size) LoopbackFake.assertSameRequest(rust.a[i].hexToByteArray(), fake.log[i], "$name request $i")
        } finally {
            reactor.close()
            fake.stop()
        }
    }

    @Test fun commit() = case("commit", GcsVectors.commit, listOf(held, held, media(generation, data))) { text(it.commit(relative, data)) }

    @Test fun commitPreconditionFailed() =
        case("commitPreconditionFailed", GcsVectors.commitPreconditionFailed, listOf(LoopbackFake.json(412, """{"error":{"code":412}}"""), held, media(generation, data))) {
            text(it.commit(relative, data))
        }

    @Test fun commitServerError() =
        case("commitServerError", GcsVectors.commitServerError, listOf(LoopbackFake.json(503, """{"error":{"code":412}}"""), held, media(generation, data))) {
            text(it.commit(relative, data))
        }

    @Test fun commitRejected() =
        case("commitRejected", GcsVectors.commitRejected, listOf(LoopbackFake.json(403, """{"error":{"code":403}}"""))) { text(it.commit(relative, data)) }

    @Test fun commitUnheld() =
        case("commitUnheld", GcsVectors.commitUnheld, listOf(LoopbackFake.json(200, metadata(generation, false)))) { text(it.commit(relative, data)) }

    @Test fun commitContentMismatch() {
        val other = data.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
        case("commitContentMismatch", GcsVectors.commitContentMismatch, listOf(held, held, media(generation, other))) { text(it.commit(relative, data)) }
    }

    @Test fun commitMediaGeneration() =
        case("commitMediaGeneration", GcsVectors.commitMediaGeneration, listOf(held, held, media(generation + 1uL, data))) { text(it.commit(relative, data)) }

    @Test fun commitBounds() = case("commitBounds", GcsVectors.commitBounds, emptyList()) { text(it.commit("../bundle", data)) }

    @Test fun verify() {
        val receipt = GcsReceipt(bucket, objectName, generation, data.size.toULong(), sha256(data))
        case("verify", GcsVectors.verify, listOf(held, media(generation, data))) { it.verify(receipt, data); text(receipt) }
    }

    @Test fun verifyOutsidePrefix() {
        val receipt = GcsReceipt(bucket, "elsewhere/bundle-0001.cbor", generation, data.size.toULong(), sha256(data))
        case("verifyOutsidePrefix", GcsVectors.verifyOutsidePrefix, emptyList()) { it.verify(receipt, data); text(receipt) }
    }

    @Test fun leasedCommit() = case("leasedCommit", GcsVectors.leasedCommit, listOf(held, held, media(generation, data)), { base, routes ->
        val now = now()
        val lease = Lease.from_json(
            """{"format":"loom-lease/v1","scope":"$sa","issued_at":$now,"not_after":${now + 3600uL},"credential":{"provider":"google","access_token":"ya29.lease-capture-token"}}"""
                .encodeToByteArray(),
        )
        GcsArchive.leased_for_loopback_test(config.copy(service_account = sa), base, routes).also { it.install_lease(lease, now) }
    }) { text(it.commit(relative, data)) }

    @Test fun leasedAbsent() = case("leasedAbsent", GcsVectors.leasedAbsent, emptyList(), { base, routes ->
        GcsArchive.leased_for_loopback_test(config.copy(service_account = sa), base, routes)
    }) { text(it.commit(relative, data)) }

    /** A route service that counts exchanges and serves none: the service-account archive's origin is real GCS. */
    class Refusing : HtxRouteService {
        var exchanges = 0

        override suspend fun exchange(state: HtxExchangeState, request: HtxRequest): HtxExchangeResult {
            exchanges++
            error("no exchange leaves this test")
        }
    }

    @Test
    fun serviceAccountSourcesAgree() = runBlocking {
        @Suppress("UNCHECKED_CAST")
        val valid = json(GcsVectors.serviceAccountJson.encodeToByteArray(), unique = true) as MutableMap<String, Any>
        fun with(key: String, value: Any) = jsonText(LinkedHashMap(valid).also { it[key] = value })
        fun without(key: String) = jsonText(LinkedHashMap(valid).also { it.remove(key) })
        val cases = mapOf(
            "valid" to (config to GcsVectors.serviceAccountJson),
            "authorizedUser" to (config to with("type", "authorized_user")),
            "emailDomain" to (config to with("client_email", "loom-handoff@operator-a.example.com")),
            "tokenUri" to (config to with("token_uri", "https://oauth2.example.com/token")),
            "tokenUriNull" to (config to with("token_uri", Null)),
            "tokenUriAbsent" to (config to without("token_uri")),
            "privateKeyIdAbsent" to (config to without("private_key_id")),
            "projectIdAbsent" to (config to without("project_id")),
            "universeDomainNumber" to (config to with("universe_domain", 5L)),
            "garbageKey" to (config to with("private_key", "-----BEGIN PRIVATE KEY-----\nAAAA\n-----END PRIVATE KEY-----\n")),
            "notJson" to (config to "{"),
            "oversize" to (config to with("client_id", "x".repeat(64 * 1024))),
            "credentialsFile" to (config.copy(credentials_file = "/dev/null") to GcsVectors.serviceAccountJson),
            "badBucket" to (config.copy(bucket = "Operator") to GcsVectors.serviceAccountJson),
        )
        val routes = Refusing()
        for ((name, expected) in GcsVectors.serviceAccountSources.view) {
            val (config, source) = cases.getValue(name)
            val result = try {
                GcsArchive.from_service_account_json(config, source, routes).close()
                "ok"
            } catch (refused: IllegalStateException) {
                "error:${refused.message}"
            }
            assertEquals(expected, result, name)
        }
        assertEquals(cases.size, GcsVectors.serviceAccountSources.size)
        val garbage = GcsArchive.from_service_account_json(config, cases.getValue("garbageKey").second, routes)
        val result = try {
            text(garbage.commit(relative, data))
        } catch (refused: IllegalStateException) {
            "error:${refused.message}"
        } finally {
            garbage.close()
        }
        assertEquals(GcsVectors.garbageKeyCommit, result)
        assertEquals(0, routes.exchanges)
    }

    @Test
    fun serviceAccountTokenIsTheRustBearer() {
        val key = ServiceAccountKey(sa, "0123456789abcdef0123456789abcdef01234567", GcsVectors.serviceAccountKeyPem)
        assertEquals(GcsVectors.jwtBearer, "Bearer " + key.generate(STORAGE_SCOPE, GcsVectors.jwtNow.toULong()))
    }
}
