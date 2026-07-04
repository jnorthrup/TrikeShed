package borg.trikeshed.userspace.reactor

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path

/**
 * JVM-only persistence for the ModelApiCache.
 *
 * Reads and writes ~/.hermes/model_cache.json as DATA. No Python, no Hermes
 * CLI, no gateway, no messaging platform.
 *
 * File shape:
 * {
 *   "entries": [
 *     {"key": "...", "provider": "...", "modelId": "...", "storedAtMs": 0, "hits": 0, "payload": "..."}
 *   ]
 * }
 */

/**
 * Serialization container for CacheEntry list.
 * Must be top-level for kotlinx.serialization plugin to generate serializer.
 */
@Serializable
data class CacheSnapshot(val entries: List<CacheEntry>)

object CacheStoreJvm {

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    fun loadEntries(path: Path): List<CacheEntry> {
        if (!Files.exists(path)) return emptyList()
        val text = Files.readString(path)
        return decodeEntries(text)
    }

    fun saveEntries(path: Path, entries: List<CacheEntry>) {
        val container = CacheSnapshot(entries)
        Files.createDirectories(path.parent ?: Path.of("."))
        Files.writeString(path, json.encodeToString(container))
    }

    fun decodeEntries(text: String): List<CacheEntry> {
        if (text.isBlank()) return emptyList()
        val root = json.parseToJsonElement(text)
        val obj = root as? kotlinx.serialization.json.JsonObject ?: return emptyList()
        val entries = (obj["entries"] as? kotlinx.serialization.json.JsonArray) ?: return emptyList()
        return entries.mapNotNull { el ->
            val entryObj = el as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
            val key = entryObj["key"]?.toString()?.trim('"') ?: return@mapNotNull null
            val provider = entryObj["provider"]?.toString()?.trim('"') ?: ""
            val modelId = entryObj["modelId"]?.toString()?.trim('"') ?: ""
            val storedAtMs = entryObj["storedAtMs"]?.toString()?.toLongOrNull() ?: 0L
            val hits = entryObj["hits"]?.toString()?.toLongOrNull() ?: 0L
            // Properly decode the payload string from JSON (handles escaped quotes)
            val payloadJson = entryObj["payload"]
            val payload = if (payloadJson is kotlinx.serialization.json.JsonPrimitive) {
                payloadJson.content
            } else {
                payloadJson?.toString()?.trim('"') ?: ""
            }
            CacheEntry(
                key = key,
                provider = provider,
                modelId = modelId,
                storedAtMs = storedAtMs,
                hits = hits,
                payload = payload,
            )
        }
    }
}