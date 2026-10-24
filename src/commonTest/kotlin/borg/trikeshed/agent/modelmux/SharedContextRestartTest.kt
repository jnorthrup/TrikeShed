package borg.trikeshed.agent.modelmux

import borg.trikeshed.cas.CasPaths
import borg.trikeshed.job.CasStore
import borg.trikeshed.job.ContentId
import borg.trikeshed.lib.*
import borg.trikeshed.userspace.nio.file.spi.FileOperations
import borg.trikeshed.userspace.nio.spi.NioSupervisor
import borg.trikeshed.util.oroboros.FileCasStore
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * A retained closure is the files on disk, not anything held in memory: a new "process" sees exactly what a crash would leave.
 *
 * The logic is commonTest. Every file operation goes through the [FileOperations] seam, so the same test runs on the JVM and,
 * on Linux native, over `LinuxFileOperations` (the io_uring adapter). No platform code is involved.
 */
class SharedContextRestartTest {
    private val scope = "team/a"
    private val notes = "The manifest must be verified before caching.\nfiller one\nfiller two\nCargo manifest lists forty crates of oranges.\n"

    /** Runs [body] against a fresh directory managed by its own supervisor; the directory is always removed. */
    private fun onDisk(body: suspend (root: String, files: FileOperations) -> Unit) = runTest {
        val nio = NioSupervisor()
        try {
            nio.open()
            val files = checkNotNull(nio.service<FileOperations>()) { "no FileOperations on this target" }
            val root = files.createTempDir("ctx-restart-")
            try {
                body(root, files)
            } finally {
                files.deleteRecursively(root)
            }
        } finally {
            withContext(NonCancellable) { nio.drain(); nio.close() }
        }
    }

    /** One process lifetime: a new supervisor and a new store over the same directory, torn down afterwards. */
    private suspend fun <T> process(root: String, body: suspend (SharedContext, CasStore) -> T): T {
        val nio = NioSupervisor()
        try {
            nio.open()
            val cas = FileCasStore(checkNotNull(nio.service<FileOperations>()), root)
            return body(SharedContext(cas, null, ContextReadPolicy(allowedScopes = setOf(scope))), cas)
        } finally {
            withContext(NonCancellable) { nio.drain(); nio.close() }
        }
    }

    private fun excerpts(snapshot: ContextSnapshot): List<Triple<String, String, Pair<Int, Int>>> {
        val projection = BreadcrumbProjector.project(snapshot, "manifest verified caching")
        return (0 until projection.excerpts.size).map { i -> projection.excerpts[i].let { Triple(it.document, it.text, it.range.start to it.range.end) } }
    }

    @Test
    fun aRetainedClosureSurvivesARestartAndProjectsIdentically() = onDisk { root, _ ->
        val bytes = notes.encodeToByteArray()
        var manifest = ContentId("sha256:" + "0".repeat(64))
        var before = emptyList<Triple<String, String, Pair<Int, Int>>>()

        process(root) { context, _ ->
            context.admit(bytes)
            manifest = context.admit(BreadcrumbContextManifest.of(scope, BreadcrumbProjector.POLICY, "alice", emptyList(), listOf(ManifestDocument.of("notes.md", bytes))).encode())
            context.sync()
            before = excerpts(context.acquire(manifest, scope))
        }
        assertEquals(true, before.isNotEmpty())

        process(root) { context, _ ->
            val snapshot = context.acquire(manifest, scope)
            assertEquals(notes, snapshot.documents[0].text, "the exact original bytes came back from disk")
            assertEquals(before, excerpts(snapshot), "the same question over the same pinned revision gives the same breadcrumbs, with the same source ranges")
            assertEquals(0, context.counters().peerFetches, "reconstruction needed nothing from any peer")
        }
    }

    @Test
    fun anotherRevisionAfterTheRestartLeavesTheFirstReadableAndUnchanged() = onDisk { root, _ ->
        var first = ContentId("sha256:" + "0".repeat(64))
        process(root) { context, _ ->
            context.admit(notes.encodeToByteArray())
            first = context.admit(BreadcrumbContextManifest.of(scope, BreadcrumbProjector.POLICY, "alice", emptyList(), listOf(ManifestDocument.of("notes.md", notes.encodeToByteArray()))).encode())
            context.sync()
        }
        process(root) { context, _ ->
            val changed = notes.replace("forty", "fifty").encodeToByteArray()
            context.admit(changed)
            val second = context.admit(BreadcrumbContextManifest.of(scope, BreadcrumbProjector.POLICY, "bob", listOf(first), listOf(ManifestDocument.of("notes.md", changed))).encode())
            context.sync()
            assertEquals(true, "fifty" in context.acquire(second, scope).documents[0].text)
            assertEquals(notes, context.acquire(first, scope).documents[0].text, "the pinned revision is immutable")
        }
    }

    @Test
    fun aDocumentCorruptedOnDiskIsDetectedNeverServed() = onDisk { root, files ->
        var manifest = ContentId("sha256:" + "0".repeat(64))
        val bytes = notes.encodeToByteArray()
        process(root) { context, _ ->
            context.admit(bytes)
            manifest = context.admit(BreadcrumbContextManifest.of(scope, BreadcrumbProjector.POLICY, "alice", emptyList(), listOf(ManifestDocument.of("notes.md", bytes))).encode())
            context.sync()
        }
        val blob = files.resolvePath(root, CasPaths.blob(ContentId.of(bytes)))
        check(files.isFile(blob)) { "expected the blob at $blob" }
        files.write(blob, bytes.copyOf().also { it[0] = (it[0].toInt() xor 0x55).toByte() })

        process(root) { context, _ ->
            val rejected = assertFailsWith<ContextBlockException> { context.acquire(manifest, scope) }
            assertEquals(ContextRejection.DIGEST_MISMATCH, rejected.reason)
        }
    }
}
