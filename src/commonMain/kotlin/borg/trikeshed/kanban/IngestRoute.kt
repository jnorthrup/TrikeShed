package borg.trikeshed.kanban

/**
 * Single dialect for "which ingest lane does this donor take?" — replaces the
 * per-caller extension lists that used to live in JvmTikaIngestAdapter,
 * JvmKanbanServer and HermesDonorTrace.
 *
 * [Text] is read verbatim (markdown/source); everything else goes through an
 * extractor (Tika / Office / OCR). [Unsupported] is "no extension we know and
 * no magic we recognise" — callers decide whether that is an extractor attempt
 * or a refusal.
 */
enum class IngestRoute { Office, Pdf, Image, Text, Unsupported }

private val officeExt = setOf("docx", "pptx", "xlsx")
private val imageExt = setOf("png", "jpg", "jpeg", "tif", "tiff", "bmp", "gif", "webp", "heic")
private val textExt = setOf("md", "markdown", "txt", "kt", "kts", "java", "py", "json", "xml", "html", "htm")

private fun ByteArray.startsWith(vararg magic: Int): Boolean =
    size >= magic.size && magic.indices.all { this[it] == magic[it].toByte() }

/** Magic-byte sniff of the first few bytes; null when nothing recognised. */
fun ingestRouteByMagic(head: ByteArray): IngestRoute? = when {
    head.startsWith(0x50, 0x4B, 0x03, 0x04) -> IngestRoute.Office              // PK\x03\x04 (OOXML zip)
    head.startsWith(0x25, 0x50, 0x44, 0x46, 0x2D) -> IngestRoute.Pdf           // %PDF-
    head.startsWith(0x89, 0x50, 0x4E, 0x47) -> IngestRoute.Image               // \x89PNG
    head.startsWith(0xFF, 0xD8) -> IngestRoute.Image                           // JPEG SOI
    head.startsWith(0x47, 0x49, 0x46, 0x38) -> IngestRoute.Image               // GIF8
    head.startsWith(0x52, 0x49, 0x46, 0x46) && head.size >= 12 &&
        head[8] == 'W'.code.toByte() && head[9] == 'E'.code.toByte() &&
        head[10] == 'B'.code.toByte() && head[11] == 'P'.code.toByte() -> IngestRoute.Image // RIFF....WEBP
    else -> null
}

/**
 * Extension first; magic bytes break the tie when [head] is non-empty and the
 * extension is unknown (or disagrees with what the bytes say).
 */
fun ingestRoute(name: String, head: ByteArray = ByteArray(0)): IngestRoute {
    val ext = name.substringAfterLast('/').substringAfterLast('\\')
        .let { if ('.' in it) it.substringAfterLast('.').lowercase() else "" }
    val byExt = when (ext) {
        in officeExt -> IngestRoute.Office
        "pdf" -> IngestRoute.Pdf
        in imageExt -> IngestRoute.Image
        in textExt -> IngestRoute.Text
        else -> IngestRoute.Unsupported
    }
    if (head.isEmpty()) return byExt
    val byMagic = ingestRouteByMagic(head) ?: return byExt
    return if (byExt == IngestRoute.Unsupported || byExt != byMagic) byMagic else byExt
}
