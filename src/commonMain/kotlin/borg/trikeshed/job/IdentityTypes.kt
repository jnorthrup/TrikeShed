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

@Serializable
data class KanbanColumnId(val value: String) {
    companion object {
        fun of(value: String) = KanbanColumnId(value)
    }
}

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