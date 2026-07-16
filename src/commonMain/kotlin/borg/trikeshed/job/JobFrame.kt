package borg.trikeshed.job

import borg.trikeshed.parse.confix.ConfixDoc
import borg.trikeshed.parse.confix.value

/**
 * JobFrame wraps a ConfixDoc and extracts operation, jobId, idempotencyKey.
 * Uses Confix navigation functions to read fields from the parsed document.
 */
data class JobFrame(val doc: ConfixDoc) {

    val operation: String
        get() = doc.value("operation")?.toString() ?: ""

    val jobId: JobId
        get() = JobId.of(doc.value("jobId")?.toString() ?: "")

    val idempotencyKey: String
        get() = doc.value("idempotencyKey")?.toString() ?: ""

    val dependencies: List<JobId>
        get() {
            val raw = doc.value("dependencies")?.toString() ?: return emptyList()
            return if (raw.startsWith("[") && raw.endsWith("]")) {
                raw.removeSurrounding("[", "]").split(",").mapNotNull {
                    val s = it.trim().trim('"')
                    if (s.isNotEmpty()) JobId.of(s) else null
                }
            } else emptyList()
        }

    val expectedRevision: Revision?
        get() = doc.value("expectedRevision").toLongOrNullCoerce()?.let { Revision.of(it) }

    val causalKey: String
        get() = doc.value("causalKey")?.toString() ?: ""

    val sequence: Long
        get() = doc.value("sequence").toLongOrNullCoerce() ?: 0L

    val attemptId: String
        get() = doc.value("attemptId")?.toString() ?: ""
}