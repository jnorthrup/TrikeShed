package borg.trikeshed.couch

/**
 * Projects — the heading entity over one [CouchDatabase].
 *
 * Spec C7 recorded "no `Project` entity": documents were already namespaced under
 * `projects/<id>/…` by the worktree gateway and the memory bridge, but nothing declared a project,
 * so there was no way to ask what projects exist, what belongs to one, or what a project *is*
 * beyond an id prefix that happened to repeat. This is that declaration — a manifest document at
 * the namespace root, and the queries a heading needs.
 *
 * The manifest is an ordinary document, so it revisions, enters `_changes`, and replicates like
 * anything else; a project arriving on a peer brings its heading with it. Content documents are not
 * moved or rewritten — [ProjectPath] reads the layout that is already there.
 */
class Projects(private val db: CouchDatabase) {

    /** Declare or update a project's manifest. Extra [fields] are kept as the Confix body. */
    fun put(id: String, fields: Map<String, Any?> = emptyMap()): Map<String, Any?> {
        if (!ProjectPath.isValidId(id)) {
            return mapOf("error" to "bad_request", "reason" to "invalid project id '$id'", "id" to id)
        }
        val docId = ProjectPath.Manifest(id).id
        val body = linkedMapOf<String, Any?>("kind" to KIND, "name" to id) +
            fields.filterKeys { it != "kind" && !it.startsWith("_") }
        return db.put(docId, body, db.store.head.getRev(docId))
    }

    /** The manifest, or null when the project has never been declared. */
    fun get(id: String): Map<String, Any?>? =
        db.docJson(ProjectPath.Manifest(id).id)?.takeIf { it["kind"] == KIND }

    fun exists(id: String): Boolean = get(id) != null

    /** Every declared project, by id, in collation order. */
    fun list(): List<String> =
        db.store.all()
            .filter { !db.isTombstone(it) }
            .mapNotNull { doc -> (ProjectPath.of(doc.id) as? ProjectPath.Manifest)?.projectId }
            .distinct()
            .sorted()

    /**
     * Projects that have documents but no manifest — a namespace in use that nobody declared.
     * Worth surfacing rather than hiding: the worktree gateway mints `projects/<repo>/…` on its own,
     * so this is the normal state until someone declares the heading.
     */
    fun undeclared(): List<String> {
        val declared = list().toSet()
        return db.store.all()
            .filter { !db.isTombstone(it) }
            .mapNotNull { ProjectPath.of(it.id)?.projectId }
            .distinct()
            .filter { it !in declared }
            .sorted()
    }

    /** Document ids belonging to [id], optionally narrowed to those under [under]. */
    fun documents(id: String, under: String = ""): List<String> {
        val prefix = ProjectPath.prefixOf(id) + under
        return db.store.all()
            .filter { !db.isTombstone(it) && it.id.startsWith(prefix) }
            .map { it.id }
            .sorted()
    }

    /** How many documents sit under the heading, manifest excluded. */
    fun size(id: String): Int = documents(id).size

    /**
     * A project as a wire row: the manifest plus what the store knows about it. `declared` tells a
     * caller whether the heading exists or only its documents do.
     */
    fun summary(id: String): Map<String, Any?> {
        val manifest = get(id)
        val docs = documents(id)
        return linkedMapOf<String, Any?>(
            "id" to id,
            "declared" to (manifest != null),
            "doc_count" to docs.size,
            "prefix" to ProjectPath.prefixOf(id),
        ) + (manifest?.filterKeys { it != "_id" && it != "kind" } ?: emptyMap())
    }

    /** Every project the store knows of, declared or merely in use. */
    fun summaries(): List<Map<String, Any?>> = (list() + undeclared()).distinct().sorted().map(::summary)

    companion object {
        const val KIND = "project"
    }
}
