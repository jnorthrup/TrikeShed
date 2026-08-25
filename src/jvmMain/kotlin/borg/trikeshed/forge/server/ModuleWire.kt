package borg.trikeshed.forge.server

import borg.trikeshed.litebike.JvmKanbanServer
import borg.trikeshed.module.ModuleRouteRegistry
import borg.trikeshed.module.ModuleSupervisor
import borg.trikeshed.parse.json.JsonSupport

/**
 * ModuleWire — the module control surface, mounted on the kanban listener:
 *
 *   GET    /api/modules            attached modules (describe) + claimed routes
 *   POST   /api/modules            {class: fqcn} → proxy-ctor attach (app CP, then build/live)
 *   DELETE /api/modules/<id>       drain → release routes → close
 *
 * This wire itself stays a STATIC extra route — the control plane must survive
 * every module coming and going.
 */
class ModuleWire(
    private val supervisor: ModuleSupervisor,
    private val routes: ModuleRouteRegistry,
) {
    suspend fun route(
        method: String,
        path: String,
        text: String,
        @Suppress("UNUSED_PARAMETER") respond: (suspend (ByteArray) -> Unit)?,
    ): JvmKanbanServer.HttpResponse? {
        val p = path.substringBefore('?')
        return when {
            method == "GET" && p == "/api/modules" -> json(
                mapOf("modules" to supervisor.describeAll(), "routes" to routes.paths()),
            )

            method == "POST" && p == "/api/modules" -> {
                val fqcn = parse(text)["class"]?.toString()
                    ?: return json(mapOf("error" to "class required"), 400)
                runCatching { supervisor.attach(fqcn) }.fold(
                    onSuccess = { json(mapOf("verdict" to "attached", "id" to it.id) + it.describe()) },
                    onFailure = { json(mapOf("verdict" to "refused", "detail" to (it.message ?: it.toString())), 400) },
                )
            }

            method == "DELETE" && p.startsWith("/api/modules/") -> {
                val id = p.removePrefix("/api/modules/")
                if (id.isBlank()) return json(mapOf("error" to "module id required"), 400)
                if (supervisor.detach(id)) json(mapOf("verdict" to "detached", "id" to id))
                else json(mapOf("error" to "no such module", "id" to id), 404)
            }

            else -> null
        }
    }

    private fun json(value: Any?, status: Int = 200): JvmKanbanServer.HttpResponse =
        JvmKanbanServer.HttpResponse(status, JsonSupport.stringify(value))

    @Suppress("UNCHECKED_CAST")
    private fun parse(text: String): Map<String, Any?> {
        val body = when {
            "\r\n\r\n" in text -> text.substringAfter("\r\n\r\n")
            "\n\n" in text -> text.substringAfter("\n\n")
            else -> text
        }
        if (body.isBlank()) return emptyMap()
        return runCatching { JsonSupport.parse(body) as? Map<String, Any?> }.getOrNull() ?: emptyMap()
    }
}
