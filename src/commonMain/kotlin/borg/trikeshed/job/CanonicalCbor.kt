package borg.trikeshed.job

import borg.trikeshed.lib.get
import borg.trikeshed.lib.size
import borg.trikeshed.parse.confix.ConfixDoc

/**
 * Canonical encoding for ContentId computation.
 *
 * Produces deterministic sorted-key JSON bytes — same logical content
 * always produces the same bytes regardless of field order.
 * Confix is the serializer; this is the canonical form for CID hashing.
 */
object CanonicalCbor {

    /** Canonical encode a ConfixDoc (its raw source bytes). */
    fun encode(doc: ConfixDoc): ByteArray {
        val src = doc.b
        val n = src.size
        return ByteArray(n) { i -> src[i] }
    }

    /** Canonical encode a JobCommand to sorted-key JSON. */
    fun encode(cmd: JobCommand): ByteArray =
        commandToJson(cmd).encodeToByteArray()

    /** Canonical encode any string. */
    fun encode(value: String): ByteArray = value.encodeToByteArray()

    private fun commandToJson(cmd: JobCommand): String {
        val fields = mutableMapOf<String, String>()
        fields["operation"] = "\"${cmd.operationName}\""
        fields["jobId"] = "\"${cmd.jobId.value}\""
        fields["idempotencyKey"] = "\"${cmd.idempotencyKey}\""

        when (cmd) {
            is JobCommand.Submit -> {
                if (cmd.dependencies.isNotEmpty()) {
                    fields["dependencies"] = cmd.dependencies.joinToString(prefix = "[", postfix = "]") { "\"${it.value}\"" }
                }
                cmd.expectedRevision?.let { fields["expectedRevision"] = it.toString() }
            }
            is JobCommand.Start -> fields["expectedRevision"] = cmd.expectedRevision.toString()
            is JobCommand.Complete -> fields["expectedRevision"] = cmd.expectedRevision.toString()
            is JobCommand.Fail -> {
                fields["expectedRevision"] = cmd.expectedRevision.toString()
                fields["reason"] = "\"${cmd.reason}\""
            }
            is JobCommand.Retry -> fields["expectedRevision"] = cmd.expectedRevision.toString()
            is JobCommand.Progress -> {
                fields["expectedRevision"] = cmd.expectedRevision.toString()
                fields["progress"] = cmd.progress.toString()
            }
            is JobCommand.Block -> {
                fields["expectedRevision"] = cmd.expectedRevision.toString()
                fields["reason"] = "\"${cmd.reason}\""
            }
            is JobCommand.Cancel -> fields["expectedRevision"] = cmd.expectedRevision.toString()
            is JobCommand.Move -> {
                fields["expectedRevision"] = cmd.expectedRevision.toString()
                fields["toColumn"] = "\"${cmd.toColumn.value}\""
            }
            is JobCommand.Acknowledge -> fields["expectedRevision"] = cmd.expectedRevision.toString()
            is JobCommand.Retract -> fields["expectedRevision"] = cmd.expectedRevision.toString()
        }

        // Sorted keys for canonical output
        return fields.toSortedMap().entries.joinToString(
            separator = ",",
            prefix = "{",
            postfix = "}",
        ) { (k, v) -> "\"$k\":$v" }
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