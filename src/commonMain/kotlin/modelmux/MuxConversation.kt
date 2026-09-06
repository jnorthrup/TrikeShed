package modelmux

import kotlinx.serialization.Serializable

@Serializable
data class MuxMessage(val role: String, val content: String, val at: Long, val model: String? = null)

@Serializable
data class MuxConversation(
    val id: Long,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val parentId: Long? = null,
    val turnId: Long = 0,
    val status: String = "idle",
    val model: String? = null,
    val mode: String = "chat",
    val archived: Boolean = false,
    val messages: List<MuxMessage> = emptyList(),
    val calls: List<MuxCallRecord> = emptyList(),
    val children: List<Long> = emptyList(),
    val error: String? = null,
)
