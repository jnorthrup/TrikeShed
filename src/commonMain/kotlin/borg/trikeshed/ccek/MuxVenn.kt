package borg.trikeshed.ccek

data class MuxVenn(
    val keyLinkedProviders: Set<String>,
    val discoverableModels: Map<String, String>,
    val muxableProviders: Set<String>,
) {
    val runnableNow: Set<String>
        get() = discoverableModels
            .filterValues { provider -> provider in keyLinkedProviders && provider in muxableProviders }
            .keys

    fun document(): MuxVennDocument = MuxVennDocument(
        linkedProviders = keyLinkedProviders.sorted(),
        discoveredModelCount = discoverableModels.size,
        muxableProviders = muxableProviders.sorted(),
        runnableModels = runnableNow.sorted(),
    )
}

data class MuxVennDocument(
    val linkedProviders: List<String>,
    val discoveredModelCount: Int,
    val muxableProviders: List<String>,
    val runnableModels: List<String>,
)
