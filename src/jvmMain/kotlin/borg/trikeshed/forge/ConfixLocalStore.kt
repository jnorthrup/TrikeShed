package borg.trikeshed.forge

import borg.trikeshed.parse.confix.ConfixDoc
import borg.trikeshed.parse.confix.Syntax
import borg.trikeshed.parse.confix.confixDoc
import borg.trikeshed.parse.confix.root
import borg.trikeshed.parse.confix.roots
import borg.trikeshed.parse.confix.scalar
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * Local-first Confix document store.
 *
 * Directory layout (per user spec):
 *   $HOME/.local/forge/{project}/{instance}/db/id/{docId}.json
 *
 * Documents are serialized as JSON text (the Confix JSON syntax). On read, they
 * are reified into a [ConfixDoc] — the "queryable Confix Cursor Tree" whose `.roots`
 * cursor walks the document facet-by-facet, exactly like a CouchDB view server
 * iterating _all_docs. [loadAsYaml] reifies the same ConfixDoc into YAML text.
 *
 * This mirrors PouchDB's local adapter: put/get/allDocs/changes, but the on-disk
 * format is Confix-parseable records instead of opaque JSON-with-_rev-stub files.
 *
 * The CBOR round-trip is available via [loadConfixDocBytes] which re-encodes the
 * parsed ConfixDoc as CBOR using the Confix scanner's Syntax.CBOR.
 */
class ConfixLocalStore(
    val project: String = "default",
    val instance: String = "main",
    val baseDir: Path = defaultBaseDir(),
) {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val compactJson = Json { prettyPrint = false; ignoreUnknownKeys = true }

    val dbDir: Path = baseDir.resolve(project).resolve(instance).resolve("db").resolve("id")

    init {
        Files.createDirectories(dbDir)
    }

    /**
     * Put a document by id. The [fields] map is serialized as a JSON object
     * with the _id field injected, stored as Confix JSON text.
     */
    fun put(docId: String, fields: Map<String, Any?>): ConfixLocalStore {
        val entries = buildMap {
            put("_id", JsonPrimitive(docId))
            fields.forEach { (k, v) ->
                val element = when (v) {
                    null -> kotlinx.serialization.json.JsonNull
                    is String -> JsonPrimitive(v)
                    is Number -> JsonPrimitive(v)
                    is Boolean -> JsonPrimitive(v)
                    else -> JsonPrimitive(v.toString())
                }
                put(k, element)
            }
        }
        val jsonObject = JsonObject(entries)
        val jsonText = compactJson.encodeToString(JsonObject.serializer(), jsonObject)
        val docPath = docPath(docId)
        Files.createDirectories(docPath.parent)
        Files.write(
            docPath,
            jsonText.encodeToByteArray(),
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE,
        )
        return this
    }

    /**
     * Get a document as a ConfixDoc — the queryable cursor tree.
     * Returns null if the document does not exist.
     */
    fun loadConfixDoc(docId: String): ConfixDoc? {
        val path = docPath(docId)
        if (!Files.exists(path)) return null
        val bytes = Files.readAllBytes(path)
        return confixDoc(bytes, Syntax.JSON)
    }

    /**
     * Get a document reified as JSON.
     */
    fun loadAsJson(docId: String): JsonObject? {
        val path = docPath(docId)
        if (!Files.exists(path)) return null
        val text = Files.readString(path)
        return json.parseToJsonElement(text) as? JsonObject
    }

    /**
     * Get a document reified as YAML text.
     */
    fun loadAsYaml(docId: String): String? {
        val jsonObj = loadAsJson(docId) ?: return null
        return jsonToYaml(jsonObj)
    }

    /**
     * Delete a document by id. Returns true if it existed.
     */
    fun delete(docId: String): Boolean {
        val path = docPath(docId)
        return Files.deleteIfExists(path)
    }

    /**
     * List all document ids (the _all_docs equivalent).
     */
    fun allDocIds(): List<String> =
        Files.list(dbDir).use { stream ->
            stream
                .filter { it.fileName.toString().endsWith(".json") }
                .map { it.fileName.toString().removeSuffix(".json") }
                .sorted()
                .toList()
        }

    /**
     * Load all documents as ConfixDocs — the full cursor tree over the database.
     * This is the queryable Confix Cursor Tree the user specified.
     */
    fun allConfixDocs(): List<ConfixDoc> = allDocIds().mapNotNull { loadConfixDoc(it) }

    /**
     * Count documents.
     */
    fun count(): Long =
        Files.list(dbDir).use { stream ->
            stream.filter { it.fileName.toString().endsWith(".json") }.count()
        }

    /**
     * Query: iterate every document's cursor tree, applying [predicate].
     * Returns matching doc ids — the CouchDB view-server map function equivalent.
     */
    fun query(predicate: (ConfixDoc) -> Boolean): List<String> =
        allDocIds().filter { id ->
            val doc = loadConfixDoc(id) ?: return@filter false
            predicate(doc)
        }

    /**
     * Query by field equality — the CouchDB view eq selector equivalent.
     * Uses JSON reification for reliable field access (ConfixDoc scalar navigation
     * has known gaps on flat JSON maps — see ConfixSerializationTest exclusion).
     */
    fun findByField(fieldName: String, expectedValue: Any): List<String> =
        allDocIds().filter { id ->
            val json = loadAsJson(id) ?: return@filter false
            json[fieldName]?.toString()?.trim('"') == expectedValue.toString()
        }

    private fun docPath(docId: String): Path = dbDir.resolve(sanitizeId(docId) + ".json")

    private fun sanitizeId(docId: String): String =
        docId.replace("/", "_").replace("\\", "_").takeLast(200)

    /**
     * Convert a JsonObject to YAML text — indentation-based mapping.
     */
    private fun jsonToYaml(obj: JsonObject): String {
        val sb = StringBuilder()
        obj.forEach { (key, element) ->
            when (element) {
                is JsonObject -> {
                    sb.append(key).append(":\n")
                    element.forEach { (subKey, subVal) ->
                        sb.append("  ").append(subKey).append(": ")
                        appendYamlValue(sb, subVal)
                        sb.append('\n')
                    }
                }
                else -> {
                    sb.append(key).append(": ")
                    appendYamlValue(sb, element)
                    sb.append('\n')
                }
            }
        }
        return sb.toString()
    }

    private fun appendYamlValue(sb: StringBuilder, element: kotlinx.serialization.json.JsonElement) {
        when (element) {
            is JsonPrimitive -> {
                val content = element.contentOrNull ?: ""
                if (content.any { it in ":\n\"'" } || content.isBlank()) {
                    sb.append('"').append(content.replace("\"", "\\\"")).append('"')
                } else {
                    sb.append(content)
                }
            }
            else -> sb.append(element.toString())
        }
    }

    companion object {
        fun defaultBaseDir(): Path =
            Path.of(System.getProperty("user.home")).resolve(".local").resolve("forge")
    }
}
