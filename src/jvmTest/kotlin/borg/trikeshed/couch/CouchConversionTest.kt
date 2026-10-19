package borg.trikeshed.couch

import borg.trikeshed.couch.persistence.*
import borg.trikeshed.isam.*
import borg.trikeshed.job.*
import borg.trikeshed.lib.*
import borg.trikeshed.parse.confix.*
import borg.trikeshed.userspace.nio.file.spi.*
import borg.trikeshed.util.oroboros.FileCasStore
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import kotlin.test.*

class CouchConversionTest {
    @org.junit.jupiter.api.io.TempDir lateinit var root: Path
    private val files = JvmFileOperations()
    private fun path(name: String) = files.resolvePath(files.canonicalPath(root.toString()), name)
    private fun cas() = FileCasStore(files, path("cas"))
    private fun source() = CouchCommitStore(cas(), StorageDurability.DURABLE, files, path("source"))
    private fun frame(sequence: Long, value: String) = CouchCommittedFrame(sequence, "case", "$sequence-original", false,
        Document("case", listOf(Field("value", value))))
    private fun open(name: String, format: CouchStorageFormat, operations: IsamOperations = UringIsamOperations()) =
        CouchStorage.open("test", cas(), files, path(name), format, operations)

    @Test fun dictionaryConversionRetainsExactHistoryLocalsAttachmentsAndSequenceGaps() = runBlocking<Unit> {
        val cas = cas()
        val payload = "Synthetic medical/legal quotation — 患者".encodeToByteArray()
        val cid = cas.put(payload)
        val dictionary = cas.put("document evidence quotation patient legal review nested field".encodeToByteArray())
        val body = Document("case", listOf(Field("contentId", cid.value), Field("contentType", "text/plain"),
            Field("length", payload.size.toLong()), Field("nested", mapOf("患者" to listOf("医", "review"))),
            Field("bytes", byteArrayOf(0, 127, -1))))
        lateinit var local: Map<String, Any?>
        source().use { original ->
            original.localPut("peer", mapOf("last_seq" to 1L))
            original.commit(CouchCommittedFrame(4, "case", "1-original", false, body))
            original.localPut("deleted", mapOf("last_seq" to 9L))
            original.localDelete("peer")
            original.commit(frame(8, "updated"))
            local = original.localPut("peer", mapOf("last_seq" to 41L))
            original.localPut("deleted", mapOf("last_seq" to 99L))
            original.localDelete("deleted")
            original.commit(CouchCommittedFrame(11, "gone", "2-deleted", true, null))
        }
        val head = files.readAllBytes(path("source/HEAD"))
        val before = open("source", CouchStorageFormat.COMMIT).use { it.couch.changes(0, includeDocs = true) }
        val operations = UringIsamOperations(leafBytes = 256,
            leafCodec = CasLeafCodec(cas, { ZstdDictionaryCodec(it) }, dictionary))
        val result = CouchStorage.convert(cas, files, path("source"), path("dense"), operations)
        assertEquals(ContentId.of(head), result.sourceRoot)
        assertEquals(ContentId.of(files.readAllBytes(path("dense.store/HEAD"))), result.destinationRoot)
        assertEquals(3, result.revisions)
        assertEquals(1, result.localDocuments)
        assertContentEquals(head, files.readAllBytes(path("source/HEAD")))
        val receipt = CanonicalCbor.decodeMap(files.readAllBytes(path("dense/conversion.cbor")))
        assertEquals("complete", receipt["state"])
        assertEquals(result.sourceRoot.value, receipt["sourceRoot"])
        assertEquals(result.destinationRoot.value, receipt["destinationRoot"])
        // A fresh codec has no selected dictionary: each historical leaf must resolve its own identity.
        val reader = UringIsamOperations(leafBytes = 256, leafCodec = CasLeafCodec(cas(), { ZstdDictionaryCodec(it) }))
        open("dense", CouchStorageFormat.CONFIX_ISAM, reader).use { restored ->
            val couch = restored.couch
            assertContentEquals(CanonicalCbor.encodeMap(before), CanonicalCbor.encodeMap(couch.changes(0, includeDocs = true)))
            assertEquals(local, couch.localGet("peer"))
            assertNull(couch.localGet("deleted"))
            assertEquals(12L, couch.updateSeq)
            assertTrue(couch.store.head.isDeleted("gone"))
            val retained = assertNotNull(restored.dense!!.document("case", "1-original"))
            assertContentEquals(CouchStoreFactory.canonicalBody(body), retained.src.toArray())
            assertEquals("医", retained.docAt("nested", "患者")!!.cellKids[0].reify())
            assertContentEquals(payload, cas.get(cid))
            assertEquals("0-5", couch.localPut("next", mapOf("last_seq" to 12L))["rev"])
            assertEquals(true, couch.put("next", mapOf("value" to "after conversion"), null)["ok"])
            assertEquals(13L, couch.updateSeq)
        }
        // Complete receipt names the conversion root, not every future root: later writes must reopen.
        open("dense", CouchStorageFormat.CONFIX_ISAM, reader).use {
            assertEquals("after conversion", it.couch.docJson("next")!!["value"])
            assertEquals("0-5", it.couch.localGet("next")!!["_rev"])
        }
        open("source", CouchStorageFormat.COMMIT).use {
            assertContentEquals(CanonicalCbor.encodeMap(before), CanonicalCbor.encodeMap(it.couch.changes(0, includeDocs = true)))
            assertEquals(local, it.couch.localGet("peer"))
            assertNull(it.couch.docJson("next"))
        }
        assertContentEquals(head, files.readAllBytes(path("source/HEAD")))
    }

    @Test fun emptyAndLocalOnlyDatabasesHaveVerifiedDenseRoots() {
        for (locals in 0..1) {
            val source = path("source-$locals")
            val destination = "dense-$locals"
            CouchCommitStore(cas(), StorageDurability.DURABLE, files, source).use {
                if (locals == 1) {
                    it.localPut("gone", emptyMap())
                    it.localDelete("gone")
                    it.localPut("peer", mapOf("last_seq" to 0L))
                }
            }
            val head = files.readAllBytes("$source/HEAD")
            val result = CouchStorage.convert(cas(), files, source, path(destination))
            assertEquals(0, result.revisions)
            assertEquals(locals, result.localDocuments)
            open(destination, CouchStorageFormat.CONFIX_ISAM).use {
                assertEquals(0L, it.couch.updateSeq)
                assertNull(it.couch.localGet("gone"))
                assertEquals("0-${if (locals == 0) 1 else 3}", it.couch.localPut("next", emptyMap())["rev"])
            }
            assertContentEquals(head, files.readAllBytes("$source/HEAD"))
        }
    }

    @Test fun conversionRequiresExclusiveSourceAndNewDurableDestination() {
        open("source", CouchStorageFormat.COMMIT).use {
            it.couch.put("case", mapOf("value" to "retained"), null)
            assertFails { CouchStorage.convert(cas(), files, path("source"), path("dense")) }
        }
        source().use { assertFails { CouchStorage.convert(cas(), files, path("source"), path("dense")) } }
        assertFalse(files.exists(path("dense")))
        val head = files.readAllBytes(path("source/HEAD"))
        files.mkdirs(path("existing"))
        files.writeAtomically(path("existing/sentinel"), byteArrayOf(1, 2, 3))
        assertFails { CouchStorage.convert(cas(), files, path("source"), path("existing")) }
        assertContentEquals(byteArrayOf(1, 2, 3), files.readAllBytes(path("existing/sentinel")))
        files.mkdirs(path("sibling.store"))
        assertFails { CouchStorage.convert(cas(), files, path("source"), path("sibling")) }
        assertFails { CouchStorage.convert(CasStore.inMemory(), files, path("source"), path("dense")) }
        assertFails { CouchStorage.convert(cas(), files, path("missing"), path("dense")) }
        assertFalse(files.exists(path("dense")))
        assertFalse(files.exists(path("missing")))
        assertContentEquals(head, files.readAllBytes(path("source/HEAD")))
    }

    @Test fun sourceAdmissionIsRecheckedUnderTheSelectionLease() {
        source().close()
        val head = files.readAllBytes(path("source/HEAD"))
        var leased = false
        val changed = object : FileOperations by files {
            override fun acquireExclusiveLease(path: String): FileLease = files.acquireExclusiveLease(path).also {
                if (path == this@CouchConversionTest.path("source/.format.lock")) leased = true
            }
            override fun exists(filename: String): Boolean =
                if (leased && filename == path("source/HEAD")) false else files.exists(filename)
        }
        assertFails { CouchStorage.convert(cas(), changed, path("source"), path("dense")) }
        assertTrue(leased)
        assertFalse(files.exists(path("dense")))
        assertContentEquals(head, files.readAllBytes(path("source/HEAD")))
        files.acquireExclusiveLease(path("source/.format.lock")).use {}
    }

    @Test fun missingOrCorruptHistoryCannotBeConvertedAsAReadableSuffix() {
        lateinit var first: ContentId
        source().use {
            it.commit(frame(0, "first"))
            first = ContentId(CanonicalCbor.decodeMap(files.readAllBytes(path("source/HEAD")))["head"] as String)
            it.commit(frame(1, "second"))
        }
        val original = cas()
        val head = files.readAllBytes(path("source/HEAD"))
        for (corrupt in s_[false, true].view) {
            val broken = object : CasStore() {
                override val durability = StorageDurability.DURABLE
                override fun put(bytes: ByteArray): ContentId = error("Corrupt source must fail before publication")
                override fun get(cid: ContentId): ByteArray? = if (cid == first)
                    if (corrupt) byteArrayOf(99) else null else original.get(cid)
            }
            assertFails { CouchStorage.convert(broken, files, path("source"), path("dense-$corrupt")) }
            assertFalse(files.exists(path("dense-$corrupt")))
            files.acquireExclusiveLease(path("source/.format.lock")).use {}
            files.acquireExclusiveLease(path("source/.lock")).use {}
            assertContentEquals(head, files.readAllBytes(path("source/HEAD")))
        }
    }

    @Test fun failedPublicationOrFreshReopenKeepsDestinationPendingAndSourceIntact() {
        source().use { it.commit(frame(0, "retained")); it.localPut("peer", mapOf("last_seq" to 1L)) }
        val head = files.readAllBytes(path("source/HEAD"))
        for (stage in s_["artifact", "head", "reopen", "receipt"].view) {
            val destination = path(stage)
            var roots = 0
            var leases = 0
            var injected = false
            val faulty = object : FileOperations by files {
                override fun acquireExclusiveLease(path: String): FileLease = files.acquireExclusiveLease(path).also {
                    if (path == "$destination.store/.lock") leases++
                }
                override fun readAllBytes(filename: String): ByteArray {
                    if (stage == "reopen" && leases == 2 && filename == "$destination.store/HEAD") {
                        injected = true
                        return "invalid reopened root".encodeToByteArray()
                    }
                    return files.readAllBytes(filename)
                }
                override fun writeAtomically(filename: String, bytes: ByteArray) {
                    if (filename == "$destination.store/HEAD") roots++
                    val fail = when (stage) {
                        "artifact" -> filename.startsWith("$destination.store/") && filename.endsWith(".meta")
                        "head" -> filename == "$destination.store/HEAD" && roots == 2
                        "receipt" -> filename == "$destination/conversion.cbor" && CanonicalCbor.decodeMap(bytes)["state"] == "complete"
                        else -> false
                    }
                    if (fail) { injected = true; error("injected $stage failure") }
                    files.writeAtomically(filename, bytes)
                }
            }
            assertFails { CouchStorage.convert(cas(), faulty, path("source"), destination) }
            assertTrue(injected, "$stage injection must be reached")
            val pending = files.readAllBytes("$destination/conversion.cbor")
            assertEquals("pending", CanonicalCbor.decodeMap(pending)["state"])
            for (format in CouchStorageFormat.entries) assertFails { open(stage, format) }
            assertFails { CouchStorage.convert(cas(), files, path("source"), destination) }
            assertContentEquals(pending, files.readAllBytes("$destination/conversion.cbor"))
            files.acquireExclusiveLease("$destination/.format.lock").use {}
            files.acquireExclusiveLease("$destination.store/.lock").use {}
            open("source", CouchStorageFormat.COMMIT).use {
                assertEquals("retained", it.couch.docJson("case")!!["value"])
                assertEquals(1L, it.couch.localGet("peer")!!["last_seq"])
            }
            assertContentEquals(head, files.readAllBytes(path("source/HEAD")))
        }
    }

    @Test fun completionPublicationRetainsSourceAndDestinationWriterLeases() {
        source().use { it.commit(frame(0, "retained")) }
        var publication = false
        val guarded = object : FileOperations by files {
            override fun writeAtomically(filename: String, bytes: ByteArray) {
                if (filename == path("dense/conversion.cbor") && CanonicalCbor.decodeMap(bytes)["state"] == "complete") {
                    publication = true
                    for (lock in s_["source/.format.lock", "source/.lock", "dense/.format.lock", "dense.store/.lock"].view) {
                        assertFails("Conversion must still own $lock") { files.acquireExclusiveLease(path(lock)).use {} }
                    }
                }
                files.writeAtomically(filename, bytes)
            }
        }
        CouchStorage.convert(cas(), guarded, path("source"), path("dense"))
        assertTrue(publication)
        for (lock in s_["source/.format.lock", "source/.lock", "dense/.format.lock", "dense.store/.lock"].view) {
            files.acquireExclusiveLease(path(lock)).use {}
        }
    }

    @Test fun completedReceiptCannotCreateAnEmptyReplacementForALostDenseRoot() {
        source().use { it.commit(frame(0, "retained")) }
        CouchStorage.convert(cas(), files, path("source"), path("dense"))
        val denseHead = files.readAllBytes(path("dense.store/HEAD"))
        val missing = object : FileOperations by files {
            override fun exists(filename: String): Boolean = if (filename == path("dense.store/HEAD")) false else files.exists(filename)
        }
        for (format in CouchStorageFormat.entries) assertFails {
            CouchStorage.open("test", cas(), missing, path("dense"), format)
        }
        assertContentEquals(denseHead, files.readAllBytes(path("dense.store/HEAD")))
        assertFalse(files.exists(path("dense/HEAD")))
        // Malformed but nominally complete receipts are refused without rewriting them.
        val receiptPath = path("dense/conversion.cbor")
        val receipt = CanonicalCbor.decodeMap(files.readAllBytes(receiptPath))
        val malformed = CanonicalCbor.encodeMap(receipt - "sourceRoot")
        files.writeAtomically(receiptPath, malformed)
        assertFails { open("dense", CouchStorageFormat.CONFIX_ISAM) }
        assertContentEquals(malformed, files.readAllBytes(receiptPath))
        files.acquireExclusiveLease(path("dense/.format.lock")).use {}
    }

    @Test fun normalizedConversionDestinationCannotCreateASecondDenseRoot() {
        source().use { it.commit(frame(0, "retained")) }
        val head = files.readAllBytes(path("source/HEAD"))
        CouchStorage.convert(cas(), files, "${path("source")}/./", "${path("dense")}/./")
        assertTrue(files.exists(path("dense.store/HEAD")))
        assertFalse(files.exists(path("dense/.store")))
        assertFails { open("dense", CouchStorageFormat.COMMIT) }
        open("dense", CouchStorageFormat.CONFIX_ISAM).use {
            assertEquals("retained", it.couch.docJson("case")!!["value"])
        }
        assertContentEquals(head, files.readAllBytes(path("source/HEAD")))
    }

    @Test fun conversionThroughSymlinkedSourceAndNewDestinationRetainsOneDatabaseRoot() {
        source().use { it.commit(frame(0, "retained")) }
        val head = files.readAllBytes(path("source/HEAD"))
        files.mkdirs(path("destinations"))
        java.nio.file.Files.createSymbolicLink(root.resolve("source-alias"), root.resolve("source"))
        java.nio.file.Files.createSymbolicLink(root.resolve("destinations-alias"), root.resolve("destinations"))
        CouchStorage.convert(cas(), files, path("source-alias"), path("destinations-alias/dense"))
        for (name in listOf("destinations/dense", "destinations-alias/dense")) {
            assertFails { open(name, CouchStorageFormat.COMMIT) }
            open(name, CouchStorageFormat.CONFIX_ISAM).use {
                assertEquals("retained", it.couch.docJson("case")!!["value"])
            }
        }
        assertTrue(files.exists(path("destinations/dense.store/HEAD")))
        assertFails { CouchStorage.convert(cas(), files, path("source"), path("destinations/dense")) }
        assertContentEquals(head, files.readAllBytes(path("source/HEAD")))
    }
}
