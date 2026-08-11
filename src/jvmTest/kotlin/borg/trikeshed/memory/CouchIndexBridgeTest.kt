<<<<<<< HEAD
package borg.trikeshed.memory            val indexes = MemoryIndexLayer(memory)            // deletion removal (partition replaced by empty)}    }        }            forge.toFile().deleteRecursively()        } finally {            assertEquals(0, repoPaths3.size, "Deletion removal should clear the partition")            val repoPaths3 = indexes.queryRepositoryPath("repo1/")            bridge.indexReconciliation("repo1/", emptyBatch)            val emptyBatch = 0 j { _: Int -> error("empty") }            assertEquals("repo1/file3.txt", repoPaths2[0])            assertEquals(1, repoPaths2.size, "Partition replacement should remove previous entries")            val repoPaths2 = indexes.queryRepositoryPath("repo1/")            bridge.indexReconciliation("repo1/", worktreeBatch)            val worktreeBatch = 1 j { "repo1/file3.txt" }                        )                data3                OroborosAttachmentRef("repo1/file3.txt", "text/plain", data3.size.toLong(), cid3, "test", "rev2", 102L),            attachments.putAttachment(            val cid3 = cas.put(data3)            val data3 = "worktree batch file 3".toByteArray()            // worktree batch & partition replacement            assertTrue(taxRoute.entryCount >= 2)            val taxRoute = indexes.route(IndexKind.RepositoryTaxonomy)                        assertEquals(0, memoryPaths.size, "Repository paths must not leak into memory document taxonomy")            val memoryPaths = indexes.queryByPath("repo1/")            // separation from memory-document taxonomy            assertTrue(list1.contains("repo1/dir/file2.txt"))            assertTrue(list1.contains("repo1/file1.txt"))            val list1 = (0 until repoPaths1.size).map { repoPaths1[it] }            assertEquals(2, repoPaths1.size)            val repoPaths1 = indexes.queryRepositoryPath("repo1/")            bridge.indexReconciliation("repo1/", initialBatch)            val initialBatch = 2 j { i -> if (i == 0) "repo1/file1.txt" else "repo1/dir/file2.txt" }            // Series inputs & initial Git batch            )                data2                OroborosAttachmentRef("repo1/dir/file2.txt", "text/plain", data2.size.toLong(), cid2, "test", "rev1", 101L),            attachments.putAttachment(            )                data1                OroborosAttachmentRef("repo1/file1.txt", "text/plain", data1.size.toLong(), cid1, "test", "rev1", 100L),            attachments.putAttachment(            val cid2 = cas.put(data2)            val cid1 = cas.put(data1)            val data2 = "initial git batch file 2".toByteArray()            val data1 = "initial git batch file 1".toByteArray()            // ContentId identity test            val bridge = CouchIndexBridge(attachments, indexes)            val memory = MemoryStore(cas, couch)            val attachments = CouchAttachmentGateway(couch, cas)            val couch = CouchStoreFactory.inMemory()            val cas = FileCasStore(fileOps, forge.resolve("cas").toString())            val fileOps = JvmFileOperations()        try {        val forge = Files.createTempDirectory("oroboros-forge-")    fun testReconciliation() {    @Testclass CouchIndexBridgeTest {import kotlin.test.assertTrueimport kotlin.test.assertEqualsimport kotlin.test.Testimport java.nio.file.Filesimport borg.trikeshed.util.oroboros.OroborosAttachmentRefimport borg.trikeshed.util.oroboros.FileCasStoreimport borg.trikeshed.util.oroboros.CouchAttachmentGatewayimport borg.trikeshed.userspace.nio.file.spi.JvmFileOperationsimport borg.trikeshed.lib.sizeimport borg.trikeshed.lib.jimport borg.trikeshed.job.ContentIdimport borg.trikeshed.couch.CouchStoreFactory
=======
package borg.trikeshed.memory

import borg.trikeshed.couch.CouchStoreFactory
import borg.trikeshed.job.ContentId
import borg.trikeshed.lib.j
import borg.trikeshed.lib.size
import borg.trikeshed.userspace.nio.file.spi.JvmFileOperations
import borg.trikeshed.util.oroboros.CouchAttachmentGateway
import borg.trikeshed.util.oroboros.FileCasStore
import borg.trikeshed.util.oroboros.OroborosAttachmentRef
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CouchIndexBridgeTest {

    @Test
    fun testReconciliation() {
        val forge = Files.createTempDirectory("oroboros-forge-")
        try {
            val fileOps = JvmFileOperations()
            val cas = FileCasStore(fileOps, forge.resolve("cas").toString())
            val couch = CouchStoreFactory.inMemory()
            val attachments = CouchAttachmentGateway(couch, cas)
            val memory = MemoryStore(cas, couch)
            val indexes = MemoryIndexLayer(memory)

            val bridge = CouchIndexBridge(attachments, indexes)

            // ContentId identity test
            val data1 = "initial git batch file 1".toByteArray()
            val data2 = "initial git batch file 2".toByteArray()
            val cid1 = cas.put(data1)
            val cid2 = cas.put(data2)

            attachments.putAttachment(
                OroborosAttachmentRef("repo1/file1.txt", "text/plain", data1.size.toLong(), cid1, "test", "rev1", 100L),
                data1
            )
            attachments.putAttachment(
                OroborosAttachmentRef("repo1/dir/file2.txt", "text/plain", data2.size.toLong(), cid2, "test", "rev1", 101L),
                data2
            )

            // Series inputs & initial Git batch
            val initialBatch = 2 j { i -> if (i == 0) "repo1/file1.txt" else "repo1/dir/file2.txt" }
            bridge.indexReconciliation("repo1/", initialBatch)

            val repoPaths1 = indexes.queryRepositoryPath("repo1/")
            assertEquals(2, repoPaths1.size)
            val list1 = (0 until repoPaths1.size).map { repoPaths1[it] }
            assertTrue(list1.contains("repo1/file1.txt"))
            assertTrue(list1.contains("repo1/dir/file2.txt"))

            // separation from memory-document taxonomy
            val memoryPaths = indexes.queryByPath("repo1/")
            assertEquals(0, memoryPaths.size, "Repository paths must not leak into memory document taxonomy")

            val taxRoute = indexes.route(IndexKind.RepositoryTaxonomy)
            assertTrue(taxRoute.entryCount >= 2)

            // worktree batch & partition replacement
            val data3 = "worktree batch file 3".toByteArray()
            val cid3 = cas.put(data3)
            attachments.putAttachment(
                OroborosAttachmentRef("repo1/file3.txt", "text/plain", data3.size.toLong(), cid3, "test", "rev2", 102L),
                data3
            )

            val worktreeBatch = 1 j { "repo1/file3.txt" }
            bridge.indexReconciliation("repo1/", worktreeBatch)

            val repoPaths2 = indexes.queryRepositoryPath("repo1/")
            assertEquals(1, repoPaths2.size, "Partition replacement should remove previous entries")
            assertEquals("repo1/file3.txt", repoPaths2[0])

            // deletion removal (partition replaced by empty)
            val emptyBatch = 0 j { _: Int -> error("empty") }
            bridge.indexReconciliation("repo1/", emptyBatch)

            val repoPaths3 = indexes.queryRepositoryPath("repo1/")
            assertEquals(0, repoPaths3.size, "Deletion removal should clear the partition")
        } finally {
            forge.toFile().deleteRecursively()
        }
    }
}
>>>>>>> origin/add-couchindexbridge-tests-13167123593579461329
