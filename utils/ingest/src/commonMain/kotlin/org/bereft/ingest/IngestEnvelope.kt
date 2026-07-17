package org.bereft.ingest

import borg.trikeshed.cursor.Cursor
import borg.trikeshed.job.ContentId

/**
 * A portable ingest envelope carrying source identity, media type/facet,
 * requested projections, raw payload CID, extracted payload CID(s),
 * canonical Confix metadata CID, and stable Cursor projections.
 */
data class IngestEnvelope(
    val sourcePath: String,
    val mediaType: String,
    val formatFacet: String,
    val projections: Set<IngestProjection>,
    val rawPayloadCid: ContentId,
    val extractedPayloadCids: List<ContentId>,
    val canonicalMetadataCid: ContentId,
    val metadataCursor: Cursor,
)
