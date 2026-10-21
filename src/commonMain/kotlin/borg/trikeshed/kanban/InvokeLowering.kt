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
        val suppliedRevision = raw["expectedRevision"] ?: raw["revision"]
        val revision = longOf(suppliedRevision)
        if (suppliedRevision != null && revision == null)
            return Outcome.Rejected(key, type, "invalid expectedRevision")

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

            "update" -> {
                val rev = needRevision() ?: return Outcome.Rejected(key, type, "update without expectedRevision")
                // Metadata is not a back door into Move, ownership, execution receipts or dependencies.
                val allowed = setOf("type", "kind", "jobId", "id", "idempotencyKey", "expectedRevision",
                    "revision", "title", "priority", "atMs", "actor")
                for (field in raw.keys) if (field !in allowed)
                    return Outcome.Rejected(key, type, "update cannot change '$field'")
                val title = raw["title"] as? String
                if (raw.containsKey("title") && title.isNullOrBlank())
                    return Outcome.Rejected(key, type, "invalid update title")
                val priority = longOf(raw["priority"])
                if (raw.containsKey("priority") && (priority == null || priority !in 0L..2L))
                    return Outcome.Rejected(key, type, "invalid update priority")
                if (title == null && priority == null)
                    return Outcome.Rejected(key, type, "update without title or priority")
                Outcome.Lowered(JobCommand.Update(jobId, key, rev, title, priority?.toInt()))
            }

            "move" -> {
                val colStr = (raw["toColumn"] ?: raw["toColumnId"] ?: raw["column"])?.toString()
                    ?: return Outcome.Rejected(key, type, "move without toColumn")
                val col = BoardCol.legacyCol(colStr)
                    ?: return Outcome.Rejected(key, type, "unknown column '$colStr' (canonical: ${BoardCol.rendered.joinToString { it.wire }})")
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
            "receipt" -> {
                val allowed = setOf("type", "jobId", "idempotencyKey", "expectedRevision", "receipt", "atMs")
                for (field in raw.keys) if (field !in allowed)
                    return Outcome.Rejected(key, type, "receipt cannot change '$field'")
                if (raw["receipt"] !is Map<*, *>) return Outcome.Rejected(key, type, "receipt without payload")
                withRev(key, type, revision) { JobCommand.Acknowledge(jobId, key, it, receipt = true) }
            }
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

    /** Batch extraction shared with the HTTP handler. reify hands arrays back as Array — accept both. */
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
        // Kotlin/JS represents Int and Double as Number: validate before conversion.
        // Long.MAX_VALUE rounds to 2^63 as a Double: keep the upper bound strict,
        // otherwise conversion saturates and changes the supplied precondition.
        is Number -> v.toDouble().takeIf {
            it.isFinite() && it % 1.0 == 0.0 &&
                it >= Long.MIN_VALUE.toDouble() && it < Long.MAX_VALUE.toDouble()
        }?.toLong()
        is String -> v.toLongOrNull()
        else -> null
    }

    private fun doubleOf(v: Any?): Double? = when (v) {
        is Number -> v.toDouble()
        is String -> v.toDoubleOrNull()
        else -> null
    }
}
