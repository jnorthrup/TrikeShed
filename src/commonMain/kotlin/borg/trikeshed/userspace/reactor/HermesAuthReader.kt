package borg.trikeshed.userspace.reactor

import borg.trikeshed.userspace.nio.file.spi.FileOperations
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Multiplatform credential pool reader using FileOperations SPI.
 *
 * Reads the credential pool JSON from ~/.hermes/auth.json as DATA.
 * No Python, no Hermes CLI, no gateway, no messaging platform.
 */
object HermesAuthReader {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    /** Read the credential pool at [path] and decode to typed records. */
    suspend fun readCredentialPool(fileOps: FileOperations, path: String): Map<String, List<MuxCredentialRecord>> {
        if (!fileOps.exists(path)) return emptyMap()
        val text = fileOps.readString(path)
        return decodeCredentialPool(text)
    }

    /** Decode a credential-pool JSON string to typed records. */
    fun decodeCredentialPool(jsonText: String): Map<String, List<MuxCredentialRecord>> {
        if (jsonText.isBlank()) return emptyMap()
        val root = json.parseToJsonElement(jsonText)
        val pool = (root as? kotlinx.serialization.json.JsonObject)?.get("credential_pool") as? kotlinx.serialization.json.JsonObject ?: kotlinx.serialization.json.JsonObject(emptyMap())
        val out = mutableMapOf<String, MutableList<MuxCredentialRecord>>()
        for ((provider, value) in pool) {
            val entries = (value as? kotlinx.serialization.json.JsonArray) ?: continue
            for (entry in entries) {
                val obj = entry as? kotlinx.serialization.json.JsonObject ?: continue
                val id = (obj["id"] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull ?: continue
                val status = (obj["last_status"] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
                if (status == "benched") continue
                out.getOrPut(provider) { mutableListOf() }.add(
                    MuxCredentialRecord(
                        id = id,
                        label = (obj["label"] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull ?: id,
                        baseUrl = (obj["base_url"] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull ?: "",
                        lastStatus = status,
                        lastModel = (obj["last_model"] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
                            ?: (obj["last_success_model"] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull,
                        lastSuccessModel = (obj["last_success_model"] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull,
                        lastUsedAt = (obj["last_used_at"] as? kotlinx.serialization.json.JsonPrimitive)?.longOrNull ?: 0L,
                        requestCount = (obj["request_count"] as? kotlinx.serialization.json.JsonPrimitive)?.longOrNull ?: 0L,
                    ),
                )
            }
        }
        return out
    }
}
