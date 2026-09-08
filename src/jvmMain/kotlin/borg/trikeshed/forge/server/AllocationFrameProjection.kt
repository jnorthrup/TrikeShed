package borg.trikeshed.forge.server

import borg.trikeshed.couch.Couch
import borg.trikeshed.cursor.ClassfileTaxonomy
import borg.trikeshed.graal.vitals.AllocationFrame
import borg.trikeshed.job.ContentId
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile

/** On-demand classpath inspection; no class initialization, attach pause or recursive file walk. */
class AllocationFrameProjection(
    private val database: Couch?,
    private val loader: ClassLoader = AllocationFrameProjection::class.java.classLoader,
) {
    fun project(frame: AllocationFrame): Map<String, Any?> {
        val resource = frame.className.replace('.', '/') + ".class"
        val url = loader.getResource(resource)
            ?: return frame.wire() + mapOf("available" to false, "reason" to "class_resource_unavailable")
        if (url.protocol !in setOf("file", "jar", "jrt"))
            return frame.wire() + mapOf("available" to false, "reason" to "not_a_local_class_resource")
        val bytes = url.openStream().use { it.readNBytes(MAX_CLASS_BYTES + 1) }
        if (bytes.size > MAX_CLASS_BYTES)
            return frame.wire() + mapOf("available" to false, "reason" to "class_resource_too_large")
        val taxonomy = ClassfileTaxonomy.openBytes(bytes)
        val instructions = taxonomy.instructions().filter {
            it.get(7) == frame.method && it.get(8) == frame.descriptor
        }
        val exactIndex = instructions.indexOfFirst { it.get(0) == frame.bci }
        val begin = maxOf(0, exactIndex - 12)
        val rows = instructions.drop(begin).take(160).map { row ->
            val owner = row.get(3).toString().replace('/', '.')
            val name = row.get(4).toString()
            mapOf("bci" to row.get(0), "opcode" to row.get(2), "owner" to owner,
                "name" to name, "descriptor" to row.get(5), "line" to row.get(6),
                "sampled" to (row.get(0) == frame.bci),
                "boxing" to (((name == "valueOf" || name == "<init>" || row.get(2) == "NEW") && owner in WRAPPERS) ||
                    (owner == "kotlin.coroutines.jvm.internal.Boxing" && name.startsWith("box"))))
        }
        val sourceFile = taxonomy.sourceFile()
        val source = source(frame, sourceFile, url.protocol == "jrt", url.path)
        return frame.wire() + mapOf(
            "available" to true, "resource" to url.toString(), "cid" to ContentId.of(bytes).value,
            "bytes" to bytes.size, "sourceFile" to sourceFile, "source" to source,
            "instructions" to rows, "instructionCount" to instructions.size,
            "bciMatched" to (exactIndex >= 0), "methodFound" to taxonomy.methods().any {
                it.get(0) == frame.method && it.get(1) == frame.descriptor
            },
            "bytecodeEvidence" to "current classpath resource; loaded or retransformed byte identity unverified",
            "sourceEvidence" to "package and SourceFile match; source revision and Kotlin inline mapping unverified",
            "aotSiteEvidence" to "unavailable; classpath bytes do not establish AOT execution",
        )
    }

    private fun source(frame: AllocationFrame, file: String, jdk: Boolean, resourcePath: String): Map<String, Any?>? {
        if (file == "Unknown" || '/' in file || '\\' in file) return null
        val packagePath = frame.className.substringBeforeLast('.', "").replace('.', '/')
        val suffix = if (packagePath.isEmpty()) file else "$packagePath/$file"
        if (jdk) {
            val archive = Path.of(System.getProperty("java.home"), "lib", "src.zip")
            if (!Files.isRegularFile(archive)) return null
            val module = resourcePath.removePrefix("/").substringBefore('/')
            return ZipFile(archive.toFile()).use { zip ->
                val entry = zip.getEntry("$module/$suffix") ?: return@use null
                val text = zip.getInputStream(entry).use { it.readNBytes(MAX_SOURCE_BYTES + 1) }
                if (text.size > MAX_SOURCE_BYTES) null else excerpt("$archive!/${entry.name}", text.decodeToString(), frame.line)
            }
        }
        val db = database ?: return null
        val ids = db.store.ids()
        var match: String? = null
        for (i in 0 until ids.a) {
            val id = ids.b(i)
            if (!id.endsWith("/$suffix")) continue
            if (match != null) return null
            match = id
        }
        val id = match ?: return null
        val bytes = db.attachment(id)?.second ?: return null
        if (bytes.size > MAX_SOURCE_BYTES) return null
        return excerpt(id, bytes.decodeToString(), frame.line)
    }

    private fun excerpt(id: String, text: String, line: Int): Map<String, Any?> {
        val lines = text.lines()
        val center = if (line in 1..lines.size) line else 1
        val start = maxOf(1, center - 8)
        return mapOf("id" to id, "startLine" to start, "lineMatched" to (line in 1..lines.size),
            "lines" to lines.drop(start - 1).take(24))
    }

    companion object {
        private const val MAX_CLASS_BYTES = 4 * 1024 * 1024
        private const val MAX_SOURCE_BYTES = 1024 * 1024
        private val WRAPPERS = setOf("java.lang.Integer", "java.lang.Long", "java.lang.Short", "java.lang.Byte",
            "java.lang.Character", "java.lang.Boolean", "java.lang.Float", "java.lang.Double")
    }
}
