@file:OptIn(ExperimentalStdlibApi::class)

package borg.trikeshed.loom

import borg.trikeshed.htx.*
import borg.trikeshed.lib.*
import borg.trikeshed.userspace.nio.channels.spi.JvmChannelOperations
import kotlinx.coroutines.*
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.*

/**
 * lease.rs `keeper` against the requests and results its Rust build produced for the same inputs ([KeeperVectors]):
 * each case scripts a [LoopbackFake] with the reply the Rust harness's fake gave, and the requests it records must
 * equal Rust's ([LoopbackFake.assertSameRequest]) with the same lease, receipt or error.
 */
class KeeperJvmTest {
    val now = 1_791_590_400uL
    val sa = "loom-handoff@operator-a.iam.gserviceaccount.com"
    val source = "Bearer ya29.source-capture-token"
    val account = "0123456789abcdef0123456789abcdef"
    val master = "vast_master_key_0123456789"
    val permissions = json("""{"api":{"instance_read":{},"instance_write":{}}}""".encodeToByteArray(), unique = false)!!
    val minted = LoopbackFake.json(200, """{"accessToken":"ya29.minted-capture-token","expireTime":"2026-10-10T00:59:58.123456789Z"}""")
    val r2 = LoopbackFake.json(
        200,
        """{"success":true,"errors":[],"messages":[],"result":{"accessKeyId":"AKIDTEMPCAPTURE","secretAccessKey":"c2VjcmV0LWNhcHR1cmU=","sessionToken":"session-token.capture-0123"}}""",
    )

    fun text(lease: Lease) = "ok:" + lease.to_json().decodeToString() + " " + lease.receipt().to_json().decodeToString()

    fun case(name: String, rust: Join<Series<String>, String>, replies: List<ByteArray>, action: suspend (Keeper.Http, String) -> String) = runBlocking {
        val fake = LoopbackFake(replies).start()
        val reactor = HtxReactorElement(JvmChannelOperations()).also { it.open() }
        try {
            val http = Keeper.Http.for_loopback_test(reactor)
            val result = try {
                action(http, fake.base)
            } catch (refused: IllegalStateException) {
                "error:${refused.message}"
            } finally {
                http.close()
            }
            assertEquals(rust.b, result, name)
            assertEquals(rust.a.size, fake.log.size, name)
            for (i in 0 until rust.a.size) LoopbackFake.assertSameRequest(rust.a[i].hexToByteArray(), fake.log[i], "$name request $i")
        } finally {
            reactor.close()
            fake.stop()
        }
    }

    @Test
    fun google() {
        case("mintGoogle", KeeperVectors.mintGoogle, listOf(minted)) { http, base -> text(Keeper.mint_google(http, base, source, sa, 3600uL, false, now)) }
        case("mintGoogleExtended", KeeperVectors.mintGoogleExtended, listOf(LoopbackFake.json(200, """{"accessToken":"ya29.minted-capture-token","expireTime":"2026-10-10T12:00:00Z"}"""))) { http, base ->
            text(Keeper.mint_google(http, base, source, sa, 43_200uL, true, now))
        }
        case("mintGoogleRefused", KeeperVectors.mintGoogleRefused, listOf(LoopbackFake.json(403, """{"error":{"code":403,"status":"PERMISSION_DENIED"}}"""))) { http, base ->
            text(Keeper.mint_google(http, base, source, sa, 3600uL, false, now))
        }
        case("mintGoogleTimestamp", KeeperVectors.mintGoogleTimestamp, listOf(LoopbackFake.json(200, """{"accessToken":"ya29.minted-capture-token","expireTime":"2026-10-10 00:59:58Z"}"""))) { http, base ->
            text(Keeper.mint_google(http, base, source, sa, 3600uL, false, now))
        }
        case("mintGoogleTarget", KeeperVectors.mintGoogleTarget, emptyList()) { http, base -> text(Keeper.mint_google(http, base, source, "Not-An-Email", 3600uL, false, now)) }
        case("mintGoogleDuration", KeeperVectors.mintGoogleDuration, emptyList()) { http, base -> text(Keeper.mint_google(http, base, source, sa, 3601uL, false, now)) }
        case("mintGoogleOrigin", KeeperVectors.mintGoogleOrigin, emptyList()) { http, _ -> text(Keeper.mint_google(http, "http://example.com/", source, sa, 3600uL, false, now)) }
    }

    @Test
    fun cloudflare() {
        val unsuccessful = LoopbackFake.json(200, """{"success":false,"errors":[{"code":10000,"message":"Authentication error"}],"result":null}""")
        case("mintCloudflare", KeeperVectors.mintCloudflare, listOf(r2)) { http, base ->
            text(Keeper.mint_cloudflare(http, base, "cf_api_token-capture", account, "operator-a-archive", "settlement", "AKIDPARENTCAPTURE", 86_400uL, now))
        }
        case("mintCloudflareUnsuccessful", KeeperVectors.mintCloudflareUnsuccessful, listOf(unsuccessful)) { http, base ->
            text(Keeper.mint_cloudflare(http, base, "cf_api_token-capture", account, "operator-a-archive", "settlement", "AKIDPARENTCAPTURE", 86_400uL, now))
        }
        case("mintCloudflareToken", KeeperVectors.mintCloudflareToken, emptyList()) { http, base ->
            text(Keeper.mint_cloudflare(http, base, "cf api token", account, "operator-a-archive", "settlement", "AKIDPARENTCAPTURE", 86_400uL, now))
        }
    }

    @Test
    fun vast() {
        case("mintVast", KeeperVectors.mintVast, listOf(LoopbackFake.json(200, """{"success":true,"id":4242,"key":"vast_scoped_key_0123456789abcdef"}"""))) { http, base ->
            text(Keeper.mint_vast(http, base, master, "operator-a-keeper", permissions, 7200uL, now))
        }
        case("mintVastPermissions", KeeperVectors.mintVastPermissions, emptyList()) { http, base ->
            text(Keeper.mint_vast(http, base, master, "operator-a-keeper", listOf(1L), 7200uL, now))
        }
        for ((name, status) in listOf("revokeVast" to 200, "revokeVastAbsent" to 404, "revokeVastRefused" to 500)) {
            val rust = mapOf("revokeVast" to KeeperVectors.revokeVast, "revokeVastAbsent" to KeeperVectors.revokeVastAbsent, "revokeVastRefused" to KeeperVectors.revokeVastRefused)
            case(name, rust.getValue(name), listOf(LoopbackFake.json(status, """{"success":true}"""))) { http, base -> "ok:" + Keeper.revoke_vast(http, base, master, 4242uL) }
        }
    }

    @Test
    fun runpod() = case("wrapRunpod", KeeperVectors.wrapRunpod, emptyList()) { _, _ ->
        text(Keeper.wrap_runpod("https://abc123.api.runpod.ai/", "rpa_capture_key_0123456789abcdefghijklmnop", 600uL, now))
    }

    /** `loom-lease` refuses the same arguments with the same message, before any file read or provider call, and exits 2. */
    @Test
    fun cliAgrees() = runBlocking {
        assertEquals(2, binaries.getValue("loom-lease").a)
        val reactor = HtxReactorElement(JvmChannelOperations()).also { it.open() }
        try {
            for ((args, message) in KeeperVectors.cli) {
                val result = try {
                    Keeper.cli(args, reactor)
                    "ok"
                } catch (refused: IllegalStateException) {
                    refused.message
                }
                assertEquals(message, result, args.toString())
            }
        } finally {
            reactor.close()
        }
    }

    /** The JDK's view of what write_new leaves: a new 0600 file, no clobber, and no write through a final symlink. */
    @Test
    fun writeNewCreatesOneOwnerOnlyFile() {
        val dir = Files.createTempDirectory("keeper")
        try {
            val path = dir.resolve("lease.json")
            Keeper.write_new(path.toString(), "{}".encodeToByteArray())
            assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(path)))
            assertContentEquals("{}".encodeToByteArray(), Files.readAllBytes(path))
            assertEquals("cannot create lease output", assertFailsWith<IllegalStateException> { Keeper.write_new(path.toString(), ByteArray(1)) }.message)
            val link = Files.createSymbolicLink(dir.resolve("link"), dir.resolve("target"))
            assertEquals("cannot create lease output", assertFailsWith<IllegalStateException> { Keeper.write_new(link.toString(), ByteArray(1)) }.message)
            assertFalse(Files.exists(dir.resolve("target")))
        } finally {
            Files.walk(dir).sorted(Comparator.reverseOrder()).forEach(Files::delete)
        }
    }
}
