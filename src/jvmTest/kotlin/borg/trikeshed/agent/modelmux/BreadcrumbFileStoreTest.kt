package borg.trikeshed.agent.modelmux

import borg.trikeshed.job.ContentId
import borg.trikeshed.lib.*
import borg.trikeshed.parse.jsonOf
import borg.trikeshed.userspace.nio.file.spi.JvmFileOperations
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class BreadcrumbFileStoreTest {
    @Test fun reopenRetainsIndependentContextsAndDurableDeletion(): Unit = runBlocking {
        val files = JvmFileOperations()
        val directory = files.createTempDir("breadcrumb-files")
        try {
            val first = "session/../one"
            val second = "session-two"
            val firstState = """{ "content": "original\\n", "at":9007199254740993 }"""
            val secondState = """{"content":"second"}"""
            val store = BreadcrumbFileStore(files, directory)
            store.write(first, firstState)
            store.write(second, secondState)
            val secondPath = files.resolvePath(directory, ContentId.of(second.encodeToByteArray()).hex + ".json")
            val secondBytes = files.readAllBytes(secondPath)
            val reopened = BreadcrumbFileStore(files, directory)
            assertEquals(mapOf(first to firstState, second to secondState), reopened.load().view.associate { it.a to it.b })
            store.write(first, "updated")
            assertContentEquals(secondBytes, files.readAllBytes(secondPath), "Updating one context must not rewrite another")
            reopened.write(first, null)
            assertEquals(mapOf(second to secondState), BreadcrumbFileStore(files, directory).load().view.associate { it.a to it.b })
            assertEquals(2, files.listDir(directory).size, "Deletion is an atomic tombstone, not an unsynced unlink")
            reopened.write(first, firstState)
            assertEquals(firstState, BreadcrumbFileStore(files, directory).load().view.first { it.a == first }.b)
        } finally { files.deleteRecursively(directory) }
    }

    @Test fun corruptIdentityAndVersionRefuseReopen(): Unit = runBlocking {
        val files = JvmFileOperations()
        val directory = files.createTempDir("breadcrumb-corrupt")
        try {
            val store = BreadcrumbFileStore(files, directory)
            store.write("session", "state")
            val path = files.resolvePath(directory, files.listDir(directory).single())
            files.writeAtomically(path, jsonOf(mapOf("version" to 1, "context" to "different", "state" to "state")).encodeToByteArray())
            assertFailsWith<IllegalArgumentException> { BreadcrumbFileStore(files, directory).load() }
            files.writeAtomically(path, jsonOf(mapOf("version" to 2, "context" to "session", "state" to "state")).encodeToByteArray())
            assertFailsWith<IllegalArgumentException> { BreadcrumbFileStore(files, directory).load() }
            files.writeAtomically(path, "{\"version\":1}".encodeToByteArray())
            assertFailsWith<IllegalArgumentException> { BreadcrumbFileStore(files, directory).load() }
        } finally { files.deleteRecursively(directory) }
    }
}
