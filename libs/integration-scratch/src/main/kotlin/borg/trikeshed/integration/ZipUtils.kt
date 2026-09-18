package borg.trikeshed.integration

import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * ZIP utility for extracting Binance CSV ZIP archives.
 *
 * Binance data files are served as .csv.zip where the ZIP contains exactly
 * one entry named "{symbol}-{interval}-{date}.csv".
 */
object ZipUtils {

    /**
     * Read the first matching ZIP entry as a String.
     *
     * @param input ZIP input stream (caller owns the stream)
     * @param entryName name of the entry to extract (exact match)
     * @return entry contents as String, or null if not found or ZIP is empty
     */
    fun readZipEntry(input: InputStream, entryName: String): String? {
        ZipInputStream(input).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (entry.name == entryName) {
                    return zis.bufferedReader().readText()
                }
                entry = zis.nextEntry
            }
            return null
        }
    }
}
