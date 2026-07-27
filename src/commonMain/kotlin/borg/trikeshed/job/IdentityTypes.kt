package borg.trikeshed.job

import kotlinx.serialization.Serializable

/**
 * Core identity types for the Job Nexus.
 */
@Serializable
data class JobId(val value: String) {
    companion object {
        fun of(value: String) = JobId(value)
    }
}

@Serializable
data class Revision(val value: Long) {
    companion object {
        fun of(value: Long) = Revision(value)
    }
}

/**
 * KanbanColumnId is canonically owned by [borg.trikeshed.kanban.KanbanColumnId].
 * This alias preserves source-compatibility for job-package call sites
 * (e.g. [JobCommand.Move.toColumn]) while unifying the serialization identity
 * to a single @Serializable type and a single generated serializer.
 */
typealias KanbanColumnId = borg.trikeshed.kanban.KanbanColumnId

@Serializable
data class Sequence(val value: Long) {
    companion object {
        fun of(value: Long) = Sequence(value)
    }
}

@Serializable
data class AttemptId(val value: String) {
    companion object {
        fun of(value: String) = AttemptId(value)
    }
}

@Serializable
data class CausalKey(val value: String) {
    companion object {
        fun of(value: String) = CausalKey(value)
    }
}