package borg.trikeshed.vm

import borg.trikeshed.graal.subvm.GuestBounds
import borg.trikeshed.graal.subvm.SubVmMain
import borg.trikeshed.pointcut.VmFacet
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File

/** A child process over stdin/stdout lines — the JVM's contribution to the process tier. */
class JvmProcessPipe(command: List<String>) : ProcessPipe {
    private val process: Process = ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.INHERIT).start()
    private val out: BufferedWriter = process.outputStream.bufferedWriter()
    private val input: BufferedReader = process.inputStream.bufferedReader()
    override val isAlive: Boolean get() = process.isAlive
    override fun writeLine(line: String) { out.write(line); out.newLine(); out.flush() }
    override fun readLine(): String? = input.readLine()
    override fun kill() { process.destroyForcibly() }
}

/** Tier 2 standalone: a `java … SubVmMain` child per guest (a whole Graal DAG launched as a process). */
object JvmProcessIsolateProvider : VmProvider {
    override val id: String = "jvm-process-isolate"
    private val java: String get() = File(System.getProperty("java.home"), "bin/java").path
    override fun isAvailable(): Boolean = File(java).canExecute()
    override fun report(): VmCapabilityReport = VmCapabilityReport(
        id, isAvailable(), listOf("js", "python"), "process", wallBudgetSupported = true, callSupported = true,
        note = "child JVM running SubVmMain over SubVmProtocol; separate address space",
    )

    override fun open(): VmHost = ProcessVmHost("jvm", setOf(VmFacet.GRAAL_JS, VmFacet.GRAAL_PYTHON)) { spec ->
        val bounds = GuestBounds.of(spec.facet)
        val statements = if (spec.budget.statements > 0) spec.budget.statements else GuestBounds.DEFAULT_STATEMENT_LIMIT
        val wall = if (spec.budget.wallMillis > 0) spec.budget.wallMillis else GuestBounds.DEFAULT_WALL_MILLIS
        JvmProcessPipe(listOf(java, "-Xss4m", "-cp", System.getProperty("java.class.path"), SubVmMain::class.java.name,
            bounds.languageId, spec.id, statements.toString(), wall.toString()))
    }
}

actual fun platformVmProviders(): List<VmProvider> = listOf(GraalHypervisorProvider, JvmProcessIsolateProvider)
