package borg.trikeshed.forge.server

import borg.trikeshed.couch.CouchDatabase
import borg.trikeshed.cursor.ClassfileTaxonomy
import borg.trikeshed.job.ContentId
import borg.trikeshed.util.oroboros.WorktreeCouchGateway

/**
 * Joins a source attachment to the compiled class blobs absorbed from `build/live/classes`.
 *
 * The class bytes come from Couch/CAS, not from a second filesystem walk. JDK 25's public
 * ClassFile API parses those exact blobs through [ClassfileTaxonomy]. The running classloader is
 * then consulted to prove whether each blob is the byte-identical classpath mate Oroboros can
 * introspect right now.
 */
class ClasspathSourceProjection(
    private val database: CouchDatabase,
    private val classLoader: ClassLoader = ClasspathSourceProjection::class.java.classLoader,
) {
    fun project(requestedSourceId: String): Map<String, Any?> {
        val sourceId = resolveSourceId(requestedSourceId)
            ?: return mapOf("error" to "source_not_found", "source" to requestedSourceId)
        val (_, sourceBytes) = database.attachment(sourceId)
            ?: return mapOf("error" to "source_blob_missing", "source" to sourceId)
        val sourceText = sourceBytes.decodeToString()
        val sourceName = sourceId.substringAfterLast('/')
        val packageName = PACKAGE.find(sourceText)?.groupValues?.get(1).orEmpty()
        val packagePath = packageName.replace('.', '/')
        val classRoot = BUILD_CLASSES_PREFIX + if (packagePath.isEmpty()) "" else "$packagePath/"
        // Bolt: avoid materializing all documents into a List and Sequence allocations
        // by iterating directly over the zero-allocation store.ids() view.
        val ids = database.store.ids()
        val classDocsList = ArrayList<String>()
        for (i in 0 until ids.a) {
            val id = ids.b(i)
            if (id.startsWith(classRoot) && id.endsWith(".class")) classDocsList.add(id)
        }
        classDocsList.sort()
        val classDocs = classDocsList.take(MAX_CLASS_CANDIDATES)

        val mates = ArrayList<Map<String, Any?>>()
        for (classId in classDocs) {
            val (_, classBytes) = database.attachment(classId) ?: continue
            val taxonomy = runCatching { ClassfileTaxonomy.openBytes(classBytes) }.getOrNull() ?: continue
            if (taxonomy.sourceFile() != sourceName) continue
            val blobCid = ContentId.of(classBytes)
            val resourceName = taxonomy.className() + ".class"
            val runtimeBytes = classLoader.getResourceAsStream(resourceName)?.use { it.readBytes() }
            val runtimeCid = runtimeBytes?.let(ContentId::of)
            mates += linkedMapOf(
                "classId" to classId,
                "className" to taxonomy.className().replace('/', '.'),
                "sourceFile" to taxonomy.sourceFile(),
                "blobCid" to blobCid.value,
                "blobBytes" to classBytes.size,
                "classpathResource" to resourceName,
                "onClasspath" to (runtimeBytes != null),
                "runtimeCid" to runtimeCid?.value,
                "exactRuntimeBlob" to (runtimeCid == blobCid),
                "decompiler" to taxonomy.projection(),
            )
        }
        val sourceCid = ContentId.of(sourceBytes)
        return linkedMapOf(
            "source" to linkedMapOf(
                "id" to sourceId,
                "name" to sourceName,
                "package" to packageName,
                "cid" to sourceCid.value,
                "bytes" to sourceBytes.size,
                "text" to sourceText,
            ),
            "classBlobPlane" to BUILD_CLASSES_PREFIX,
            "classfileApi" to "java.lang.classfile (JDK 25)",
            "candidateBlobs" to classDocs.size,
            "mates" to mates,
            "mated" to mates.any { it["exactRuntimeBlob"] == true },
        )
    }

    private fun resolveSourceId(requested: String): String? {
        if (database.store.get(requested) != null) return requested
        val normalized = requested.removePrefix("/").removePrefix(WORKTREE_PREFIX)
        val prefixed = WORKTREE_PREFIX + normalized
        if (database.store.get(prefixed) != null) return prefixed
        return database.store.ids().let { ids ->
            var match: String? = null
            for (i in 0 until ids.a) {
                val id = ids.b(i)
                if (id.endsWith("/$normalized")) {
                    if (match != null) return@let null // ambiguous suffix is not a mate proof
                    match = id
                }
            }
            match
        }
    }

    companion object {
        private val WORKTREE_PREFIX: String get() = WorktreeCouchGateway.WORKTREE_PREFIX
        private val BUILD_CLASSES_PREFIX: String get() = WORKTREE_PREFIX + "build/live/classes/"
        private const val MAX_CLASS_CANDIDATES = 1024
        private val PACKAGE = Regex("(?m)^\\s*package\\s+([A-Za-z_][A-Za-z0-9_.]*)")
    }
}
