package borg.trikeshed.jules

import borg.trikeshed.parse.json.JsonSupport
import keymux.KeyMux

/**
 * Stateless Jules REST client. Zero board state — the Kanban cards own all state.
 * This replaces every curl+jq invocation in bin/trikeshed-jules.
 *
 * Transport is the common HTX-backed client [TrikeHtxHttpClient]; the daemon
 * installs a TLS-backed [borg.trikeshed.htx.HtxElement] in the coroutine context.
 *
 * The API key is resolved from [keyMux] on every request so that KeyMux can
 * rotate credentials when a 429 is encountered (quota containment).
 */
class JulesRestClient(
    private val keyMux: KeyMux,
    private val base: String = "https://jules.googleapis.com/v1alpha",
) {
    /** Resolve the key from KeyMux and build a fresh transport for this call. */
    private suspend fun transport(): JulesHttpClient {
        val key = keyMux.get("JULES_API_KEY")
            ?: error("JULES_API_KEY not resolved by KeyMux — add env() or persist() binding")
        return julesHtxClient(key, base)
    }

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
                if (!pageToken.isNullOrEmpty()) append("&pageToken=${percentEncode(pageToken)}")
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

    /** Latest task delta; repository snapshots containing build caches are not task deltas. */
    suspend fun lastPatch(sessionId: String): String? {
        val activityDelta = activityMaps(sessionId).asSequence()
            .flatMap { patchTexts(it).asSequence() }
            .filterNot(::isRepositorySnapshot)
            .lastOrNull()
        if (!activityDelta.isNullOrEmpty()) return activityDelta
        return sessionOutputsPatches(sessionId).lastOrNull { !isRepositorySnapshot(it) }
    }

    private fun isRepositorySnapshot(patch: String): Boolean =
        "diff --git a/.gradle/" in patch ||
            "diff --git a/build/" in patch ||
            "diff --git a/.git/" in patch

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
                if (!pageToken.isNullOrEmpty()) append("&pageToken=${percentEncode(pageToken)}")
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
        transport().delete("/sessions/$sessionId")
    }

    private suspend fun get(path: String): String = transport().get(path)

    private suspend fun post(path: String, json: String): String = transport().post(path, json)

    private suspend fun delete(path: String): String = transport().delete(path)

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

    private val HEX_CHARS = "0123456789ABCDEF".toCharArray()

    /** URL form encoding without the JVM-only URLEncoder boundary. */
    private fun percentEncode(s: String): String {
        // ⚡ Bolt: Optimize by caching encoded bytes to avoid iterator allocations and using bitwise ops for hex encoding
        val bytes = s.encodeToByteArray()
        return buildString(bytes.size + 16) {
            for (i in bytes.indices) {
                val unsigned = bytes[i].toInt() and 0xff
                when {
                    unsigned in 'A'.code..'Z'.code ||
                        unsigned in 'a'.code..'z'.code ||
                        unsigned in '0'.code..'9'.code ||
                        unsigned == '-'.code || unsigned == '_'.code ||
                        unsigned == '.'.code || unsigned == '~'.code -> append(unsigned.toChar())
                    unsigned == ' '.code -> append('+')
                    else -> {
                        append('%')
                        append(HEX_CHARS[unsigned shr 4])
                        append(HEX_CHARS[unsigned and 0x0F])
                    }
                }
            }
        }
    }
}
