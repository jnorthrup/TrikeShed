package borg.trikeshed.forge.server

import borg.trikeshed.litebike.JvmKanbanServer
import borg.trikeshed.parse.json.JsonSupport
import borg.trikeshed.sleeve.AgentSleeveRegistry
import borg.trikeshed.sleeve.SleeveTddRedKanban

/**
 * Read-only view of the polyglot agent sleeves and the red board they generate.
 *
 * Read-only on purpose: a trap is resolved by writing a source fork and watching a red test go green, not
 * by a POST. What this surface is for is seeing which sleeve is red and why without booting a guest.
 */
class SleeveWire {

    suspend fun route(
        method: String,
        path: String,
        text: String,
        respond: (suspend (ByteArray) -> Unit)?,
    ): JvmKanbanServer.HttpResponse? {
        val p = path.substringBefore('?')
        return when {
            method == "GET" && p == "/api/sleeves" -> json(200, AgentSleeveRegistry.summarize())
            method == "GET" && p == "/api/sleeves/cards" -> json(200, mapOf(
                "column" to SleeveTddRedKanban.COLUMN.value,
                "submissions" to SleeveTddRedKanban.submissions(),
                "cards" to SleeveTddRedKanban.cards().map { card ->
                    mapOf(
                        "id" to card.id.value,
                        "title" to card.title,
                        "description" to card.description,
                        "columnId" to card.columnId.value,
                        "priority" to card.priority.name,
                        "dependencies" to card.dependencies.map { it.value },
                        "tags" to card.tags.toList(),
                        "metadata" to card.metadata,
                    )
                },
            ))
            method == "GET" && p.startsWith("/api/sleeves/") -> {
                val id = p.removePrefix("/api/sleeves/").trim('/')
                val manifest = AgentSleeveRegistry.byId(id)
                    ?: return json(404, mapOf("error" to "unknown_sleeve", "id" to id,
                        "known" to AgentSleeveRegistry.ALL.map { it.id }))
                json(200, manifest.toMap())
            }
            p.startsWith("/api/sleeves") -> json(405, mapOf("error" to "method_not_allowed", "path" to p))
            else -> null
        }
    }

    private fun json(status: Int, value: Map<String, Any?>): JvmKanbanServer.HttpResponse =
        JvmKanbanServer.HttpResponse(status, JsonSupport.stringify(value))
}
