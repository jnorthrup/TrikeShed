package borg.trikeshed.couch

import borg.trikeshed.job.PackCasStore
import borg.trikeshed.userspace.nio.file.spi.JvmFileOperations
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.*

class CouchPurgeTest {
    @TempDir lateinit var home: Path

    fun CouchStore.write(id: String, text: String) = assertTrue(put(Document(id, listOf(Field("text", text))), head.getRev(id)))

    @Test fun purgedDocumentsLeaveTheLogAndEveryOtherRevisionStays() {
        val files = JvmFileOperations()
        val cas = PackCasStore(files, home.resolve("cas").toString())
        val source = home.resolve("couch").toString()
        val destination = home.resolve("couch.purged").toString()
        val (rev, top) = CouchStorage.open("db", cas, files, source).use { storage ->
            val store = storage.couch.store
            store.write("keep/a", "one")
            store.write("drop/x", "one")
            store.write("keep/a", "two")
            store.write("keep/b", "one")
            store.write("drop/y", "one")
            assertTrue(store.delete("keep/b"))
            store.write("drop/y", "two")
            storage.couch.localPut("follower", mapOf("since" to "5"))
            store.write("drop/z", "one")
            requireNotNull(store.head.getRev("keep/a")) to store.changes.lastCommittedSequence
        }
        val root = files.readAllBytes(files.resolvePath(source, "HEAD"))

        val purge = CouchStorage.purge(cas, files, source, destination) { it.startsWith("drop/") }

        assertContentEquals(root, files.readAllBytes(files.resolvePath(source, "HEAD")))
        // drop/z is the log's newest revision and stays: the next commit's sequence follows it.
        assertEquals(CouchPurge(purge.sourceRoot, purge.destinationRoot, revisions = 5, purged = 3, localDocuments = 1), purge)
        assertFailsWith<IllegalArgumentException> { CouchStorage.purge(cas, files, source, destination) { false } }
        CouchStorage.open("db", cas, files, destination, requireCompleteHistory = true).use { storage ->
            val store = storage.couch.store
            assertEquals(Document("keep/a", listOf(Field("text", "two"))), store.get("keep/a"))
            assertEquals(rev, store.head.getRev("keep/a"))
            assertTrue(store.head.isDeleted("keep/b"))
            assertNull(store.head.getRev("drop/x"))
            assertNull(store.head.getRev("drop/y"))
            assertEquals("5", storage.couch.localGet("follower")?.get("since"))
            store.write("keep/c", "one")
            assertEquals(top + 1, store.changes.lastCommittedSequence)
        }
        cas.close()
    }
}
