/*
 * Copyright (c) 2017 TrikeShed Contributors
 * AGPLv3 — see LICENSE
 */
package borg.trikeshed.jules

import borg.trikeshed.parse.json.JsonSupport
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/**
 * Stateless Jules REST client. Zero board state — the Kanban cards own all state.
 * This replaces every curl+jq invocation in bin/trikeshed-jules.
 */
class JulesRestClient(
    private val apiKey: String,
    private val base: String = "https://jules.googleapis.com/v1alpha",
) {
    private val http: HttpClient = HttpClient.newBuilder().build()

    data class SessionInfo(
        val id: String,
        val state: String,
        val title: String,
        val patchBytes: Long,
    )

    /**
     * One Jules activity with the board-minted serial. Jules gives random hex ids
     * and microsecond createTime but no sequence numbers; [seq] is our serial —
     * the activity's index in the chronologically-ordered list. [id] is the dedup
     * anchor that survives retroactive insertions.
     */
    data class ActivityInfo(
        val id: String,
        val seq: Int,
        val createTime: String,
        val originator: String,
        val kind: String,       // agentMessaged | userMessaged | planGenerated | progressUpdated | artifacts
        val patchBytes: Long,   // unidiff bytes carried by this activity, 0 if none
    )

    /** List all sessions (pageSize 100). */
    fun listSessions(): List<SessionInfo> {
        val body = get("/sessions?pageSize=100")
        val parsed = JsonSupport.parse(body) as? Map<*, *> ?: return emptyList()
        val sessions = parsed["sessions"] as? List<*> ?: return emptyList()
        return sessions.mapNotNull { s ->
            val m = s as? Map<*, *> ?: return@mapNotNull null
            val name = m["name"]?.toString() ?: return@mapNotNull null
            SessionInfo(
                id = name.substringAfterLast('/'),
                state = m["state"]?.toString() ?: "UNKNOWN",
                title = m["title"]?.toString() ?: "",
                patchBytes = 0L, // filled by patchProbe for COMPLETED sessions
            )
        }
    }

    /** Sum of unidiff patch bytes across a session's artifacts. */
    fun patchProbe(sessionId: String): Long {
        val body = get("/sessions/$sessionId/activities?pageSize=100")
        val parsed = JsonSupport.parse(body) as? Map<*, *> ?: return 0L
        val activities = parsed["activities"] as? List<*> ?: return 0L
        var total = 0L
        for (a in activities) {
            val m = a as? Map<*, *> ?: continue
            val artifacts = m["artifacts"] as? List<*> ?: continue
            for (art in artifacts) {
                val am = art as? Map<*, *> ?: continue
                val cs = am["changeSet"] as? Map<*, *> ?: continue
                val gp = cs["gitPatch"] as? Map<*, *> ?: continue
                val p = gp["unidiffPatch"]?.toString() ?: continue
                total += p.length.toLong()
            }
        }
        return total
    }

    /** Last activity's cumulative patch (the only one worth applying). */
    fun lastPatch(sessionId: String): String? {
        val body = get("/sessions/$sessionId/activities?pageSize=100")
        val parsed = JsonSupport.parse(body) as? Map<*, *> ?: return null
        val activities = parsed["activities"] as? List<*> ?: return null
        var last: String? = null
        for (a in activities) {
            val m = a as? Map<*, *> ?: continue
            val artifacts = m["artifacts"] as? List<*> ?: continue
            for (art in artifacts) {
                val am = art as? Map<*, *> ?: continue
                val cs = am["changeSet"] as? Map<*, *> ?: continue
                val gp = cs["gitPatch"] as? Map<*, *> ?: continue
                val p = gp["unidiffPatch"]?.toString() ?: continue
                last = p
            }
        }
        return last
    }

    /** Send a message to an AWAITING session. */
    fun sendMessage(sessionId: String, message: String) {
        post("/sessions/$sessionId:sendMessage", """{"message": ${jsonString(message)}}""")
    }

    /** Create a session. Returns the new session id. */
    fun createSession(prompt: String, title: String, source: String = "sources/github/jnorthrup/TrikeShed", branch: String = "master"): String {
        val body = """
        {
          "prompt": ${jsonString(prompt)},
          "title": ${jsonString(title)},
          "sourceContext": {
            "source": ${jsonString(source)},
            "githubRepoContext": { "startingBranch": ${jsonString(branch)} }
          }
        }
        """.trimIndent()
        val resp = post("/sessions", body)
        val parsed = JsonSupport.parse(resp) as? Map<*, *> ?: error("createSession: bad response")
        return parsed["name"]?.toString()?.substringAfterLast('/') ?: error("createSession: no id in $resp")
    }

    /** Delete a session. */
    fun deleteSession(sessionId: String) {
        request("DELETE", "/sessions/$sessionId", null)
    }

    private fun get(path: String): String = request("GET", path, null)

    private fun post(path: String, json: String): String = request("POST", path, json)

    private fun request(method: String, path: String, json: String?): String {
        val builder = HttpRequest.newBuilder()
            .uri(URI.create("$base$path"))
            .header("x-goog-api-key", apiKey)
            .header("Content-Type", "application/json")
        when (method) {
            "GET" -> builder.GET()
            "DELETE" -> builder.DELETE()
            else -> builder.POST(HttpRequest.BodyPublishers.ofString(json ?: "{}"))
        }
        val resp = http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
        if (resp.statusCode() >= 400) error("Jules API ${resp.statusCode()}: ${resp.body().take(300)}")
        return resp.body()
    }

    private fun jsonString(s: String): String = buildString {
        append('"')
        for (c in s) when (c) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> append(c)
        }
        append('"')
    }
}
