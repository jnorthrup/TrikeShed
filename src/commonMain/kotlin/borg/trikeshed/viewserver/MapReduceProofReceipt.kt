package borg.trikeshed.viewserver

import borg.trikeshed.job.ContentId

/** Canonical bytes that identify the map definition used for a view execution. */
class ViewDefinitionIdentity(canonicalBytes: ByteArray) {
    val canonicalBytes: ByteArray = canonicalBytes.copyOf()
}

/** Stable reducer name and version; both contribute to a proof receipt identity. */
data class ReducerIdentity(
    val name: String,
    val version: String,
)

/**
 * Content-addressed evidence for one deterministic map/reduce execution.
 *
 * The canonical format is a versioned sequence of length-delimited fields. Source CIDs are
 * encoded in their supplied order, so execution provenance retains input ordering.
 */
class MapReduceProofReceipt private constructor(
    val viewDefinition: ViewDefinitionIdentity,
    sourceDocumentCids: List<ContentId>,
    val reducer: ReducerIdentity,
    outputBytes: ByteArray,
    canonicalBytes: ByteArray,
) {
    val sourceDocumentCids: List<ContentId> = sourceDocumentCids.toList()
    val outputBytes: ByteArray = outputBytes.copyOf()
    val canonicalBytes: ByteArray = canonicalBytes.copyOf()
    val contentId: ContentId = ContentId.of(this.canonicalBytes)

    companion object {
        fun mint(
            viewDefinition: ViewDefinitionIdentity,
            sourceDocumentCids: List<ContentId>,
            reducer: ReducerIdentity,
            outputBytes: ByteArray,
        ): MapReduceProofReceipt {
            val canonicalBytes = CanonicalProofReceiptBytes.encode(
                viewDefinition = viewDefinition.canonicalBytes,
                sourceDocumentCids = sourceDocumentCids,
                reducer = reducer,
                outputBytes = outputBytes,
            )
            return MapReduceProofReceipt(
                viewDefinition = viewDefinition,
                sourceDocumentCids = sourceDocumentCids,
                reducer = reducer,
                outputBytes = outputBytes,
                canonicalBytes = canonicalBytes,
            )
        }
    }
}

private object CanonicalProofReceiptBytes {
    private val formatMarker = "view-proof-receipt".encodeToByteArray()
    private val formatVersion = "1".encodeToByteArray()

    fun encode(
        viewDefinition: ByteArray,
        sourceDocumentCids: List<ContentId>,
        reducer: ReducerIdentity,
        outputBytes: ByteArray,
    ): ByteArray = ByteCollector().apply {
        field(formatMarker)
        field(formatVersion)
        field(viewDefinition)
        count(sourceDocumentCids.size)
        sourceDocumentCids.forEach { field(it.value.encodeToByteArray()) }
        field(reducer.name.encodeToByteArray())
        field(reducer.version.encodeToByteArray())
        field(outputBytes)
    }.toByteArray()
}

private class ByteCollector {
    private var bytes = ByteArray(64)
    private var size = 0

    fun count(value: Int) = writeInt(value)

    fun field(value: ByteArray) {
        writeInt(value.size)
        write(value)
    }

    fun toByteArray(): ByteArray = bytes.copyOf(size)

    private fun writeInt(value: Int) {
        require(value >= 0) { "canonical proof fields cannot have negative lengths" }
        write(byteArrayOf(
            (value ushr 24).toByte(),
            (value ushr 16).toByte(),
            (value ushr 8).toByte(),
            value.toByte(),
        ))
    }

    private fun write(value: ByteArray) {
        ensureCapacity(value.size)
        value.copyInto(bytes, destinationOffset = size)
        size += value.size
    }

    private fun ensureCapacity(additional: Int) {
        val required = size + additional
        if (required > bytes.size) {
            bytes = bytes.copyOf(maxOf(required, bytes.size * 2))
        }
    }
}
