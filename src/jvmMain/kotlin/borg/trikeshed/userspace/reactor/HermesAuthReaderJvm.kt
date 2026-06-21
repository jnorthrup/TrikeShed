package borg.trikeshed.userspace.reactor

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.nio.file.Files
import java.nio.file.Path

/**
 * JVM-only reader for ~/.hermes/auth.json as DATA.
 *
 * This is NOT a Hermes Agent feature integration. It is a plain stdlib file
 * read of the local credential pool JSON that Hermes Agent happens to write.
 * No Python, no Hermes CLI, no gateway, no messaging platform.
 */
object HermesAuthReaderJvm {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    /** Read the credential pool at [path] and decode to typed records. */
    fun readCredentialPool(path: Path): Map<String, List<MuxCredentialRecord>> {
        if (!Files.exists(path)) return emptyMap()
        val text = Files.readString(path)
        return decodeCredentialPool(text)
    }

    /** Decode a credential-pool JSON string to typed records. */
    fun decodeCredentialPool(jsonText: String): Map<String, List<MuxCredentialRecord>> {
        if (jsonText.isBlank()) return emptyMap()
        val root = json.parseToJsonElement(jsonText)
        val pool = (root as? JsonObject)?.get("credential_pool") as? JsonObject ?: JsonObject(emptyMap())
        val out = mutableMapOf<String, MutableList<MuxCredentialRecord>>()
        for ((provider, value) in pool) {
            val entries = (value as? JsonArray) ?: continue
            for (entry in entries) {
                val obj = entry as? JsonObject ?: continue
                val id = (obj["id"] as? JsonPrimitive)?.contentOrNull ?: continue
                val status = (obj["last_status"] as? JsonPrimitive)?.contentOrNull
                if (status == "benched") continue
                out.getOrPut(provider) { mutableListOf() }.add(
                    MuxCredentialRecord(
                        id = id,
                        label = (obj["label"] as? JsonPrimitive)?.contentOrNull ?: id,
                        baseUrl = (obj["base_url"] as? JsonPrimitive)?.contentOrNull ?: "",
                        lastStatus = status,
                        lastModel = (obj["last_model"] as? JsonPrimitive)?.contentOrNull
                            ?: (obj["last_success_model"] as? JsonPrimitive)?.contentOrNull,
                        lastSuccessModel = (obj["last_success_model"] as? JsonPrimitive)?.contentOrNull,
                        lastUsedAt = (obj["last_used_at"] as? JsonPrimitive)?.longOrNull ?: 0L,
                        requestCount = (obj["request_count"] as? JsonPrimitive)?.longOrNull ?: 0L,
                    ),
                )
            }
        }
        return out
    }
}