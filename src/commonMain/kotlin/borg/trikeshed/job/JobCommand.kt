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

    /** Card metadata only; revision advances without resubmitting or moving the job. */
    @Serializable
    data class Update(
        override val jobId: JobId,
        override val idempotencyKey: String,
        val expectedRevision: Long,
        val title: String? = null,
        val priority: Int? = null,
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

    /** Compare and replace a retained state head without restarting the job lifecycle. */
    @Serializable
    data class State(
        override val jobId: JobId,
        override val idempotencyKey: String,
        val expectedRevision: Long,
        val priorStateCid: String?,
        val stateCid: String,
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
    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    data class Acknowledge(
        override val jobId: JobId,
        override val idempotencyKey: String,
        val expectedRevision: Long,
        /** Evidence acknowledgment retains the prior lifecycle; ordinary acknowledgment keeps its wire semantics. */
        @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
        val receipt: Boolean = false,
    ) : JobCommand()

    @Serializable
    data class Retract(
        override val jobId: JobId,
        override val idempotencyKey: String,
        val expectedRevision: Long,
    ) : JobCommand()
}
