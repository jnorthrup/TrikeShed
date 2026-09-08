package borg.trikeshed.lcnc

import borg.trikeshed.couch.isam.DurableAppendLog
import borg.trikeshed.cursor.Cursor
import borg.trikeshed.forge.sheet.SheetRef
import borg.trikeshed.forge.sheet.sheetSeed
import borg.trikeshed.graal.subvm.DocumentFeed
import borg.trikeshed.job.CasStore
import borg.trikeshed.job.ContentId
import borg.trikeshed.lib.get
import borg.trikeshed.lib.j
import borg.trikeshed.lib.size
import borg.trikeshed.lib.α
import borg.trikeshed.modelmux.ModelResponse
import borg.trikeshed.modelmux.Prompt
import borg.trikeshed.narsese.BeliefBagElement
import borg.trikeshed.narsese.DocumentCurationIndexK
import borg.trikeshed.narsese.DocumentCuratorCodec
import borg.trikeshed.narsese.curationIndex
import borg.trikeshed.narsese.facet
import borg.trikeshed.pointcut.PointcutBlackboardAdapter
import borg.trikeshed.userspace.nio.DocumentExtent
import borg.trikeshed.userspace.nio.DocumentContent
import borg.trikeshed.userspace.nio.Volume
import kotlinx.coroutines.CoroutineScope

/** LCNC admission into the existing managed feed. The supplying host owns [DocumentFeed.drain]. */
object DocumentCurationLegos {
    const val CURATE = "document.curate"

    /**
     * Compose and register with explicit storage ownership. No filesystem or volatile volume
     * is selected here. The returned feed must drain before the caller closes its dependencies.
     */
    suspend fun create(
        scope: CoroutineScope,
        volume: Volume,
        cas: CasStore,
        log: DurableAppendLog,
        bag: BeliefBagElement,
        points: PointcutBlackboardAdapter,
        model: suspend (Prompt) -> ModelResponse,
        modelId: String,
        runners: MutableMap<String, LcncNodeRunner>,
        stagingLba: Long? = null,
    ): DocumentFeed {
        require(CURATE !in runners) { "$CURATE is already registered" }
        val feed = DocumentFeed.create(scope, volume, cas, log, bag, points, model, modelId, stagingLba = stagingLba)
        try {
            runners[CURATE] = curate(feed)
            return feed
        } catch (failure: Throwable) {
            feed.drain()
            throw failure
        }
    }

    /** [boundLcnc] resolves the caller's [DocumentFeed.Key] before borrowing this host default. */
    fun curate(feed: DocumentFeed): LcncNodeRunner = boundLcnc(feed) { owner, _, inputs ->
        require((inputs["extent"] != null) != (inputs["source"] != null)) {
            "$CURATE requires exactly one of extent or source"
        }
        val receipt = inputs["source"]?.let { value ->
            val fields = value as? Map<*, *>
            if (fields?.get("originalCid") != null) {
                require(fields["text"] == null && fields["base64"] == null) {
                    "$CURATE.source requires exactly one of text, base64, or originalCid"
                }
                owner.submit(ContentId(requireNotNull(fields["originalCid"] as? String) {
                    "$CURATE.source.originalCid must be a string"
                }), sourceName(fields), sourceMediaType(fields))
            } else {
                val source = content(value)
                owner.submit(source.bytes, source.name, source.mediaType)
            }
        } ?: owner.submit(extent(inputs["extent"]))
        val index = receipt.record.curationIndex()
        val rootId = "document/${receipt.cid.value}"
        val sentences = index.facet(DocumentCurationIndexK.SentenceCursor)
        // Nested Cursor facets become ordinary grid-in-cell references at the existing UI boundary.
        val references: Cursor = sentences.size j { ordinal -> sentences[ordinal] α { cell ->
            when (cell.b().name.toString()) {
                "tokens" -> SheetRef("$rootId/$ordinal/tokens") j cell.b
                "dependencies" -> SheetRef("$rootId/$ordinal/dependencies") j cell.b
                else -> cell
            }
        } }
        val root = sheetSeed(rootId, receipt.record.source.name, references).toMap()
        val sheets = buildList {
            add(root)
            for (ordinal in 0 until sentences.size) {
                add(sheetSeed("$rootId/$ordinal/tokens", "tokens",
                    index.facet(DocumentCurationIndexK.TokenCursor)(ordinal), rootId).toMap())
                add(sheetSeed("$rootId/$ordinal/dependencies", "dependencies",
                    index.facet(DocumentCurationIndexK.DependencyCursor)(ordinal), rootId).toMap())
            }
        }
        mapOf(
            "receiptCid" to receipt.cid.value,
            "record" to DocumentCuratorCodec.record(receipt.record),
            "nlpStatus" to index.facet(DocumentCurationIndexK.NlpStatus).name,
            "sheet" to root,
            "sheets" to sheets,
        )
    }

    internal fun content(value: Any?): DocumentContent {
        if (value is DocumentContent) return value
        val fields = requireNotNull(value as? Map<*, *>) { "$CURATE.source must be an object" }
        val name = sourceName(fields)
        require((fields["text"] != null) != (fields["base64"] != null)) {
            "$CURATE.source requires exactly one of text or base64"
        }
        val bytes = fields["text"]?.let {
            requireNotNull(it as? String) { "$CURATE.source.text must be a string" }.encodeToByteArray()
        } ?: java.util.Base64.getDecoder().decode(requireNotNull(fields["base64"] as? String) {
            "$CURATE.source.base64 must be a string"
        })
        return DocumentContent(bytes, name, sourceMediaType(fields))
    }

    private fun sourceName(fields: Map<*, *>): String {
        val name = requireNotNull(fields["name"] as? String) { "$CURATE.source.name must be a string" }
        require(name.isNotBlank()) { "$CURATE.source.name must not be blank" }
        return name
    }

    private fun sourceMediaType(fields: Map<*, *>): String? = fields["mediaType"]?.let {
            requireNotNull(it as? String) { "$CURATE.source.mediaType must be a string" }
    }

    internal fun extent(value: Any?): DocumentExtent {
        if (value is DocumentExtent) return value
        val fields = requireNotNull(value as? Map<*, *>) { "$CURATE.extent must be an extent object" }
        fun integer(name: String): Long {
            val number = fields[name]
            // JsonSupport reifies numeric literals as Double. Accept integral JSON values
            // without truncating fractions or accepting coordinates that lost precision.
            if (number is Double || number is Float) {
                val value = (number as Number).toDouble()
                require(value.isFinite() && value % 1.0 == 0.0 &&
                    value in -9_007_199_254_740_991.0..9_007_199_254_740_991.0) {
                    "$CURATE.extent.$name must be an exactly representable integer"
                }
                return value.toLong()
            }
            return requireNotNull(number?.toString()?.toLongOrNull()) {
                "$CURATE.extent.$name must be an integer"
            }
        }
        val length = integer("byteLength")
        require(length in 0..Int.MAX_VALUE.toLong()) { "$CURATE.extent.byteLength is out of range" }
        val name = requireNotNull(fields["name"] as? String) { "$CURATE.extent.name must be a string" }
        require(name.isNotBlank()) { "$CURATE.extent.name must not be blank" }
        return DocumentExtent(integer("lba"), length.toInt(), name,
            fields["mediaType"]?.let { requireNotNull(it as? String) { "$CURATE.extent.mediaType must be a string" } },
            fields["expectedCid"]?.let { ContentId(requireNotNull(it as? String) { "$CURATE.extent.expectedCid must be a string" }) })
    }
}
