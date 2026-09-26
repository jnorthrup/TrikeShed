package borg.trikeshed.graal.console

/** Folder drop lanes: host-path mount (uri-list) and the entry-API walk uploaded as framed putBatch bodies. */
object Ingest {
    val PROJECT_MARKERS = Regex("^(settings\\.gradle(\\.kts)?|build\\.gradle(\\.kts)?|package\\.json|Cargo\\.toml|CMakeLists\\.txt|Makefile|pom\\.xml|go\\.mod|pyproject\\.toml|setup\\.py|mix\\.exs|Package\\.swift|.*\\.xcodeproj/.*)$")
    val SKIP_DIRS = setOf("node_modules", "build", "target", "dist", "out", "__pycache__", "venv", ".venv", ".git", ".gradle", ".idea", "DerivedData")

    /** Files above this are skipped (request cap ~4MB). */
    const val FILE_CAP = 3500000
    /** A batch flushes past this many packed bytes … */
    const val BATCH_BYTES = 3000000
    /** … or at this many frame parts. */
    const val BATCH_PARTS = 256

    /** Chrome hides .git from the walker — classify by build-system markers instead. */
    fun kind(rels: List<String>): String = if (rels.any { PROJECT_MARKERS.matches(it) }) "project" else "assets"

    /** A putBatch frame is: int32 BE path length, UTF-8 path, int64 BE byte length, bytes. */
    fun int32(n: Int): ByteArray = byteArrayOf((n ushr 24).toByte(), (n ushr 16).toByte(), (n ushr 8).toByte(), n.toByte())

    fun int64(len: Long): ByteArray = ByteArray(8) { i -> (len ushr (56 - 8 * i)).toByte() }

    /** The db name the daemon derives from a mounted folder path. */
    fun mountName(path: String): String =
        (path.split('/').lastOrNull { it.isNotEmpty() } ?: "").lowercase()
            .replace(Regex("[^a-z0-9._-]+"), "-").replace(Regex("^[-.]+|[-.]+$"), "")

    /** `file://` uri-list entries → host paths; [decode] is decodeURIComponent, [pathname] the URL pathname or null. */
    fun hostPaths(raw: String, pathname: (String) -> String?, decode: (String) -> String): List<String> =
        raw.split('\n').map { it.trim() }.filter { it.isNotEmpty() && it.startsWith("file://") }
            .map { u -> pathname(u)?.let(decode) ?: decode(u.replaceFirst(Regex("^file://"), "")) }
}

/** Product rows: the forge board cards and jules sessions as synthetic terrain ids. */
object ProductIds {
    private val UNSAFE = Regex("[^\\w .-]+")
    fun card(column: String, title: String): String = "forge/board/" + column + "/" + title.replace(UNSAFE, "_")
    fun session(name: String): String = "forge/jules/" + name.replace(UNSAFE, "_")
}
