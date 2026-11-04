package borg.trikeshed.lcnc

import borg.trikeshed.job.*
import borg.trikeshed.lib.*
import borg.trikeshed.narsese.DocumentSource

/** Explicit synthetic wire fixtures: no production codec overlay or claimed parser execution. */
internal fun englishTaxonomyFixtureSource(source: DocumentSource): Map<String, Any?> = _m[
    "originalCid" j source.originalCid.value, "extractedTextCid" j source.extractedTextCid.value,
    "text" j source.text, "name" j source.name, "mediaType" j source.mediaType,
    "correlation" j source.correlation, "metadata" j source.metadata, "category" j source.category,
    "provenance" j source.provenance, "project" j source.project?.toMap(), "extraction" j source.extraction?.toMap(),
    "anchors" j source.anchors.map { it.toMap() }, "unresolved" j source.unresolved]

/** Minimal legacy-compatible v1 document fixture used to test the manual KIF/export boundary. */
internal fun englishTaxonomyFixtureRecord(cas: CasStore, source: DocumentSource, model: String): ContentId =
    cas.put(CanonicalCbor.encodeMap(_m["version" j 1, "source" j englishTaxonomyFixtureSource(source),
        "modelId" j model, "model" j null, "nlp" j null, "instructions" j "", "proposals" j emptyList<Any>(),
        "reasons" j emptyList<Any>(), "reserved" j emptyList<Any>(), "submitted" j emptyList<Any>(),
        "duplicates" j emptyList<Any>(), "observerFailures" j emptyList<Any>()]))
