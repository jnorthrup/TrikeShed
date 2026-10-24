package borg.trikeshed.agent.modelmux

import borg.trikeshed.cas.LineCas
import borg.trikeshed.job.ContentId
import borg.trikeshed.lib.*

class ManifestFormatException(message: String) : IllegalArgumentException(message)

/**
 * One pinned document: a logical [name], the identity of its EXACT original bytes ([content]), and the identity of
 * the LineCas spine those bytes normalize to ([spine]).
 *
 * The two identities answer different questions. [content] changes with any byte, including indentation and blank
 * lines, and is what execution and citation must use. [spine] ignores formatting and is what discovery and lexical
 * facets key on. Recording both lets a consumer detect a peer that normalizes differently before trusting any
 * ordinal-to-bytes mapping.
 *
 * [name] is a relative, '/'-separated label. It is never a filesystem path: no leading '/', no '\', no empty, '.'
 * or '..' segments, so a consumer that does materialize files cannot be walked out of its directory.
 */
data class ManifestDocument(val name: String, val content: ContentId, val spine: ContentId) {
    init {
        BreadcrumbContextManifest.checkLine("document name", name, BreadcrumbContextManifest.MAX_NAME)
        if (name.startsWith("/") || name.contains('\\') || name.split('/').any { it.isEmpty() || it == "." || it == ".." }) {
            throw ManifestFormatException("document name '$name' is not a plain relative label")
        }
    }

    companion object {
        /** Names [bytes] the way every peer must: content by exact bytes, spine by the shared normalization. */
        fun of(name: String, bytes: ByteArray): ManifestDocument {
            if (!isExactText(bytes)) throw ManifestFormatException("document '$name' is not valid UTF-8 text")
            return ManifestDocument(name, ContentId.of(bytes), spineOf(bytes))
        }

        /**
         * Whether [bytes] are valid UTF-8, so that decoding and re-encoding returns exactly them. Lines, spines and every
         * excerpt range are computed over the decoded text; only for such documents do they describe the exact bytes the
         * content identity names. Anything else decodes lossily and is refused rather than cited under a false range.
         */
        fun isExactText(bytes: ByteArray): Boolean = bytes.decodeToString().encodeToByteArray().contentEquals(bytes)

        fun spineOf(bytes: ByteArray): ContentId = LineCas.spineCid(LineCas.spine(bytes.decodeToString()))
    }
}

/**
 * An immutable, content-addressed revision of shared context: which exact documents a task is working from.
 *
 * A manifest pins evidence; it does not hold it. Projections (breadcrumbs, excerpts) are derived from the pinned
 * documents per task and are never part of the manifest, so changing a lexical rule invalidates no revision.
 * A new revision is a new manifest whose [parents] name the one it was derived from; nothing is overwritten, so
 * a child working from revision R keeps seeing R until it is explicitly handed a successor.
 *
 * Wire form (version 1): UTF-8, one `\n`-terminated line per item, in exactly this order:
 *
 *     trikeshed-context-manifest 1
 *     scope <scope>
 *     policy <policyRevision>
 *     author <author>
 *     parent <sha256:...>              zero or more, ascending, unique
 *     doc <content> <spine> <name>     zero or more, ascending by name, unique names
 *
 * [decode] accepts only canonical bytes: it parses, rebuilds, re-encodes and requires byte equality, so a given
 * revision has exactly one encoding and therefore exactly one identity. An unknown version is rejected, never
 * guessed at.
 */
class BreadcrumbContextManifest private constructor(
    val scope: String,
    val policyRevision: String,
    val author: String,
    val parents: Series<ContentId>,
    val documents: Series<ManifestDocument>,
) {
    fun encode(): ByteArray {
        val out = StringBuilder()
        out.append(HEADER).append('\n')
        out.append("scope ").append(scope).append('\n')
        out.append("policy ").append(policyRevision).append('\n')
        out.append("author ").append(author).append('\n')
        for (i in 0 until parents.size) out.append("parent ").append(parents[i].value).append('\n')
        for (i in 0 until documents.size) {
            val d = documents[i]
            out.append("doc ").append(d.content.value).append(' ').append(d.spine.value).append(' ').append(d.name).append('\n')
        }
        return out.toString().encodeToByteArray()
    }

    /** The identity of this revision: sha256 of its single canonical encoding. */
    val cid: ContentId by lazy { ContentId.of(encode()) }

    companion object {
        const val HEADER = "trikeshed-context-manifest 1"
        const val MAX_BYTES = 1_048_576
        const val MAX_DOCUMENTS = 4_096
        const val MAX_PARENTS = 64
        const val MAX_NAME = 1_024
        const val MAX_FIELD = 256

        private const val CID_TEXT = 71

        internal fun checkLine(label: String, value: String, max: Int) {
            if (value.isEmpty() || value.length > max) throw ManifestFormatException("$label must be 1..$max characters")
            if (value != value.trim()) throw ManifestFormatException("$label has surrounding whitespace")
            if (value.any { it < ' ' || it == '\u007f' }) throw ManifestFormatException("$label has control characters")
        }

        /** Builds the canonical revision: parents are a set, documents are ordered by name, names are unique. */
        fun of(
            scope: String,
            policyRevision: String,
            author: String,
            parents: Collection<ContentId>,
            documents: Collection<ManifestDocument>,
        ): BreadcrumbContextManifest {
            checkLine("scope", scope, MAX_FIELD)
            checkLine("policy revision", policyRevision, MAX_FIELD)
            checkLine("author", author, MAX_FIELD)
            val orderedParents = parents.toSet().sortedBy { it.value }
            if (orderedParents.size > MAX_PARENTS) throw ManifestFormatException("more than $MAX_PARENTS parents")
            val orderedDocs = documents.sortedBy { it.name }
            if (orderedDocs.size > MAX_DOCUMENTS) throw ManifestFormatException("more than $MAX_DOCUMENTS documents")
            for (i in 1 until orderedDocs.size) {
                if (orderedDocs[i].name == orderedDocs[i - 1].name) throw ManifestFormatException("duplicate document name '${orderedDocs[i].name}'")
            }
            val manifest = BreadcrumbContextManifest(scope, policyRevision, author, orderedParents.toSeries(), orderedDocs.toSeries())
            if (manifest.encode().size > MAX_BYTES) throw ManifestFormatException("manifest exceeds $MAX_BYTES bytes")
            return manifest
        }

        /** Strict, bounded decode. Anything that is not the canonical encoding of some manifest is rejected. */
        fun decode(bytes: ByteArray): BreadcrumbContextManifest {
            if (bytes.size > MAX_BYTES) throw ManifestFormatException("manifest exceeds $MAX_BYTES bytes")
            val text = try {
                bytes.decodeToString(throwOnInvalidSequence = true)
            } catch (invalid: Exception) {
                throw ManifestFormatException("manifest is not valid UTF-8")
            }
            if (!text.endsWith("\n")) throw ManifestFormatException("manifest must end with a newline")
            val lines = text.substring(0, text.length - 1).split('\n')

            if (lines[0] != HEADER) throw ManifestFormatException("unsupported manifest header")
            fun field(index: Int, key: String): String {
                val line = lines.getOrNull(index) ?: throw ManifestFormatException("missing '$key' line")
                if (!line.startsWith("$key ")) throw ManifestFormatException("expected '$key' line")
                return line.substring(key.length + 1)
            }

            val scope = field(1, "scope")
            val policy = field(2, "policy")
            val author = field(3, "author")

            val parents = ArrayList<ContentId>()
            val docs = ArrayList<ManifestDocument>()
            var at = 4
            while (at < lines.size && lines[at].startsWith("parent ")) {
                parents.add(cid(lines[at].substring("parent ".length), "parent"))
                at++
            }
            while (at < lines.size) {
                val line = lines[at]
                if (!line.startsWith("doc ")) throw ManifestFormatException("unexpected line ${at + 1}")
                // doc <71> <71> <name>
                val nameAt = 4 + CID_TEXT + 1 + CID_TEXT + 1
                if (line.length <= nameAt || line[4 + CID_TEXT] != ' ' || line[nameAt - 1] != ' ') {
                    throw ManifestFormatException("malformed doc line ${at + 1}")
                }
                docs.add(
                    ManifestDocument(
                        name = line.substring(nameAt),
                        content = cid(line.substring(4, 4 + CID_TEXT), "doc content"),
                        spine = cid(line.substring(4 + CID_TEXT + 1, nameAt - 1), "doc spine"),
                    ),
                )
                at++
            }

            val rebuilt = of(scope, policy, author, parents, docs)
            if (!rebuilt.encode().contentEquals(bytes)) throw ManifestFormatException("manifest is not in canonical form")
            return rebuilt
        }

        private fun cid(text: String, label: String): ContentId =
            try {
                ContentId(text)
            } catch (bad: IllegalArgumentException) {
                throw ManifestFormatException("$label is not a sha256 identity")
            }
    }
}

/** One pinned document with its verified bytes. */
class ContextDocument(val name: String, val cid: ContentId, val bytes: ByteArray) {
    val text: String get() = bytes.decodeToString()
}

/**
 * A fully verified revision: the manifest and every document it names. There is no partial snapshot; if any
 * required block is missing, oversize, mismatched or out of scope, acquisition fails and nothing is returned.
 */
class ContextSnapshot(val manifestCid: ContentId, val manifest: BreadcrumbContextManifest, val documents: Series<ContextDocument>)
