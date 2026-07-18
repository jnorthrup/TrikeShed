package borg.trikeshed.util.oroboros

import borg.trikeshed.couch.CouchStoreFactory
import borg.trikeshed.job.ContentId
import borg.trikeshed.lib.Join
import borg.trikeshed.userspace.nio.file.spi.FileOperations
import borg.trikeshed.userspace.nio.channels.spi.ProcessOperations
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import java.io.File
import java.nio.file.Files

// Simple stub for JVM ProcessOperations
class JvmProcessOperations : ProcessOperations {
    override suspend fun exec(command: String, args: List<String>, stdin: ByteArray?, env: Map<String, String>): borg.trikeshed.userspace.nio.channels.spi.ProcessResult {
        val process = ProcessBuilder(listOf(command) + args).start()
        val exitCode = process.waitFor()
        val stdout = process.inputStream.readAllBytes()
        val stderr = process.errorStream.readAllBytes()
        return borg.trikeshed.userspace.nio.channels.spi.ProcessResult(exitCode, stdout, stderr)
    }
}

// Simple stub for JVM FileOperations
class JvmFileOperations : FileOperations {
    override fun open(path: String, readOnly: Boolean): Int = 0
    override fun readAllLines(filename: String): List<String> = File(filename).readLines()
    override fun readAllBytes(filename: String): ByteArray = File(filename).readBytes()
    override fun readString(filename: String): String = File(filename).readText()
    override fun write(filename: String, bytes: ByteArray) { File(filename).writeBytes(bytes) }
    override fun write(filename: String, lines: List<String>) { File(filename).writeText(lines.joinToString("\n")) }
    override fun write(filename: String, string: String) { File(filename).writeText(string) }
    override fun cwd(): String = System.getProperty("user.dir")
    override fun exists(filename: String): Boolean = File(filename).exists()
    override fun streamLines(fileName: String, bufsize: Int): Sequence<borg.trikeshed.lib.Join<Long, ByteArray>> = emptySequence()
    override fun iterateLines(fileName: String, bufsize: Int): Iterable<borg.trikeshed.lib.Join<Long, borg.trikeshed.lib.Series<Byte>>> = emptyList()
    override fun listDir(path: String): List<String> = File(path).list()?.toList() ?: emptyList()
    override fun isDir(path: String): Boolean = File(path).isDirectory
    override fun isFile(path: String): Boolean = File(path).isFile
    override fun mkdirs(path: String) { File(path).mkdirs() }
    override fun deleteRecursively(path: String) { File(path).deleteRecursively() }
    override fun resolvePath(vararg parts: String): String = parts.joinToString(File.separator)
    override fun readZip(path: String): List<Pair<String, ByteArray>> = emptyList()
    override fun createTempDir(prefix: String): String = Files.createTempDirectory(prefix).toAbsolutePath().toString()
    override fun close(fd: Int): Int = 0
    override fun size(fd: Int): Long = 0
}

class OroborosJvmIntegrationTest {
    @Test
    fun testIntegration() = runBlocking {
        val fileOps = JvmFileOperations()
        val processOps = JvmProcessOperations()

        val forgeHome = fileOps.createTempDir("oroboros_test_forge")
        val casRoot = fileOps.createTempDir("oroboros_test_cas")

        val casStore = FileCasStore(fileOps, casRoot)
        val couchStore = CouchStoreFactory.inMemory()
        val attachments = CouchAttachmentGateway(couchStore, casStore)

        val versionGateway = GitVersionGateway(processOps)

        // Ensure git is available
        if (!versionGateway.isAvailable()) {
            println("Git not available, skipping integration test")
            return@runBlocking
        }

        // Init git
        assertTrue(versionGateway.init(forgeHome))

        val storageRow = object : OroborosStorageRow, Join<OroborosStorageK<*>, (OroborosStorageK<*>) -> Any?> {
            @Suppress("UNCHECKED_CAST")
            override fun <R> get(key: OroborosStorageK<R>): R {
                return when (key) {
                    is OroborosStorageK.Cas -> casStore as R
                    is OroborosStorageK.Attachments -> attachments as R
                    else -> error("Unsupported")
                }
            }
            override val a: OroborosStorageK<*> get() = error("Not supported")
            override val b: (OroborosStorageK<*>) -> Any? get() = error("Not supported")
        }

        val gateway = object : OroborosGateway, Join<OroborosK<*>, (OroborosK<*>) -> Any?> {
            override val storage: OroborosStorageRow get() = storageRow
            override val version: VersionGateway get() = versionGateway
            override val network: OroborosNetworkRow get() = error("Not implemented")

            override val a: OroborosK<*> get() = error("Not supported")
            override val b: (OroborosK<*>) -> Any? = { k ->
                when (k) {
                    OroborosK.Storage -> storageRow
                    OroborosK.Version -> versionGateway
                    else -> null
                }
            }
        }

        val coordinator = OroborosCoordinator("agent-int", forgeHome, gateway, fileOps)

        // 1. Write file
        val bytes = "integration test data".encodeToByteArray()
        coordinator.submit(Mutation.Upsert("data.txt", bytes))
        coordinator.drain()

        val res1 = coordinator.results.receive().getOrThrow()
        assertEquals("Upsert", res1.action)

        // Check CAS
        val expectedCid = ContentId.of(bytes)
        assertEquals(expectedCid, res1.cid)
        val retrievedCas = casStore.get(expectedCid)
        assertNotNull(retrievedCas)
        assertTrue(retrievedCas.contentEquals(bytes))

        // Check File
        val fullPath = fileOps.resolvePath(forgeHome, "data.txt")
        assertTrue(fileOps.exists(fullPath))

        // Check Couch
        val pair = attachments.getAttachment("data.txt")
        assertNotNull(pair)
        assertEquals(expectedCid, pair.first.contentId)
        assertEquals("agent-int", pair.first.agentId)

        // 2. Idempotent replay
        val coordinator2 = OroborosCoordinator("agent-int", forgeHome, gateway, fileOps)
        coordinator2.submit(Mutation.Upsert("data.txt", bytes))
        coordinator2.drain()
        val res2 = coordinator2.results.receive().getOrThrow()
        assertEquals(res1.seq, res2.seq) // seq should not advance

        // 3. Delete tombstone
        val coordinator3 = OroborosCoordinator("agent-int", forgeHome, gateway, fileOps)
        coordinator3.submit(Mutation.Delete("data.txt"))
        coordinator3.drain()

        val res3 = coordinator3.results.receive().getOrThrow()
        assertEquals("Delete", res3.action)

        // Couch should now return null due to tombstone
        val deletedPair = attachments.getAttachment("data.txt")
        assertNull(deletedPair)

        fileOps.deleteRecursively(forgeHome)
        fileOps.deleteRecursively(casRoot)
    }
}
