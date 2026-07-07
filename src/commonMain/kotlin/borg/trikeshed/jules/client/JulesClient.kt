package borg.trikeshed.jules.client

import borg.trikeshed.htx.HtxElement
import borg.trikeshed.htx.HtxKey
import borg.trikeshed.htx.HtxResponse
import borg.trikeshed.htx.HtxRequest
import borg.trikeshed.htx.HtxMethod
import borg.trikeshed.htx.parseHtxRequest
import borg.trikeshed.htx.htxHeaders
import borg.trikeshed.lib.ByteSeries
import borg.trikeshed.lib.asString
import borg.trikeshed.lib.j
import borg.trikeshed.lib.toSeries
import kotlin.coroutines.CoroutineContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString

@Serializable
data class JulesSession(
    val name: String,
    val id: String,
    val prompt: String? = null,
    val title: String? = null,
    val requirePlanApproval: Boolean? = null,
    val createTime: String? = null,
    val updateTime: String? = null,
    val url: String? = null
)

@Serializable
data class JulesActivity(
    val name: String? = null,
    val type: String? = null,
    val status: String? = null
)

@Serializable
data class JulesSource(
    val name: String? = null,
    val content: String? = null
)

class JulesClient(val context: CoroutineContext, val apiKey: String) {
    private val json = Json { ignoreUnknownKeys = true }

    private fun getHtx(): HtxElement {
        return requireNotNull(context[HtxKey]) { "Expected HtxKey in coroutine context" }
    }

    private suspend fun executeRequest(method: String, path: String, body: String = ""): String {
        val htx = getHtx()
        // Ensure proper routing for Jules
        val finalPath = if (!path.contains("https://")) "https://jules.googleapis.com$path" else path

        // Inject the API key into the final path
        val requestPath = if (finalPath.contains("?")) "${finalPath}&key=$apiKey" else "${finalPath}?key=$apiKey"

        val htxMethod = when (method.uppercase()) {
            "GET" -> HtxMethod.GET
            "POST" -> HtxMethod.POST
            "PUT" -> HtxMethod.PUT
            "DELETE" -> HtxMethod.DELETE
            "PATCH" -> HtxMethod.PATCH
            else -> HtxMethod.GET
        }

        val headerList = mutableListOf<borg.trikeshed.lib.Join<String, String>>()
        headerList.add("X-Goog-Api-Key" j apiKey)
        if (body.isNotEmpty()) {
            headerList.add("Content-Type" j "application/json")
            headerList.add("Content-Length" j body.encodeToByteArray().size.toString())
        } else if (htxMethod == HtxMethod.POST || htxMethod == HtxMethod.PUT || htxMethod == HtxMethod.PATCH) {
            headerList.add("Content-Length" j "0")
        }
        val requestHeaders = headerList.toSeries()

        val request = parseHtxRequest(
            url = requestPath,
            method = htxMethod,
            body = ByteSeries(body.encodeToByteArray())
        ).copy(headers = requestHeaders)

        val response = htx.request(request)
        check(response.status in 200..299) { 
            "Jules API error: ${response.status} - ${response.body.asString()}" 
        }
        return response.body.asString()
    }

    suspend fun createSession(prompt: String? = null, title: String? = null, requirePlanApproval: Boolean? = null): JulesSession {
        val bodyObj = mutableMapOf<String, String>()
        if (prompt != null) bodyObj["prompt"] = prompt
        if (title != null) bodyObj["title"] = title
        if (requirePlanApproval != null) bodyObj["requirePlanApproval"] = requirePlanApproval.toString()
        val requestBody = json.encodeToString(bodyObj)

        val responseBody = executeRequest("POST", "/v1alpha/sessions", requestBody)
        return json.decodeFromString<JulesSession>(responseBody)
    }

    suspend fun listSessions(): List<JulesSession> {
        val responseBody = executeRequest("GET", "/v1alpha/sessions")
        if (responseBody.isBlank()) return emptyList()
        val wrapper = json.decodeFromString<JulesSessionsWrapper>(responseBody)
        return wrapper.sessions
    }

    suspend fun getSession(name: String): JulesSession {
        val responseBody = executeRequest("GET", "/v1alpha/$name")
        return json.decodeFromString<JulesSession>(responseBody)
    }

    suspend fun approvePlan(sessionName: String) {
        executeRequest("POST", "/v1alpha/$sessionName:approvePlan")
    }

    suspend fun sendMessage(sessionName: String, message: String) {
        val bodyObj = mapOf("prompt" to message)
        val requestBody = json.encodeToString(bodyObj)
        executeRequest("POST", "/v1alpha/$sessionName:sendMessage", requestBody)
    }

    suspend fun listActivities(parentSessionName: String): List<JulesActivity> {
        val responseBody = executeRequest("GET", "/v1alpha/$parentSessionName/activities")
        if (responseBody.isBlank()) return emptyList()
        val wrapper = json.decodeFromString<JulesActivitiesWrapper>(responseBody)
        return wrapper.activities
    }

    suspend fun listSources(): List<JulesSource> {
        val responseBody = executeRequest("GET", "/v1alpha/sources")
        if (responseBody.isBlank()) return emptyList()
        val wrapper = json.decodeFromString<JulesSourcesWrapper>(responseBody)
        return wrapper.sources
    }
}

@Serializable
private data class JulesSessionsWrapper(val sessions: List<JulesSession> = emptyList())

@Serializable
private data class JulesActivitiesWrapper(val activities: List<JulesActivity> = emptyList())

@Serializable
private data class JulesSourcesWrapper(val sources: List<JulesSource> = emptyList())
