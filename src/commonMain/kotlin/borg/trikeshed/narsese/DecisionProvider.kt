package borg.trikeshed.narsese

import kotlin.coroutines.coroutineContext

/** System One providers sharing Jev's question and answer taxonomy over context-owned transports. */
enum class DecisionProvider(val id: String, val model: String, val base: String, val keyName: String) {
    JEVA("jeva", Jeva.MODEL, "", ""),
    JEV("jev", Jev.MODEL, Jev.BASE, "JEV_API_KEY"),
    OPENAI("openai-decision", Decisions.MODEL, Decisions.BASE, "OPENAI_API_KEY");

    val remote: Boolean get() = this != JEVA
    val requiresKey: Boolean get() = remote
    // OPENAI's persisted identity predates the dropdown's canonical public ID.
    val namespace: String get() = "${if (this == OPENAI) "openai" else id}-${model.replace('/', '-')}"
    /** Preserve the existing Jev corpus; alternative judgments have their own tables and sense memory. */
    val tableSuffix: String get() = if (this == JEV) "jev" else namespace

    suspend fun ask(key: String, state: Any, questions: Map<String, Map<String, Any?>>): Map<String, Any?> =
        when (this) {
            JEVA -> requireNotNull(coroutineContext[JevaElement]) { "Jeva runtime is not attached" }.ask(state, questions)
            JEV -> Jev.ask(key, state, questions)
            OPENAI -> Decisions.ask(key, state, questions)
        }

    companion object {
        const val PROPERTY = "trikeshed.curation.provider"
        const val ENVIRONMENT = "TRIKESHED_CURATION_PROVIDER"

        fun of(value: String?): DecisionProvider = when (val id = value?.trim()?.lowercase()) {
            null -> JEV
            "openai" -> OPENAI
            else -> entries.firstOrNull { it.id == id }
                ?: error("Curation provider must be jeva, jev or openai-decision: $value")
        }
    }
}
