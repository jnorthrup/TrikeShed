package borg.trikeshed.job

import borg.trikeshed.userspace.nio.file.spi.JvmFileOperations
import java.nio.file.Files
import kotlin.test.*

class PackCasStoreTest {
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
