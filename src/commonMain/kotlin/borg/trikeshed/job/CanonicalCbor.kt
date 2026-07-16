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
    fun encode(cmd: JobCommand): ByteArray {
        val fields = mutableMapOf<String, Any?>()
        fields["operation"] = cmd.operationName
        fields["jobId"] = cmd.jobId.value
        fields["idempotencyKey"] = cmd.idempotencyKey

        when (cmd) {
            is JobCommand.Submit -> {
                if (cmd.dependencies.isNotEmpty()) {
                    fields["dependencies"] = cmd.dependencies
                        .map { it.value }
                        .sorted()
                }
                cmd.expectedRevision?.let { fields["expectedRevision"] = it }
            }
            is JobCommand.Start -> fields["expectedRevision"] = cmd.expectedRevision
            is JobCommand.Complete -> fields["expectedRevision"] = cmd.expectedRevision
            is JobCommand.Fail -> {
                fields["expectedRevision"] = cmd.expectedRevision
                fields["reason"] = cmd.reason
            }
            is JobCommand.Retry -> fields["expectedRevision"] = cmd.expectedRevision
            is JobCommand.Progress -> {
                fields["expectedRevision"] = cmd.expectedRevision
                fields["progress"] = cmd.progress
            }
            is JobCommand.Block -> {
                fields["expectedRevision"] = cmd.expectedRevision
                fields["reason"] = cmd.reason
            }
            is JobCommand.Cancel -> fields["expectedRevision"] = cmd.expectedRevision
            is JobCommand.Move -> {
                fields["expectedRevision"] = cmd.expectedRevision
                fields["toColumn"] = cmd.toColumn.value
            }
            is JobCommand.Acknowledge -> fields["expectedRevision"] = cmd.expectedRevision
            is JobCommand.Retract -> fields["expectedRevision"] = cmd.expectedRevision
        }

        return encodeSortedMap(fields)
    }

    /** Decode the canonical command bytes consumed by the reactor and WAL replay. */
    fun decodeJobCommand(bytes: ByteArray): JobCommand {
        val fields = Cbor.decode(bytes) as? Item.Map
            ?: error("canonical job command must be a CBOR map")
        fun string(name: String): String =
            (fields[name] as? Item.Str)?.value ?: error("missing string field: $name")
        fun long(name: String): Long =
            (fields[name] as? Item.Num)?.value ?: error("missing integer field: $name")

        val jobId = JobId.of(string("jobId"))
        val idempotencyKey = string("idempotencyKey")
        return when (val operation = string("operation")) {
            "submit" -> {
                val dependencies = (fields["dependencies"] as? Item.Arr)?.let { values ->
                    List(values.size) { index ->
                        JobId.of((values[index] as? Item.Str)?.value
                            ?: error("dependencies[$index] must be a string"))
                    }
                } ?: emptyList()
                JobCommand.Submit(
                    jobId = jobId,
                    idempotencyKey = idempotencyKey,
                    dependencies = dependencies,
                    expectedRevision = (fields["expectedRevision"] as? Item.Num)?.value,
                )
            }
            "start" -> JobCommand.Start(jobId, idempotencyKey, long("expectedRevision"))
            "complete" -> JobCommand.Complete(jobId, idempotencyKey, long("expectedRevision"))
            "fail" -> JobCommand.Fail(jobId, idempotencyKey, long("expectedRevision"), string("reason"))
            "retry" -> JobCommand.Retry(jobId, idempotencyKey, long("expectedRevision"))
            "progress" -> JobCommand.Progress(
                jobId,
                idempotencyKey,
                long("expectedRevision"),
                when (val value = fields["progress"]) {
                    is Item.Flt -> value.value
                    is Item.Num -> value.value.toDouble()
                    else -> error("missing numeric field: progress")
                },
            )
            "block" -> JobCommand.Block(jobId, idempotencyKey, long("expectedRevision"), string("reason"))
            "cancel" -> JobCommand.Cancel(jobId, idempotencyKey, long("expectedRevision"))
            "move" -> JobCommand.Move(
                jobId,
                idempotencyKey,
                long("expectedRevision"),
                KanbanColumnId.of(string("toColumn")),
            )
            "acknowledge" -> JobCommand.Acknowledge(jobId, idempotencyKey, long("expectedRevision"))
            "retract" -> JobCommand.Retract(jobId, idempotencyKey, long("expectedRevision"))
            else -> error("unknown job command operation: $operation")
        }
    }

    /** Canonical encode any string. */
    fun encode(value: String): ByteArray =
        Cbor.encode(Item.Str(value))

    /** Canonical encode a JobNexusSpec to its canonical bytes. */
    fun encode(spec: JobNexusSpec): ByteArray = spec.canonicalBytes

    /** Canonical encode a JobSnapshot for CID computation. */
    fun encode(snapshot: JobSnapshot): ByteArray {
        val fields = mutableMapOf<String, Any?>()
        fields["jobId"] = snapshot.jobId.value
        fields["revision"] = snapshot.revision
        fields["causalKey"] = snapshot.causalKey
        fields["lifecycle"] = snapshot.lifecycle
        if (snapshot.dependencies.isNotEmpty()) {
            // Sort dependencies by their underlying string value so insertion
            // order — and per-list permutation — cannot change the canonical CID.
            fields["dependencies"] = snapshot.dependencies
                .map { it.value }
                .sorted()
        }
        fields["attemptCount"] = snapshot.attemptCount
        snapshot.parentJobId?.let { fields["parentJobId"] = it.value }
        if (snapshot.attemptId.isNotEmpty()) fields["attemptId"] = snapshot.attemptId
        return encodeSortedMap(fields)
    }

    private fun encodeSortedMap(fields: Map<String, Any?>): ByteArray {
        val sorted = fields.entries.sortedBy { it.key }
        // Build Item.Map with sorted keys for canonical CBOR
        val pairs = sorted.map { it.key to it.value.toItem() }
        val map = itemMapOf(*pairs.toTypedArray())
        return Cbor.encode(map)
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
