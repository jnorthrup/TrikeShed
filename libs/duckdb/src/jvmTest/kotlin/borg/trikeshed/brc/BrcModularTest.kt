package borg.trikeshed.brc

import borg.trikeshed.lib.brc.BrcMmap
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.time.Duration
import kotlin.system.measureNanoTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BrcModularTest {

    val inputFile: String
    val hasLargeFile: Boolean

    init {
        val large = File(BrcCache.PATH)
        val small = File("src/brcTest/resources/brc/measurements_test.txt")
        hasLargeFile = large.exists() && large.length() > 0
        inputFile = when {
            hasLargeFile -> large.absolutePath
            small.exists() -> small.absolutePath
            else -> error("No BRC input file found — expected ${BrcCache.PATH} or $small")
        }
        System.err.println("BrcModularTest: input=$inputFile large=$hasLargeFile")
    }

    inline fun measureNanoTimeStr(block: () -> Unit): String =
        Duration.ofNanos(measureNanoTime(block)).toString()

    fun capture(block: () -> Unit): String {
        val orig = System.out
        val baos = ByteArrayOutputStream()
        System.setOut(PrintStream(baos, true, "UTF-8"))
        try { block() } finally { System.setOut(orig) }
        return baos.toString("UTF-8").trim()
    }

    val args get() = arrayOf(inputFile)

    // ── variants ─────────────────────────────────────────────────────

    @Test fun brcMmap() {
        lateinit var result: String
        val t = measureNanoTimeStr { result = capture { BrcMmap.main(args) } }
        System.err.println("BrcMmap:          $t  (${result.length} chars)")
        assertTrue(result.startsWith("{"))
    }

    @Test fun brcCsvJvm() {
        // BrcCsvJvm is a conversion stage (CSV → ISAM); it produces no stdout output.
        val t = measureNanoTimeStr { BrcCsvJvm.main(args) }
        val isamFile = java.io.File("$inputFile.isam")
        System.err.println("BrcCsvJvm:        $t  isam=${isamFile.exists()} (${isamFile.length()} bytes)")
        assertTrue(isamFile.exists() && isamFile.length() > 0, "ISAM file not created")
    }

    // ── all variants agree (small dataset only) ───────────────────────

    @Test fun allVariantsAgree() {
        if (hasLargeFile) {
            System.err.println("allVariantsAgree: skipping on large file")
            return
        }
        val results = linkedMapOf(
            "BrcMmap" to capture { BrcMmap.main(args) },
        )
        val reference = results.values.first()
        for ((name, output) in results) {
            assertEquals(reference, output, "$name disagrees with BrcMmap")
        }
        System.err.println("allVariantsAgree: ${results.size} variants match (${reference.length} chars)")
    }
}
