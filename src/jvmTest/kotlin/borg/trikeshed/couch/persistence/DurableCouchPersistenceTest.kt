package borg.trikeshed.couch.persistence

import borg.trikeshed.job.CasStore
import borg.trikeshed.couch.isam.JvmDurableAppendLog
import borg.trikeshed.userspace.nio.file.spi.JvmFileOperations
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DurableCouchPersistenceTest {
    @Test
    fun testPersistence() = runBlocking<Unit> {
        val dir = File("build/tmp/test-persistence").apply { mkdirs() }
        val walFile = File(dir, "wal.log")
        walFile.delete()

        val walLog = JvmDurableAppendLog(walFile)
        val casStore = CasStore.inMemory()
        val fileOps = JvmFileOperations()

        val persistence = DurableCouchPersistence(walLog, casStore, fileOps, dir.absolutePath)
        persistence.open()

        persistence.persist("doc1", "hello".encodeToByteArray())
        persistence.flush()

        persistence.drainStore()
    }
}
