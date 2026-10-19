package borg.trikeshed.couch

import borg.trikeshed.context.Synchronization
import borg.trikeshed.couch.isam.confixIsamStore
import borg.trikeshed.userspace.nio.file.spi.JvmFileOperations
import borg.trikeshed.util.oroboros.FileCasStore
import java.nio.file.Path
import kotlin.test.*

class CouchStorageSynchronizationTest {
    @org.junit.jupiter.api.io.TempDir lateinit var directory: Path

    @Test fun storageAndConversionPreserveDurableStateWithBothOwnershipModes() {
        val files = JvmFileOperations()
        val storageCas = FileCasStore(files, directory.resolve("cas").toString())
        for (mode in Synchronization.entries) {
            val source = directory.resolve("source-${mode.name}").toString()
            val dense = directory.resolve("dense-${mode.name}").toString()
            CouchStorage.open("test", storageCas, files, source, synchronization = mode).use {
                assertEquals(mode, it.synchronization)
                assertEquals(true, it.couch.put("doc", mapOf("value" to mode.name), null)["ok"])
                it.couch.localPut("peer", mapOf("seq" to 1L))
            }
            val converted = CouchStorage.convert(storageCas, files, source, dense, synchronization = mode)
            assertEquals(1, converted.revisions)
            assertEquals(1, converted.localDocuments)
            CouchStorage.open("test", storageCas, files, dense, CouchStorageFormat.CONFIX_ISAM,
                synchronization = mode).use {
                assertEquals(mode, it.synchronization)
                assertEquals(mode, assertNotNull(it.dense).synchronization)
                assertEquals(mode.name, it.couch.docJson("doc")?.get("value"))
                assertEquals(1L, it.couch.localGet("peer")?.get("seq"))
                val rev = assertNotNull(it.couch.docJson("doc")?.get("_rev") as? String)
                assertEquals(true, it.couch.put("doc", mapOf("value" to "updated"), rev)["ok"])
            }
            // The builder must carry the same ownership choice when reopening an existing dense root.
            confixIsamStore {
                dataFileLocation = dense
                fileOps = files
                cas = storageCas
                synchronization = mode
            }.use {
                assertEquals(mode, it.synchronization)
                assertEquals("updated", it.couch("test").docJson("doc")?.get("value"))
            }
        }
    }
}
