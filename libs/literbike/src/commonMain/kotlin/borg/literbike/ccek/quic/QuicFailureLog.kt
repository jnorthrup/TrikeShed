package borg.literbike.ccek.quic

// ============================================================================
// QUIC Failure Log -- ported from quic_failure_log.rs
// Structured failure event logging for QUIC diagnostics
// ============================================================================

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.time.Duration.Companion.milliseconds

/** Failure event record for structured logging */
@Serializable
data class FailureEvent(
    val tsMs: Long,
    val component: String,
    val category: String,
    val message: String,
    val context: JsonObject
)

private fun nowMs(): Long = Clocks.System.now()

/**
 * QUIC failure log -- records structured failure events.
 * In the Kotlin port, this writes to an in-memory buffer.
 * For production use, integrate with your logging framework.
 */
object QuicFailureLog {
    private val buffer = mutableListOf<FailureEvent>()

    fun logError(
        component: String,
        category: String,
        err: QuicError,
        context: JsonObject = buildJsonObject { }
    ) {
        val ev = FailureEvent(
            tsMs = nowMs(),
            component = component,
            category = category,
            message = err.message ?: "Unknown error",
            context = context
        )
        buffer.add(ev)
        println("[QUIC-FAILURE] $component/$category: ${ev.message}")
    }

    fun logMessage(
        component: String,
        category: String,
        message: String,
        context: JsonObject = buildJsonObject { }
    ) {
        val ev = FailureEvent(
            tsMs = nowMs(),
            component = component,
            category = category,
            message = message,
            context = context
        )
        buffer.add(ev)
        println("[QUIC-FAILURE] $component/$category: $message")
    }

    fun getEvents(): List<FailureEvent> = buffer.toList()
    fun clear() = buffer.clear()
}
