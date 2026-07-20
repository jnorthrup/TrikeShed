package borg.trikeshed.job

import borg.trikeshed.cursor.RowVec
import borg.trikeshed.cursor.getValue
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.j
import borg.trikeshed.lib.Join
import borg.trikeshed.parse.confix.ConfixCell
import borg.trikeshed.parse.confix.ConfixDoc
import borg.trikeshed.parse.confix.cellKids
import borg.trikeshed.parse.confix.docAt
import borg.trikeshed.parse.confix.reify
import borg.trikeshed.parse.confix.value
import borg.trikeshed.job.schema.SchemaCompiler
import borg.trikeshed.job.schema.loadConfixSchemaBytes

/**
 * ConfixFacetPlan — compiled from job-nexus.schema.json.
 * Validates, projects, and rejects unknown operations.
 */
data class ConfixFacetPlan(
    val commandOperations: Set<String>,
    val eventOperations: Set<String>,
    val requiredFields: Set<String> = emptySet(),
    val schemaText: String,
) {
    companion object {
        fun fromSchema(schemaPath: String): ConfixFacetPlan {
            val schemaBytes = loadConfixSchemaBytes(schemaPath)
            return SchemaCompiler.compilePlan(schemaBytes)
        }
    }

    data class ValidationResult(val valid: Boolean, val errors: List<String> = emptyList())

    fun validateAgainst(schema: Series<borg.trikeshed.isam.RecordMeta>): ValidationResult {
        // Stub for validation against ISAM schema
        return ValidationResult(valid = true)
    }


    fun validate(doc: ConfixDoc): ValidationResult {
        val errors = mutableListOf<String>()
        val operation = doc.value("operation")?.toString()
        val frameKind = doc.value("frameKind")?.toString()

        if (operation == null || (frameKind != "event" && operation !in commandOperations)) {
            errors.add(if (operation != null) "unknown operation: $operation" else "missing operation")
        }

        val jobId = doc.value("jobId")?.toString()
        if (jobId.isNullOrBlank()) errors.add("jobId is required")

        val idemKey = doc.value("idempotencyKey")?.toString()
        if (idemKey.isNullOrBlank()) errors.add("idempotencyKey is required")

        // Strictly check for all required fields dynamically to conform to the new schema compiler logic
        // This validates "missing required fields" requirement.
        for (field in requiredFields) {
            if (field != "operation" && field != "jobId" && field != "idempotencyKey") {
                if (doc.value(field) == null) {
                    errors.add("$field is required")
                }
            }
        }

        return ValidationResult(errors.isEmpty(), errors)
    }


    fun projectToSnapshot(doc: ConfixDoc): JobSnapshot {
        val operation = doc.value("operation")?.toString() ?: "unknown"
        val lifecycle = when (operation) {
            "submit"      -> "submitted"
            "start"       -> "active"
            "progress"    -> "active"
            "block"       -> "blocked"
            "complete"    -> "closed"
            "fail"        -> "failed"
            "cancel"      -> "cancelled"
            "retry"       -> "submitted"
            "move"        -> "moved"
            "acknowledge" -> "acknowledged"
            "retract"     -> "retracted"
            else          -> operation
        }
        val revision = doc.value("expectedRevision")?.let {
            when (it) {
                is Long  -> it
                is Int   -> it.toLong()
                is Number -> it.toLong()
                else     -> 1L
            }
        } ?: 1L

        val depCells: Series<ConfixCell>? = doc.docAt("dependencies")?.cellKids
        val dependencies: List<JobId> = if (depCells != null) {
            val n: Int = depCells.a
            val builder = mutableListOf<JobId>()
            var i = 0
            while (i < n) {
                val cell: ConfixCell = depCells.b(i)
                val s: String? = cell.reify()?.toString()
                if (s != null) builder.add(JobId.of(s))
                i++
            }
            builder.reversed()
        } else {
            emptyList()
        }

        return JobSnapshot(
            jobId       = JobId.of(doc.value("jobId")?.toString() ?: ""),
            revision    = revision,
            lifecycle   = lifecycle,
            causalKey   = doc.value("causalKey")?.toString() ?: "",
            dependencies = dependencies,
        )
    }
}
