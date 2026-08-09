package borg.trikeshed.memory

import borg.trikeshed.job.ContentId
import borg.trikeshed.lib.get
import borg.trikeshed.lib.size
import borg.trikeshed.memory.MemoryStore
import modelmux.acp.MEM_CREATE
import modelmux.acp.MEM_DELETE
import modelmux.acp.MEM_GREP
import modelmux.acp.MEM_INSERT
import modelmux.acp.MEM_RENAME
import modelmux.acp.MEM_STR_REPLACE
import modelmux.acp.MEM_VIEW
import modelmux.acp.TOOL_CREATE
import modelmux.acp.TOOL_DELETE
import modelmux.acp.TOOL_GREP
import modelmux.acp.TOOL_INSERT
import modelmux.acp.TOOL_RENAME
import modelmux.acp.TOOL_STR_REPLACE
import modelmux.acp.TOOL_VIEW

/**
 * Dispatches ACP memory tool calls to [MemoryStore] operations.
 *
 * When a management or search agent returns a tool call, the reactor resolves
 * it here. Each tool name maps to a [MemoryStore] read or write operation,
 * returning a text observation (omega) the agent sees next.
 *
 * This is the bridge between the ACP wire protocol (AcpTool declarations in
 * MemoryTools.kt) and the physical store (MemoryStore composing Rings 0-1-2).
 */
class MemoryToolDispatcher(private val store: MemoryStore) {

    /**
     * Dispatch a tool call. Returns the text observation, or null if the tool
     * name is unrecognized.
     *
     * @param toolName one of TOOL_VIEW, TOOL_CREATE, etc.
     * @param args parsed arguments as a Map<String, String>.
     * @param agentId the agent making the call (for provenance).
     */
    fun dispatch(toolName: String, args: Map<String, String>, agentId: String = "system"): String? {
        return when (toolName) {
            TOOL_VIEW -> view(args)
            TOOL_CREATE -> create(args, agentId)
            TOOL_STR_REPLACE -> strReplace(args, agentId)
            TOOL_INSERT -> insert(args, agentId)
            TOOL_DELETE -> delete(args)
            TOOL_RENAME -> rename(args, agentId)
            TOOL_GREP -> grep(args)
            else -> null
        }
    }

    private fun view(args: Map<String, String>): String {
        val path = args["path"] ?: return "error: missing 'path'"
        val file = store.get(path) ?: return "error: file not found: $path"
        return buildString {
            appendLine("---")
            appendLine("name: ${path.substringAfterLast('/').removeSuffix(".md")}")
            appendLine("description: ${file.description}")
            appendLine("---")
            appendLine()
            append(file.content.decodeToString())
        }
    }

    private fun create(args: Map<String, String>, agentId: String): String {
        val path = args["path"] ?: return "error: missing 'path'"
        val description = args["description"] ?: return "error: missing 'description'"
        val content = args["content"] ?: return "error: missing 'content'"
        val file = memoryFile(path, description, content)
        val cid = store.put(file, agentId = agentId, kind = "declarative")
        return "ok: created $path (cid=$cid)"
    }

    private fun strReplace(args: Map<String, String>, agentId: String): String {
        val path = args["path"] ?: return "error: missing 'path'"
        val oldStr = args["old"] ?: return "error: missing 'old'"
        val newStr = args["new"] ?: return "error: missing 'new'"
        val file = store.get(path) ?: return "error: file not found: $path"
        val text = file.content.decodeToString()
        val count = text.windowed(oldStr.length).count { it == oldStr }
        if (count == 0) return "error: old string not found in $path"
        if (count > 1) return "error: old string appears $count times in $path — must be unique"
        val updated = text.replace(oldStr, newStr)
        val newFile = memoryFile(path, file.description, updated)
        store.put(newFile, agentId = agentId, kind = "declarative")
        return "ok: replaced in $path"
    }

    private fun insert(args: Map<String, String>, agentId: String): String {
        val path = args["path"] ?: return "error: missing 'path'"
        val line = args["line"]?.toIntOrNull() ?: return "error: missing or invalid 'line'"
        val content = args["content"] ?: return "error: missing 'content'"
        val file = store.get(path) ?: return "error: file not found: $path"
        val lines = file.content.decodeToString().split("\n").toMutableList()
        val insertAt = line.coerceIn(0, lines.size)
        lines.add(insertAt, content)
        val updated = lines.joinToString("\n")
        val newFile = memoryFile(path, file.description, updated)
        store.put(newFile, agentId = agentId, kind = "declarative")
        return "ok: inserted at line $insertAt in $path"
    }

    private fun delete(args: Map<String, String>): String {
        val path = args["path"] ?: return "error: missing 'path'"
        store.delete(path)
        return "ok: deleted $path"
    }

    private fun rename(args: Map<String, String>, agentId: String): String {
        val oldPath = args["old_path"] ?: return "error: missing 'old_path'"
        val newPath = args["new_path"] ?: return "error: missing 'new_path'"
        val file = store.get(oldPath) ?: return "error: file not found: $oldPath"
        store.delete(oldPath)
        val newFile = memoryFile(newPath, file.description, file.content)
        store.put(newFile, agentId = agentId, kind = "declarative")
        return "ok: renamed $oldPath -> $newPath"
    }

    private fun grep(args: Map<String, String>): String {
        val pattern = args["pattern"] ?: return "error: missing 'pattern'"
        val pathFilter = args["path"]
        val regex = try { Regex(pattern) } catch (e: Exception) {
            return "error: invalid regex: ${e.message}"
        }
        val results = mutableListOf<String>()
        val paths = store.listPaths()
        for (i in 0 until paths.size) {
            val p = paths[i]
            if (pathFilter != null && !p.startsWith(pathFilter)) continue
            val file = store.get(p) ?: continue
            val text = file.content.decodeToString()
            text.lines().forEachIndexed { lineNum, line ->
                if (regex.containsMatchIn(line)) {
                    results.add("$p:${lineNum + 1}: ${line.trim()}")
                }
            }
        }
        return if (results.isEmpty()) "no matches"
        else results.joinToString("\n").take(4000)
    }
}
