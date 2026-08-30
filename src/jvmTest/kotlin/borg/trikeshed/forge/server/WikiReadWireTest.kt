package borg.trikeshed.forge.server

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The read side of the curation plane. VAL-CROSS-002 could not read a curated artifact through
 * any daemon route: both wiki legos call a model, so reading needed an API key, and the couch
 * attachment plane was empty for an unrelated reason. These routes are the fix, so the traversal
 * guard and the served-bytes-match-their-cid property are what must not regress.
 */
class WikiReadWireTest {

    private fun withPlane(block: (File, WikiReadWire) -> Unit) {
        val root = Files.createTempDirectory("wiki-read-")
        try {
            root.resolve("skills").createDirectories()
            root.resolve("index.md").writeText("# curation index\n")
            root.resolve("skills/retrieval.md").writeText("- retrieval skill\n")
            root.resolve("outside.txt").writeText("not in the plane\n")
            val plane = root.resolve("plane").toFile()
            plane.mkdirs()
            File(plane, "index.md").writeText("# curation index\n")
            File(plane, "skills").mkdirs()
            File(plane, "skills/retrieval.md").writeText("- retrieval skill\n")
            block(plane, WikiReadWire { plane })
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun readServesTheBytesAndTheirContentIdForVerification() = withPlane { plane, wire ->
        val expected = File(plane, "skills/retrieval.md").readBytes()
        val resp = runBlocking { wire.route("GET", "/api/wiki/read?path=skills/retrieval.md", ByteArray(0)) }
        assertNotNull(resp)
        assertEquals(200, resp.status)
        assertTrue(expected.contentEquals(resp.bytes), "served bytes must be the file verbatim")
        // The header is the point: a caller can check the response against a freeze record
        // without a second request, and without trusting the daemon's word for it.
        assertEquals(WikiReadWire.cidOf(expected), resp.headers["X-Content-Id"])
        assertEquals("skills/retrieval.md", resp.headers["X-Wiki-Path"])
    }

    @Test
    fun listEnumeratesThePlaneWithCidsAndNothingElse() = withPlane { plane, wire ->
        val resp = assertNotNull(runBlocking { wire.route("GET", "/api/wiki/list", ByteArray(0)) })
        assertEquals(200, resp.status)
        assertTrue(resp.body.contains("\"path\":\"index.md\""))
        assertTrue(resp.body.contains("\"path\":\"skills/retrieval.md\""))
        assertTrue(resp.body.contains(WikiReadWire.cidOf(File(plane, "index.md").readBytes())))
        assertTrue(resp.body.contains("\"count\":2"), "only the plane's own files: $resp")
        assertTrue(!resp.body.contains("outside.txt"), "the plane's parent is not the plane")
    }

    @Test
    fun traversalOutOfThePlaneIsRefusedRatherThanServed() = withPlane { _, wire ->
        for (attempt in listOf("../outside.txt", "..%2Foutside.txt", "skills/../../outside.txt")) {
            val resp = assertNotNull(runBlocking { wire.route("GET", "/api/wiki/read?path=$attempt", ByteArray(0)) })
            assertEquals(403, resp.status, "escape attempt served: $attempt")
            assertTrue(resp.body.contains("outside_curation_plane"))
            assertNull(resp.bytes)
        }
    }

    @Test
    fun absentFileIs404AndForeignPathIsNotClaimed() = withPlane { _, wire ->
        val missing = assertNotNull(runBlocking { wire.route("GET", "/api/wiki/read?path=nope.md", ByteArray(0)) })
        assertEquals(404, missing.status)
        // A wire that claimed unrelated paths would shadow every route registered after it.
        assertNull(runBlocking { wire.route("GET", "/api/cas/get?cid=sha256:00", ByteArray(0)) })
        assertNull(runBlocking { wire.route("GET", "/kanban.html", ByteArray(0)) })
    }

    @Test
    fun writeMethodsAreRefusedBecauseThisSurfaceIsReadOnly() = withPlane { plane, wire ->
        val before = File(plane, "index.md").readBytes()
        for (method in listOf("POST", "PUT", "DELETE")) {
            val resp = assertNotNull(runBlocking { wire.route(method, "/api/wiki/read?path=index.md", ByteArray(0)) })
            assertEquals(405, resp.status, "$method must not be accepted on a read-only plane")
        }
        assertTrue(before.contentEquals(File(plane, "index.md").readBytes()))
    }
}
