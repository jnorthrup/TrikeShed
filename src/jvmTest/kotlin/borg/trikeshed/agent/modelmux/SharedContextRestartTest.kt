package borg.trikeshed.agent.modelmux

import borg.trikeshed.cas.CasPaths
import borg.trikeshed.job.CasStore
import borg.trikeshed.job.ContentId
import borg.trikeshed.lib.*
import borg.trikeshed.userspace.nio.file.spi.FileOperations
import borg.trikeshed.userspace.nio.spi.NioSupervisor
import borg.trikeshed.util.oroboros.FileCasStore
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** A retained closure is the files on disk, not anything held in memory: a new "process" sees exactly what a crash would leave. */
class SharedContextRestartTest {
    private val dir: File = Files.createTempDirectory("ctx-restart-").toFile()
    private val scope = "team/a"
    private val notes = "The manifest must be verified before caching.\nfiller one\nfiller two\nCargo manifest lists forty crates of oranges.\n"

    @AfterTest
    fun cleanup() { dir.deleteRecursively() }

    private suspend fun <T> process(body: suspend (SharedContext, CasStore) -> T): T {
        val nio = NioSupervisor()
        try {
            nio.open()
            val cas = FileCasStore(checkNotNull(nio.service<FileOperations>()), dir.absolutePath)
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
    fun aRetainedClosureSurvivesARestartAndProjectsIdentically() = runBlocking {
        val bytes = notes.encodeToByteArray()
        var manifest = ContentId("sha256:" + "0".repeat(64))
        var before = emptyList<Triple<String, String, Pair<Int, Int>>>()

        process { context, cas ->
            context.admit(bytes)
            manifest = context.admit(BreadcrumbContextManifest.of(scope, BreadcrumbProjector.POLICY, "alice", emptyList(), listOf(ManifestDocument.of("notes.md", bytes))).encode())
            context.sync()
            before = excerpts(context.acquire(manifest, scope))
        }
        assertEquals(true, before.isNotEmpty())

        process { context, _ ->
            val snapshot = context.acquire(manifest, scope)
            assertEquals(notes, snapshot.documents[0].text, "the exact original bytes came back from disk")
            assertEquals(before, excerpts(snapshot), "the same question over the same pinned revision gives the same breadcrumbs, with the same source ranges")
            assertEquals(0, context.counters().peerFetches, "reconstruction needed nothing from any peer")
        }
    }

    @Test
    fun anotherRevisionAfterTheRestartLeavesTheFirstReadableAndUnchanged() = runBlocking {
        var first = ContentId("sha256:" + "0".repeat(64))
        process { context, _ ->
            context.admit(notes.encodeToByteArray())
            first = context.admit(BreadcrumbContextManifest.of(scope, BreadcrumbProjector.POLICY, "alice", emptyList(), listOf(ManifestDocument.of("notes.md", notes.encodeToByteArray()))).encode())
            context.sync()
        }
        process { context, _ ->
            val changed = notes.replace("forty", "fifty").encodeToByteArray()
            context.admit(changed)
            val second = context.admit(BreadcrumbContextManifest.of(scope, BreadcrumbProjector.POLICY, "bob", listOf(first), listOf(ManifestDocument.of("notes.md", changed))).encode())
            context.sync()
            assertEquals(true, "fifty" in context.acquire(second, scope).documents[0].text)
            assertEquals(notes, context.acquire(first, scope).documents[0].text, "the pinned revision is immutable")
        }
    }

    @Test
    fun aDocumentCorruptedOnDiskIsDetectedNeverServed() = runBlocking {
        var manifest = ContentId("sha256:" + "0".repeat(64))
        val bytes = notes.encodeToByteArray()
        process { context, _ ->
            context.admit(bytes)
            manifest = context.admit(BreadcrumbContextManifest.of(scope, BreadcrumbProjector.POLICY, "alice", emptyList(), listOf(ManifestDocument.of("notes.md", bytes))).encode())
            context.sync()
        }
        val blob = File(dir, CasPaths.blob(ContentId.of(bytes)))
        check(blob.isFile) { "expected the blob at ${blob.path}" }
        blob.writeBytes(bytes.copyOf().also { it[0] = (it[0].toInt() xor 0x55).toByte() })

        process { context, _ ->
            val rejected = assertFailsWith<ContextBlockException> { context.acquire(manifest, scope) }
            assertEquals(ContextRejection.DIGEST_MISMATCH, rejected.reason)
        }
    }
}
