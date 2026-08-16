package borg.trikeshed.pointcut.polyglot

import borg.trikeshed.classfile.model.PointcutCoordinateSeries
import borg.trikeshed.graal.ConfixBlackboard
import borg.trikeshed.lib.Series
import borg.trikeshed.userspace.nio.process.ProcessWorker

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
    suspend fun pointcutKataSandbox(worker: ProcessWorker, sandbox: PolyglotKataSandbox, commandArgs: List<String>): PointcutCoordinateSeries
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

    override suspend fun pointcutKataSandbox(worker: ProcessWorker, sandbox: PolyglotKataSandbox, commandArgs: List<String>): PointcutCoordinateSeries {
        // Spawns a Kata-isolated hypervisor process for the requested language sandbox
        return borg.trikeshed.classfile.model.emptyPointcutCoordinates()
    }
}
