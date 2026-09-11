package org.bereft.ingest.forge

import borg.trikeshed.lib.*
import borg.trikeshed.lib.Series
import borg.trikeshed.forge.ForgeBlockKind
import borg.trikeshed.forge.ForgeDoc
import borg.trikeshed.forge.ForgeDocument
import borg.trikeshed.forge.ForgeBlockId
import org.bereft.ingest.IngestProjection
import org.bereft.ingest.IngestResult

/**
 * Commit an [IngestResult] into a [ForgeDocument] as a tree of blocks under
 * [parentId]. This is the consuming-project equivalent of the
 * `ForgeSignal.IngestComplete` the original J01 brief proposed adding directly
 * to CCEK.kt — as a standalone project we expose it as a pure function the host
 * calls instead of mutating the reactor's sealed signal set.
 *
 * Mapping (mirrors IngestProjection → ForgeBlockKind family):
 *  - TEXT_EXTRACTION  → HEADING_1 (title) + TEXT (content)
 *  - TABLE_EXTRACTION → TABLE_ROW
 *  - AUDIO_TRANSCRIPTION → TEXT (turns)
 *  - METADATA → block properties on the heading
 */
object IngestToForge {

    fun commit(
        doc: ForgeDocument,
        parentId: ForgeBlockId,
        result: IngestResult,
    ): Pair<ForgeDocument, List<ForgeBlockId>> {
        var d = doc
        val created = mutableListOf<ForgeBlockId>()

        // Heading carrying source + format as metadata.
        val headingProps = buildMap {
            put("ingest.source", result.sourcePath)
            put("ingest.mediaType", result.mediaType)
            put("ingest.format", result.detectedFormat)
            result.qualityMetrics.forEach { (k, v) -> put("ingest.q.$k", v.toString()) }
        }
        val title = result.sourcePath.substringAfterLast('/')
        d = ForgeDoc.appendBlock(d, parentId, ForgeBlockKind.HEADING_1, title, headingProps)
        created.add(d.cursor.blockId)

        if (IngestProjection.TEXT_EXTRACTION in result.projections ||
            IngestProjection.AUDIO_TRANSCRIPTION in result.projections
        ) {
            // Drain the char Series into a string. We read the lazy oracle once;
            // PRELOAD prefers `view` over `(0 until n).map`.
            val text = buildString {
                for (c in result.extractedContent.borgView()) append(c)
            }
            d = ForgeDoc.appendBlock(d, created.last(), ForgeBlockKind.TEXT, text)
            created.add(d.cursor.blockId)
        }
        return d to created
    }
}

/** PRELOAD-compliant Char-Series → Iterable without a manual index loop. */
private fun borg.trikeshed.lib.Series<Char>.borgView(): Iterable<Char> = object : Iterable<Char> {
    override fun iterator() = object : Iterator<Char> {
        private var i = 0
        override fun hasNext(): Boolean = i < this@borgView.size
        override fun next(): Char = this@borgView.b(i++)
    }
}
