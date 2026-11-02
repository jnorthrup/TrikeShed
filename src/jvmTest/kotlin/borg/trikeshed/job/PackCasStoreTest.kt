package borg.trikeshed.job

import borg.trikeshed.userspace.nio.file.spi.JvmFileOperations
import java.nio.file.Files
import kotlin.test.*

class PackCasStoreTest {
    @Test fun staleRefCanBePromotedToBlobAcrossReopen() {
        val files = JvmFileOperations()
        val root = files.createTempDir("pack-ref-promotion-")
        try {
            val path = files.resolvePath(root, "source")
            val bytes = "original source".encodeToByteArray()
            files.write(path, bytes)
            val cid = PackCasStore(files, root).use { store ->
                val cid = store.putFile(path)
                assertContentEquals(bytes, store.get(cid))
                files.write(path, "changed source length".encodeToByteArray())
                assertNull(store.get(cid))
                assertEquals(cid, store.put(bytes))
                assertContentEquals(bytes, store.get(cid))
                store.sync()
                cid
            }
            files.deleteRecursively(path)
            PackCasStore(files, root).use { store ->
                assertContentEquals(bytes, store.get(cid))
            }
        } finally { files.deleteRecursively(root) }
    }

    @Test fun refCannotReplaceBlob() {
        val files = JvmFileOperations()
        val root = files.createTempDir("pack-blob-retention-")
        try {
            val path = files.resolvePath(root, "source")
            val bytes = "stored bytes".encodeToByteArray()
            files.write(path, bytes)
            val cid = PackCasStore(files, root).use { store ->
                val cid = store.put(bytes)
                assertEquals(cid, store.putFile(path))
                files.deleteRecursively(path)
                assertContentEquals(bytes, store.get(cid))
                store.sync()
                cid
            }
            PackCasStore(files, root).use { store ->
                assertContentEquals(bytes, store.get(cid))
            }
        } finally { files.deleteRecursively(root) }
    }

    @Test fun refCanReplaceRefPathAcrossReopen() {
        val files = JvmFileOperations()
        val root = files.createTempDir("pack-ref-replacement-")
        try {
            val first = files.resolvePath(root, "first")
            val second = files.resolvePath(root, "second")
            val bytes = "referenced bytes".encodeToByteArray()
            files.write(first, bytes)
            files.write(second, bytes)
            val cid = PackCasStore(files, root).use { store ->
                val cid = store.putFile(first)
                assertEquals(cid, store.putFile(second))
                files.deleteRecursively(first)
                assertContentEquals(bytes, store.get(cid))
                store.sync()
                cid
            }
            PackCasStore(files, root).use { store ->
                assertContentEquals(bytes, store.get(cid))
            }
        } finally { files.deleteRecursively(root) }
    }

    @Test fun reopenCompactsRecoveredRunsWithoutRewritingThePack() {
        val files = JvmFileOperations()
        val root = Files.createTempDirectory("pack-recovery-").toString()
        try {
            val count = (1 shl 14) + 3
            fun body(i: Int) = "packed-row-$i".encodeToByteArray()
            val ids = PackCasStore(files, root).use { store ->
                List(count) { store.put(body(it)) }.also { store.sync() }
            }
            val path = files.resolvePath(root, "pack")
            val original = files.readAllBytes(path)
            val absent = ContentId.of("absent".encodeToByteArray())
            val next = PackCasStore(files, root).use { store ->
                assertEquals(1, store.segments().size)
                assertEquals(count.toLong(), store.segments().single()[2])
                assertContentEquals(original, files.readAllBytes(path))
                for (i in ids.indices) {
                    assertTrue(store.holds(ids[i]))
                    assertContentEquals(body(i), store.get(ids[i]))
                }
                assertFalse(store.holds(absent))
                assertNull(store.get(absent))
                assertEquals(ids[0], store.put(body(0)))
                assertContentEquals(original, files.readAllBytes(path))
                store.put(body(count)).also { store.sync() }
            }
            PackCasStore(files, root).use { store ->
                assertEquals(1, store.segments().size)
                assertContentEquals(body(count), store.get(next))
                assertContentEquals(body(0), store.get(ids[0]))
            }
        } finally { files.deleteRecursively(root) }
    }

    /** Forge a REF pack record only for structural prefix-collision testing. No CID validity is implied. */
    private fun appendSyntheticRef(files: JvmFileOperations, root: String, cid: ContentId, path: String, bytes: ByteArray) {
        val name = path.encodeToByteArray()
        val payload = java.nio.ByteBuffer.allocate(16 + name.size)
            .putLong(bytes.size.toLong()).putLong(files.lastModified(path)).put(name).array()
        val digest = cid.hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        java.io.DataOutputStream(java.io.FileOutputStream(java.io.File(root, "pack"), true)).use { out ->
            out.writeByte(PackCasStore.REF.toInt()); out.writeInt(payload.size); out.write(digest); out.write(payload)
        }
    }

    @Test fun syntheticPrefixCollisionKeepsRealBlobAndReferencesAcrossReopen() {
        val files = JvmFileOperations()
        val root = files.createTempDir("pack-prefix-collision-")
        try {
            val real = "real sha256 content".encodeToByteArray()
            val id = ContentId.of(real)
            val fakeHex = id.hex.substring(0, 16) + (if (id.hex[16] == '0') '1' else '0') + id.hex.substring(17)
            val fake = ContentId("sha256:$fakeHex")
            val first = files.resolvePath(root, "first")
            val second = files.resolvePath(root, "second")
            val refs = "synthetic reference".encodeToByteArray()
            files.write(first, refs); files.write(second, refs)
            // Explicitly forge the pack header: this tests indexing, not hash integrity.
            appendSyntheticRef(files, root, fake, first, refs)
            PackCasStore(files, root).use { store ->
                assertEquals(id, store.put(real)) // real digest creates collision against synthetic header
                assertTrue(store.holds(id)); assertTrue(store.holds(fake))
                assertContentEquals(real, store.get(id))
                assertContentEquals(refs, store.get(fake)) // legacy read is not a hash check
                assertFailsWith<IllegalStateException> { store.getBounded(fake, 1024) }
                val before = java.io.File(root, "pack").length()
                assertEquals(id, store.put(real))
                assertEquals(before, java.io.File(root, "pack").length())
                val absent = ContentId("sha256:" + id.hex.substring(0, 16) + "f".repeat(48))
                if (absent != id && absent != fake) { assertFalse(store.holds(absent)); assertNull(store.get(absent)) }
                store.sync()
            }
            // Simulate legacy REF replacement while no PackCasStore handle is open.
            appendSyntheticRef(files, root, fake, second, refs)
            files.deleteRecursively(first)
            PackCasStore(files, root).use { store ->
                assertContentEquals(real, store.get(id))
                assertContentEquals(refs, store.get(fake)) // legacy read is not a hash check
                assertFailsWith<IllegalStateException> { store.getBounded(fake, 1024) }
                val before = java.io.File(root, "pack").length()
                assertEquals(id, store.put(real))
                assertEquals(before, java.io.File(root, "pack").length())
            }
        } finally { files.deleteRecursively(root) }
    }

    @Test fun legacyInterleavedCollisionRecoveryPrefersStoredBlobOverLaterReference() {
        val files = JvmFileOperations()
        val root = files.createTempDir("pack-legacy-collision-")
        try {
            val original = "verified blob".encodeToByteArray()
            val id = PackCasStore(files, root).use { store -> store.put(original).also { store.sync() } }
            val fake = ContentId("sha256:" + id.hex.substring(0, 16) +
                (if (id.hex[16] == '0') '1' else '0') + id.hex.substring(17))
            val fakeFile = files.resolvePath(root, "fake-ref")
            val oldFile = files.resolvePath(root, "later-ref")
            files.write(fakeFile, "fake".encodeToByteArray())
            files.write(oldFile, original)
            // Simulate the old collision bug's interleaved on-disk history; the fake
            // header is synthetic and cannot validate cryptographic content integrity.
            fun appendRef(cid: ContentId, path: String, bytes: ByteArray) {
                val name = path.encodeToByteArray()
                val payload = java.nio.ByteBuffer.allocate(16 + name.size)
                    .putLong(bytes.size.toLong()).putLong(files.lastModified(path)).put(name).array()
                val digest = cid.hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
                java.io.DataOutputStream(java.io.FileOutputStream(java.io.File(root, "pack"), true)).use { out ->
                    out.writeByte(PackCasStore.REF.toInt()); out.writeInt(payload.size); out.write(digest); out.write(payload)
                }
            }
            appendRef(fake, fakeFile, "fake".encodeToByteArray())
            appendRef(id, oldFile, original)
            files.deleteRecursively(oldFile)
            PackCasStore(files, root).use { store ->
                assertTrue(store.holds(id)); assertTrue(store.holds(fake))
                assertContentEquals(original, store.get(id))
                val before = java.io.File(root, "pack").length()
                assertEquals(id, store.put(original))
                assertEquals(before, java.io.File(root, "pack").length())
            }
        } finally { files.deleteRecursively(root) }
    }

    @Test fun realReferencePromotesToBlobAndIsNotDemotedAcrossSyntheticCollision() {
        val files = JvmFileOperations()
        val root = files.createTempDir("pack-collision-promotion-")
        try {
            val real = "real promotion body".encodeToByteArray()
            val id = ContentId.of(real)
            val fake = ContentId("sha256:" + id.hex.substring(0, 16) +
                (if (id.hex[16] == '0') '1' else '0') + id.hex.substring(17))
            val actualPath = files.resolvePath(root, "real-ref")
            val replacementPath = files.resolvePath(root, "real-ref-replacement")
            val fakePath = files.resolvePath(root, "synthetic-ref")
            val bogus = "synthetic index reference".encodeToByteArray()
            files.write(actualPath, real); files.write(replacementPath, real); files.write(fakePath, bogus)
            // Synthetic header is a structural fixture, not a valid content-identity assertion.
            appendSyntheticRef(files, root, fake, fakePath, bogus)
            PackCasStore(files, root).use { store ->
                assertEquals(id, store.putFile(actualPath))
                // The second authentic reference replaces the first in an existing collision bucket.
                assertEquals(id, store.putFile(replacementPath))
                files.deleteRecursively(actualPath)
                assertContentEquals(real, store.get(id))
                assertContentEquals(real, store.getBounded(id, real.size))
                store.sync()
            }
            PackCasStore(files, root).use { store ->
                assertContentEquals(real, store.get(id))
                assertContentEquals(real, store.getBounded(id, real.size))
                files.deleteRecursively(replacementPath)
                assertNull(store.get(id))
                assertEquals(id, store.put(real))
                assertContentEquals(real, store.get(id))
                assertContentEquals(real, store.getBounded(id, real.size))
                files.write(actualPath, real)
                val before = java.io.File(root, "pack").length()
                assertEquals(id, store.putFile(actualPath))
                assertEquals(before, java.io.File(root, "pack").length())
                assertContentEquals(bogus, store.get(fake)) // legacy read is not a hash check
                assertFailsWith<IllegalStateException> { store.getBounded(fake, 1024) }
                store.sync()
            }
            files.deleteRecursively(actualPath)
            PackCasStore(files, root).use { store ->
                assertContentEquals(real, store.get(id))
                assertContentEquals(real, store.getBounded(id, real.size))
                assertTrue(store.holds(fake))
                assertContentEquals(bogus, store.get(fake)) // legacy read is not a hash check
                assertFailsWith<IllegalStateException> { store.getBounded(fake, 1024) }
            }
        } finally { files.deleteRecursively(root) }
    }
}
