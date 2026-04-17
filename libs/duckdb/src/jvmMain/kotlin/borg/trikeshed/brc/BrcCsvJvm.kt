/**
 * 1BRC Stage 1 — CSV → ISAM conversion  (IO strategy: mmap CSV)
 *
 * Streams measurements.txt via CSVUtil.streamSpec (TypeEvidence-deduced types,
 * zero row materialisation) and writes a binary ISAM for the next pipeline stage.
 *
 * Output: <file>.isam + <file>.isam.meta  (consumed by BrcIsamJvm / BrcDuckDbJvm)
 * Caller is responsible for deleting the ISAM files after use.
 */
package borg.trikeshed.brc

import borg.trikeshed.common.FileBuffer
import borg.trikeshed.isam.IsamDataFile
import borg.trikeshed.parse.csv.CSVUtil

object BrcCsvJvm {

    @JvmStatic
    fun main(args: Array<String>) {
        val csvPath = args.firstOrNull() ?: System.getenv("BRC_FILE") ?: "measurements.txt"
        val isamPath = "$csvPath.isam"

        val fb = FileBuffer(csvPath, 0L, -1L, true)
        fb.open()
        try {
            val spec = CSVUtil.streamSpec(fb, ';', false)
            IsamDataFile.append(spec.rows.asIterable(), isamPath, spec.varChars)
        } finally {
            fb.close()
        }
        System.err.println("BrcCsvJvm: wrote $isamPath")
    }
}
