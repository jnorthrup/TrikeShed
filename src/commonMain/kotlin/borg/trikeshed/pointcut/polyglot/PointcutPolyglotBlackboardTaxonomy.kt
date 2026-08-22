package borg.trikeshed.pointcut.polyglot

import borg.trikeshed.classfile.model.PointcutCoordinateSeries
import borg.trikeshed.graal.ConfixBlackboard
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.toSeries
import borg.trikeshed.userspace.nio.process.ProcessWorker
import borg.trikeshed.userspace.nio.process.ProcessWorkerFactory
import borg.trikeshed.userspace.nio.process.ProcessCapability
import borg.trikeshed.userspace.nio.process.ProcessSpec
import borg.trikeshed.userspace.nio.platform.spi.SystemOperations
import borg.trikeshed.classfile.model.PointcutCoordinate
import borg.trikeshed.classfile.model.BytecodePointcutKind
import borg.trikeshed.classfile.model.SourceCoordinate
import borg.trikeshed.classfile.model.SymbolCoordinate

/**
 * Defines a Kata Container-based sandbox specification for a polyglot language,
 * providing lighter-than-docker, CAS-aware, Pijul-trackable, broadcast couch and git state isolation.
 */
data class PolyglotKataSandbox(
    val language: String,
    val standardPackage: String,
    val runtime: String = "kata-runtime"
)

object PolyglotKataRegistry {
    val NODE = PolyglotKataSandbox("node", "docker.io/library/node:alpine")
    val PYTHON = PolyglotKataSandbox("python", "docker.io/library/python:alpine")
    val CLOJURE = PolyglotKataSandbox("clojure", "docker.io/library/clojure:alpine")
    val JAVA = PolyglotKataSandbox("java", "docker.io/library/openjdk:alpine")
    val RUBY = PolyglotKataSandbox("ruby", "docker.io/library/ruby:alpine")

    fun suggest(language: String): PolyglotKataSandbox? = when (language.lowercase()) {
        "node" -> NODE
        "python" -> PYTHON
        "clojure" -> CLOJURE
        "java" -> JAVA
        "ruby" -> RUBY
        else -> null
    }
}

/**
 * Defines the taxonomy for a polyglot classfile blackboard where
 * child GraalCE VMs can contribute pointcut coordinates.
 */
interface PolyglotBlackboardTaxonomy {
    val blackboard: ConfixBlackboard
    
    /**
     * Spawns a child GraalCE VM process to resolve or intercept pointcuts,
     * merging its contributions back into the central blackboard.
     */
    suspend fun pointcutChildVm(worker: ProcessWorker, commandArgs: List<String>): PointcutCoordinateSeries

    /**
     * Spawns a child Kata container sandbox to resolve or intercept pointcuts for a specific language,
     * leveraging lighter-than-docker hypervisor capabilities.
     */
    suspend fun pointcutKataSandbox(sandbox: PolyglotKataSandbox, commandArgs: List<String>): PointcutCoordinateSeries
}

/**
 * Implementation of the polyglot blackboard taxonomy.
 */
class GraalPolyglotBlackboardTaxonomy(
    override val blackboard: ConfixBlackboard = ConfixBlackboard.empty(),
    private val tspyPolyglotHost: TspyPolyglotHost? = null
) : PolyglotBlackboardTaxonomy {

    override suspend fun pointcutChildVm(worker: ProcessWorker, commandArgs: List<String>): PointcutCoordinateSeries {
        val host = tspyPolyglotHost ?: throw UnsupportedOperationException("Blocked by complex, platform-specific FFI requirements: evaluating python source via TspyPolyglotHost and mapping ExecutionListener-derived coordinates into PointcutCoordinateSeries is not currently supported.")
        return host.evaluatePython(commandArgs.joinToString(" "))
    }

    override suspend fun pointcutKataSandbox(sandbox: PolyglotKataSandbox, commandArgs: List<String>): PointcutCoordinateSeries {
        val worker = ProcessWorkerFactory.create(ProcessCapability("pointcut-kata", setOf("java")))
        val classpath = SystemOperations.default.getProperty("java.class.path") ?: "."
        val spec = ProcessSpec(
            command = "java",
            args = listOf("-cp", classpath, "borg.trikeshed.pointcut.KataSandboxRunner", sandbox.language) + commandArgs
        )
        val result = worker.spawn(spec)
        val buf = result.stdout

        // Find magic prefix "KATA"
        var offset = 0
        val magic = "KATA".encodeToByteArray()
        while (offset <= buf.size - magic.size) {
            if (buf[offset] == magic[0] && buf[offset+1] == magic[1] && buf[offset+2] == magic[2] && buf[offset+3] == magic[3]) {
                offset += 4
                break
            }
            offset++
        }

        val coords = mutableListOf<PointcutCoordinate>()
        while (offset + 24 <= buf.size) {
            val opcode = buf[offset]
            val phase = buf[offset + 1]
            val methodIdx = (buf[offset + 2].toInt() and 0xFF) or ((buf[offset + 3].toInt() and 0xFF) shl 8)
            val addr = (buf[offset + 4].toInt() and 0xFF) or
                       ((buf[offset + 5].toInt() and 0xFF) shl 8) or
                       ((buf[offset + 6].toInt() and 0xFF) shl 16) or
                       ((buf[offset + 7].toInt() and 0xFF) shl 24)
            val seq = (buf[offset + 8].toInt() and 0xFF) or
                      ((buf[offset + 9].toInt() and 0xFF) shl 8) or
                      ((buf[offset + 10].toInt() and 0xFF) shl 16) or
                      ((buf[offset + 11].toInt() and 0xFF) shl 24)

            // Map the unpacked struct to a PointcutCoordinate.
            // Since we lack the original string table in the parent JVM, we synthesize the coordinates
            // based on the raw wire protocol IDs.
            coords.add(PointcutCoordinate(
                kind = BytecodePointcutKind.INVOKE,
                jvmOpcode = "OP_0x${opcode.toUByte().toString(16).padStart(2, '0').uppercase()}",
                bytecodeOffset = addr,
                source = SourceCoordinate("kata-sandbox", seq, 0, sandbox.language, 0),
                symbol = SymbolCoordinate("method$methodIdx", "method$methodIdx", "phase$phase", "unknown", "unknown")
            ))
            offset += 24
        }
        return coords.toSeries()
    }
}
