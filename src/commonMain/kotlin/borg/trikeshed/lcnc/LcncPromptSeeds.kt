package borg.trikeshed.lcnc

/**
 * The prompts the presets read, authored once here and installed at boot.
 *
 * A seed yields to the person: it is installed as the head of its name only
 * when that name has no head yet, so an edited `hello` stays edited across a
 * restart. Its bytes always go into the store, so a receipt that cites the
 * seed's cid resolves whether or not the head has moved on.
 */
object LcncPromptSeeds {
    const val HELLO = "hello"
    const val SUMMARIZE = "summarize"

    fun all(): List<PromptDocument> = listOf(
        PromptDocument(HELLO, "Say hello in one sentence.", tags = listOf("preset-brain-mux")),
        PromptDocument(SUMMARIZE, "Summarise this document in three sentences.", tags = listOf("preset-corpus")),
    )

    fun byName(name: String): PromptDocument? = all().firstOrNull { it.name == name }
}
