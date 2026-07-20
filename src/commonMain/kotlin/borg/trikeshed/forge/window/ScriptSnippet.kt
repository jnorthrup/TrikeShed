package borg.trikeshed.forge.window

enum class RunAt {
    DOMReady,
    Immediate,
    WindowLoad
}

data class ScriptSnippet(
    val id: String,
    val source: String,
    val runAt: RunAt = RunAt.DOMReady,
    val dependencies: List<String> = emptyList(),
) {
    init { require(source.isNotBlank()) { "script source must not be blank" } }
    init { require(id.isNotBlank()) { "script id must not be blank" } }
    init { require(source.length <= 65_536) { "script source > 65536 bytes" } }
}
