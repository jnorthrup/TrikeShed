package borg.trikeshed.miniduck.columnar

object IndexPluginRegistry {
    fun resolve(name: String): IndexPlugin {
        return when (name) {
            "ZranIndex" -> ZranIndex()
            "Lz4Index" -> Lz4Index()
            else -> throw IllegalArgumentException("Unknown index plugin: $name")
        }
    }
}
