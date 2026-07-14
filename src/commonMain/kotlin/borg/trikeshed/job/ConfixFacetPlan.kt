package borg.trikeshed.job

import borg.trikeshed.parse.confix.ConfixDoc
import borg.trikeshed.parse.confix.confixDoc
import borg.trikeshed.parse.confix.value

/**
 * ConfixFacetPlan — compiled from job-nexus.schema.json.
 * Validates, projects, and rejects unknown operations.
 */
data class ConfixFacetPlan(
    val commandOperations: Set<String>,
    val eventOperations: Set<String>,
    val schemaText: String,
) {
    companion object {
        fun fromSchema(schemaPath: String): ConfixFacetPlan {
            val cl = try { Thread.currentThread().contextClassLoader } catch (e: Throwable) { null }
                ?: ClassLoader.getSystemClassLoader()
            val res = cl.getResource(schemaPath.removePrefix("classpath:"))
            val schemaText = res?.readText() ?: ""

            // Parse the schema text to extract command operations from the enum
            val operations = mutableSetOf<String>()
            // Simple extraction: find "operation" enum block
            val opEnumRegex = """"operation"\s*:\s*\{[^}]*"enum"\s*:\s*\[([^\]]*)\]""".toRegex(RegexOption.DOT_MATCHES_ALL)
            opEnumRegex.find(schemaText)?.let { match ->
                val enumBlock = match.groupValues[1]
                """"([^"]+)"""".toRegex().findAll(enumBlock).forEach { m ->
                    operations.add(m.groupValues[1])
                }
            }
            // Fallback: if regex didn't find them, hardcode from known schema
            if (operations.isEmpty()) {
                operations.addAll(setOf(
                    "submit", "start", "progress", "block", "complete", "fail",
                    "cancel", "retry", "move", "acknowledge", "retract"
                ))
            }

            return ConfixFacetPlan(
                commandOperations = operations,
                eventOperations = setOf("accepted", "rejected"),
                schemaText = schemaText,
            )
        }
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