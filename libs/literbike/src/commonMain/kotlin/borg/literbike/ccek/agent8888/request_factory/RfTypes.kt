package borg.literbike.ccek.agent8888.request_factory

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Type-safe entity identifier wrapping a CouchDB `_id`.
 */
@Serializable
data class EntityId<T>(
    val id: String
) {
    companion object {
        fun <T> new(id: String): EntityId<T> = EntityId(id)
    }
}

/**
 * Maps to CouchDB `_rev` -- optimistic concurrency token.
 */
@Serializable
data class Revision(
    val value: String
) {
    companion object {
        fun new(rev: String): Revision = Revision(rev)
    }

    fun asString(): String = value
}

/**
 * Marker interface for entities that have identity (id + revision).
 */
interface EntityProxy {
    fun entityId(): String
    fun revision(): String?
}

/**
 * Marker interface for value objects with no independent identity.
 */
interface ValueProxy

/**
 * A single operation in a batched request context.
 */
@Serializable
sealed class Operation {
    @Serializable
    data class Find(
        val entityType: String,
        val id: String
    ) : Operation()

    @Serializable
    data class Persist(
        val entityType: String,
        val id: String,
        val rev: String? = null,
        val payload: JsonElement
    ) : Operation()

    @Serializable
    data class Delete(
        val entityType: String,
        val id: String,
        val rev: String
    ) : Operation()
}

/**
 * Accumulates operations before firing them as a batch.
 */
class RequestContext {
    val operations: MutableList<Operation> = mutableListOf()

    companion object {
        fun new(): RequestContext = RequestContext()
    }

    fun find(entityType: String, id: String) {
        operations.add(Operation.Find(entityType, id))
    }

    fun persist(
        entityType: String,
        id: String,
        rev: String? = null,
        payload: JsonElement
    ) {
        operations.add(Operation.Persist(entityType, id, rev, payload))
    }

    fun delete(entityType: String, id: String, rev: String) {
        operations.add(Operation.Delete(entityType, id, rev))
    }
}
