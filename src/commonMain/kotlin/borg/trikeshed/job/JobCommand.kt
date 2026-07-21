package borg.trikeshed.job

import kotlinx.serialization.Serializable

/**
 * JobCommand — all mutation intents enter through this sealed class.
 */
@Serializable
sealed class JobCommand {
    abstract val jobId: JobId
    abstract val idempotencyKey: String
    val operationName: String get() = this::class.simpleName?.lowercase() ?: ""

    @Serializable
    data class Submit(
        override val jobId: JobId,
        override val idempotencyKey: String,
        val dependencies: List<JobId> = emptyList(),
        val expectedRevision: Long? = null,
    ) : JobCommand()

    @Serializable
    data class Start(
        override val jobId: JobId,
        override val idempotencyKey: String,
        val expectedRevision: Long,
    ) : JobCommand()

    @Serializable
    data class Complete(
        override val jobId: JobId,
        override val idempotencyKey: String,
        val expectedRevision: Long,
    ) : JobCommand()

    @Serializable
    data class Fail(
        override val jobId: JobId,
        override val idempotencyKey: String,
        val expectedRevision: Long,
        val reason: String,
    ) : JobCommand()

    @Serializable
    data class Retry(
        override val jobId: JobId,
        override val idempotencyKey: String,
        val expectedRevision: Long,
    ) : JobCommand()

    @Serializable
    data class Progress(
        override val jobId: JobId,
        override val idempotencyKey: String,
        val expectedRevision: Long,
        val progress: Double,
    ) : JobCommand()

    @Serializable
    data class Block(
        override val jobId: JobId,
        override val idempotencyKey: String,
        val expectedRevision: Long,
        val reason: String,
    ) : JobCommand()

    @Serializable
    data class Cancel(
        override val jobId: JobId,
        override val idempotencyKey: String,
        val expectedRevision: Long,
    ) : JobCommand()

    @Serializable
    data class Move(
        override val jobId: JobId,
        override val idempotencyKey: String,
        val expectedRevision: Long,
        val toColumn: KanbanColumnId,
    ) : JobCommand()

    @Serializable
    data class Acknowledge(
        override val jobId: JobId,
        override val idempotencyKey: String,
        val expectedRevision: Long,
    ) : JobCommand()

    @Serializable
    data class Retract(
        override val jobId: JobId,
        override val idempotencyKey: String,
        val expectedRevision: Long,
    ) : JobCommand()
}