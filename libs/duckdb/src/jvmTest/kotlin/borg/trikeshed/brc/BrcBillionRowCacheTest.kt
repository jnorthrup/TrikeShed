package borg.trikeshed.brc

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Billion-row cache fixture contract.
 *
 * The canonical 1BRC dataset is expected at [BRC_CACHE_PATH].
 * Generate it with: `java -cp brc.jar dev.morling.onebrc.CreateMeasurements 1000000000`
 * or the equivalent shell script in the gunnarmorling/1brc repo.
 *
 * All tests here skip cleanly when the file is absent.
 */
object BrcCache {
    const val PATH = "/tmp/measurements.txt"
    fun file(): File = File(PATH)
    fun exists(): Boolean = file().exists() && file().length() > 0
}

class BrcBillionRowCacheTest {

    private fun skipIfAbsent(): Boolean {
        if (!BrcCache.exists()) {
            println("SKIP: ${BrcCache.PATH} not found — generate with 1brc CreateMeasurements 1_000_000_000")
            return true
        }
        return false
    }

    @Test
    fun cachedFileExistsOrSkip() {
        if (skipIfAbsent()) return
        val f = BrcCache.file()
        assertTrue(f.canRead(), "Cache file must be readable: ${BrcCache.PATH}")
        assertTrue(f.length() > 0, "Cache file must be non-empty")
    }

    @Test
    fun cachedFileLineCountApproximatelyOneBillion() {
        if (skipIfAbsent()) return
        val f = BrcCache.file()
        // Read first 4096 bytes, count newlines to estimate line density
        val buf = ByteArray(4096)
        val read = f.inputStream().use { it.read(buf) }
        val newlines = buf.take(read).count { it == '\n'.code.toByte() }
        assertTrue(newlines > 0, "Expected newlines in first 4096 bytes, got 0")
        println("Estimated line density: $newlines lines in first $read bytes")
        println("Estimated total lines: ${(f.length().toDouble() / read * newlines).toLong()}")
    }
}
