package borg.trikeshed.jules

import borg.trikeshed.parse.json.JsonSupport
import borg.trikeshed.util.toUpperHex
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.get
import borg.trikeshed.lib.size
import borg.trikeshed.lib.toSeries
import borg.trikeshed.lib.view
import keymux.KeyMux
import kotlinx.coroutines.delay

/**
 * Stateless Jules REST client. Zero board state — the Kanban cards own all state.
 * This is the sole Jules HTTP boundary. The former curl+jq shell control plane
 * was removed so polling, messages, creation, and archive all share HTX,
 * KeyMux, and the causal WAL/CAS funnel.
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

    /**
     * Retry wrapper for HTTP GET with exponential backoff on 5xx and credential
     * rotation on 429 (quota containment).  Each retry builds a fresh transport
     * so the rotated key is used on every attempt.
     */
    private suspend fun retryingGet(path: String): String {
        var delayMs = 1_000L
        for (attempt in 1..5) {
            runCatching { transport().get(path) }
                .onSuccess { return it }
                .onFailure { ex: Throwable ->
                    // Empty/truncated response body — treat as retryable server malfunction
                    val isParseEx = ex is IndexOutOfBoundsException || ex is java.lang.StringIndexOutOfBoundsException
                    val is5xx = ex.message?.contains("500") == true || ex.message?.contains("502") == true ||
                            ex.message?.contains("503") == true || ex.message?.contains("504") == true
                    val is429 = ex.message?.contains("429") == true ||
                            ex.message?.contains("Too Many Requests", ignoreCase = true) == true
                    if (attempt == 5 || (!isParseEx && !is5xx && !is429)) throw ex
                    if (is429) {
                        keyMux.rotate("JULES_API_KEY")
                        delayMs = 1_000L
                    } else {
                        delay(delayMs)
                        delayMs = (delayMs * 2).coerceAtMost(32_000L)
                    }
                }
        }
        error("unreachable")
    }

    private suspend fun retryingPost(path: String, json: String): String {
        var delayMs = 1_000L
        for (attempt in 1..5) {
            runCatching { transport().post(path, json) }
                .onSuccess { return it }
                .onFailure { ex: Throwable ->
                    val isParseEx = ex is IndexOutOfBoundsException || ex is java.lang.StringIndexOutOfBoundsException
                    val is5xx = ex.message?.contains("500") == true || ex.message?.contains("502") == true ||
                            ex.message?.contains("503") == true || ex.message?.contains("504") == true
                    val is429 = ex.message?.contains("429") == true ||
                            ex.message?.contains("Too Many Requests", ignoreCase = true) == true
                    if (attempt == 5 || (!isParseEx && !is5xx && !is429)) throw ex
                    if (is429) {
                        keyMux.rotate("JULES_API_KEY")
                        delayMs = 1_000L
                    } else {
                        delay(delayMs)
                        delayMs = (delayMs * 2).coerceAtMost(32_000L)
                    }
                }
        }
        error("unreachable")
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
        val message: String,    // full message body; GUIDE selects its final question-bearing paragraph
    )

    /**
     * One immutable cumulative-patch observation from the activity stream.
     *
     * Jules does not promise that a later cumulative snapshot contains the
     * same file set as an earlier one.  [causalOrdinal] therefore identifies
     * the observation order while [activityId] remains the stable API anchor;
     * the CAS content hash is minted by the JVM continuity store before drain.
     */
    data class ActivityPatch(
        val activityId: String,
        val activitySeq: Int,
        val artifactSeq: Int,
        val causalOrdinal: Int,
        val createTime: String,
        val patch: String,
    )

    /**
     * One complete agent message as observed in the chronological activity
     * stream.  [message] is never excerpted: the continuity store puts these
     * exact UTF-8 bytes in CAS before bonding the activity to the WAL.
     */
    data class ActivityReport(
        val activityId: String,
        val activitySeq: Int,
        val causalOrdinal: Int,
        val createTime: String,
        val message: String,
    )

    /** One HTTP projection supplies conversation metadata, reports, and patches. */
    data class ActivityTimeline(
        val activities: Series<ActivityInfo>,
        val reports: Series<ActivityReport>,
        val patches: Series<ActivityPatch>,
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
        val seenPageTokens = mutableSetOf<String>()
        do {
            val path = buildString {
                append("/sessions?pageSize=100")
                if (!pageToken.isNullOrEmpty()) append("&pageToken=${percentEncode(pageToken)}")
            }
            val parsed = try {
                JsonSupport.parse(retryingGet(path)) as? Map<*, *>
            } catch (ex: Exception) {
                throw RuntimeException("Jules session page failed: ${ex.message}", ex)
            }
            requireNotNull(parsed) { "Jules session page is not an object" }
            val sessions = parsed.optionalList("sessions", "Jules session page")
            for (s in sessions) {
                val m = requireNotNull(s as? Map<*, *>) { "Jules session entry is not an object" }
                val name = requireNotNull(m["name"]?.toString()?.takeIf { it.isNotBlank() }) {
                    "Jules session entry has no name"
                }
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
            pageToken = parsed["nextPageToken"]?.toString()?.let(::jsonUnescape)?.takeIf { it.isNotBlank() }
            if (pageToken != null && !seenPageTokens.add(pageToken)) {
                // Repeated token: abort gracefully rather than looping forever.
                // The sessions collected so far are still valid — return them.
                println("[JULES] pagination token repeated at page ${seenPageTokens.size}; returning ${out.size} sessions")
                break
            }
        } while (pageToken != null)
        return out
    }

    /** Ordered activities for a session, each carrying its minted serial. */
    suspend fun activities(sessionId: String): List<ActivityInfo> =
        activityTimeline(sessionId).activities.view.toList()

    /**
     * Fetch the activity stream once and preserve every task-delta snapshot.
     * Every producer artifact is retained here. Unsafe repository/build paths
     * are review policy at drain time; filtering them before CAS would destroy
     * the exact evidence needed to diagnose a mixed source+scratch snapshot.
     */
    suspend fun activityTimeline(sessionId: String): ActivityTimeline {
        val raw = activityMaps(sessionId)
        val activityList = raw.mapIndexed { seq, m -> activityInfo(seq, m) }
        val reportList = activityList.asSequence()
            .filter { it.kind == "agentMessaged" && it.message.isNotEmpty() }
            .mapIndexed { causalOrdinal, activity ->
                ActivityReport(
                    activityId = activity.id,
                    activitySeq = activity.seq,
                    causalOrdinal = causalOrdinal,
                    createTime = activity.createTime,
                    message = activity.message,
                )
            }
            .toList()
        val activityPatches = raw.flatMapIndexed { seq, m ->
            val activityId = requireNotNull(
                m["name"]?.toString()?.substringAfterLast('/')?.takeIf { it.isNotBlank() },
            ) { "Jules activity entry for $sessionId has no name" }
            patchTexts(m).mapIndexed { artifactSeq, patch ->
                ActivityPatch(
                    activityId = activityId,
                    activitySeq = seq,
                    artifactSeq = artifactSeq,
                    causalOrdinal = 0,
                    createTime = m["createTime"]?.toString() ?: "",
                    patch = patch,
                )
            }
        }
        val outputPatches = sessionOutputsPatches(sessionId)
            .filterNot { output -> activityPatches.any { it.patch == output } }
            .mapIndexed { outputSeq, patch ->
                ActivityPatch(
                    activityId = "session-output-$outputSeq",
                    activitySeq = raw.size + outputSeq,
                    artifactSeq = outputSeq,
                    causalOrdinal = 0,
                    createTime = "",
                    patch = patch,
                )
            }
        val patchList = (activityPatches + outputPatches)
            .mapIndexed { causalOrdinal, patch -> patch.copy(causalOrdinal = causalOrdinal) }
        return ActivityTimeline(
            activities = activityList.toSeries(),
            reports = reportList.toSeries(),
            patches = patchList.toSeries(),
        )
    }

    private fun activityInfo(seq: Int, m: Map<*, *>): ActivityInfo {
        val activityId = requireNotNull(
            m["name"]?.toString()?.substringAfterLast('/')?.takeIf { it.isNotBlank() },
        ) { "Jules activity entry at sequence $seq has no name" }
        val kindWithoutPatch = listOf("agentMessaged", "userMessaged", "planGenerated", "progressUpdated")
            .firstOrNull(m::containsKey) ?: "unknown"
        val msgBody = when (kindWithoutPatch) {
            "agentMessaged" -> (m["agentMessaged"] as? Map<*, *>)
                ?.get("agentMessage")?.toString()?.let(::jsonUnescape)
            "userMessaged" -> (m["userMessaged"] as? Map<*, *>)
                ?.get("userMessage")?.toString()?.let(::jsonUnescape)
            "progressUpdated" -> (m["progressUpdated"] as? Map<*, *>)?.let { p ->
                listOfNotNull(
                    p["title"]?.toString()?.let(::jsonUnescape),
                    p["description"]?.toString()?.let(::jsonUnescape),
                )
                    .joinToString(": ")
                    .takeIf { it.isNotBlank() }
            }
            else -> null
        }
        val activityPatches = patchTexts(m)
        val kind = if (activityPatches.isNotEmpty() && kindWithoutPatch == "unknown") "artifacts"
            else kindWithoutPatch
        return ActivityInfo(
            id = activityId,
            seq = seq,
            createTime = m["createTime"]?.toString() ?: "",
            originator = m["originator"]?.toString() ?: "unknown",
            kind = kind,
            patchBytes = activityPatches.lastOrNull()?.length?.toLong() ?: 0L,
            excerpt = msgBody?.take(140) ?: "",
            message = msgBody.orEmpty(),
        )
    }

    /** Byte length of the latest cumulative patch. */
    suspend fun patchProbe(sessionId: String): Long = lastPatch(sessionId)?.length?.toLong() ?: 0L

    /** Latest exact API patch, including session-output fallback observations. */
    suspend fun lastPatch(sessionId: String): String? {
        val patches = activityTimeline(sessionId).patches
        return if (patches.size == 0) null else patches[patches.size - 1].patch
    }

    /** Fetch the session resource and read outputs[*].changeSet.gitPatch.unidiffPatch. */
    private suspend fun sessionOutputsPatches(sessionId: String): List<String> {
        val out = mutableListOf<String>()
        val parsed = try {
            JsonSupport.parse(retryingGet("/sessions/$sessionId")) as? Map<*, *>
        } catch (ex: Exception) {
            throw RuntimeException("Jules session $sessionId fetch failed: ${ex.message}", ex)
        }
        requireNotNull(parsed) { "Jules session $sessionId response is not an object" }
        // Use a safe read so a Jules API schema change (outputs=null, outputs={}, etc.)
        // does not throw — missing or malformed outputs just means no output patches.
        val outputsRaw = parsed["outputs"]
        val outputsList: List<*> = when (outputsRaw) {
            is List<*> -> outputsRaw
            else -> emptyList<Any?>()
        }
        for (output in outputsList) {
            val outputMap = requireNotNull(output as? Map<*, *>) {
                "Jules session output for $sessionId is not an object"
            }
            val rawChangeSet = outputMap["changeSet"] ?: continue
            val changeSet = requireNotNull(rawChangeSet as? Map<*, *>) {
                "Jules session output changeSet for $sessionId is not an object"
            }
            gitPatchText(changeSet)?.let { out += it }
        }
        return out
    }

    /** Fetch every chronological activity page; sequence numbers are minted afterwards. */
    private suspend fun activityMaps(sessionId: String): List<Map<*, *>> {
        val out = mutableListOf<Map<*, *>>()
        var pageToken: String? = null
        val seenPageTokens = mutableSetOf<String>()
        do {
            val path = buildString {
                append("/sessions/$sessionId/activities?pageSize=100")
                if (!pageToken.isNullOrEmpty()) append("&pageToken=${percentEncode(pageToken)}")
            }
            val parsed = try {
                JsonSupport.parse(retryingGet(path)) as? Map<*, *>
            } catch (ex: Exception) {
                throw RuntimeException("Jules activity page for $sessionId failed: ${ex.message}", ex)
            }
            requireNotNull(parsed) { "Jules activity page for $sessionId is not an object" }
            // Safe read: if the Jules API schema changes and activities is not an
            // array (null, {}, scalar), skip gracefully instead of throwing.
            val activitiesRaw = parsed["activities"]
            val page: List<*> = when (activitiesRaw) {
                is List<*> -> activitiesRaw
                else -> emptyList<Any?>()
            }
            page.forEach { activity ->
                out += requireNotNull(activity as? Map<*, *>) {
                    "Jules activity entry for $sessionId is not an object"
                }
            }
            // Defensive nextPageToken read: skip malformed tokens.
            val rawNextToken = parsed["nextPageToken"]
            pageToken = when (rawNextToken) {
                is String -> jsonUnescape(rawNextToken).takeIf { it.isNotBlank() }
                else -> null
            }
            if (pageToken != null && !seenPageTokens.add(pageToken)) {
                println("[JULES] activity pagination token repeated for session $sessionId; returning ${out.size} activities")
                break
            }
        } while (pageToken != null)
        return out.sortedWith(compareBy<Map<*, *>>(
            { it["createTime"]?.toString().orEmpty() },
            { it["name"]?.toString().orEmpty() },
        ))
    }

    /** Normalize both live gitPatch shapes via [gitPatchText]. */
    private fun patchTexts(activity: Map<*, *>): List<String> {
        val out = mutableListOf<String>()
        val artifacts = activity.optionalList("artifacts", "Jules activity")
        for (artifact in artifacts) {
            val artifactMap = requireNotNull(artifact as? Map<*, *>) {
                "Jules activity artifact is not an object"
            }
            val rawChangeSet = artifactMap["changeSet"] ?: continue
            val changeSet = requireNotNull(rawChangeSet as? Map<*, *>) {
                "Jules activity changeSet is not an object"
            }
            gitPatchText(changeSet)?.let { out += it }
        }
        return out
    }

    /** Extract the diff text from a changeSet map: string or {unidiffPatch: string}. */
    private fun gitPatchText(changeSet: Map<*, *>): String? {
        val patch = when (val gitPatch = changeSet["gitPatch"]) {
            null -> null
            is String -> gitPatch
            is Map<*, *> -> gitPatch["unidiffPatch"] as? String
            else -> error("Jules gitPatch has unsupported shape ${gitPatch::class.simpleName}")
        }
        return patch?.takeIf { it.isNotEmpty() }?.let(::jsonUnescape)
    }

    private fun Map<*, *>.optionalList(key: String, owner: String): List<*> {
        val value = this[key] ?: return emptyList<Any?>()
        return requireNotNull(value as? List<*>) { "$owner field '$key' is not an array" }
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
        val inner = if (s.startsWith("\"") && s.endsWith("\"") && s.length >= 2)
            s.substring(1, s.length - 1) else s
        return borg.trikeshed.util.jsonUnescape(inner)
    }

    /**
     * Send a message to an AWAITING session. Returns the created activity's id
     * (the dedup anchor — without it the next poll would double-count our own
     * answer as a fresh user event), or null if the response carried no id.
     */
    suspend fun sendMessage(sessionId: String, message: String): String? {
        val resp = retryingPost("/sessions/$sessionId:sendMessage", """{"prompt": ${jsonString(message)}}""")
        val parsed = try { JsonSupport.parse(resp) as? Map<*, *> } catch (_: Exception) { null }
        return parsed?.get("name")?.toString()?.substringAfterLast('/')
    }

    /**
     * Approve the session's latest plan (AWAITING_PLAN_APPROVAL → execution).
     * The API encodes the empty request as `{}`; any non-2xx throws from the
     * transport. The response body is not consulted — the sign-off is recorded
     * on the card's cause log by the conductor.
     */
    suspend fun approvePlan(sessionId: String) {
        retryingPost("/sessions/$sessionId:approvePlan", "{}")
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
        val resp = retryingPost("/sessions", body)
        val parsed = try { JsonSupport.parse(resp) as? Map<*, *> }
            catch (ex: Exception) { throw RuntimeException("createSession: bad response: ${ex.message}", ex) }
            ?: error("createSession: bad response")
        return parsed["name"]?.toString()?.substringAfterLast('/')
            ?: error("createSession: no id in response")
    }

    /** Archive a settled session while preserving its conversation and outputs. */
    suspend fun archiveSession(sessionId: String) {
        retryingPost("/sessions/$sessionId:archive", "{}")
    }

    /** Permanently delete a session. Reserved for explicit operator actions. */
    suspend fun deleteSession(sessionId: String) {
        transport().delete("/sessions/$sessionId")
    }

    private suspend fun post(path: String, json: String): String = retryingPost(path, json)

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

    /** URL form encoding without the JVM-only URLEncoder boundary. */
    private fun percentEncode(s: String): String {
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
                        append(byteArrayOf(bytes[i]).toUpperHex())
                    }
                }
            }
        }
    }
}
