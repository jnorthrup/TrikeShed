package borg.trikeshed.lcnc

import borg.trikeshed.graal.subvm.CamelRuntime
import borg.trikeshed.graal.subvm.GuestModules
import borg.trikeshed.job.*
import borg.trikeshed.lib.*
import borg.trikeshed.parse.*
import borg.trikeshed.userspace.nio.channels.spi.JvmProcessProtocolPipes
import borg.trikeshed.userspace.nio.file.spi.*
import borg.trikeshed.userspace.nio.process.ProcessCaptureChannel
import borg.trikeshed.vm.*
import kotlinx.coroutines.*
import java.io.File
import java.io.ByteArrayInputStream
import java.net.URLClassLoader
import java.util.jar.Manifest
import java.util.Base64
import java.util.zip.ZipInputStream
import kotlin.coroutines.coroutineContext

/** A host-captured deployment closure. No request can select a command, classpath or worker. */
class CamelCorpusProcess private constructor(
    private val files: FileOperations,
    private val java: String,
    private val classPath: List<String>,
    private val moduleHome: String,
    private val pins: Map<String, String>,
    private val directories: Set<String>,
    private val workerClass: String,
    private val testProfile: Boolean,
) {
    data class Execution(val reply: Map<String, Any?>, val record: Map<String, Any?>)

    private fun verify() {
        require(snapshot(files, java, classPath, moduleHome, directories) == pins) { "Bounded Camel deployment changed" }
    }

    private fun descriptor() = _m["kind" j if (testProfile) "test-class-directory-closure" else "packaged-main-jar-closure",
        "workerClass" j workerClass, "java" j java, "classPath" j classPath, "moduleHome" j moduleHome, "pins" j pins,
        "javaVerification" j "launcher-sha256", "networkIsolationProvided" j false,
        "memoryLimit" j "JVM-Xmx256m-not-an-OS-memory-limit"]

    /** Returns only after the exact owned process and its pipe readers/writer have joined. */
    internal suspend fun execute(request: Map<String, Any?>, wallMillis: Long = WALL_MILLIS): Execution {
        require(wallMillis in 1..WALL_MILLIS)
        val input = jsonOf(request).encodeToByteArray(throwOnInvalidSequence = true)
        require(input.size <= JSON_LIMIT) { "Camel worker request limit" }
        verify()
        val started = System.nanoTime()
        val pipes = withTimeout(wallMillis) { JvmProcessProtocolPipes.open(
            command = { _, _ -> _l[java, "-Xmx256m", "-XX:ActiveProcessorCount=2", "-cp",
                classPath.joinToString(File.pathSeparator), workerClass] },
            environment = _m["TRIKESHED_AIRGAP" j "1", GuestModules.HOME_ENV j moduleHome], mergeError = true) }
        val capture = try {
            val elapsed = (System.nanoTime() - started) / 1_000_000
            val remaining = wallMillis - elapsed
            if (remaining <= 0) {
                withContext(NonCancellable) { pipes.revoke(0); pipes.close() }
                error("Camel worker exceeded its launch deadline")
            }
            ProcessCaptureChannel(coroutineContext, pipes, stdin = input, maxBytes = OUTPUT_LIMIT,
                timeoutMillis = remaining, failOnOverflow = true).await()
        } catch (failure: Throwable) {
            var exit: Int? = null
            val cleanup = runCatching { withContext(NonCancellable) {
                try { pipes.revoke(0); exit = pipes.awaitExit() } finally { pipes.close() }
            } }
            cleanup.exceptionOrNull()?.let { if (it !== failure) failure.addSuppressed(it) }
            val record = _m["kind" j "bounded-camel-child-execution/1", "phase" j "ROUTE_EXECUTION",
                "processId" j pipes.processId, "exit" j exit, "joined" j cleanup.isSuccess,
                "capture" j "refused-before-complete-capture", "error" j failure::class.java.name,
                "wallLimitMillis" j wallMillis, "outputLimitBytes" j OUTPUT_LIMIT,
                "elapsedNanos" j (System.nanoTime() - started), "requestCid" j ContentId.of(input).value,
                "profileCheckedBefore" j true, "profileCheckedAfter" j runCatching { verify() }.isSuccess,
                "profile" j descriptor()]
            if (failure is CancellationException) throw Cancelled(record, failure)
            throw Refused(failure.message ?: "Camel worker capture refused", record, failure)
        }
        val postVerification = runCatching { verify() }
        val utf8 = runCatching { capture.bytes.decodeToString(throwOnInvalidSequence = true) }
        val record = _m["kind" j "bounded-camel-child-execution/1", "phase" j "ROUTE_EXECUTION",
            "processId" j pipes.processId, "exit" j capture.exit, "timedOut" j capture.timedOut,
            "joined" j true, "keptBytes" j capture.kept, "droppedBytes" j capture.dropped,
            "wallLimitMillis" j wallMillis, "outputLimitBytes" j OUTPUT_LIMIT, "elapsedNanos" j (System.nanoTime() - started),
            "output" j utf8.getOrNull(), "outputUtf8Valid" j utf8.isSuccess,
            "outputBase64" j if (utf8.isFailure) Base64.getEncoder().encodeToString(capture.bytes) else null,
            "requestCid" j ContentId.of(input).value,
            "profileCheckedBefore" j true, "profileCheckedAfter" j postVerification.isSuccess,
            "profile" j descriptor()]
        try {
            postVerification.getOrThrow()
            require(!capture.timedOut && capture.exit == 0 && capture.dropped == 0L) { "Camel worker did not complete within its bounded capture" }
            val frames = utf8.getOrThrow().lineSequence().filter { it.startsWith(PREFIX) }.toList()
            require(frames.size == 1) { "Camel worker must return exactly one bounded reply" }
            val json = frames.single().removePrefix(PREFIX)
            require(json.encodeToByteArray().size <= JSON_LIMIT) { "Camel worker reply limit" }
            val envelope = DocumentResult.parse(json)
            require(envelope.keys == _s["kind", "requestCid", "reply", "workerOrigin", "camelOrigin"] && envelope["kind"] == PROTOCOL &&
                envelope["requestCid"] == ContentId.of(input).value) { "Camel worker reply identity differs" }
            val workerOrigin = files.canonicalPath(File(CamelCorpusWorker::class.java.protectionDomain.codeSource.location.toURI()).path)
            require(envelope["workerOrigin"] == workerOrigin && envelope["camelOrigin"] in pins.keys &&
                (envelope["camelOrigin"] as? String)?.startsWith("$moduleHome${File.separator}camel${File.separator}") == true) {
                "Camel worker class origins differ from the pinned deployment"
            }
            val raw = envelope["reply"] as? Map<*, *> ?: error("Camel worker reply required")
            val reply = raw.entries.associate { (key, value) -> (key as? String ?: error("Camel reply key")) to value }
            return Execution(reply, record)
        } catch (failure: Exception) { throw Refused(failure.message ?: "Camel worker reply refused", record, failure) }
    }

    class Refused(message: String, val record: Map<String, Any?>, cause: Throwable? = null) : IllegalStateException(message, cause)
    class Cancelled(val record: Map<String, Any?>, cause: CancellationException) : CancellationException(cause.message) {
        init { initCause(cause) }
    }

    companion object {
        const val PROTOCOL = "english-corpus-camel-worker/1"
        const val PREFIX = "CAMEL_CORPUS_ROUTE/1 "
        const val JSON_LIMIT = 65_536
        internal const val MANIFEST_LIMIT = 2 * 1024 * 1024
        const val OUTPUT_LIMIT = 131_072
        const val WALL_MILLIS = 10_000L
        private const val WORKER = "borg.trikeshed.lcnc.CamelCorpusWorker"

        /** Whole leading manifest: signed deployments can carry many per-entry digests. */
        internal fun checkJarManifest(bytes: ByteArray) {
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                var entry = zip.nextEntry
                if (entry?.name == "META-INF/") entry = zip.nextEntry
                require(entry?.name == "META-INF/MANIFEST.MF") { "Deployment JAR requires a leading bounded manifest" }
                val manifest = zip.readNBytes(MANIFEST_LIMIT + 1)
                require(manifest.size <= MANIFEST_LIMIT) { "Deployment JAR manifest exceeds 2MiB" }
                manifest.decodeToString(throwOnInvalidSequence = true)
                require(Manifest(ByteArrayInputStream(manifest)).mainAttributes.getValue("Class-Path").isNullOrBlank()) {
                    "Transitive JAR manifest Class-Path is refused; deploy explicit pinned JARs"
                }
            }
        }

        /** Production refuses loose main/dependency classes. This is not the native OWN adapter. */
        fun packaged(files: FileOperations): CamelCorpusProcess {
            val main = files.canonicalPath(File(CamelCorpusProcess::class.java.protectionDomain.codeSource.location.toURI()).path)
            require(files.isFile(main) && main.endsWith(".jar")) { "Bounded Camel requires an immutable packaged main JAR" }
            val cp = System.getProperty("java.class.path").split(File.pathSeparator).map(files::canonicalPath)
            require(main in cp && cp.isNotEmpty() && cp.size <= 256 && cp.toSet().size == cp.size &&
                cp.all { files.isFile(it) && it.endsWith(".jar") }) { "Bounded Camel requires a fixed JAR-only deployment classpath" }
            return capture(files, cp, emptySet(), WORKER, false)
        }

        /** Internal Gradle fixture seam: pinned class-directory closure, never a production fallback. */
        internal fun testing(files: FileOperations, fixture: Class<*>, worker: Class<*> = CamelCorpusWorker::class.java): CamelCorpusProcess {
            val paths = linkedSetOf<String>()
            var loader: ClassLoader? = fixture.classLoader
            while (loader != null) {
                if (loader is URLClassLoader) loader.getURLs().filter { it.protocol == "file" }.forEach { paths.add(File(it.toURI()).path) }
                loader = loader.parent
            }
            s_[CamelCorpusProcess::class.java, fixture, worker].view.forEach { paths.add(File(it.protectionDomain.codeSource.location.toURI()).path) }
            System.getProperty("java.class.path").split(File.pathSeparator).filter(String::isNotBlank).forEach(paths::add)
            val cp = paths.map(files::canonicalPath).distinct()
            require(cp.size in 1..256)
            val dirs = cp.filter(files::isDir).toSet()
            require(cp.all { it in dirs || files.isFile(it) && it.endsWith(".jar") })
            return capture(files, cp, dirs, worker.name, true)
        }

        private fun capture(files: FileOperations, cp: List<String>, dirs: Set<String>, worker: String, test: Boolean): CamelCorpusProcess {
            val java = files.canonicalPath(files.resolvePath(System.getProperty("java.home"), "bin", "java"))
            require(cp.joinToString(File.pathSeparator).encodeToByteArray().size <= 65_536) { "Camel deployment classpath limit" }
            val home = files.canonicalPath(requireNotNull(GuestModules.root()) { "Installed Camel module required" }.path)
            val pins = snapshot(files, java, cp, home, dirs)
            return CamelCorpusProcess(files, java, cp.toList(), home, pins, dirs.toSet(), worker, test)
        }

        private fun snapshot(files: FileOperations, java: String, cp: List<String>, home: String, dirs: Set<String>): Map<String, String> {
            val pins = linkedMapOf<String, String>()
            fun file(path: String, limit: Int = 256 * 1024 * 1024) {
                require(files.canonicalPath(path) == path && files.isFile(path)) { "Deployment file is absent or changed" }
                val bytes = NativeHornProfile.boundedRead(files, path, limit)
                if (path.endsWith(".jar")) checkJarManifest(bytes)
                pins[path] = ContentId.of(bytes).value.removePrefix("sha256:")
            }
            file(java)
            for (entry in cp) if (entry !in dirs) file(entry) else {
                require(files.isDir(entry)) { "Test class directory is absent" }
                val closure = linkedMapOf<String, String>()
                val visited = HashSet<String>()
                fun visit(directory: String) {
                    require(visited.size < 20_000 && visited.add(directory)) { "Test directory closure cycle or limit" }
                    for (child in files.listDir(directory).sorted()) {
                        val path = files.canonicalPath(files.resolvePath(directory, child))
                        require(path.startsWith("$entry${File.separator}")) { "Test closure escapes its code source" }
                        if (files.isDir(path)) visit(path) else {
                            require(closure.size < 100_000)
                            closure[path.removePrefix(entry)] = NativeHornProfile.sha256(files, path, 16 * 1024 * 1024)
                        }
                    }
                }
                visit(entry)
                require(closure.isNotEmpty()) { "Empty test code source" }
                pins[entry] = ContentId.of(CanonicalCbor.encodeMap(closure)).value
            }
            val module = files.resolvePath(home, "camel")
            require(!files.exists(files.resolvePath(module, GuestModuleLayout.CLASSES))) { "Loose Camel module overlays are refused" }
            val manifest = files.canonicalPath(files.resolvePath(module, GuestModuleLayout.MANIFEST))
            file(manifest, 64 * 1024)
            val parsed = GuestModuleManifest.parse(NativeHornProfile.boundedRead(files, manifest, 64 * 1024).decodeToString(), "camel")
            require(parsed.module == "camel" && parsed.parent == null && parsed.entries.size == 58 && parsed.entries.map { it.file }.toSet().size == 58) {
                "Expected the fixed standalone 58-JAR Camel module"
            }
            val lib = files.resolvePath(module, GuestModuleLayout.LIB)
            val names = files.listDir(lib).sorted()
            require(names == parsed.entries.map { it.file }.sorted() && names.all { it.endsWith(".jar") && '/' !in it && '\\' !in it }) { "Camel module manifest closure differs" }
            for (entry in parsed.entries) {
                val path = files.canonicalPath(files.resolvePath(lib, entry.file)); file(path)
                val fd = files.open(path); val size = try { files.size(fd) } finally { files.close(fd) }
                require(size == entry.size && pins[path] == entry.sha256) { "Camel module artifact pin differs" }
            }
            return pins
        }
    }
}

/** Fixed entrypoint: the parent supplies only checked CIDs and one two-valued lexical signal. */
object CamelCorpusWorker {
    @JvmStatic fun main(args: Array<String>) {
        require(args.isEmpty() && System.getenv("TRIKESHED_AIRGAP") == "1") { "Fixed airgapped worker invocation required" }
        val bytes = System.`in`.readNBytes(CamelCorpusProcess.JSON_LIMIT + 1)
        require(bytes.size <= CamelCorpusProcess.JSON_LIMIT) { "Camel worker request limit" }
        val value = DocumentResult.parse(bytes.decodeToString(throwOnInvalidSequence = true))
        require(value.keys == _s["kind", "receiptCid", "corpusCid", "matrixCid", "topicCid", "signal"] &&
            value["kind"] == CamelCorpusProcess.PROTOCOL) { "Unexpected Camel worker request fields" }
        fun cid(key: String): String = (value[key] as? String ?: error("Worker CID required")).also { ContentId(it) }
        val receipt = cid("receiptCid"); val corpus = cid("corpusCid"); val matrix = cid("matrixCid")
        val topic = value["topicCid"]?.let { require(it is String); ContentId(it); it }
        val signal = value["signal"] as? String ?: error("Worker signal required"); CamelCorpusRouting.lane(signal)
        val plan = CamelCorpusRouting.plan(receipt, corpus, matrix, topic)
        val result = CamelRuntime.execute("english-corpus-triage", plan, CamelRuntime.Request(signal))
        CamelCorpusRouting.checkedReply(result, receipt, corpus, matrix, topic, signal)
        val loader = checkNotNull(GuestModules.loaderFor("camel"))
        fun origin(type: Class<*>) = File(type.protectionDomain.codeSource.location.toURI()).canonicalPath
        val reply = jsonOf(_m["kind" j CamelCorpusProcess.PROTOCOL, "requestCid" j ContentId.of(bytes).value, "reply" j result,
            "workerOrigin" j origin(CamelCorpusWorker::class.java), "camelOrigin" j origin(loader.loadClass("org.apache.camel.impl.DefaultCamelContext"))])
        require(reply.encodeToByteArray().size <= CamelCorpusProcess.JSON_LIMIT)
        println(CamelCorpusProcess.PREFIX + reply)
    }
}
