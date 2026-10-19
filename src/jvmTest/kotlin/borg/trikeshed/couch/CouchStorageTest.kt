package borg.trikeshed.couch

import borg.trikeshed.couch.persistence.CouchCommitStore
import borg.trikeshed.isam.*
import borg.trikeshed.job.*
import borg.trikeshed.userspace.nio.file.spi.*
import borg.trikeshed.util.oroboros.FileCasStore
import java.nio.file.Path
import kotlin.test.*

class CouchStorageTest {
    @org.junit.jupiter.api.io.TempDir lateinit var root: Path
    private val files = JvmFileOperations()
    private fun cas() = FileCasStore(files, root.resolve("cas").toString())
    private fun location(name: String = "couch") = root.resolve(name).toString()
    private fun open(format: CouchStorageFormat, name: String = "couch") =
        CouchStorage.open("test", cas(), files, location(name), format)

    @Test fun formatSelectionIsStrictAndDenseCannotDegradeToVolatileStorage() {
        assertEquals(CouchStorageFormat.COMMIT, CouchStorageFormat.parse(null))
        assertEquals(CouchStorageFormat.CONFIX_ISAM, CouchStorageFormat.parse("confix-isam"))
        assertFailsWith<IllegalArgumentException> { CouchStorageFormat.parse("dense-ish") }
        assertFailsWith<IllegalArgumentException> {
            CouchStorage.open("test", CasStore.inMemory(), files, location(), CouchStorageFormat.CONFIX_ISAM)
        }
        assertFalse(files.exists(location()))
    }

    @Test fun existingLegacyDatabaseCannotBecomeAnEmptyDenseDatabase() {
        CouchCommitStore(cas(), StorageDurability.DURABLE, files, location()).use { commits ->
            commits.commit(CouchCommittedFrame(0, "retained", "1-original", false,
                Document("retained", listOf(Field("v", "source")))))
            commits.localPut("peer", mapOf("last_seq" to 1L))
        }
        val head = files.readAllBytes("${location()}/HEAD")
        assertFailsWith<IllegalArgumentException> { open(CouchStorageFormat.CONFIX_ISAM) }
        assertContentEquals(head, files.readAllBytes("${location()}/HEAD"))
        assertFalse(files.exists("${location()}.store"))
        open(CouchStorageFormat.COMMIT).use {
            assertEquals("source", it.couch.docJson("retained")!!["v"])
            assertEquals(1L, it.couch.localGet("peer")!!["last_seq"])
        }
    }

    @Test fun denseRootCannotBeIgnoredByTheDefaultFormatAndLeasesExcludeBothFormats() {
        open(CouchStorageFormat.CONFIX_ISAM).use { owner ->
            owner.couch.put("retained", mapOf("v" to "dense"), null)
            for (format in CouchStorageFormat.entries) assertFails { open(format) }
        }
        val head = files.readAllBytes("${location()}.store/HEAD")
        assertFailsWith<IllegalArgumentException> { open(CouchStorageFormat.COMMIT) }
        assertContentEquals(head, files.readAllBytes("${location()}.store/HEAD"))
        assertFalse(files.exists("${location()}/HEAD"))
        open(CouchStorageFormat.CONFIX_ISAM).use {
            assertEquals("dense", it.couch.docJson("retained")!!["v"])
        }
    }

    @Test fun failedDenseRecoveryReleasesBothLeasesWithoutOverwritingTheCorruptRoot() {
        open(CouchStorageFormat.CONFIX_ISAM).close()
        val path = "${location()}.store/HEAD"
        val corrupt = "invalid root".encodeToByteArray()
        files.writeAtomically(path, corrupt)
        repeat(2) { assertFails { open(CouchStorageFormat.CONFIX_ISAM) } }
        files.acquireExclusiveLease("${location()}/.format.lock").use {}
        files.acquireExclusiveLease("${location()}.store/.lock").use {}
        assertContentEquals(corrupt, files.readAllBytes(path))
    }

    @Test fun normalizedDenseLocationsRetainOneRootAndSelectionLease() {
        val alias = "${location()}/./"
        CouchStorage.open("test", cas(), files, alias, CouchStorageFormat.CONFIX_ISAM).use { owner ->
            owner.couch.put("retained", mapOf("v" to "dense"), null)
            for (format in CouchStorageFormat.entries) assertFails { open(format) }
        }
        assertTrue(files.exists("${location()}.store/HEAD"))
        assertFalse(files.exists("${location()}/.store"))
        assertFails { open(CouchStorageFormat.COMMIT) }
        open(CouchStorageFormat.CONFIX_ISAM).use {
            assertEquals("dense", it.couch.docJson("retained")!!["v"])
        }
    }

    @Test fun volatileCasCannotHideDurableRootsAndOwnsDurableLocationSelection() {
        for (format in CouchStorageFormat.entries) {
            val name = format.id
            open(format, name).use { it.couch.put("retained", mapOf("v" to name), null) }
            assertFails {
                CouchStorage.open("test", CasStore.inMemory(), files, location(name))
            }
            open(format, name).use { assertEquals(name, it.couch.docJson("retained")!!["v"]) }
        }
        CouchStorage.open("test", CasStore.inMemory(), files, location("volatile")).use { owner ->
            assertEquals(true, owner.couch.put("ephemeral", emptyMap(), null)["ok"])
            for (format in CouchStorageFormat.entries) assertFails { open(format, "volatile") }
        }
        assertFalse(files.exists("${location("volatile")}/HEAD"))
        assertFalse(files.exists("${location("volatile")}.store"))
        open(CouchStorageFormat.COMMIT, "volatile").use { assertNull(it.couch.docJson("ephemeral")) }
    }

    @Test fun symlinkedDenseLocationRetainsTheSameRootAndSelectionLease() {
        files.mkdirs(location())
        java.nio.file.Files.createSymbolicLink(root.resolve("alias"), root.resolve("couch"))
        open(CouchStorageFormat.CONFIX_ISAM, "alias").use { owner ->
            owner.couch.put("retained", mapOf("v" to "dense"), null)
            for (name in listOf("alias", "couch")) for (format in CouchStorageFormat.entries)
                assertFails { open(format, name) }
        }
        assertTrue(files.exists("${location()}.store/HEAD"))
        assertFalse(files.exists("${location("alias")}.store"))
        for (name in listOf("alias", "couch")) {
            assertFails { open(CouchStorageFormat.COMMIT, name) }
            open(CouchStorageFormat.CONFIX_ISAM, name).use {
                assertEquals("dense", it.couch.docJson("retained")!!["v"])
            }
        }
        assertFalse(files.exists("${location()}/HEAD"))
    }

    @Test fun durableAdapterWithoutCanonicalPathsFailsBeforeStorageAdmission() {
        val unbound = object : FileOperations by files {
            override fun canonicalPath(path: String): String = super<FileOperations>.canonicalPath(path)
        }
        assertFailsWith<UnsupportedOperationException> {
            CouchStorage.open("test", cas(), unbound, location())
        }
        assertFalse(files.exists(location()))
        assertFalse(files.exists("${location()}.store"))
    }
}
