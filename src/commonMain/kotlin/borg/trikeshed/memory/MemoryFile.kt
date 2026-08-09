package borg.trikeshed.memory

import borg.trikeshed.job.ContentId
import borg.trikeshed.lib.Join
import borg.trikeshed.lib.j

/**
 * Memory file — the paper's f = (p_f, d_f, c_f) triple.
 *
 * path j (description j content). Maps to arXiv:2607.26637v1 Section 2.1:
 * "Each file f in M is a triple f = (p_f, d_f, c_f): a path p_f, a one-line
 * description d_f, and text content c_f."
 *
 * The ContentId is derivable from content via [ContentId.of]; it is not part
 * of the triple but is carried alongside for CAS resolution (see [MemoryStore]).
 */
typealias MemoryFile = Join<String, MemoryBody>

/** description j content — the body of a memory file below the path. */
typealias MemoryBody = Join<String, ByteArray>

/** Path component of a memory file. */
val MemoryFile.path: String get() = a

/** One-line description (the frontmatter `description:` field). */
val MemoryFile.description: String get() = b.a

/** Raw content bytes (markdown body). */
val MemoryFile.content: ByteArray get() = b.b

/** Compute the ContentId (SHA-256) of this file's content. */
val MemoryFile.contentId: ContentId get() = ContentId.of(b.b)

/** Construct a memory file from path, description, and content bytes. */
fun memoryFile(path: String, description: String, content: ByteArray): MemoryFile =
    path j (description j content)

/** Construct a memory file from path, description, and string content. */
fun memoryFile(path: String, description: String, content: String): MemoryFile =
    memoryFile(path, description, content.encodeToByteArray())
