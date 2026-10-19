package borg.trikeshed.util.oroboros

import borg.trikeshed.couch.CouchStoreFactory
import borg.trikeshed.job.CasStore
import borg.trikeshed.job.ContentId
import borg.trikeshed.userspace.nio.file.spi.FileOperations
import borg.trikeshed.userspace.nio.file.spi.InMemoryFileOperations
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HermesCorpusTest {
    @Test
    fun traceAdmissionPreservesOtherFilesAcrossProfilesAndProjects() {
        for (profile in listOf("src-trikeshed", "second")) {
            for (lane in listOf("asks", "curation")) {
                val relative = "profiles/$profile/hillclimb/$lane/trial/traces"
                assertTrue(HermesCorpus.isTrace(relative), "trace directory: $relative")
                assertTrue(HermesCorpus.isTrace("$relative/nested/event.json"))
                assertFalse(HermesCorpus.admits(HermesCorpus.PREFIX + "$relative/event.json"))
                val nested = "profiles/$profile/hillclimb/$lane/analyzer/r1/traces"
                assertTrue(HermesCorpus.isTrace(nested), "nested trace directory: $nested")
                assertFalse(HermesCorpus.admits(HermesCorpus.PREFIX + "$nested/event.json"))
                assertTrue(HermesCorpus.admits("projects/example/$relative/event.json"),
                    "the Hermes source policy must not exclude another project's source")
                for (name in listOf("results.jsonl", "summary.json", "prereg.json", "traces-extra/event.json")) {
                    val path = "profiles/$profile/hillclimb/$lane/trial/$name"
                    assertFalse(HermesCorpus.isTrace(path), path)
                    assertTrue(HermesCorpus.admits(HermesCorpus.PREFIX + path), path)
                }
            }
        }
        for (path in listOf("sessions/event.json", "hillclimb/asks//traces/event.json",
            "hillclimb/other/trial/traces/event.json", "other-hillclimb/asks/trial/traces/event.json")) {
            assertFalse(HermesCorpus.isTrace(path), path)
            assertTrue(HermesCorpus.admits(HermesCorpus.PREFIX + path), path)
        }
    }

    @Test
    fun reconcileTombstonesExcludedTraceOrphansAndNeverReadsThem() {
        val root = "/hermes"
        val trace = "profiles/profile/hillclimb/asks/trial/traces/event.json"
        val curationTrace = "profiles/profile/hillclimb/curation/analyzer/r1/traces/event.json"
        val useful = listOf("profiles/profile/hillclimb/asks/trial/results.jsonl",
            "profiles/profile/hillclimb/curation/analyzer/r1/summary.json", "profiles/profile/sessions/session.json")
        val memory = InMemoryFileOperations()
        memory.write("$root/$trace", "source trace")
        memory.write("$root/$curationTrace", "curation trace")
        for (path in useful) memory.write("$root/$path", "retained $path")
        val listed = mutableListOf<String>()
        val read = mutableListOf<String>()
        val files = object : FileOperations by memory {
            override fun listDir(path: String): List<String> {
                listed.add(path)
                check(!path.endsWith("/traces")) { "generated trace directory was enumerated" }
                return memory.listDir(path)
            }
            override fun readAllBytes(filename: String): ByteArray {
                read.add(filename)
                check("/traces/" !in filename) { "generated trace content was read" }
                return memory.readAllBytes(filename)
            }
        }
        val cas = CasStore.inMemory()
        val couch = CouchStoreFactory.inMemory()
        val attachments = CouchAttachmentGateway(couch, cas)
        val historical = "earlier retained trace".encodeToByteArray()
        val logical = HermesCorpus.PREFIX + trace
        attachments.putAttachment(OroborosAttachmentRef(logical, "application/json", historical.size.toLong(),
            ContentId.of(historical), "hermes", "earlier", 0), historical)
        val revision = couch.head.getRev(logical)
        val gateway = WorktreeCouchGateway(files, attachments, prefix = HermesCorpus.PREFIX,
            excludedPath = HermesCorpus::isTrace)

        val snapshot = gateway.reconcile(root, "hermes", "current", 1)

        assertEquals(useful.map { HermesCorpus.PREFIX + it }.sorted(), snapshot.paths)
        // An excludedPath doc is this plane's orphan, not another plane's property: the
        // absorbed trace leaves the plane even while its source file stays on disk.
        // Retention lives in the FILESYSTEM (retained files); the document plane holds
        // no trace citizens. Ghosts that outlive their exclusion are clearable garbage.
        assertEquals(listOf(logical), snapshot.deletedPaths)
        assertTrue(snapshot.skippedDirs.isEmpty())
        assertEquals(useful.map { "$root/$it" }.sorted(), read.sorted())
        assertTrue(listed.none { it.endsWith("/traces") })
        assertFalse(revision == couch.head.getRev(logical), "the orphan's tombstone is a new revision")
        assertEquals(null, attachments.getAttachment(logical))
        // retained files: the plane never touches trace bytes on disk
        assertContentEquals("source trace".encodeToByteArray(), memory.readAllBytes("$root/$trace"))
        assertContentEquals("curation trace".encodeToByteArray(), memory.readAllBytes("$root/$curationTrace"))

        val tombstone = couch.head.getRev(logical)
        memory.deleteRecursively("$root/${trace.substringBeforeLast('/')}")
        val absent = gateway.reconcile(root, "hermes", "later", 2)
        assertTrue(absent.deletedPaths.isEmpty(), "a tombstoned orphan is not deleted twice")
        assertEquals(null, attachments.getAttachment(logical))
        assertEquals(tombstone, couch.head.getRev(logical))
    }
}
