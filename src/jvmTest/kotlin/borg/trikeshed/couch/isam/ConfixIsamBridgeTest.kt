package borg.trikeshed.couch.isam

import borg.trikeshed.couch.*
import borg.trikeshed.couch.persistence.CouchCommitStore
import borg.trikeshed.isam.*
import borg.trikeshed.job.*
import borg.trikeshed.lcnc.CursorFacetQuery
import borg.trikeshed.lcnc.WorkspaceFacets
import borg.trikeshed.lib.*
import borg.trikeshed.lib.get
import borg.trikeshed.parse.confix.*
import borg.trikeshed.userspace.nio.ByteBuffer
import borg.trikeshed.userspace.nio.file.spi.*
import borg.trikeshed.util.oroboros.FileCasStore
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class ConfixIsamBridgeTest {
    inline fun directory(body: (Path) -> Unit) {
        val root = Files.createTempDirectory("confix-isam-")
        try { body(root) } finally {
            Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        }
    }

    fun cas(root: Path) = FileCasStore(JvmFileOperations(), root.resolve("cas").toString())
    fun bridge(root: Path, files: FileOperations = JvmFileOperations(), store: CasStore = cas(root), dictionary: ContentId? = null) =
        confixIsamStore {
            dataFileLocation = root.resolve("dense").toString()
            fileOps = files
            cas = store
            operations = UringIsamOperations(leafBytes = 256, leafCodec = CasLeafCodec(store, { ZstdDictionaryCodec(it) }, dictionary))
        }

    fun equivalent(expected: ConfixDoc, actual: ConfixDoc) {
        assertContentEquals(expected.src.toArray(), actual.src.toArray())
        for (key in listOf(ConfixIndexK.Spans, ConfixIndexK.Tags, ConfixIndexK.Depths, ConfixIndexK.StructuralNodes)) {
            @Suppress("UNCHECKED_CAST")
            val a = expected.index.b(key) as Series<Any?>
            @Suppress("UNCHECKED_CAST")
            val b = actual.index.b(key) as Series<Any?>
            assertEquals(a.size, b.size)
            for (i in 0 until a.size) assertEquals(a[i].toString(), b[i].toString(), "$key token $i")
        }
        val childrenA = expected.index.facet(ConfixIndexK.DirectChildren)
        val childrenB = actual.index.facet(ConfixIndexK.DirectChildren)
        for (i in 0 until expected.index.facet(ConfixIndexK.Tags).size) assertEquals(childrenA(i).view.toList(), childrenB(i).view.toList())
        assertContentEquals(CanonicalCbor.encode(expected), CanonicalCbor.encode(actual), "Nested cursor reification")
    }

    @Test fun exactSourcesTreesAndHistoricalRevisionsSurviveNewOwners() = directory { root ->
        val store = cas(root)
        val dictionary = store.put("nested source patients evidence legal review parent open close tag".encodeToByteArray())
        val json = (""" { "patients": [{"name":"Māori 医", "evidence":[1,null,{"text":"""" + "quoted evidence ".repeat(20) +
            """"}]},{}], "flag":true } """).encodeToByteArray()
        val yaml = "case: review\nparties:\n  - claimant\n  - respondent\n".encodeToByteArray()
        val cbor = CanonicalCbor.encodeMap(mapOf("bytes" to byteArrayOf(0, 127, -1), "nested" to listOf(mapOf("v" to 17L))))
        val sources = listOf(json to Syntax.JSON, yaml to Syntax.YAML, cbor to Syntax.CBOR, byteArrayOf() to Syntax.YAML)
        val longId = "clinical/医/" + "x".repeat(150)
        bridge(root, store = store, dictionary = dictionary).use { out ->
            for ((i, pair) in sources.withIndex()) out.put(i.toLong(), if (i == 0) longId else "doc-$i", "1-$i", pair.first, pair.second)
            out.put(10, longId, "2-new", "[1,2,{\"x\":[]}]".encodeToByteArray(), Syntax.JSON)
            out.delete(12, "doc-1", "2-deleted")
            assertFails { bridge(root).close() }
        }
        bridge(root).use { reopened ->
            assertEquals(6, reopened.revisions.size)
            for ((i, pair) in sources.withIndex()) equivalent(confixDoc(pair.first, pair.second),
                assertNotNull(reopened.document(if (i == 0) longId else "doc-$i", "1-$i")))
            assertNull(reopened.document("doc-1"))
            assertTrue(reopened.revision("doc-1")!!.deleted)
            assertEquals("2-new", reopened.revision(longId)!!.rev)
            assertEquals(3, reopened.toCursor().size)
            assertNotNull(reopened.toCursor()[0][4].a)
            reopened.put(13, "new-owner", "1-fresh", "{}".encodeToByteArray(), Syntax.JSON)
        }
        bridge(root).use { assertNotNull(it.document("new-owner")) }
    }

    @Test fun couchConversionIsExplicitIdempotentAndKeepsTombstones() = directory { root ->
        val files = JvmFileOperations()
        val store = cas(root)
        val sourceDirectory = root.resolve("couch").toString()
        val first = CouchCommittedFrame(7, "a", "1-a", false, Document("a", listOf(Field("parties", listOf("one", "two")))))
        val second = CouchCommittedFrame(9, "a", "2-a", false, Document("a", listOf(Field("result", mapOf("review" to true)))))
        val tombstone = CouchCommittedFrame(15, "a", "3-a", true, null)
        CouchCommitStore(store, StorageDurability.DURABLE, files, sourceDirectory).use {
            for (frame in s_[first, second, tombstone].view) it.commit(frame)
        }
        val sourceHead = files.readAllBytes("$sourceDirectory/HEAD")
        CouchCommitStore(store, StorageDurability.DURABLE, files, sourceDirectory).use { source ->
            bridge(root).use { destination ->
                destination.importFrames(source.recoveredFrames)
                val head = files.readAllBytes(root.resolve("dense.store/HEAD").toString())
                destination.importFrames(source.recoveredFrames)
                assertContentEquals(head, files.readAllBytes(root.resolve("dense.store/HEAD").toString()))
                assertFails { destination.put(7, "a", "1-a", "{}".encodeToByteArray(), Syntax.JSON) }
            }
        }
        bridge(root).use { destination ->
            assertEquals(listOf(7L, 9L, 15L), destination.revisions.view.map { it.sequence })
            assertNull(destination.document("a"))
            assertContentEquals(CouchStoreFactory.canonicalBody(first.doc!!), destination.document("a", "1-a")!!.src.toArray())
            assertContentEquals(CouchStoreFactory.canonicalBody(second.doc!!), destination.document("a", "2-a")!!.src.toArray())
        }
        assertContentEquals(sourceHead, files.readAllBytes("$sourceDirectory/HEAD"))
    }

    @Test fun couchUnicodeKeysAndValuesRemainUsableThroughConfix() = directory { root ->
        val text = "Māori 医 — testimony 🧬 ".repeat(4)
        val doc = Document("unicode", listOf(Field("患者", mapOf("résumé" to text))))
        bridge(root).use { it.importFrames(s_[CouchCommittedFrame(0, doc.id, "1-a", false, doc)]) }
        val recovered = bridge(root).use { assertNotNull(it.document(doc.id)) }
        assertEquals(text, recovered.value("患者", "résumé"))
        assertNotNull(recovered.index.facet(ConfixIndexK.KeyToChild)("患者"))
        assertContentEquals(CouchStoreFactory.canonicalBody(doc), recovered.src.toArray())
    }

    @Test fun failedPublicationDoesNotReplacePriorRevisionOrRoot() = directory { root ->
        val delegate = JvmFileOperations()
        var fail = false
        val files = object : FileOperations by delegate {
            override fun writeAtomically(filename: String, bytes: ByteArray) {
                if (fail && filename.endsWith("/HEAD")) error("injected root failure")
                delegate.writeAtomically(filename, bytes)
            }
        }
        bridge(root, files).use { out ->
            out.put(0, "a", "1-a", "{\"v\":1}".encodeToByteArray(), Syntax.JSON)
            val head = delegate.readAllBytes(root.resolve("dense.store/HEAD").toString())
            fail = true
            assertFails { out.put(1, "a", "2-a", "{\"v\":2}".encodeToByteArray(), Syntax.JSON) }
            assertContentEquals(head, delegate.readAllBytes(root.resolve("dense.store/HEAD").toString()))
            assertFails { out.document("a") }
        }
        bridge(root).use { reopened ->
            assertEquals("1-a", reopened.revision("a")!!.rev)
            reopened.put(1, "a", "2-a", "{\"v\":2}".encodeToByteArray(), Syntax.JSON)
            assertEquals("2-a", reopened.revision("a")!!.rev)
        }
    }

    @Test fun corruptGeometryFailsWithoutChangingStoredBytes() = directory { root ->
        val entry = bridge(root).use { it.put(0, "a", "1-a", "{\"v\":1}".encodeToByteArray(), Syntax.JSON) }
        val file = root.resolve("dense.store").resolve(entry.dataFile!!)
        val corrupt = Files.readAllBytes(file).also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        Files.write(file, corrupt)
        bridge(root).use { assertFails { it.document("a") } }
        assertContentEquals(corrupt, Files.readAllBytes(file))
    }

    @Test fun reopenedGeometryFeedsThePanelsHierarchicalConcurrentIndexes() = directory { root ->
        val source = """{"rows":[{"a":1,"b":null},{"a":2,"b":"ok"}],"review":true}""".encodeToByteArray()
        val query = CursorFacetQuery(path = s_[0, 1], axes = s_[intArrayOf(2), intArrayOf(0)], selections = s_[0 j 0, 1 j 1])
        val expected = WorkspaceFacets.cursorFacet(confixDoc(source, Syntax.JSON), query, 32)
        bridge(root).use { it.put(0, "panel", "1-a", source, Syntax.JSON) }
        val doc = bridge(root).use { assertNotNull(it.document("panel")) }
        // The returned Confix tree is usable after its storage owner's channels and lease close.
        val actual = WorkspaceFacets.cursorFacet(doc, query, 32)
        assertEquals(expected, actual)
        assertEquals(1, actual["selectedCount"])
        assertEquals(1, ((actual["rows"] as List<*>).single() as Map<*, *>)["ordinal"])
        assertEquals(2, (actual["indexes"] as List<*>).size)
    }

    @Test fun missingOlderHistoryCannotBeAcceptedAsACompleteConversion() = directory { root ->
        val store = cas(root)
        bridge(root, store = store).use {
            it.put(0, "a", "1-a", "{}".encodeToByteArray(), Syntax.JSON)
            it.put(1, "b", "1-b", "[]".encodeToByteArray(), Syntax.JSON)
        }
        val headPath = root.resolve("dense.store/HEAD")
        val headBytes = Files.readAllBytes(headPath)
        val head = ContentId(CanonicalCbor.decodeMap(headBytes)["head"] as String)
        val missing = ContentId(CanonicalCbor.decodeMap(store.get(head)!!)["previous"] as String)
        val incomplete = object : CasStore() {
            override val durability = StorageDurability.DURABLE
            override fun get(cid: ContentId): ByteArray? = if (cid == missing) null else store.get(cid)
        }
        assertFailsWith<IllegalArgumentException> { bridge(root, store = incomplete).close() }
        assertContentEquals(headBytes, Files.readAllBytes(headPath))
        bridge(root).use { assertEquals(2, it.revisions.size) }
    }

    @Test fun revisionMetadataCannotMutateTheStorageOwnersIntegrityManifest() = directory { root ->
        bridge(root).use { out ->
            val returned = out.put(0, "a", "1-a", "{\"x\":[]}".encodeToByteArray(), Syntax.JSON)
            (returned.files as MutableMap).clear()
            (out.revision("a")!!.files as MutableMap).clear()
            (out.revisions[0].files as MutableMap).clear()
            assertEquals(5, out.revision("a")!!.files.size)
            assertNotNull(out.document("a"))
        }
    }

    fun walFrame(seq: Long, id: String, rev: String, source: ByteArray): ByteArray {
        val a = id.encodeToByteArray(); val b = rev.encodeToByteArray()
        return ByteBuffer.allocate(20 + a.size + b.size + source.size).apply {
            putLong(seq); putInt(a.size); put(a); putInt(b.size); put(b); putInt(source.size); put(source)
        }.array()
    }

    @Test fun legacyReplayPreservesBinarySourceAndRejectsPartialTailBeforeConversion() = directory { root ->
        val source = CanonicalCbor.encodeMap(mapOf("blob" to byteArrayOf(-1, 0, 1)))
        val frame = walFrame(3, "binary", "7-retained", source)
        val path = root.resolve("legacy.wal")
        Files.write(path, frame + byteArrayOf(0))
        bridge(root).use { out ->
            val wal = ConfixWal(path.toString(), JvmFileOperations())
            assertFails { wal.replay(out, Syntax.CBOR) }
            assertEquals(0, out.revisions.size)
            assertContentEquals(frame + byteArrayOf(0), Files.readAllBytes(path))
            Files.write(path, frame)
            wal.replay(out, Syntax.CBOR)
            wal.replay(out, Syntax.CBOR)
            assertEquals(1, out.revisions.size)
            assertEquals("7-retained", out.revision("binary")!!.rev)
            assertContentEquals(source, out.document("binary")!!.src.toArray())
            assertContentEquals(frame, Files.readAllBytes(path))
        }
    }
}
