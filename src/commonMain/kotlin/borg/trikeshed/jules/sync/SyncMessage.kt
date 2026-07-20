package borg.trikeshed.jules.sync

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class SyncMessage(
    val id: String,
    val sequenceNumber: Long,
    val clientId: String,
    val payload: JsonElement,
    val timestamp: Long
)

@Serializable
data class Ack(
    val messageId: String,
    val sequenceNumber: Long,
    val clientId: String,
    val timestamp: Long
)

@Serializable
data class Nack(
    val messageId: String,
    val sequenceNumber: Long,
    val clientId: String,
    val reason: String,
    val timestamp: Long
)
