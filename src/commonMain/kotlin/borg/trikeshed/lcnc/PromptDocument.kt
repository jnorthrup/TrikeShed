package borg.trikeshed.lcnc

import borg.trikeshed.job.ContentId
import borg.trikeshed.parse.json.JsonSupport

/**
 * A STORED PROMPT IS A CITIZEN.
 *
 * The post Forge answers asked for "a stored set of prompts". Until this file
 * every prompt in the tree was a string literal inside a preset: no name, no
 * version, no way to reuse one across programs, no way for a receipt to say
 * which prompt it ran. A prompt is now a document with a name, a role, a text
 * with `{{variables}}`, tags, and a content id minted from its canonical bytes
 * exactly the way [LcncBlackboard.cidOf] mints a program's — so
 * `GET /api/lcnc/content?cid=` serves a prompt version verbatim and the
 * inspector renders it with no new route.
 *
 * Lineage rides the document: [previousCid] is the head this version replaced,
 * the same discipline as a run receipt's `previousReceiptCid`.
 */
data class PromptDocument(
    val name: String,
    val text: String,
    val role: String = ROLE_USER,
    val tags: List<String> = emptyList(),
    val previousCid: String? = null,
) {
    /** The `{{variables}}` the text declares, in declared order, deduplicated. */
    val variables: List<String> get() = PromptTemplate.variables(text)

    /** Canonical bytes: a fixed key order, rendered by the one JSON writer. */
    fun canonicalJson(): String = JsonSupport.stringify(
        linkedMapOf<String, Any?>(
            "kind" to KIND,
            "name" to name,
            "role" to role,
            "text" to text,
            "variables" to variables,
            "tags" to tags,
            "previousCid" to previousCid,
        ),
    )

    /** The content id of [canonicalJson] — the version's identity. */
    val cid: String get() = ContentId.of(canonicalJson().encodeToByteArray()).value

    companion object {
        const val KIND = "lcnc.prompt/1"
        const val ROLE_USER = "user"
        const val ROLE_SYSTEM = "system"
        val ROLES: List<String> = listOf(ROLE_USER, ROLE_SYSTEM)
        /** The same name grammar a saved panel obeys. */
        val NAME: Regex = Regex("^[a-z0-9][a-z0-9._-]*$")
        /** One prompt is a paragraph or a page, never a corpus (the mux session's own cap). */
        const val MAX_CHARS = 32_768

        fun isValidName(name: String): Boolean = NAME.matches(name)

        /** A document read back from its canonical bytes; loud when the bytes are not a prompt. */
        fun fromJson(json: String): PromptDocument {
            val m = JsonSupport.parseMap(json)
            require(m["kind"] == KIND) { "not a prompt document: kind=${m["kind"]}" }
            val name = m["name"]?.toString() ?: error("prompt document without a name")
            return PromptDocument(
                name = name,
                text = m["text"]?.toString() ?: "",
                role = m["role"]?.toString() ?: ROLE_USER,
                tags = (m["tags"] as? List<*>)?.map { it.toString() } ?: emptyList(),
                previousCid = m["previousCid"]?.toString(),
            )
        }
    }
}

/** What a listing shows for one stored prompt: its head version, never the text of every version. */
data class PromptHead(
    val name: String,
    val cid: String,
    val previousCid: String?,
    val role: String,
    val variables: List<String>,
    val tags: List<String>,
    val chars: Int,
    val savedAtMs: Long,
) {
    fun toMap(): Map<String, Any?> = linkedMapOf(
        "name" to name, "cid" to cid, "previousCid" to previousCid, "role" to role,
        "variables" to variables, "tags" to tags, "chars" to chars, "savedAtMs" to savedAtMs,
    )
}

/**
 * `{{name}}` substitution. All or nothing: a template rendered with an unbound
 * variable throws naming every unbound one, because a prompt with a hole in it
 * sent to a model is the failure this object exists to end.
 */
object PromptTemplate {
    val VARIABLE: Regex = Regex("""\{\{\s*([A-Za-z_][A-Za-z0-9_.]*)\s*\}\}""")

    fun variables(text: String): List<String> =
        VARIABLE.findAll(text).map { it.groupValues[1] }.distinct().toList()

    fun render(text: String, args: Map<String, Any?>): String {
        val unbound = variables(text).filter { !args.containsKey(it) }
        require(unbound.isEmpty()) { "prompt.render: unbound " + unbound.joinToString(", ") { "{{$it}}" } }
        return VARIABLE.replace(text) { m -> valueText(args[m.groupValues[1]]) }
    }

    private fun valueText(v: Any?): String = when (v) {
        null -> "null"
        is String -> v
        is Number, is Boolean -> v.toString()
        else -> JsonSupport.stringify(v)
    }
}
