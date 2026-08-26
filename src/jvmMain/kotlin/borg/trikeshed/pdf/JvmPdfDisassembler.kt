package borg.trikeshed.pdf

import borg.trikeshed.lib.FileBuffer
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.j
import java.util.zip.Inflater

/**
 * JVM wiring for the baremetal disassembler: the ONLY JDK dependency is
 * `java.util.zip.Inflater` for FlateDecode (zlib/RFC 1950 — nowrap=false is
 * correct, no external inflate library, this is the JDK itself). Everything
 * else — lexing, object scanning, filters, page tree, ToUnicode CMaps,
 * content-stream operators — is pure commonMain over `Series<Byte>`.
 *
 * The file is never read fully into the heap: [FileBuffer] is a windowed
 * (64KB), lazily-filled view over the file (`SeekFileBufferCommon`), and
 * `LongSeries.get(IntRange)` downconverts it to an immutable `Series<Byte>`
 * whose accessor still routes through that window — a multi-GB PDF costs
 * one window's worth of heap, not its own size. PStream slices stay views
 * into this same chain until a filter forces materialization (FlateDecode
 * output, never the compressed input or an unhandled image codec).
 */
object JvmPdfDisassembler {

    /** Raw zlib inflate — the disassembler's only injected platform seam. */
    fun inflate(data: ByteArray): ByteArray? = runCatching {
        val inflater = Inflater()
        inflater.setInput(data)
        val out = java.io.ByteArrayOutputStream(maxOf(data.size * 3, 4096))
        val buf = ByteArray(65536)
        while (!inflater.finished()) {
            val n = inflater.inflate(buf)
            if (n == 0) {
                if (inflater.needsInput() || inflater.needsDictionary()) break
            }
            out.write(buf, 0, n)
        }
        inflater.end()
        out.toByteArray()
    }.getOrNull()

    private val disassembler = PdfDisassembler(::inflate)

    /** In-memory bytes (small files, or bytes already in hand from a CAS/attachment read). */
    fun parse(bytes: ByteArray): PdfDocument = disassembler.parse(bytes)

    /**
     * File path, windowed — the multi-GB-safe path. Caller owns nothing extra:
     * the FileBuffer opens, is consumed synchronously, and closes before return.
     *
     * KNOWN PRE-EXISTING BUG (found 2026-08-25, not fixed here — out of scope):
     * `JvmUserspaceChannelBackend.submitBatch`'s "auto-register" fallback
     * (`userspace/UserspaceIO.jvm.kt`) opens `Paths.get("")` with an EMPTY
     * option set instead of the real path/mode when a fd reaches it without
     * having been explicitly registered — which is exactly what happens for
     * any [FileBuffer] opened via the ordinary `Files.open()` path (this one
     * included). Every read then fails and this function throws
     * `IndexOutOfBoundsException("EOF at position 0")` regardless of file
     * size. The fix belongs in that registration seam (thread the real
     * `FileImpl`'s path/jvmChannel through instead of a placeholder open);
     * this function needs no changes once it lands.
     */
    fun parseFile(path: String): PdfDocument {
        val fb = FileBuffer(path)
        fb.open()
        try {
            val size = fb.size()
            require(size <= Int.MAX_VALUE.toLong()) { "PDF exceeds 2GB Int-indexed limit: $path ($size bytes)" }
            // Built directly against fb.get(Long), NOT the LongSeries.get(IntRange) helper —
            // that extension's (last-first) length math is off-by-one for `until` ranges.
            val n = size.toInt()
            val view: Series<Byte> = n j { x: Int -> fb.get(x.toLong()) }
            return disassembler.parse(view)
        } finally {
            fb.close()
        }
    }

    /** Convenience: disassemble + extract text in one call, windowed all the way through. */
    fun extractText(path: String): PdfText.Extraction = PdfText.extract(parseFile(path))
}
