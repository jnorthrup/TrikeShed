package modelmux.acp

import borg.trikeshed.lib.*
import borg.trikeshed.parse.jsonOf
import modelmux.*

const val TOOL_BREADCRUMB = "breadcrumb"
const val BREADCRUMB_SKILL = "Use breadcrumb with id and presence from 0 to 1 to resize retained context in the next tool result: 0 keeps its reference, 1 retrieves the original, and fractions retrieve its most distinct words in their original order. Automatic presence decays with age and time since explicit access; originals remain retrievable."

val BREADCRUMB_TOOL: AcpTool = TOOL_BREADCRUMB j """{"type":"object","description":"Resize a retained breadcrumb's context in the next tool result, once. Zero retains only its reference; one retrieves all original content.","properties":{"id":{"type":"string","minLength":1,"maxLength":256},"presence":{"type":"number","minimum":0,"maximum":1}},"required":["id","presence"],"additionalProperties":false}"""

class BreadcrumbToolDispatcher(val context: BreadcrumbContext) {
    /** A successful call's own result consumes the one-shot override. Invalid calls consume nothing. */
    fun dispatch(toolName: String, args: Map<String, String>): String? {
        if (toolName != TOOL_BREADCRUMB) return null
        val id = args["id"]?.takeIf { it.isNotBlank() && it.length <= 256 } ?: return failure("missing or invalid id")
        val presence = args["presence"]?.toDoubleOrNull()
            ?.takeIf { it.isFinite() && it in 0.0..1.0 } ?: return failure("presence must be finite and within [0,1]")
        if (args.keys.any { it != "id" && it != "presence" }) return failure("unexpected breadcrumb argument")
        if (!context.resize(BreadcrumbId(id), BreadcrumbPresence(presence))) return failure("unknown breadcrumb id: $id")
        return context.nextToolResult().breadcrumbJson()
    }

    private fun failure(message: String): String = "{\"error\":${jsonOf(message)}}"
}
