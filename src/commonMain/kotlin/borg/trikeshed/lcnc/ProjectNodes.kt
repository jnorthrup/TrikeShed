package borg.trikeshed.lcnc

import borg.trikeshed.job.ContentId
import kotlinx.coroutines.currentCoroutineContext

/**
 * A MOUNTED FOLDER IS A DOCUMENT SET A PROGRAM CAN WALK.
 *
 * The post Forge answers asked for "a set of input files (Markdown or similar)
 * plus other general-purpose context" to process over in an iterated way. A
 * dropped folder was already a project database (doc ids are paths, every
 * document an attachment with typed fields), but no lego let a program read
 * one. These do: `project.docs` lists a project's documents as exact
 * `List<ProjectDoc>`, `project.read` reads one as text, `project.extract` reads
 * the mined twin the miner leaves beside it. The daemon binds [ProjectCorpus]
 * to its project registry; a test binds [InMemoryProjectCorpus].
 */
interface ProjectCorpus {
    suspend fun projects(): List<ProjectRef>
    suspend fun docs(project: String, prefix: String = "", glob: String = "", limit: Int = 256): List<ProjectDoc>
    /** Null when the document is absent, binary, or over the character budget. */
    suspend fun read(project: String, id: String, maxChars: Int = 65_536): ProjectText?
}

/** One mounted project, as `project.list` reports it. */
data class ProjectRef(val name: String, val kind: String, val path: String, val docs: Int) {
    fun toMap(): Map<String, Any?> = linkedMapOf("name" to name, "kind" to kind, "path" to path, "docs" to docs)
}

/**
 * One document of a project — the exact CCEK type `ProjectDoc` a cable carries.
 * The wire value is this map; `cid` is the content identity of the bytes and
 * `seq` the store sequence that last changed it.
 */
data class ProjectDoc(
    val project: String,
    val id: String,
    val cid: String,
    val rev: String,
    val seq: Long,
    val length: Long,
    val contentType: String,
) {
    fun toMap(): Map<String, Any?> = linkedMapOf(
        "project" to project, "id" to id, "cid" to cid, "rev" to rev, "seq" to seq, "length" to length, "contentType" to contentType,
    )

    companion object {
        const val KIND = "ProjectDoc"
        const val LIST_KIND = "List<ProjectDoc>"
        /** The keys every row must carry to be a [ProjectDoc] (LcncFacts.refineLiteral). */
        val SHAPE: List<String> = listOf("project", "id", "cid")

        fun fromMap(m: Map<*, *>): ProjectDoc? {
            val project = m["project"]?.toString() ?: return null
            val id = m["id"]?.toString() ?: return null
            val cid = m["cid"]?.toString() ?: return null
            return ProjectDoc(
                project, id, cid,
                rev = m["rev"]?.toString().orEmpty(),
                seq = (m["seq"] as? Number)?.toLong() ?: 0L,
                length = (m["length"] as? Number)?.toLong() ?: 0L,
                contentType = m["contentType"]?.toString().orEmpty(),
            )
        }
    }
}

data class ProjectText(val project: String, val id: String, val cid: String, val rev: String, val seq: Long, val text: String)

/** Map-backed corpus — the zero-thread test seam; documents carry real content ids. */
class InMemoryProjectCorpus : ProjectCorpus {
    private class Entry(val bytes: ByteArray, val contentType: String, val cid: String, val rev: String, val seq: Long)

    private val projects = linkedMapOf<String, LinkedHashMap<String, Entry>>()
    private val kinds = linkedMapOf<String, String>()
    private var sequence = 0L

    /** Put (or revise) one document; returns its cid. Revisions advance the sequence. */
    fun put(project: String, id: String, bytes: ByteArray, contentType: String = contentTypeOf(id), kind: String = "assets"): String {
        val docs = projects.getOrPut(project) { LinkedHashMap() }
        if (project !in kinds) kinds[project] = kind
        val cid = ContentId.of(bytes).value
        val generation = (docs[id]?.rev?.substringBefore('-')?.toIntOrNull() ?: 0) + 1
        docs[id] = Entry(bytes, contentType, cid, "$generation-${cid.removePrefix("sha256:").take(8)}", ++sequence)
        return cid
    }

    fun remove(project: String, id: String) { projects[project]?.remove(id); sequence++ }

    override suspend fun projects(): List<ProjectRef> =
        projects.map { (name, docs) -> ProjectRef(name, kinds[name] ?: "assets", "/memory/$name", docs.size) }

    override suspend fun docs(project: String, prefix: String, glob: String, limit: Int): List<ProjectDoc> {
        val docs = projects[project] ?: return emptyList()
        return docs.entries.asSequence()
            .filter { it.key.startsWith(prefix) && ProjectGlob.matches(glob, it.key) }
            .sortedBy { it.key }
            .take(limit)
            .map { (id, e) -> ProjectDoc(project, id, e.cid, e.rev, e.seq, e.bytes.size.toLong(), e.contentType) }
            .toList()
    }

    override suspend fun read(project: String, id: String, maxChars: Int): ProjectText? {
        val e = projects[project]?.get(id) ?: return null
        if (!ProjectGlob.isTextual(e.contentType, id)) return null
        val text = runCatching { e.bytes.decodeToString() }.getOrNull() ?: return null
        if (text.any { it == '�' }) return null
        return ProjectText(project, id, e.cid, e.rev, e.seq, if (text.length > maxChars) text.take(maxChars) else text)
    }

    companion object {
        fun contentTypeOf(id: String): String = when (id.substringAfterLast('.', "").lowercase()) {
            "md", "markdown" -> "text/markdown"
            "txt", "text" -> "text/plain"
            "json" -> "application/json"
            "csv" -> "text/csv"
            "kt", "kts", "java", "js", "py", "sh", "html", "css", "xml", "yaml", "yml", "toml" -> "text/plain"
            "pdf" -> "application/pdf"
            else -> "application/octet-stream"
        }
    }
}

/**
 * The document-id glob `project.docs` filters with: `*` within a path segment,
 * `**` across segments, `?` one character. A glob without a `/` matches the
 * last path segment (the way `find -name` reads it), so `*.md` finds Markdown
 * anywhere in the project; a glob with a `/` matches the whole id. Empty = all.
 */
object ProjectGlob {
    fun matches(glob: String, id: String): Boolean {
        if (glob.isBlank()) return true
        val subject = if ('/' in glob) id else id.substringAfterLast('/')
        return toRegex(glob).matches(subject)
    }

    private fun toRegex(glob: String): Regex {
        val sb = StringBuilder("^")
        var i = 0
        while (i < glob.length) {
            val c = glob[i]
            when {
                c == '*' && i + 1 < glob.length && glob[i + 1] == '*' -> { sb.append(".*"); i++ }
                c == '*' -> sb.append("[^/]*")
                c == '?' -> sb.append("[^/]")
                c in ".()+|^$[]{}\\" -> sb.append('\\').append(c)
                else -> sb.append(c)
            }
            i++
        }
        return Regex(sb.append('$').toString())
    }

    private val TEXT_EXTENSIONS = setOf(
        "md", "markdown", "txt", "text", "json", "csv", "kt", "kts", "java", "js", "cjs", "mjs", "ts", "py", "sh",
        "html", "css", "xml", "yaml", "yml", "toml", "ini", "cfg", "conf", "rst", "adoc", "tex", "sql", "ttl", "kif",
    )

    /** Text by content type, else by a known text extension — a PDF is never read as text here. */
    fun isTextual(contentType: String, id: String): Boolean =
        contentType.startsWith("text/") ||
            contentType in setOf("application/json", "application/xml", "application/x-yaml", "application/toml") ||
            id.substringAfterLast('.', "").lowercase() in TEXT_EXTENSIONS
}

object ProjectNodes {
    const val LIST = "project.list"
    const val DOCS = "project.docs"
    const val READ = "project.read"
    const val EXTRACT = "project.extract"
    /** The mined twin the project miner leaves beside a document (ProjectMiner). */
    const val EXTRACT_SUFFIX = ".extract.md"

    fun servedTypes(): Set<String> = setOf(LIST, DOCS, READ, EXTRACT)

    private fun str(inputs: Map<String, Any?>, node: LcncNode, port: String): String? =
        (inputs[port] ?: inputs["$port?"])?.toString()?.takeIf { it.isNotBlank() }
            ?: node.params[port]?.takeIf { it.isNotBlank() }

    private fun docOf(inputs: Map<String, Any?>): ProjectDoc? =
        ((inputs["doc"] ?: inputs["doc?"]) as? Map<*, *>)?.let(ProjectDoc::fromMap)

    fun registry(corpus: ProjectCorpus): Map<String, LcncNodeRunner> = mapOf(
        LIST to LcncNodeRunner { _, _ ->
            val refs = corpus.projects()
            mapOf("scopes" to refs.map { it.toMap() }, "projects" to refs.map { it.toMap() })
        },
        DOCS to LcncNodeRunner { node, inputs ->
            val project = str(inputs, node, "project")
                ?: throw IllegalArgumentException("project.docs: no project named — wire one in or set the project param")
            val limit = node.params["limit"]?.toIntOrNull()?.coerceIn(1, 4096) ?: 256
            val prefix = node.params["prefix"].orEmpty()
            val glob = node.params["glob"].orEmpty()
            val docs = corpus.docs(project, prefix, glob, limit)
            // The listing itself is an input: an added or removed file moves its fingerprint.
            currentCoroutineContext()[LcncConsumedLedger]?.let { ledger ->
                ledger.consumed(LcncConsumedLedger.PROJECT_INDEX, "$project/", ledger.indexFingerprint(docs.map { it.id }), prefix = prefix, glob = glob)
            }
            mapOf("docs" to docs.map { it.toMap() }, "count" to docs.size)
        },
        READ to LcncNodeRunner { node, inputs ->
            val doc = docOf(inputs)
            val project = doc?.project ?: str(inputs, node, "project")
                ?: throw IllegalArgumentException("project.read: no document wired and no project named")
            val id = doc?.id ?: str(inputs, node, "id")
                ?: throw IllegalArgumentException("project.read: no document wired and no id named")
            val maxChars = node.params["maxChars"]?.toIntOrNull()?.coerceIn(1, 1_048_576) ?: 65_536
            val text = corpus.read(project, id, maxChars)
            if (text != null) currentCoroutineContext()[LcncConsumedLedger]?.consumed(LcncConsumedLedger.PROJECT, "$project/$id", text.cid, text.seq, text.rev)
            if (text == null) mapOf("error" to "project.read: '$id' in '$project' is absent, binary, or over $maxChars chars")
            else mapOf(
                "text" to text.text, "cid" to text.cid, "id" to text.id,
                "doc" to (doc ?: ProjectDoc(project, id, text.cid, text.rev, text.seq, text.text.length.toLong(), InMemoryProjectCorpus.contentTypeOf(id))).toMap(),
            )
        },
        EXTRACT to LcncNodeRunner { node, inputs ->
            val doc = docOf(inputs)
            val project = doc?.project ?: str(inputs, node, "project")
                ?: throw IllegalArgumentException("project.extract: no document wired and no project named")
            val id = doc?.id ?: str(inputs, node, "id")
                ?: throw IllegalArgumentException("project.extract: no document wired and no id named")
            val twin = corpus.read(project, id + EXTRACT_SUFFIX, 1_048_576)
            if (twin != null) currentCoroutineContext()[LcncConsumedLedger]?.consumed(LcncConsumedLedger.PROJECT, "$project/$id$EXTRACT_SUFFIX", twin.cid, twin.seq, twin.rev)
            if (twin == null) mapOf("found" to false)
            else mapOf("text" to twin.text, "cid" to twin.cid, "found" to true)
        },
    )
}
