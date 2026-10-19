@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package borg.trikeshed.couch

import borg.trikeshed.couch.persistence.CouchCommitStore
import borg.trikeshed.isam.*
import borg.trikeshed.job.*
import borg.trikeshed.lib.*
import borg.trikeshed.parse.confix.*
import borg.trikeshed.userspace.nio.file.spi.*
import borg.trikeshed.userspace.nio.spi.platformNioProviders
import borg.trikeshed.util.oroboros.FileCasStore
import borg.trikeshed.tilting.zran.ZstdDictionaryTest
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class NativeCouchStorageTest {
    @Test
    fun dictionaryConversionReopensWithThePortableCasCodec() {
        val files = platformNioProviders().filterIsInstance<FileOperations>().single()
        val directory = files.canonicalPath(files.createTempDir("trikeshed-native-dictionary"))
        val source = "$directory/source"
        val destination = "$directory/dense"
        val cas = FileCasStore(files, "$directory/cas")
        try {
            val dictionary = cas.put(ZstdDictionaryTest.trained())
            CouchStorage.open("test", cas, files, source).use {
                assertEquals(true, it.couch.put("case", mapOf("value" to "medical/legal evidence", "terms" to listOf("医", "review")), null)["ok"])
            }
            val head = files.readAllBytes("$source/HEAD")
            val writer = UringIsamOperations(leafBytes = 256, leafCodec = CasLeafCodec(cas, dictionary = dictionary))
            assertEquals(1, CouchStorage.convert(cas, files, source, destination, writer).revisions)
            val denseHead = files.readAllBytes("$destination.store/HEAD")
            CouchStorage.open("test", cas, files, destination, CouchStorageFormat.CONFIX_ISAM,
                UringIsamOperations(leafBytes = 256, leafCodec = CasLeafCodec(cas))).use {
                assertEquals("medical/legal evidence", it.couch.docJson("case")!!["value"])
                val doc = it.dense!!.document("case")!!
                assertEquals(2, doc.docAt("terms")!!.cellKids.size)
                assertEquals("医", doc.value("terms", 0))
                assertEquals("review", doc.value("terms", 1))
            }
            val missing = object : CasStore() {
                override val durability = StorageDurability.DURABLE
                override fun get(cid: ContentId): ByteArray? = if (cid == dictionary) null else cas.get(cid)
            }
            val failure = assertFailsWith<IllegalArgumentException> {
                CouchStorage.open("test", missing, files, destination, CouchStorageFormat.CONFIX_ISAM,
                    UringIsamOperations(leafBytes = 256, leafCodec = CasLeafCodec(missing))).use { it.couch.docJson("case") }
            }
            assertContains(failure.message.orEmpty(), dictionary.value)
            assertContentEquals(denseHead, files.readAllBytes("$destination.store/HEAD"))
            assertContentEquals(head, files.readAllBytes("$source/HEAD"))
        } finally { files.deleteRecursively(directory) }
    }

    @Test
    fun denseDirectoryAliasesRecoverTheSameRootAndRefuseCommitFormat() {
        val files = platformNioProviders().filterIsInstance<FileOperations>().single()
        val directory = files.canonicalPath(files.createTempDir("trikeshed-native-couch-alias"))
        val location = "$directory/dense"
        val linked = "$directory/linked"
        val cas = FileCasStore(files, "$directory/cas")
        try {
            CouchStorage.open("test", cas, files, "$location/", CouchStorageFormat.CONFIX_ISAM).use {
                assertEquals(true, it.couch.put("case", mapOf("value" to "retained"), null)["ok"])
            }
            assertEquals(0, platform.posix.symlink(location, linked))
            val head = files.readAllBytes("$location.store/HEAD")
            for (alias in s_["$location/", "$location/.", "$directory/./dense", "$location/../dense", linked].view) {
                assertFails { CouchStorage.open("test", cas, files, alias).close() }
                CouchStorage.open("test", cas, files, alias, CouchStorageFormat.CONFIX_ISAM).use {
                    assertEquals("retained", it.couch.docJson("case")!!["value"])
                }
                assertContentEquals(head, files.readAllBytes("$location.store/HEAD"))
            }
            assertFalse(files.exists("$location/HEAD"))
            assertFalse(files.exists("$location/.store"))
            assertFalse(files.exists("$linked.store"))
        } finally { platform.posix.unlink(linked); files.deleteRecursively(directory) }
    }

    @Test
    fun conversionReopensExactHistoryAndKeepsTheSourceRoot() = runBlocking<Unit> {
        val files = platformNioProviders().filterIsInstance<FileOperations>().single()
        val directory = files.canonicalPath(files.createTempDir("trikeshed-native-couch"))
        val source = "$directory/source"
        val destination = "$directory/dense"
        fun cas() = FileCasStore(files, "$directory/cas")
        try {
            assertEquals(StorageDurability.DURABLE, cas().durability)
            val payload = "Synthetic evidence — 患者".encodeToByteArray()
            val cid = cas().put(payload)
            val original = Document("case", listOf(Field("contentId", cid.value), Field("bytes", byteArrayOf(0, 127, -1))))
            CouchCommitStore(cas(), StorageDurability.DURABLE, files, source).use {
                it.commit(CouchCommittedFrame(4, "case", "1-original", false, original))
                it.commit(CouchCommittedFrame(8, "case", "2-updated", false, Document("case", listOf(Field("value", "updated")))))
                it.commit(CouchCommittedFrame(11, "gone", "1-deleted", true, null))
                it.localPut("peer", mapOf("last_seq" to 12L))
                it.localPut("removed", emptyMap())
                assertTrue(it.localDelete("removed"))
            }
            val sourceHead = files.readAllBytes("$source/HEAD")
            val before = CouchStorage.open("test", cas(), files, source).use { it.couch.changes(0, includeDocs = true) }
            val converted = CouchStorage.convert(cas(), files, source, destination, UringIsamOperations(leafBytes = 256))
            assertEquals(3, converted.revisions)
            assertEquals(1, converted.localDocuments)
            assertContentEquals(sourceHead, files.readAllBytes("$source/HEAD"))
            CouchStorage.open("test", cas(), files, destination, CouchStorageFormat.CONFIX_ISAM).use { owner ->
                assertContentEquals(CanonicalCbor.encodeMap(before), CanonicalCbor.encodeMap(owner.couch.changes(0, includeDocs = true)))
                assertContentEquals(CouchStoreFactory.canonicalBody(original), owner.dense!!.document("case", "1-original")!!.src.toArray())
                assertContentEquals(payload, cas().get(cid))
                assertEquals(12L, owner.couch.localGet("peer")!!["last_seq"])
                assertNull(owner.couch.localGet("removed"))
                assertEquals(12L, owner.couch.updateSeq)
                assertFails { CouchStorage.open("test", cas(), PosixFileOperations(), destination, CouchStorageFormat.CONFIX_ISAM).close() }
                assertEquals("0-3", owner.couch.localPut("next", emptyMap())["rev"])
                assertEquals(true, owner.couch.put("next", mapOf("value" to "native"), null)["ok"])
            }
            CouchStorage.open("test", cas(), files, destination, CouchStorageFormat.CONFIX_ISAM).use {
                assertEquals("native", it.couch.docJson("next")!!["value"])
                assertEquals("0-3", it.couch.localGet("next")!!["_rev"])
                assertEquals(13L, it.couch.updateSeq)
            }
            assertContentEquals(sourceHead, files.readAllBytes("$source/HEAD"))
        } finally { files.deleteRecursively(directory) }
    }

    @Test
    fun failedCompletionPublicationRetainsPendingDestinationAndReleasesLeases() {
        val files = platformNioProviders().filterIsInstance<FileOperations>().single()
        val directory = files.canonicalPath(files.createTempDir("trikeshed-native-conversion-failure"))
        val source = "$directory/source"
        val destination = "$directory/dense"
        val cas = FileCasStore(files, "$directory/cas")
        try {
            CouchCommitStore(cas, StorageDurability.DURABLE, files, source).use {
                it.commit(CouchCommittedFrame(0, "case", "1-retained", false,
                    Document("case", listOf(Field("value", "retained")))))
            }
            val head = files.readAllBytes("$source/HEAD")
            var completion = false
            val faulty = object : FileOperations by files {
                override fun writeAtomically(filename: String, bytes: ByteArray) {
                    if (filename == "$destination/conversion.cbor" && CanonicalCbor.decodeMap(bytes)["state"] == "complete") {
                        completion = true
                        error("Injected completion publication failure")
                    }
                    files.writeAtomically(filename, bytes)
                }
            }
            assertFails { CouchStorage.convert(cas, faulty, source, destination, UringIsamOperations(leafBytes = 256)) }
            assertTrue(completion)
            val pending = files.readAllBytes("$destination/conversion.cbor")
            assertEquals("pending", CanonicalCbor.decodeMap(pending)["state"])
            for (format in CouchStorageFormat.entries) {
                assertFails { CouchStorage.open("test", cas, files, destination, format).close() }
            }
            assertFails { CouchStorage.convert(cas, files, source, destination) }
            assertContentEquals(pending, files.readAllBytes("$destination/conversion.cbor"))
            assertContentEquals(head, files.readAllBytes("$source/HEAD"))
            for (lock in s_["$source/.format.lock", "$source/.lock", "$destination/.format.lock", "$destination.store/.lock"].view) {
                files.acquireExclusiveLease(lock).close()
            }
            CouchStorage.open("test", cas, files, source).use {
                assertEquals("retained", it.couch.docJson("case")!!["value"])
            }
        } finally { files.deleteRecursively(directory) }
    }
}
