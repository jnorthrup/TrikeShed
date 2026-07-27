package borg.trikeshed.jules

import borg.trikeshed.parse.json.JsonSupport

/**
 * Stateless Jules REST client. Zero board state — the Kanban cards own all state.
 * This replaces every curl+jq invocation in bin/trikeshed-jules.
 *
 * Transport delegated to [JulesHttpClient] — the platform abstraction.
 * jvmMain: [JvmJulesHttpClient]. Future: userspace NIO actual.
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
        val source: String = "",
        val updateTime: String = "",
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
        val excerpt: String,    // first 140 chars of the message body, if any
    )

    /**
     * List sessions across every API page, optionally constrained to one exact
     * Jules source. Empty/missing source values never match a requested source:
     * adopting them would let patches from another repository cross the tenant
     * boundary and reach this repository's settlement gate.
     */
    suspend fun listSessions(source: String? = null): List<SessionInfo> {
        val out = mutableListOf<SessionInfo>()
        var pageToken: String? = null
        do {
            val path = buildString {
                append("/sessions?pageSize=100")
                if (!pageToken.isNullOrEmpty()) append("&pageToken=${java.net.URLEncoder.encode(pageToken, Charsets.UTF_8)}")
            }
            val parsed = JsonSupport.parse(get(path)) as? Map<*, *> ?: break
            val sessions = parsed["sessions"] as? List<*> ?: emptyList<Any?>()
            for (s in sessions) {
                val m = s as? Map<*, *> ?: continue
                val name = m["name"]?.toString() ?: continue
                val sessionSource = ((m["sourceContext"] as? Map<*, *>)?.get("source"))?.toString() ?: ""
                if (source != null && sessionSource != source) continue
                out += SessionInfo(
                    id = name.substringAfterLast('/'),
                    state = m["state"]?.toString() ?: "UNKNOWN",
                    title = m["title"]?.toString() ?: "",
                    patchBytes = 0L, // filled from activities for COMPLETED sessions
                    source = sessionSource,
                    updateTime = m["updateTime"]?.toString() ?: m["createTime"]?.toString() ?: "",
                )
            }
            pageToken = parsed["nextPageToken"]?.toString()?.takeIf { it.isNotBlank() }
        } while (pageToken != null)
        return out
    }

    /** Ordered activities for a session, each carrying its minted serial. */
    suspend fun activities(sessionId: String): List<ActivityInfo> {
        val raw = activityMaps(sessionId)
        val out = ArrayList<ActivityInfo>(raw.size)
        for ((seq, m) in raw.withIndex()) {
            val name = m["name"]?.toString() ?: continue
            var kind = "unknown"
            for (k in listOf("agentMessaged", "userMessaged", "planGenerated", "progressUpdated")) {
                if (m.containsKey(k)) { kind = k; break }
            }
            val msgBody = when (kind) {
                "agentMessaged" -> (m["agentMessaged"] as? Map<*, *>)?.get("agentMessage")?.toString()
                "userMessaged" -> (m["userMessaged"] as? Map<*, *>)?.get("userMessage")?.toString()
                "progressUpdated" -> (m["progressUpdated"] as? Map<*, *>)?.let { p ->
                    listOfNotNull(p["title"]?.toString(), p["description"]?.toString())
                        .joinToString(": ")
                        .takeIf { it.isNotBlank() }
                }
                else -> null
            }
            val patches = patchTexts(m)
            if (patches.isNotEmpty() && kind == "unknown") kind = "artifacts"
            out += ActivityInfo(
                id = name.substringAfterLast('/'),
                seq = seq,
                createTime = m["createTime"]?.toString() ?: "",
                originator = m["originator"]?.toString() ?: "unknown",
                kind = kind,
                patchBytes = patches.lastOrNull()?.length?.toLong() ?: 0L,
                excerpt = msgBody?.take(140) ?: "",
            )
        }
        return out
    }

    /** Byte length of the latest cumulative patch. */
    suspend fun patchProbe(sessionId: String): Long = lastPatch(sessionId)?.length?.toLong() ?: 0L

    /**
     * Last patch worth applying. Two sources, in order of preference:
     *
     *  1. `/sessions/$id` top-level `outputs[*].changeSet.gitPatch.unidiffPatch`
     *     — the FINAL completed patch for the session. This is what landed.
     *  2. Fallback: in-progress `activities[*].artifacts[*].changeSet.gitPatch`
     *     — for sessions still mid-flight, the most recent artifact's diff.
     *
     * The previous code only read the activity stream and missed the canonical
     * output entirely; the flywheel drained zero patches because `outputs`
     * was never consulted.
     */
    suspend fun lastPatch(sessionId: String): String? {
        val fromOutputs = sessionOutputsPatches(sessionId).lastOrNull()
        if (!fromOutputs.isNullOrEmpty()) return fromOutputs
        return activityMaps(sessionId).asSequence().flatMap { patchTexts(it).asSequence() }.lastOrNull()
    }

    /** Fetch the session resource and read outputs[*].changeSet.gitPatch.unidiffPatch. */
    private suspend fun sessionOutputsPatches(sessionId: String): List<String> {
        val out = mutableListOf<String>()
        val parsed = try {
            JsonSupport.parse(get("/sessions/$sessionId")) as? Map<*, *> ?: return out
        } catch (t: Throwable) {
            return out
        }
        val outputs = parsed["outputs"] as? List<*> ?: return out
        for (output in outputs) {
            val changeSet = (output as? Map<*, *>)?.get("changeSet") as? Map<*, *> ?: continue
            gitPatchText(changeSet)?.let { out += it }
        }
        return out
    }

    /** Fetch every chronological activity page; sequence numbers are minted afterwards. */
    private suspend fun activityMaps(sessionId: String): List<Map<*, *>> {
        val out = mutableListOf<Map<*, *>>()
        var pageToken: String? = null
        do {
            val path = buildString {
                append("/sessions/$sessionId/activities?pageSize=100")
                if (!pageToken.isNullOrEmpty()) append("&pageToken=${java.net.URLEncoder.encode(pageToken, Charsets.UTF_8)}")
            }
            val raw = try { get(path) } catch (t: Throwable) { return out }
            val parsed = try { JsonSupport.parse(raw) } catch (t: Throwable) { return out } as? Map<*, *> ?: break
            val page = parsed["activities"] as? List<*> ?: emptyList<Any?>()
            page.mapNotNullTo(out) { it as? Map<*, *> }
            pageToken = parsed["nextPageToken"]?.toString()?.takeIf { it.isNotBlank() }
        } while (pageToken != null)
        return out
    }

    /** Normalize both live gitPatch shapes via [gitPatchText]. */
    private fun patchTexts(activity: Map<*, *>): List<String> {
        val out = mutableListOf<String>()
        val artifacts = activity["artifacts"] as? List<*> ?: return out
        for (artifact in artifacts) {
            val changeSet = (artifact as? Map<*, *>)?.get("changeSet") as? Map<*, *> ?: continue
            gitPatchText(changeSet)?.let { out += it }
        }
        return out
    }

    /** Extract the diff text from a changeSet map: string or {unidiffPatch: string}. */
    private fun gitPatchText(changeSet: Map<*, *>): String? {
        val patch = when (val gitPatch = changeSet["gitPatch"]) {
            is String -> gitPatch
            is Map<*, *> -> gitPatch["unidiffPatch"]?.toString()
            else -> null
        }
        return patch?.takeIf { it.isNotEmpty() }?.let(::jsonUnescape)
    }

    /**
     * Decode JSON string escapes. JsonParser.reify slices raw token chars — its
     * string branch (parse/json/Json.kt) returns the escaped source verbatim,
     * so every multi-line string arrived with literal `\n`. A harvested
     * "patch" was a single-line backslash-n blob that `git apply` rejected
     * ("No valid patches in input"): every patch-bearing drain failed since
     * inception (WAL: 281 DrainFailed, 0 harvested) and each failure bred a
     * rework. HARVEST could not have ever landed through this path.
     */
    private fun jsonUnescape(s: String): String {
        var actualS = s
        if (s.startsWith("\"") && s.endsWith("\"") && s.length >= 2) {
            actualS = s.substring(1, s.length - 1)
        }
        if ('\\' !in actualS) return actualS
        val out = StringBuilder(actualS.length)
        var i = 0
        while (i < actualS.length) {
            val c = actualS[i]
            if (c == '\\' && i + 1 < actualS.length) {
                when (actualS[i + 1]) {
                    '"' -> { out.append('"'); i += 2 }
                    '\\' -> { out.append('\\'); i += 2 }
                    '/' -> { out.append('/'); i += 2 }
                    'b' -> { out.append('\b'); i += 2 }
                    'f' -> { out.append('\u000C'); i += 2 }
                    'n' -> { out.append('\n'); i += 2 }
                    'r' -> { out.append('\r'); i += 2 }
                    't' -> { out.append('\t'); i += 2 }
                    'u' -> {
                        val code = actualS.substring(i + 2, minOf(i + 6, actualS.length)).toIntOrNull(16)
                        if (code != null && i + 6 <= actualS.length) { out.append(code.toChar()); i += 6 }
                        else { out.append(c); i += 1 }
                    }
                    else -> { out.append(c); i += 1 }
                }
            } else { out.append(c); i += 1 }
        }
        return out.toString()
    }

    /**
     * Send a message to an AWAITING session. Returns the created activity's id
     * (the dedup anchor — without it the next poll would double-count our own
     * answer as a fresh user event), or null if the response carried no id.
     */
    suspend fun sendMessage(sessionId: String, message: String): String? {
        val resp = post("/sessions/$sessionId:sendMessage", """{"prompt": ${jsonString(message)}}""")
        val parsed = JsonSupport.parse(resp) as? Map<*, *> ?: return null
        return parsed["name"]?.toString()?.substringAfterLast('/')
    }

    /**
     * Approve the session's latest plan (AWAITING_PLAN_APPROVAL → execution).
     * The API encodes the empty request as `{}`; any non-2xx throws from the
     * transport. The response body is not consulted — the sign-off is recorded
     * on the card's cause log by the conductor.
     */
    suspend fun approvePlan(sessionId: String) {
        post("/sessions/$sessionId:approvePlan", "{}")
    }

    /** Create a session. Returns the new session id. */
    suspend fun createSession(prompt: String, title: String, source: String = "sources/github/jnorthrup/TrikeShed", branch: String = "master"): String {
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
    suspend fun deleteSession(sessionId: String) {
        transport.delete("/sessions/$sessionId")
    }

    private suspend fun get(path: String): String = transport.get(path)

    private suspend fun post(path: String, json: String): String = transport.post(path, json)

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
