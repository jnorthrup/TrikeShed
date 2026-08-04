package borg.trikeshed.job

import borg.trikeshed.collections.associative.Cbor
import borg.trikeshed.collections.associative.Item
import borg.trikeshed.collections.associative.itemArrayOf
import borg.trikeshed.collections.associative.itemMapOf
import borg.trikeshed.collections.associative.toItem
import borg.trikeshed.lib.get
import borg.trikeshed.lib.size
import borg.trikeshed.parse.confix.ConfixDoc
import borg.trikeshed.parse.confix.ConfixCell
import borg.trikeshed.parse.confix.cellKids
import borg.trikeshed.parse.confix.rootCell
import borg.trikeshed.parse.confix.reify
import borg.trikeshed.parse.confix.value

/**
 * Canonical encoding for ContentId computation.
 *
 * Produces canonical CBOR: definite-length, sorted map keys, minimal encoding.
 * Same logical content always produces the same bytes regardless of input JSON
 * whitespace or key order.
 *
 * Delegates to the existing Cbor codec (RFC 8949) — sorts map keys before encoding
 * to guarantee canonical form.
 */
object CanonicalCbor {

    /** Canonical encode a ConfixDoc to canonical CBOR (sorted-key, definite-length). */
    fun encode(doc: ConfixDoc): ByteArray {
        val rootCell = doc.rootCell ?: return Cbor.encode(itemMapOf())
        val pairs = mutableMapOf<String, Any?>()
        val kids = rootCell.cellKids
        // Confix flat-kid order: (value, key) pairs.
        var i = 0
        while (i + 1 < kids.size) {
            val valCell = kids[i]
            val keyCell = kids[i + 1]
            val key = keyCell.reify()?.toString() ?: ""
            pairs[key] = valCell.reify()
            i += 2
        }
        return encodeSortedMap(pairs)
    }

    /** Canonical encode a JobCommand to canonical CBOR. */
    @Suppress("UNCHECKED_CAST")
    fun encode(cmd: JobCommand): ByteArray {
        val serializer = JobCommand.serializer() as kotlinx.serialization.KSerializer<JobCommand>
        return borg.trikeshed.parse.confix.ConfixFormat.encodeToByteArray(serializer, cmd)
    }

    /** Decode the canonical command bytes consumed by the reactor and WAL replay. */
    @Suppress("UNCHECKED_CAST")
    fun decodeJobCommand(bytes: ByteArray): JobCommand {
        val serializer = JobCommand.serializer() as kotlinx.serialization.KSerializer<JobCommand>
        return borg.trikeshed.parse.confix.ConfixFormat.decodeFromByteArray(serializer, bytes)
    }

    /** Canonical encode any string. */
    fun encode(value: String): ByteArray =
        Cbor.encode(Item.Str(value))

    /** Canonical encode a JobNexusSpec to its canonical bytes. */
    fun encode(spec: JobNexusSpec): ByteArray = spec.canonicalBytes

    /** Decode the canonical bytes of a JobSnapshot. */
    fun decodeJobSnapshot(bytes: ByteArray): JobSnapshot {
        return borg.trikeshed.parse.confix.ConfixFormat.decodeFromByteArray(JobSnapshot.serializer(), bytes)
    }

    /** Canonical encode a JobSnapshot for CID computation. */
    fun encode(snapshot: JobSnapshot): ByteArray {
        // To preserve sorting of dependencies logic if required, we can rely on Confix sorting map keys.
        // If sorting of dependencies themselves is required, it must be done before creating the JobSnapshot
        // or we sort it here and copy. But since they're value objects, they should be created sorted or
        // the serializer will just output what's given.
        // We ensure dependencies are sorted here to maintain previous semantics:
        val sortedDeps = snapshot.dependencies.sortedBy { it.value }
        val copy = if (sortedDeps != snapshot.dependencies) snapshot.copy(dependencies = sortedDeps) else snapshot
        return borg.trikeshed.parse.confix.ConfixFormat.encodeToByteArray(JobSnapshot.serializer(), copy)
    }

    private fun toCanonical(item: Item): Item = when (item) {
        is Item.Map -> {
            val k = item.keys()
            val v = item.values()
            val pairs = mutableListOf<Pair<String, Item>>()
            for (idx in 0 until item.size) {
                pairs.add(k[idx] to toCanonical(v[idx]))
            }
            pairs.sortBy { it.first }
            itemMapOf(*pairs.toTypedArray())
        }
        is Item.Arr -> {
            val arr = mutableListOf<Item>()
            for (idx in 0 until item.size) {
                arr.add(toCanonical(item[idx]))
            }
            itemArrayOf(arr)
        }
        is Item.Tag -> Item.Tag(item.tag, toCanonical(item.item))
        else -> item
    }

    private fun encodeSortedMap(fields: Map<String, Any?>): ByteArray {
        val pairs = fields.entries.map { it.key to toCanonical(it.value.toItem()) }.toTypedArray()
        val sortedPairs = pairs.sortedBy { it.first }.toTypedArray()
        return Cbor.encode(itemMapOf(*sortedPairs))
    }
}

/** Operation name for a JobCommand (wire-level string). */
val JobCommand.operationName: String
    get() = when (this) {
        is JobCommand.Submit -> "submit"
        is JobCommand.Start -> "start"
        is JobCommand.Complete -> "complete"
        is JobCommand.Fail -> "fail"
        is JobCommand.Retry -> "retry"
        is JobCommand.Progress -> "progress"
        is JobCommand.Block -> "block"
        is JobCommand.Cancel -> "cancel"
        is JobCommand.Move -> "move"
        is JobCommand.Acknowledge -> "acknowledge"
        is JobCommand.Retract -> "retract"
    }
