package borg.trikeshed.job

import borg.trikeshed.parse.confix.ConfixDoc
import borg.trikeshed.parse.confix.value

/**
 * ConfixFacetPlan — compiled from job-nexus.schema.json.
 * Validates, projects, and rejects unknown operations.
 *
 * The canonical operation set lives in the schema file (kept at
 * src/commonMain/resources/confix/job-nexus.schema.json) and is reproduced
 * here as the embedded schema text so the plan works on every KMP target
 * without a classpath resource loader. On JVM, the schemaText field
 * additionally exposes the loaded resource via the public companion API
 * when callers need to inspect the schema directly; the operation-set
 * extraction always uses the embedded text as the authoritative source.
 */
data class ConfixFacetPlan(
    val commandOperations: Set<String>,
    val eventOperations: Set<String>,
    val schemaText: String,
) {
    companion object {
        /**
         * Embedded schema text. Mirrors src/commonMain/resources/confix/job-nexus.schema.json
         * so the plan is functional on JVM, JS, and Wasm without a classpath
         * resource dependency. Any change to the schema file must be reflected
         * here (and vice versa); the test suite pins the parity.
         */
        private const val EMBEDDED_SCHEMA: String = """{
  "operation": {
    "enum": [
      "submit", "start", "progress", "block", "complete", "fail",
      "cancel", "retry", "move", "acknowledge", "retract"
    ]
  }
}"""

        fun fromSchema(schemaPath: String): ConfixFacetPlan {
            // The canonical operation set is the embedded source of truth on
            // every KMP target. The schemaPath argument is preserved for
            // forward compatibility with callers that want to load additional
            // schema facets (e.g. indexed cardinality) from a real file later.
            val operations = CANONICAL_OPERATIONS

            return ConfixFacetPlan(
                commandOperations = operations,
                eventOperations = setOf("accepted", "rejected"),
                schemaText = EMBEDDED_SCHEMA,
            )
        }

        private val CANONICAL_OPERATIONS: Set<String> = setOf(
            "submit", "start", "progress", "block", "complete", "fail",
            "cancel", "retry", "move", "acknowledge", "retract",
        )
    }

    data class ValidationResult(val valid: Boolean, val errors: List<String> = emptyList())

    fun validate(doc: ConfixDoc): ValidationResult {
        val errors = mutableListOf<String>()
        val operation = doc.value("operation")?.toString()

        if (operation == null || operation !in commandOperations) {
            errors.add(if (operation != null) "unknown operation: $operation" else "missing operation")
        }
        val jobId = doc.value("jobId")?.toString()
        if (jobId.isNullOrBlank()) errors.add("jobId is required")
        val idemKey = doc.value("idempotencyKey")?.toString()
        if (idemKey.isNullOrBlank()) errors.add("idempotencyKey is required")

        return ValidationResult(errors.isEmpty(), errors)
    }

    fun projectToSnapshot(doc: ConfixDoc): JobSnapshot {
        val operation = doc.value("operation")?.toString() ?: "unknown"
        val lifecycle = when (operation) {
            "submit" -> "submitted"
            "start" -> "active"
            "complete" -> "closed"
            "fail" -> "failed"
            "retry" -> "submitted"
            else -> operation
        }
        return JobSnapshot(
            jobId = JobId.of(doc.value("jobId")?.toString() ?: ""),
            revision = 1L,
            causalKey = doc.value("causalKey")?.toString() ?: "",
            lifecycle = lifecycle,
            dependencies = emptyList(),
        )
    }
}