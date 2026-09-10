package borg.trikeshed.graal

/** Credential classification shared by Graal document and byte-preview projections. */
object GraalDocumentPolicy {
    val SENSITIVE_ID = Regex("""(^|[/:._-])(credential|credentials|secret|secrets|token|tokens|apikey|api-key|api_key|keymux)([/:._-]|$)""", RegexOption.IGNORE_CASE)
    val SAFE_FIELD_NAMES = setOf(
            "id", "rev", "type", "kind", "name", "label", "provider", "model", "contenttype",
            "length", "agentid", "revision", "sequence", "code", "codering8",
        )
    val SENSITIVE_FIELD_NAMES = setOf(
            "apikey", "apitoken", "accesskey", "accesskeyid", "secretkey", "clientsecret",
            "token", "refreshtoken", "accesstoken", "password", "authorization", "bearertoken",
            "privatekey", "providerkey", "credential", "credentials", "secret",
        )
    val SECRET_TEXT_PATTERNS = listOf(
            Regex("""(?i)\bBearer\s+[A-Za-z0-9._+/=-]{16,}"""),
            Regex("""(?i)\b(sk|rk|pk|ghp|github_pat|xox[baprs]|ya29|AIza)[A-Za-z0-9._-]{12,}"""),
        )

    fun sensitiveDocId(id: String): Boolean {
        val normalized = id.replace('\\', '/').lowercase()
        val file = normalized.substringAfterLast('/')
        return file == ".env" || file.startsWith(".env.") || file.endsWith(".env") || SENSITIVE_ID.containsMatchIn(normalized)
    }

    fun sensitiveFieldName(name: String, documentSensitive: Boolean): Boolean {
        val compact = name.filter { it.isLetterOrDigit() }.lowercase()
        if (compact in SAFE_FIELD_NAMES) return false
        return compact in SENSITIVE_FIELD_NAMES || compact.contains("apikey") || compact.contains("token") ||
            compact.contains("password") || compact.contains("secret") || compact.contains("credential") ||
            compact.contains("authorization") || compact.endsWith("privatekey") || compact.endsWith("providerkey") ||
            documentSensitive && compact == "key"
    }

    fun looksSecretText(value: String): Boolean = value.length >= 16 && SECRET_TEXT_PATTERNS.any { it.containsMatchIn(value) }

    fun containsSecretText(value: Any?): Boolean = when (value) {
        is String -> looksSecretText(value)
        is Map<*, *> -> value.any { (key, item) -> sensitiveFieldName(key?.toString().orEmpty(), true) || containsSecretText(item) }
        is List<*> -> value.any { containsSecretText(it) }
        else -> false
    }

    fun sensitiveDocument(id: String, raw: Map<String, Any?>): Boolean = sensitiveDocId(id) ||
        raw.keys.any { sensitiveFieldName(it, true) } || raw.values.any { containsSecretText(it) }
}
