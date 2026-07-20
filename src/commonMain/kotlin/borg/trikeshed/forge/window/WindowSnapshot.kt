package borg.trikeshed.forge.window

data class WindowSnapshot(
    val timestampMillis: Long,
    val dom: String,
    val boundScripts: List<String>,
    val dispatchedEvents: List<WindowEvent>,
    val isNoop: Boolean = false,
)
