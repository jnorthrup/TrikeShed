package borg.literbike.ccek.agent8888.request_factory

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonElement

/**
 * HTTP batch request envelope -- POST `/_rf`
 */
@Serializable
data class RfRequest(
    val invocations: List<Invocation>
)

/**
 * One operation within a batch.
 */
@Serializable
data class Invocation(
    val operation: String,
    val entityType: String,
    val id: String? = null,
    /// Maps to CouchDB `_rev`
    val version: String? = null,
    val payload: JsonElement? = null
)

/**
 * HTTP batch response envelope.
 */
@Serializable
data class RfResponse(
    val results: List<RfResult>,
    val sideEffects: List<JsonElement>
)

/**
 * Result for one invocation.
 */
@Serializable
data class RfResult(
    val id: String,
    /// Updated CouchDB `_rev` after write.
    val version: String,
    val payload: JsonElement? = null,
    val error: String? = null
)
