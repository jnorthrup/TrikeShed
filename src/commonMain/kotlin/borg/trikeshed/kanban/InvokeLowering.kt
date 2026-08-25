package borg.trikeshed.kanban

import borg.trikeshed.job.JobCommand
import borg.trikeshed.job.JobId
import borg.trikeshed.job.KanbanColumnId

/**
 * InvokeLowering — the pure `/api/invoke` → [JobCommand] lowering the ACK-only
 * endpoint never had. One browser command map in, one outcome out; an unknown
 * or malformed command is a REJECTION WITH A REASON, never a silent drop
 * (risk 5: mislabeled moves stay visible until the PWA ships real Move).
 */
object InvokeLowering {

    sealed class Outcome {
        abstract val idempotencyKey: String?

        data class Lowered(val command: JobCommand) : Outcome() {
            override val idempotencyKey: String get() = command.idempotencyKey
        }

        data class Rejected(
            override val idempotencyKey: String?,
            val type: String?,
            val reason: String,
        ) : Outcome()
    }

    /** Lower one browser command map. Pure; tolerant of JSON number widening (Double/Long). */
    fun lower(raw: Map<*, *>): Outcome {
        val type = (raw["type"] ?: raw["kind"])?.toString()?.lowercase()
        val key = raw["idempotencyKey"]?.toString()
        val jobIdStr = (raw["jobId"] ?: raw["id"])?.toString()

        if (type.isNullOrBlank()) return Outcome.Rejected(key, type, "missing command type")
        if (key.isNullOrBlank()) return Outcome.Rejected(key, type, "missing idempotencyKey")
        if (jobIdStr.isNullOrBlank()) return Outcome.Rejected(key, type, "missing jobId")

        val jobId = JobId(jobIdStr)
        val revision = longOf(raw["expectedRevision"] ?: raw["revision"])

        fun needRevision(): Long? {
            return revision
        }

        return when (type) {
            "submit", "create", "new" -> {
                // A "submit" smuggling a move (today's PWA labels card clicks Submit) is refused loudly.
                if (raw["toColumn"] != null || raw["toColumnId"] != null) {
                    Outcome.Rejected(key, type, "submit carrying toColumn — a move mislabeled as submit; send type=move")
                } else {
                    val deps = listishOf(raw["dependencies"])?.mapNotNull { it?.toString() }?.map(::JobId) ?: emptyList()
                    Outcome.Lowered(JobCommand.Submit(jobId, key, deps, revision))
                }
            }

            "move" -> {
                val colStr = (raw["toColumn"] ?: raw["toColumnId"] ?: raw["column"])?.toString()
                    ?: return Outcome.Rejected(key, type, "move without toColumn")
                val col = BoardCol.legacyCol(colStr)
                    ?: return Outcome.Rejected(key, type, "unknown column '$colStr' (canonical: ${BoardCol.entries.joinToString { it.wire }})")
                val rev = needRevision() ?: return Outcome.Rejected(key, type, "move without expectedRevision")
                Outcome.Lowered(JobCommand.Move(jobId, key, rev, KanbanColumnId(col.wire)))
            }

            "start" -> withRev(key, type, revision) { JobCommand.Start(jobId, key, it) }
            "complete", "done" -> withRev(key, type, revision) { JobCommand.Complete(jobId, key, it) }
            "fail" -> withRev(key, type, revision) { JobCommand.Fail(jobId, key, it, raw["reason"]?.toString() ?: "unspecified") }
            "retry" -> withRev(key, type, revision) { JobCommand.Retry(jobId, key, it) }
            "progress" -> {
                val p = doubleOf(raw["progress"]) ?: return Outcome.Rejected(key, type, "progress without progress value")
                withRev(key, type, revision) { JobCommand.Progress(jobId, key, it, p) }
            }
            "block" -> withRev(key, type, revision) { JobCommand.Block(jobId, key, it, raw["reason"]?.toString() ?: "unspecified") }
            "cancel" -> withRev(key, type, revision) { JobCommand.Cancel(jobId, key, it) }
            "acknowledge", "ack" -> withRev(key, type, revision) { JobCommand.Acknowledge(jobId, key, it) }
            "retract", "delete" -> withRev(key, type, revision) { JobCommand.Retract(jobId, key, it) }

            else -> Outcome.Rejected(key, type, "unknown command type '$type'")
        }
    }

    /** Lower a whole `/api/invoke` batch body (already JSON-parsed): `{commands:[…]}`, a bare list, or one map. */
    fun lowerBatch(parsed: Any?): List<Outcome> =
        commandsOf(parsed).map { c ->
            (c as? Map<*, *>)?.let(::lower)
                ?: Outcome.Rejected(null, null, "command is not an object")
        }

    /** Batch extraction shared with the HTTP handler. JsonSupport hands arrays back as Array — accept both. */
    fun commandsOf(parsed: Any?): List<*> = when (parsed) {
        is Map<*, *> -> listishOf(parsed["commands"]) ?: listOf(parsed)
        else -> listishOf(parsed) ?: emptyList<Any?>()
    }

    /** JSON "array" normalization: parser backends disagree (List vs Array); the lowering accepts both. */
    fun listishOf(v: Any?): List<*>? = when (v) {
        is List<*> -> v
        is Array<*> -> v.toList()
        else -> null
    }

    private inline fun withRev(key: String, type: String, revision: Long?, build: (Long) -> JobCommand): Outcome =
        revision?.let { Outcome.Lowered(build(it)) }
            ?: Outcome.Rejected(key, type, "$type without expectedRevision")

    private fun longOf(v: Any?): Long? = when (v) {
        is Long -> v
        is Int -> v.toLong()
        is Double -> if (v % 1.0 == 0.0) v.toLong() else null
        is Number -> v.toLong()
        is String -> v.toLongOrNull()
        else -> null
    }

    private fun doubleOf(v: Any?): Double? = when (v) {
        is Number -> v.toDouble()
        is String -> v.toDoubleOrNull()
        else -> null
    }
}
