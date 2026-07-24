package borg.trikeshed.util.io

/**
 * Shared MIME / content-type table. Replaces the two near-identical copies
 * that used to live in `GitCouchGateway` (git-attachment specific) and
 * `OroborosMain` (project-tree specific).
 *
 * Order of matching matters: longer / more specific suffixes win via the
 * `when` chain. The git-object/git-ref suffixes are intentionally last so
 * they only apply to paths inside a `.git/` tree.
 */
object ContentTypes {

    private const val GIT_OBJECTS_DIR = ".git/objects/"
    private const val GIT_REFS_DIR = ".git/refs/"

    /** Map a relative file path to a MIME type suitable for an attachment ref. */
    fun forPath(path: String): String {
        if (GIT_OBJECTS_DIR in path) return "application/x-git-object"
        if (GIT_REFS_DIR in path) return "application/x-git-ref"
        return when {
            path.endsWith(".go") -> "text/plain"
            path.endsWith(".kt") -> "text/kotlin"
            path.endsWith(".kts") -> "text/kotlin"
            path.endsWith(".md") -> "text/markdown"
            path.endsWith(".json") -> "application/json"
            path.endsWith(".sh") || path.endsWith(".bash") -> "application/x-sh"
            path.endsWith(".txt") -> "text/plain"
            path.endsWith(".html") -> "text/html"
            path.endsWith(".css") -> "text/css"
            path.endsWith(".js") || path.endsWith(".mjs") -> "application/javascript"
            path.endsWith(".xml") -> "application/xml"
            path.endsWith(".yaml") || path.endsWith(".yml") -> "application/yaml"
            path.endsWith(".toml") -> "application/toml"
            path.endsWith(".csv") -> "text/csv"
            path.endsWith(".png") -> "image/png"
            path.endsWith(".jpg") || path.endsWith(".jpeg") -> "image/jpeg"
            path.endsWith(".gif") -> "image/gif"
            path.endsWith(".svg") -> "image/svg+xml"
            path.endsWith(".pdf") -> "application/pdf"
            path.endsWith(".zip") -> "application/zip"
            path.endsWith(".tar") -> "application/x-tar"
            path.endsWith(".gz") -> "application/gzip"
            path.endsWith(".bz2") -> "application/x-bzip2"
            path.endsWith(".xz") -> "application/x-xz"
            path.endsWith(".zst") -> "application/zstd"
            else -> "application/octet-stream"
        }
    }
}
