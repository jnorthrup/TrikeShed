package borg.trikeshed.pdf

import borg.trikeshed.lib.Series
import borg.trikeshed.lib.get
import borg.trikeshed.lib.j
import borg.trikeshed.lib.size

/**
 * Scan-based PDF disassembly: find every `N G obj` body directly (the xref is
 * bookkeeping we never need to trust), decode stream filters, and expand
 * object streams (`/Type /ObjStm`) so PDF 1.5+ compressed bodies surface too.
 *
 * Inflate is INJECTED — `(ByteArray) -> ByteArray?` — so this file stays pure
 * common Kotlin with zero dependencies; the JVM passes `java.util.zip`.
 * FlateDecode + PNG predictors, ASCIIHexDecode and ASCII85Decode are handled
 * here; image codecs (DCT/JPX/CCITT) are DATA to a disassembler, not text —
 * they stay raw with a note.
 */
class PdfDisassembler(private val inflate: (ByteArray) -> ByteArray?) {

    fun parse(bytes: ByteArray): PdfDocument = parse(borg.trikeshed.lib.ByteSeries(bytes) as Series<Byte>)

    fun parse(src: Series<Byte>): PdfDocument {
        val notes = ArrayList<String>()
        val head = PdfLexer(src)
        val version = if (src.size > 8 && head.startsWith("%PDF-", 0)) head.str(5, minOf(8, src.size)).trim() else "?"

        val objects = LinkedHashMap<ObjId, PdfObject>()
        scanObjects(src) { id, obj -> objects[id] = obj }

        // Two passes so /Length indirections and filters resolve against the full table.
        val doc0 = PdfDocument(version, objects, null, notes)
        for (o in objects.values) if (o is PdfObject.PStream) decodeStream(o, doc0)

        // ObjStm expansion: compressed bodies become first-class objects.
        var expanded = 0
        for (o in objects.values.toList()) {
            if (o !is PdfObject.PStream) continue
            val type = (o.dict["Type"] as? PdfObject.PName)?.value
            if (type != "ObjStm") continue
            val data = o.decoded ?: continue
            val n = (doc0.resolve(o.dict["N"]) as? PdfObject.PNum)?.int ?: continue
            val first = (doc0.resolve(o.dict["First"]) as? PdfObject.PNum)?.int ?: continue
            val head = PdfLexer(data)
            val pairs = ArrayList<Pair<Int, Int>>()
            repeat(n) {
                val num = head.bareToken().toIntOrNull() ?: return@repeat
                val off = head.bareToken().toIntOrNull() ?: return@repeat
                pairs.add(num to off)
            }
            for ((num, off) in pairs) {
                if (first + off >= data.size) continue
                val lx = PdfLexer(data, first + off)
                val obj = lx.parseObject() ?: continue
                val id = ObjId(num, 0)
                if (id !in objects) { objects[id] = obj; expanded++ }
            }
        }
        if (expanded > 0) notes.add("ObjStm expanded: $expanded objects")

        val catalog = objects.values.firstNotNullOfOrNull { o ->
            val d = (o as? PdfObject.PDict) ?: (o as? PdfObject.PStream)?.dict
            if ((d?.get("Type") as? PdfObject.PName)?.value == "Catalog") d else null
        }
        if (catalog == null) notes.add("no /Type /Catalog found")

        return PdfDocument(version, objects, catalog, notes)
    }

    /** Find every `N G obj … endobj` by byte scan; streams captured raw between stream/endstream. */
    private fun scanObjects(src: Series<Byte>, sink: (ObjId, PdfObject) -> Unit) {
        var i = 0
        val lx = PdfLexer(src)
        val n = src.size
        while (i < n - 3) {
            // find "obj" preceded by two integers — walk to each 'o'
            if (src[i].toInt() != 'o'.code || !lx.startsWith("obj", i)) { i++; continue }
            val after = i + 3
            if (after < n && !lx.ws(src[after].toInt() and 0xFF) &&
                !lx.delim(src[after].toInt() and 0xFF)) { i++; continue }
            // walk back: ws, gen digits, ws, num digits
            var j = i - 1
            while (j >= 0 && lx.ws(src[j].toInt() and 0xFF)) j--
            val genEnd = j
            while (j >= 0 && src[j].toInt() in '0'.code..'9'.code) j--
            val genStart = j + 1
            if (genStart > genEnd) { i++; continue }
            while (j >= 0 && lx.ws(src[j].toInt() and 0xFF)) j--
            val numEnd = j
            while (j >= 0 && src[j].toInt() in '0'.code..'9'.code) j--
            val numStart = j + 1
            if (numStart > numEnd) { i++; continue }
            val num = lx.str(numStart, numEnd + 1).toIntOrNull()
            val gen = lx.str(genStart, genEnd + 1).toIntOrNull()
            if (num == null || gen == null) { i++; continue }

            val body = PdfLexer(src, after)
            val obj = body.parseObject()
            if (obj == null) { i++; continue }
            body.skipWs()
            var result: PdfObject = obj
            if (obj is PdfObject.PDict && body.startsWith("stream")) {
                body.pos += 6
                if (body.peek() == 13) body.pos++
                if (body.peek() == 10) body.pos++
                val dataStart = body.pos
                val end = findEndstream(src, dataStart)
                if (end > dataStart) {
                    // IMMUTABLE zero-copy slice of the (possibly mapped) source
                    val sliceLen = end - dataStart
                    result = PdfObject.PStream(obj, sliceLen j { k: Int -> src[dataStart + k] })
                    body.pos = end
                }
            }
            sink(ObjId(num, gen), result)
            i = maxOf(body.pos, after + 1)
        }
    }

    private fun findEndstream(src: Series<Byte>, from: Int): Int {
        var i = from
        val n = src.size
        val lx = PdfLexer(src)
        while (i < n - 9) {
            if (src[i].toInt() == 'e'.code && lx.startsWith("endstream", i)) {
                var e = i
                // trim the single EOL the spec puts before endstream
                if (e > from && src[e - 1].toInt() == 10) e--
                if (e > from && src[e - 1].toInt() == 13) e--
                return e
            }
            i++
        }
        return -1
    }

    // ── filters ───────────────────────────────────────────────────────

    fun decodeStream(s: PdfObject.PStream, doc: PdfDocument) {
        val filters: List<String> = when (val f = doc.resolve(s.dict["Filter"])) {
            is PdfObject.PName -> listOf(f.value)
            is PdfObject.PArr -> f.items.mapNotNull { (doc.resolve(it) as? PdfObject.PName)?.value }
            else -> emptyList()
        }
        val parmsList: List<PdfObject.PDict?> = when (val p = doc.resolve(s.dict["DecodeParms"])) {
            is PdfObject.PDict -> listOf(p)
            is PdfObject.PArr -> p.items.map { doc.resolve(it) as? PdfObject.PDict }
            else -> listOf(null)
        }
        // Refuse-before-materialize: image codecs stay as mapped views, untouched.
        if (filters.any { it !in DECODABLE }) {
            s.decodeNote = "filter ${filters.first { it !in DECODABLE }} left raw"
            return
        }
        // honor /Length when it resolves shorter than the scan capture, THEN materialize once
        val extent = ((doc.resolve(s.dict["Length"]) as? PdfObject.PNum)?.int
            ?.takeIf { it in 1..s.raw.size }) ?: s.raw.size
        var data = ByteArray(extent) { s.raw[it] }
        if (filters.isEmpty()) { s.decoded = data; return }
        for ((idx, f) in filters.withIndex()) {
            val parms = parmsList.getOrNull(idx)
            data = when (f) {
                "FlateDecode", "Fl" -> {
                    val out = inflate(data)
                    if (out == null) { s.decodeNote = "inflate failed"; return }
                    predictorFix(out, parms, doc)
                }
                "ASCIIHexDecode", "AHx" -> asciiHex(data)
                "ASCII85Decode", "A85" -> ascii85(data)
                else -> { s.decodeNote = "filter $f left raw"; s.decoded = null; return }
            }
        }
        s.decoded = data
    }

    /** PNG predictors (xref/object streams use Predictor 12 routinely). */
    private fun predictorFix(data: ByteArray, parms: PdfObject.PDict?, doc: PdfDocument): ByteArray {
        val pred = (doc.resolve(parms?.get("Predictor")) as? PdfObject.PNum)?.int ?: 1
        if (pred < 10) return data
        val columns = (doc.resolve(parms?.get("Columns")) as? PdfObject.PNum)?.int ?: 1
        val colors = (doc.resolve(parms?.get("Colors")) as? PdfObject.PNum)?.int ?: 1
        val bpc = (doc.resolve(parms?.get("BitsPerComponent")) as? PdfObject.PNum)?.int ?: 8
        val bpp = maxOf(1, colors * bpc / 8)
        val rowLen = (columns * colors * bpc + 7) / 8
        val out = ArrayList<Byte>(data.size)
        val prev = ByteArray(rowLen)
        var i = 0
        while (i + 1 + rowLen <= data.size + rowLen && i < data.size) {
            val tag = data[i].toInt() and 0xFF; i++
            val row = ByteArray(rowLen)
            val n = minOf(rowLen, data.size - i)
            for (k in 0 until n) row[k] = data[i + k]
            i += n
            for (k in 0 until rowLen) {
                val left = if (k >= bpp) row[k - bpp].toInt() and 0xFF else 0
                val up = prev[k].toInt() and 0xFF
                val ul = if (k >= bpp) prev[k - bpp].toInt() and 0xFF else 0
                val cur = row[k].toInt() and 0xFF
                row[k] = when (tag) {
                    0 -> cur
                    1 -> cur + left
                    2 -> cur + up
                    3 -> cur + (left + up) / 2
                    4 -> { // Paeth
                        val p = left + up - ul
                        val pa = kotlin.math.abs(p - left); val pb = kotlin.math.abs(p - up); val pc = kotlin.math.abs(p - ul)
                        cur + if (pa <= pb && pa <= pc) left else if (pb <= pc) up else ul
                    }
                    else -> cur
                }.toByte()
            }
            out.addAll(row.toList())
            row.copyInto(prev)
        }
        return out.toByteArray()
    }

    private fun asciiHex(data: ByteArray): ByteArray {
        val out = ArrayList<Byte>()
        var hi = -1
        for (b in data) {
            val c = b.toInt() and 0xFF
            if (c == '>'.code) break
            val v = when (c) {
                in '0'.code..'9'.code -> c - '0'.code
                in 'a'.code..'f'.code -> c - 'a'.code + 10
                in 'A'.code..'F'.code -> c - 'A'.code + 10
                else -> -1
            }
            if (v < 0) continue
            if (hi < 0) hi = v else { out.add(((hi shl 4) or v).toByte()); hi = -1 }
        }
        if (hi >= 0) out.add((hi shl 4).toByte())
        return out.toByteArray()
    }

    private fun ascii85(data: ByteArray): ByteArray {
        val out = ArrayList<Byte>()
        var tuple = 0L
        var count = 0
        var i = 0
        while (i < data.size) {
            val c = data[i].toInt() and 0xFF; i++
            when {
                c == '~'.code -> break
                c == 'z'.code && count == 0 -> { out.add(0); out.add(0); out.add(0); out.add(0) }
                c in '!'.code..'u'.code -> {
                    tuple = tuple * 85 + (c - '!'.code)
                    if (++count == 5) {
                        for (sh in 24 downTo 0 step 8) out.add(((tuple shr sh) and 0xFF).toByte())
                        tuple = 0; count = 0
                    }
                }
            }
        }
        if (count > 0) { // partial group
            repeat(5 - count) { tuple = tuple * 85 + 84 }
            val keep = count - 1
            for ((k, sh) in (24 downTo 0 step 8).withIndex()) if (k < keep) out.add(((tuple shr sh) and 0xFF).toByte())
        }
        return out.toByteArray()
    }

    companion object {
        /** Filters we decode in common Kotlin; everything else stays a mapped view. */
        val DECODABLE = setOf("FlateDecode", "Fl", "ASCIIHexDecode", "AHx", "ASCII85Decode", "A85")
    }
}
