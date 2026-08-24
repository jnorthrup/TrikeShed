package borg.trikeshed.forge.server

import borg.trikeshed.couch.CouchDatabase
import borg.trikeshed.couch.Document
import borg.trikeshed.couch.Field
import borg.trikeshed.job.ContentId
import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import javax.management.ObjectName

/**
 * Read-only discovery plus explicit CAS capture for HotSpot JEP 483/514 AOT cache blobs.
 *
 * A HotSpot `.aot` cache is an opaque VM archive, not a classfile; JDK 25's ClassFile API must
 * not be pretended to parse it. Individual compiled class blobs are decompiled separately by
 * [ClasspathSourceProjection]. This surface reports the process's actual AOT flags and makes the
 * configured archive replicable through Couch/CAS when [capture] is explicitly called.
 */
object HotSpotAotBlobAccess {
    private const val AOT_MXBEAN = "jdk.management:type=HotSpotAOTCache"

    fun snapshot(): Map<String, Any?> {
        val flags = flags()
        val path = artifactPath(flags)
        val exists = path?.let(Files::isRegularFile) == true
        val mxBeanRegistered = runCatching {
            ManagementFactory.getPlatformMBeanServer().isRegistered(ObjectName(AOT_MXBEAN))
        }.getOrDefault(false)
        return linkedMapOf(
            "vm" to System.getProperty("java.vm.name"),
            "runtime" to System.getProperty("java.runtime.version"),
            "mode" to (flags["AOTMode"] ?: "auto"),
            "cacheInput" to flags["AOTCache"],
            "cacheOutput" to flags["AOTCacheOutput"],
            "configuration" to flags["AOTConfiguration"],
            "artifact" to path?.toAbsolutePath()?.normalize()?.toString(),
            "exists" to exists,
            "bytes" to if (exists) Files.size(path) else null,
            "modifiedMs" to if (exists) Files.getLastModifiedTime(path).toMillis() else null,
            "mxBeanRegistered" to mxBeanRegistered,
            "endRecordingAvailable" to mxBeanRegistered,
            "archiveProjection" to "opaque-hotspot-aot-cache",
            "classfileProjection" to "java.lang.classfile is applied to mated build/live class blobs, not this archive",
        )
    }

    fun blob(): Pair<Path, ByteArray>? {
        val path = artifactPath(flags()) ?: return null
        if (!Files.isRegularFile(path)) return null
        return path to Files.readAllBytes(path)
    }

    /** Persist the configured AOT archive as a normal replicated attachment document. */
    fun capture(database: CouchDatabase): Map<String, Any?> {
        val (path, bytes) = blob()
            ?: return mapOf("error" to "aot_blob_unavailable", "state" to snapshot())
        val cid = database.blockPut(bytes)
        val id = "runtime/aot/${cid.hex}.aot"
        val document = Document(
            id,
            listOf(
                Field("contentType", "application/x-java-aot-cache"),
                Field("length", bytes.size.toString()),
                Field("contentId", cid.value),
                Field("sourcePath", path.toAbsolutePath().normalize().toString()),
                Field("runtime", System.getProperty("java.runtime.version")),
                Field("format", "hotspot-jep-483-aot-cache"),
            ),
        )
        val currentRev = database.store.head.getRev(id)
        if (database.store.get(id) == null) {
            check(database.store.put(document, currentRev)) { "failed to publish AOT attachment $id" }
        }
        val landed = database.attachment(id)
        check(landed != null && ContentId.of(landed.second) == cid) { "AOT attachment read-back failed for $id" }
        return linkedMapOf(
            "ok" to true,
            "id" to id,
            "cid" to cid.value,
            "bytes" to bytes.size,
            "contentType" to landed.first,
        )
    }

    private fun flags(): Map<String, String> {
        val values = linkedMapOf<String, String>()
        for (arg in ManagementFactory.getRuntimeMXBean().inputArguments) {
            for (name in FLAG_NAMES) {
                val prefix = "-XX:$name="
                if (arg.startsWith(prefix)) values[name] = arg.removePrefix(prefix)
            }
        }
        return values
    }

    private fun artifactPath(flags: Map<String, String>): Path? {
        val raw = flags["AOTCache"] ?: flags["AOTCacheOutput"] ?: return null
        return runCatching { Paths.get(raw) }.getOrNull()
    }

    private val FLAG_NAMES = listOf("AOTMode", "AOTCache", "AOTCacheOutput", "AOTConfiguration")
}
