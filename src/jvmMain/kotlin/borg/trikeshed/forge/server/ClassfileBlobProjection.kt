package borg.trikeshed.forge.server

import borg.trikeshed.couch.Couch
import borg.trikeshed.cursor.ClassfileTaxonomy
import borg.trikeshed.graal.vitals.JvmVitals
import borg.trikeshed.job.ContentId

/**
 * Projection for a selected `.class` attachment in the Graal surface.
 *
 * Static class-file structure comes from the JDK Class-File API. Runtime data is kept in a
 * separate observation block because JFR/JMX events do not identify every method descriptor,
 * loader generation, or instruction execution count here.
 */
class ClassfileBlobProjection(
    private val database: Couch?,
    private val vitals: JvmVitals?,
    private val classLoader: ClassLoader = ClassfileBlobProjection::class.java.classLoader,
) {
    fun project(id: String): Map<String, Any?> {
        if (id.isBlank()) return error(400, "class_id_required", "id" to id)
        val db = database ?: return error(503, "cas_database_unavailable", "id" to id)
        val (contentType, bytes) = db.attachment(id)
            ?: return error(404, "class_blob_missing", "id" to id)
        val taxonomy = runCatching { ClassfileTaxonomy.openBytes(bytes) }.getOrElse { cause ->
            return error(
                422,
                "classfile_parse_failed",
                "id" to id,
                "contentType" to contentType,
                "detail" to (cause.message ?: cause::class.java.name),
            )
        }

        val blobCid = ContentId.of(bytes)
        val resourceName = taxonomy.className() + ".class"
        val runtimeBytes = classLoader.getResourceAsStream(resourceName)?.use { it.readBytes() }
        val runtimeCid = runtimeBytes?.let(ContentId::of)
        val projection = LinkedHashMap<String, Any?>()
        projection["id"] = id
        projection["contentType"] = contentType
        projection["classfileApi"] = "java.lang.classfile (JDK 25)"
        projection["blobCid"] = blobCid.value
        projection["blobBytes"] = bytes.size
        projection["classpathResource"] = resourceName
        projection["onClasspath"] = runtimeBytes != null
        projection["runtimeCid"] = runtimeCid?.value
        projection["exactRuntimeBlob"] = runtimeCid == blobCid
        projection.putAll(taxonomy.projection())
        projection["runtimeObservation"] = runtimeObservation(taxonomy.className().replace('/', '.'))
        return projection
    }

    private fun runtimeObservation(className: String): Map<String, Any?> {
        val snapshot = vitals?.snapshot()
            ?: return linkedMapOf(
                "available" to false,
                "source" to "JvmVitals",
                "reason" to "vitals_unavailable",
                "classCompileGranularity" to "unavailable",
                "classRecentCompiles" to emptyList<Map<String, Any?>>(),
                "methodCounters" to unavailable("method", "JvmVitals does not expose descriptor-identified method counters for this selected class"),
                "instructionCounters" to unavailable("instruction", "JvmVitals does not expose bytecode-offset execution counters for this selected class"),
                "deoptimizationCorrelation" to unavailable("deoptimization", "recent deoptimization events are not class/descriptor keyed in this projection"),
            )

        val jit = snapshot["jit"] as? Map<*, *>
        val deopt = snapshot["deopt"] as? Map<*, *>
        val classRecent = ArrayList<Map<String, Any?>>()
        val recent = (jit?.get("recent") as? List<*>).orEmpty()
        for (item in recent) {
            val event = item as? Map<*, *> ?: continue
            if (event["className"]?.toString() != className) continue
            classRecent += linkedMapOf(
                "method" to event["method"],
                "level" to event["level"],
                "codeSize" to event["codeSize"],
                "osr" to event["osr"],
                "durationUs" to event["durationUs"],
                "ok" to event["ok"],
            )
        }
        return linkedMapOf(
            "available" to true,
            "source" to "JvmVitals JFR/JMX snapshot",
            "className" to className,
            "classCompileGranularity" to if (classRecent.isEmpty()) "unobserved" else "class",
            "classRecentCompiles" to classRecent,
            "processTotals" to linkedMapOf(
                "compilations" to jit?.get("compilations"),
                "deoptimizations" to deopt?.get("deoptimizations"),
            ),
            "methodCounters" to unavailable("method", "recent JFR compile events expose method labels but not complete descriptor/loader identity here"),
            "instructionCounters" to unavailable("instruction", "no bytecode-offset execution counters are captured by JvmVitals"),
            "deoptimizationCorrelation" to unavailable("deoptimization", "recent deoptimization events lack enough class/descriptor identity for selected-class attachment"),
        )
    }

    private fun unavailable(scope: String, reason: String): Map<String, Any?> = linkedMapOf(
        "available" to false,
        "granularity" to scope,
        "reason" to reason,
    )

    private fun error(status: Int, code: String, vararg fields: Pair<String, Any?>): Map<String, Any?> {
        val out = LinkedHashMap<String, Any?>()
        out["error"] = code
        out["status"] = status
        for (field in fields) out[field.first] = field.second
        return out
    }
}
