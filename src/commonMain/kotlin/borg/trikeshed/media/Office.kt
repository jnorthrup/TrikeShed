package borg.trikeshed.media

import borg.trikeshed.lib.*
import borg.trikeshed.lib.cascade.Span

/** A zip central-directory entry: name × (method × compressed-data span). Method 0 = stored, 8 = raw deflate. */
typealias ZipEntry = Join<String, Join<Int, Span>>

private fun ByteArray.u16(i: Int) = (this[i].toInt() and 0xFF) or ((this[i + 1].toInt() and 0xFF) shl 8)
private fun ByteArray.u32(i: Int) = u16(i) or (u16(i + 2) shl 16)

/** zip64 shows up as 0xFFFF / 0xFFFFFFFF sentinels (count/size/offset) or an extra-field header 0x0001. Not read here. */
private fun zip64(): Nothing = throw UnsupportedOperationException("zip64")
private fun ByteArray.extraHasZip64(from: Int, len: Int): Boolean {
    var q = from; val end = minOf(from + len, size - 3)
    while (q + 4 <= end) { if (u16(q) == 0x0001) return true; q += 4 + u16(q + 2) }
    return false
}

/**
 * The zip's own index: End-Of-Central-Directory → central directory → each local header's data offset. No inflate.
 * A missing EOCD, or a central-directory entry / local header / data span running past the buffer, yields what was
 * indexed so far (empty for a headless blob) — never an index crash. zip64 → [UnsupportedOperationException]("zip64").
 */
fun ByteArray.zipEntries(): Series<ZipEntry> {
    var eocd = size - 22
    while (eocd >= 0 && u32(eocd) != 0x06054b50) eocd--
    if (eocd < 0) return emptySeriesOf()
    val count = u16(eocd + 10)
    if (count == 0xFFFF || u32(eocd + 12) == -1 || u32(eocd + 16) == -1) zip64()
    val out = ArrayList<ZipEntry>(); var p = u32(eocd + 16)
    repeat(count) {
        if (p < 0 || p + 46 > size || u32(p) != 0x02014b50) return out.toSeries()
        val nameLen = u16(p + 28); val extraLen = u16(p + 30); val next = p + 46 + nameLen + extraLen + u16(p + 32)
        if (next > size) return out.toSeries()
        val csize = u32(p + 20); val local = u32(p + 42)
        if (csize == -1 || u32(p + 24) == -1 || local == -1 || extraHasZip64(p + 46 + nameLen, extraLen)) zip64()
        if (local < 0 || local + 30 > size || u32(local) != 0x04034b50) return out.toSeries()
        val data = local + 30 + u16(local + 26) + u16(local + 28)
        if (data < 0 || data + csize > size) return out.toSeries()
        out += decodeToString(p + 46, p + 46 + nameLen) j (u16(p + 10) j (data j (data + csize)))
        p = next
    }
    return out.toSeries()
}

/** One entry's bytes. [inflate] is the platform's raw deflate: JVM `Inflater(true)`, browser `DecompressionStream("deflate-raw")`. */
suspend fun ByteArray.zipEntry(name: String, inflate: suspend (ByteArray) -> ByteArray): ByteArray? {
    val (method, span) = zipEntries().view.firstOrNull { it.a == name }?.b ?: return null
    val raw = copyOfRange(span.a, span.b)
    return if (method == 8) inflate(raw) else raw
}

/** Text of Office Open XML: `<w:t>` (docx), `<a:t>` (pptx), `<t>` (xlsx shared strings); paragraphs, slides' paragraphs and rows end lines. */
fun ooxmlText(xml: String): String = Regex("<(?:w:|a:)?t(?:\\s[^>]*)?>([^<]*)</(?:w:|a:)?t>|</(?:w:p|a:p|row)>")
    .findAll(xml).joinToString("") { m -> if (m.value.startsWith("</")) "\n" else m.groupValues[1] }
    .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&").trim()

private val officeParts = Regex("^(word/document\\.xml|ppt/slides/slide\\d+\\.xml|xl/sharedStrings\\.xml)$")

/** docx / pptx / xlsx → text, parts in document order (slide2 before slide10). */
suspend fun ByteArray.officeText(inflate: suspend (ByteArray) -> ByteArray): String {
    val parts = zipEntries().view.map { it.a }.filter(officeParts::matches).sortedWith(compareBy({ it.length }, { it }))
    val texts = ArrayList<String>(parts.size)
    for (p in parts) texts += ooxmlText(zipEntry(p, inflate)!!.decodeToString())
    return texts.joinToString("\n\n").trim()
}
