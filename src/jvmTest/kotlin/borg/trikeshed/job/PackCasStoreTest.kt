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
}
