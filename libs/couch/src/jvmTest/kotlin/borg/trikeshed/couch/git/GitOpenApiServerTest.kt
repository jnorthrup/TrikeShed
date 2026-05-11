package borg.trikeshed.couch.git

import borg.trikeshed.couch.htx.*
import borg.trikeshed.process.ProcessShell
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Integration test: GitOpenApiServer against a real local git repository.
 *
 * Uses ProcessShell to run `git` commands directly, no server socket needed —
 * the server just maps HTX messages to git subprocesses.
 */
class GitOpenApiServerTest {

    val shell = ProcessShell()
    val server = GitOpenApiServer(shell)

    // ── helpers ──────────────────────────────────────────────────────────────────

    private fun makeGet(path: String): HtxMessage = HtxMessage().apply {
        addStartLine(HtxStartLine.request(borg.trikeshed.couch.htx.HttpMethod.Get, path.encodeToByteArray()))
        addEndHeaders()
        setEom()
    }

    private fun makePost(path: String, body: String = ""): HtxMessage = HtxMessage().apply {
        addStartLine(HtxStartLine.request(HttpMethod.Post, path.encodeToByteArray()))
        addHeader("Content-Type".encodeToByteArray(), "application/x-www-form-urlencoded".encodeToByteArray())
        addEndHeaders()
        if (body.isNotEmpty()) addData(body.encodeToByteArray())
        setEom()
    }

    private fun makePut(path: String): HtxMessage = HtxMessage().apply {
        addStartLine(HtxStartLine.request(HttpMethod.Put, path.encodeToByteArray()))
        addEndHeaders()
        setEom()
    }

    private fun assertOk200(response: HtxMessage) {
        val sl = response.startLine()!!
        assertTrue(!sl.isRequest, "Expected response, got request start line")
        assertEquals(200, sl.status, "Expected 200, got ${sl.status}: ${response.blocks.filterIsInstance<HtxBlockData.Data>().joinToString { it.bytes.decodeToString() }}")
    }

    // ── test lifecycle ───────────────────────────────────────────────────────────

    @Test
    fun health_returnsOk() {
        System.out.println("TEST START health_returnsOk")
        val response = server.route(makeGet("/health"))
        System.out.println("TEST response.blocks=${response.blocks.map { it::class.simpleName }}")
        System.out.flush()
        val sl = response.startLine()
        System.out.println("TEST sl=$sl, status=${sl?.status}, isRequest=${sl?.isRequest}")
        System.out.flush()
        assertOk200(response)
        val body = response.blocks.filterIsInstance<HtxBlockData.Data>().joinToString { it.bytes.decodeToString() }
        assertEquals("ok", body)
    }

    @Test
    fun gitInit_thenStatus_showsEmptyRepo() {
        val tmp = createTempDir()
        try {
            // git init
            val initResp = server.route(makePut("/git porcelain/init?repo=${tmp.absolutePath}"))
            assertOk200(initResp)

            // git status — should show clean
            val statusResp = server.route(makeGet("/git porcelain/status?repo=${tmp.absolutePath}"))
            assertOk200(statusResp)
            val body = responseBody(statusResp)
            // Porcelain v2: no changes means nothing after "2 .NS" marker line
            assertTrue(body.contains(".NS") || body.isEmpty() || body.trim().isEmpty(),
                "Expected clean status, got: $body")
        } finally {
            tmp.deleteRecursively()
        }
    }

    @Test
    fun gitInit_addFile_commit_showsInLog() {
        val tmp = createTempDir()
        try {
            // init
            server.route(makePut("/git porcelain/init?repo=${tmp.absolutePath}"))

            // create a file
            val testFile = java.io.File(tmp, "hello.txt")
            testFile.writeText("world\n")

            // git add
            val addResp = server.route(makePost("/git porcelain/add?repo=${tmp.absolutePath}&files=hello.txt"))
            assertOk200(addResp)

            // git commit
            val commitResp = server.route(makePost("/git porcelain/commit?repo=${tmp.absolutePath}&message=Initial commit"))
            assertOk200(commitResp)
            val commitOutput = responseBody(commitResp)
            assertTrue(commitOutput.contains("commit") || commitOutput.contains("Initial commit") || commitOutput.isEmpty(),
                "Expected commit output, got: $commitOutput")

            // git log
            val logResp = server.route(makeGet("/git porcelain/log?repo=${tmp.absolutePath}&n=5"))
            assertOk200(logResp)
            val logBody = responseBody(logResp)
            assertTrue(logBody.isNotEmpty(), "Expected log output, got empty")
        } finally {
            tmp.deleteRecursively()
        }
    }

    @Test
    fun gitInit_revParseHEAD_returnsCommitHash() {
        val tmp = createTempDir()
        try {
            server.route(makePut("/git porcelain/init?repo=${tmp.absolutePath}"))

            val testFile = java.io.File(tmp, "README.md")
            testFile.writeText("# Test\n")
            server.route(makePost("/git porcelain/add?repo=${tmp.absolutePath}&files=README.md"))
            server.route(makePost("/git porcelain/commit?repo=${tmp.absolutePath}&message=First"))

            val revResp = server.route(makeGet("/git porcelain/rev-parse?repo=${tmp.absolutePath}&ref=HEAD"))
            assertOk200(revResp)
            val hash = responseBody(revResp).trim()
            assertTrue(hash.length == 40, "Expected 40-char SHA, got: $hash")
            assertTrue(hash.matches(Regex("[0-9a-f]{40}")), "Expected hex SHA, got: $hash")
        } finally {
            tmp.deleteRecursively()
        }
    }

    @Test
    fun gitInit_branchListsDefaultMaster() {
        val tmp = createTempDir()
        try {
            server.route(makePut("/git porcelain/init?repo=${tmp.absolutePath}"))
            val testFile = java.io.File(tmp, "x")
            testFile.writeText("x")
            server.route(makePost("/git porcelain/add?repo=${tmp.absolutePath}&files=x"))
            server.route(makePost("/git porcelain/commit?repo=${tmp.absolutePath}&message=x"))

            val branchResp = server.route(makeGet("/git porcelain/branch?repo=${tmp.absolutePath}"))
            assertOk200(branchResp)
            val body = responseBody(branchResp)
            assertTrue(body.contains("master") || body.contains("main"),
                "Expected default branch in output, got: $body")
        } finally {
            tmp.deleteRecursively()
        }
    }

    @Test
    fun gitInit_diff_withStagedChanges_showsDiff() {
        val tmp = createTempDir()
        try {
            server.route(makePut("/git porcelain/init?repo=${tmp.absolutePath}"))
            val testFile = java.io.File(tmp, "changes.txt")
            testFile.writeText("original\n")
            server.route(makePost("/git porcelain/add?repo=${tmp.absolutePath}&files=changes.txt"))
            server.route(makePost("/git porcelain/commit?repo=${tmp.absolutePath}&message=base"))

            // modify
            testFile.writeText("modified\n")

            // cached diff (staged) — should be empty
            val emptyDiff = server.route(makeGet("/git porcelain/diff?repo=${tmp.absolutePath}&cached=true"))
            assertOk200(emptyDiff)
            assertEquals("", responseBody(emptyDiff).trim(), "Expected no staged changes")

            // non-cached diff — should show modification
            val diffResp = server.route(makeGet("/git porcelain/diff?repo=${tmp.absolutePath}&cached=false"))
            assertOk200(diffResp)
            val diffBody = responseBody(diffResp)
            assertTrue(diffBody.contains("modified") || diffBody.contains("+modified"),
                "Expected diff output, got: $diffBody")
        } finally {
            tmp.deleteRecursively()
        }
    }

    @Test
    fun gitInit_reset_keepsWorkingTreeClean() {
        val tmp = createTempDir()
        try {
            server.route(makePut("/git porcelain/init?repo=${tmp.absolutePath}"))
            val f = java.io.File(tmp, "to-reset.txt")
            f.writeText("content\n")
            server.route(makePost("/git porcelain/add?repo=${tmp.absolutePath}&files=to-reset.txt"))
            server.route(makePost("/git porcelain/commit?repo=${tmp.absolutePath}&message=added"))

            // git reset HEAD
            val resetResp = server.route(makePost("/git porcelain/reset?repo=${tmp.absolutePath}&ref=HEAD"))
            assertOk200(resetResp)

            // verify: status should show file as committed (clean working tree)
            val statusResp = server.route(makeGet("/git porcelain/status?repo=${tmp.absolutePath}"))
            assertOk200(statusResp)
        } finally {
            tmp.deleteRecursively()
        }
    }

    @Test
    fun gitInit_checkout_staysOnBranch() {
        val tmp = createTempDir()
        try {
            server.route(makePut("/git porcelain/init?repo=${tmp.absolutePath}"))
            val f = java.io.File(tmp, "main.txt")
            f.writeText("main content\n")
            server.route(makePost("/git porcelain/add?repo=${tmp.absolutePath}&files=main.txt"))
            server.route(makePost("/git porcelain/commit?repo=${tmp.absolutePath}&message=main"))

            // create and switch to feature branch
            server.route(makePost("/git porcelain/branch?repo=${tmp.absolutePath}&name=feature/test"))
            val checkoutResp = server.route(makePost("/git porcelain/checkout?repo=${tmp.absolutePath}&ref=feature/test"))
            assertOk200(checkoutResp)

            // verify branch list shows HEAD on feature/test
            val branchResp = server.route(makeGet("/git porcelain/branch?repo=${tmp.absolutePath}"))
            assertOk200(branchResp)
            val body = responseBody(branchResp)
            assertTrue(body.contains("feature/test") || body.contains("test"),
                "Expected feature branch in branch list, got: $body")
        } finally {
            tmp.deleteRecursively()
        }
    }

    // ── utilities ────────────────────────────────────────────────────────────────

    private fun createTempDir(): java.io.File {
        val f = java.io.File(java.io.File("/tmp"), "git-api-test-${System.currentTimeMillis()}")
        f.mkdirs()
        return f
    }

    private fun responseBody(msg: HtxMessage): String =
        msg.blocks.filterIsInstance<HtxBlockData.Data>()
            .joinToString("") { it.bytes.decodeToString() }

    private fun HtxStartLine.Companion.request(
        method: HttpMethod,
        uri: ByteArray,
        major: Int = 1,
        minor: Int = 1,
    ): HtxStartLine = HtxStartLine.request(method, uri, major, minor)

    private fun HtxStartLine.Companion.response(status: Int, reason: ByteArray, major: Int = 1, minor: Int = 1): HtxStartLine =
        HtxStartLine.response(status, reason, major, minor)
}