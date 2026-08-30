package borg.trikeshed.couch

/**
 * The project path grammar — the one place it is parsed.
 *
 * A project is a heading: an `_id` namespace with a manifest document at its root and everything
 * belonging to it addressed beneath. Reserved segments carry the couch meanings inside that
 * namespace, so a project can hold its own design docs and its own replica-local state rather than
 * sharing the database's.
 *
 * ```
 * projects/<id>                      the project document; body is a Confix manifest
 * projects/<id>/_design/<ddoc>       design docs scoped to this project
 * projects/<id>/_local/<name>        per-replica state; never replicated
 * projects/<id>/<path…>              content: one document per work-tree path, bytes in CAS
 * ```
 *
 * **Plural, and flat.** `docs/oroboros-service-spec.md` §3 writes this as `project/<_id>/` with an
 * `attachments/` segment. The live store does not: `WorktreeCouchGateway.WORKTREE_PREFIX` is
 * `projects/<repo>/` and content sits directly under it, which is also what `MemoryBridge` mints and
 * what the vhost rewrites resolve against. Singularising and inserting `attachments/` would rename
 * every document id already committed to a running daemon's couch — a migration, not a grammar. So
 * this parses what the store actually contains; [CONTENT_SEGMENT] exists for the day that migration
 * is done deliberately.
 */
sealed class ProjectPath {
    abstract val projectId: String

    /** `projects/<id>` — the project document itself. */
    data class Manifest(override val projectId: String) : ProjectPath()

    /** `projects/<id>/_design/<ddoc>` — a design doc owned by this project. */
    data class Design(override val projectId: String, val ddoc: String) : ProjectPath()

    /** `projects/<id>/_local/<name>` — replica-local, never replicated. */
    data class Local(override val projectId: String, val name: String) : ProjectPath()

    /** `projects/<id>/<path…>` — one document per work-tree path. */
    data class Content(override val projectId: String, val path: String) : ProjectPath()

    /** The document id this path addresses. */
    val id: String
        get() = when (this) {
            is Manifest -> "$PREFIX$projectId"
            is Design -> "$PREFIX$projectId/$DESIGN/$ddoc"
            is Local -> "$PREFIX$projectId/$LOCAL/$name"
            is Content -> "$PREFIX$projectId/$path"
        }

    override fun toString(): String = id

    companion object {
        const val PREFIX = "projects/"
        const val DESIGN = "_design"
        const val LOCAL = "_local"

        /**
         * The segment the spec reserves for attachment content. Unused by [of] today because the
         * live layout has no such segment; naming it here keeps the eventual migration a one-line
         * change in one file rather than a search across the tree.
         */
        const val CONTENT_SEGMENT = "attachments"

        /** Ids are one path segment: no separators, no couch-reserved leading underscore. */
        fun isValidId(id: String): Boolean =
            id.isNotEmpty() && !id.startsWith("_") && !id.contains('/') && !id.contains('\\') &&
                id != "." && id != ".."

        /** The namespace every document of [projectId] sits under, trailing slash included. */
        fun prefixOf(projectId: String): String = "$PREFIX$projectId/"

        /** Parse a document id. Null when it names no project, or names one invalidly. */
        fun of(docId: String): ProjectPath? {
            if (!docId.startsWith(PREFIX)) return null
            val rest = docId.removePrefix(PREFIX)
            if (rest.isEmpty()) return null
            val id = rest.substringBefore('/')
            if (!isValidId(id)) return null
            val tail = rest.removePrefix(id).removePrefix("/")
            return when {
                tail.isEmpty() -> Manifest(id)
                tail == DESIGN || tail == LOCAL -> null // a reserved segment naming nothing
                tail.startsWith("$DESIGN/") -> Design(id, tail.removePrefix("$DESIGN/"))
                tail.startsWith("$LOCAL/") -> Local(id, tail.removePrefix("$LOCAL/"))
                else -> Content(id, tail)
            }
        }

        /** The project a document id belongs to, or null when it belongs to none. */
        fun projectOf(docId: String): String? = of(docId)?.projectId
    }
}
