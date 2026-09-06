package borg.trikeshed.forge.server

import modelmux.MuxCallRecord
import modelmux.MuxConversation
import modelmux.MuxMessage

/** Explicit JSON boundary over the repository's Confix parser. */
internal fun MuxCallRecord.toWire(): Map<String, Any?> = mapOf(
    "id" to id, "conversationId" to conversationId, "turnId" to turnId, "assessmentId" to assessmentId,
    "model" to model, "provider" to provider, "startedAt" to startedAt, "endedAt" to endedAt,
    "status" to status, "keyId" to keyId, "httpStatus" to httpStatus, "inputTokens" to inputTokens,
    "outputTokens" to outputTokens, "cachedHit" to cachedHit, "cacheReadTokens" to cacheReadTokens,
    "cacheWriteTokens" to cacheWriteTokens, "error" to error,
)

internal fun MuxConversation.toWire(): Map<String, Any?> = mapOf(
    "id" to id, "title" to title, "createdAt" to createdAt, "updatedAt" to updatedAt,
    "parentId" to parentId, "turnId" to turnId, "status" to status, "model" to model,
    "mode" to mode, "archived" to archived, "children" to children, "error" to error,
    "messages" to messages.map { mapOf("role" to it.role, "content" to it.content, "at" to it.at, "model" to it.model) },
    "calls" to calls.map { it.toWire() },
)

internal fun muxConversationFromWire(value: Any?): MuxConversation {
    val m = value as? Map<*, *> ?: throw IllegalArgumentException("Invalid stored conversation")
    fun number(key: String) = (m[key] as? Number)?.toLong() ?: throw IllegalArgumentException("Missing $key")
    return MuxConversation(
        id = number("id"), title = m["title"] as String, createdAt = number("createdAt"), updatedAt = number("updatedAt"),
        parentId = (m["parentId"] as? Number)?.toLong(), turnId = number("turnId"), status = m["status"] as String,
        model = m["model"] as? String, mode = m["mode"] as String, archived = m["archived"] == true,
        error = m["error"] as? String, children = (m["children"] as List<*>).map { (it as Number).toLong() },
        messages = (m["messages"] as List<*>).map {
            val row = it as Map<*, *>
            MuxMessage(row["role"] as String, row["content"] as String, (row["at"] as Number).toLong(), row["model"] as? String)
        },
        calls = (m["calls"] as List<*>).map {
            val row = it as Map<*, *>
            fun n(key: String) = (row[key] as? Number)?.toLong() ?: 0L
            MuxCallRecord(id = n("id"), conversationId = (row["conversationId"] as? Number)?.toLong(),
                turnId = (row["turnId"] as? Number)?.toLong(), assessmentId = row["assessmentId"] as? String,
                model = row["model"] as String, provider = row["provider"] as String, startedAt = n("startedAt"),
                endedAt = (row["endedAt"] as? Number)?.toLong(), status = row["status"] as String, keyId = row["keyId"] as? String,
                httpStatus = (row["httpStatus"] as? Number)?.toInt(), inputTokens = n("inputTokens").toInt(), outputTokens = n("outputTokens").toInt(),
                cachedHit = row["cachedHit"] == true, cacheReadTokens = n("cacheReadTokens").toInt(), cacheWriteTokens = n("cacheWriteTokens").toInt(), error = row["error"] as? String)
        },
    )
}
