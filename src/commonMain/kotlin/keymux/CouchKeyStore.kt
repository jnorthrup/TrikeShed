package keymux

import borg.trikeshed.couch.CouchDatabase
import borg.trikeshed.lib.get
import borg.trikeshed.lib.size

/**
 * CouchKeyStore — manual credentials persisted in CouchDB, resolved by KeyMux.
 *
 * Documents live under `credential:<provider>` in a dedicated CouchDatabase.
 * Each doc holds {key, base_url, api_type} — the three fields BrainClient's
 * routing seams need. The KeyMux binding `llm.*.key` / `llm.*.base_url` resolves
 * through this source when the harness/env/hermes lanes miss.
 *
 * Usage in daemon wiring:
 * ```
 * val credDb = CouchDatabase("credentials", couchStore, casStore)
 * val couchKeyStore = CouchKeyStore(credDb)
 * val keyMux = KeyMux {
 *     bind("llm.*.*", couchKeyStore)   // manual credentials (CouchDB)
 *     cached("*", harnessSource)       // env + hermes .env + auth.json
 *     cached("*", EnvSource())         // raw env fallback
 * }
 * ```
 *
 * Also usable standalone (no KeyMux) by LCNC nodes that read/write directly.
 */
class CouchKeyStore(
    private val db: CouchDatabase,
) : KeySource() {
    override val name = "couch-credential"

    /**
     * Resolve `llm.<provider>.key` or `llm.<provider>.base_url` from CouchDB.
     */
    override suspend fun read(path: KeyPath): String? {
        if (path.size != 3 || path[0] != "llm") return null
        val provider = path[1]
        val field = path[2]
        val doc = db.docJson(credentialId(provider)) ?: return null
        return when (field) {
            "key" -> doc["key"] as? String
            "base_url" -> doc["base_url"] as? String
            "api_type" -> doc["api_type"] as? String
            else -> null
        }
    }

    /**
     * Store a credential: `llm.<provider>.key` → persists {key, base_url, api_type}.
     * The path's field selects which value to update; the whole document is
     * upserted so all three fields are always present.
     */
    override suspend fun write(path: KeyPath, value: String) {
        if (path.size != 3 || path[0] != "llm") return
        val provider = path[1]
        val field = path[2]
        val id = credentialId(provider)
        val existing = db.docJson(id) ?: emptyMap()
        val body = existing.toMutableMap()
        body[field] = value
        body["provider"] = provider
        db.put(id, body, existing["_rev"] as? String)
    }

    /**
     * Bulk store all three fields at once — the LCNC credential.enter node
     * calls this instead of three separate write() calls.
     */
    suspend fun storeCredential(provider: String, key: String, baseUrl: String, apiType: String) {
        val id = credentialId(provider)
        val existing = db.docJson(id) ?: emptyMap()
        val body = linkedMapOf<String, Any?>(
            "provider" to provider,
            "key" to key,
            "base_url" to baseUrl,
            "api_type" to apiType,
        )
        db.put(id, body, existing["_rev"] as? String)
    }

    /**
     * Read back a stored credential as a flat map — for LCNC nodes that
     * need all three fields at once (prompt.chat).
     */
    suspend fun readCredential(provider: String): Map<String, String?>? {
        val doc = db.docJson(credentialId(provider)) ?: return null
        return mapOf(
            "key" to (doc["key"] as? String),
            "base_url" to (doc["base_url"] as? String),
            "api_type" to (doc["api_type"] as? String),
        )
    }

    /** List all stored credential providers. */
    fun listProviders(): List<String> {
        val allDocs = db.allDocs(includeDocs = true, limit = 1000)
        @Suppress("UNCHECKED_CAST")
        val rows = allDocs["rows"] as? List<Map<String, Any?>> ?: return emptyList()
        return rows.mapNotNull { row ->
            val doc = row["doc"] as? Map<String, Any?> ?: return@mapNotNull null
            if ((row["id"] as? String)?.startsWith("credential:") == true)
                doc["provider"] as? String
            else null
        }
    }

    override suspend fun invalidate() {} // CouchDatabase is the source of truth

    private fun credentialId(provider: String): String = "credential:$provider"
}
