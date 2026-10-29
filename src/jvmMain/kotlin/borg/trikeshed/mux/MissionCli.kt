package borg.trikeshed.mux

import borg.trikeshed.context.nuid.*
import borg.trikeshed.lib.*
import borg.trikeshed.htx.*
import borg.trikeshed.parse.confix.*
import borg.trikeshed.userspace.reactor.MuxReactorElement
import borg.trikeshed.userspace.nio.channels.spi.*
import borg.trikeshed.userspace.nio.spi.NioSupervisor
import kotlinx.coroutines.*
import borg.trikeshed.agent.modelmux.*
import kotlin.system.exitProcess
import java.lang.System

/** JVM argument/stdio boundary only; work admission and execution are commonMain. */
object MissionCli {
    @JvmStatic
    fun main(args: Array<String>) {
        if (args.firstOrNull() == "--inspect" && args.size == 2) {
            val status = try { runBlocking { inspect(args[1]) }; 0 }
            catch (failure: Throwable) {
                System.err.println("mission inspection failed: ${failure::class.simpleName}")
                1
            }
            exitProcess(status)
        }
        val planPath = if (args.firstOrNull() == "--plan" && args.size == 2) args[1] else null
        if (planPath == null && (args.firstOrNull() != "--" || args.size < 2)) {
            System.err.println("Usage: mission -- BASH_ARGUMENTS... | mission --plan FILE")
            exitProcess(64)
        }
        val status = try { runBlocking { execute(args.drop(1).toSeries(), planPath) } }
        catch (failure: Throwable) {
            // Avoid writing exception messages that may contain command text or credentials.
            System.err.println("mission failed: ${failure::class.simpleName}")
            Regex("HTTP\\s+(\\d{3})").find(failure.message.orEmpty())?.groupValues?.get(1)?.let { status ->
                System.err.println("mission http-status: $status")
            }
            failure.stackTrace.take(6).forEach { frame ->
                System.err.println("mission location: ${frame.className}.${frame.methodName}:${frame.lineNumber}")
            }
            1
        }
        exitProcess(status)
    }

    suspend fun inspect(path: String) {
        val nio = NioSupervisor()
        try {
            nio.open()
            val files = checkNotNull(nio.service<borg.trikeshed.userspace.nio.file.spi.FileOperations>())
            val events = missionEvents(files.readString(path))
            val assertions = missionAssertionState(events).view.associate { it.a to it.b.name }
            println(borg.trikeshed.parse.jsonOf(mapOf("events" to events.size, "assertions" to assertions)))
        } finally { withContext(NonCancellable) { nio.drain(); nio.close() } }
    }

    fun outputTask(task: MissionTask, output: (Any?) -> Boolean): MissionTask =
        task.copy(accept = { value -> output(value) && task.accept(value) })

    suspend fun execute(arguments: Series<String>, planPath: String? = null): Int {
        val nio = NioSupervisor()
        var journal: MissionJournal? = null
        var transport: HtxElement? = null
        var reactor: MuxReactorElement? = null
        val fanout = NuidFanoutElement(parentJob = currentCoroutineContext()[Job])
        try {
            nio.open()
            val processes = checkNotNull(nio.service<ProcessOperations>())
            System.getenv("HARNESS_MISSION_JOURNAL")?.let { path ->
                val files = checkNotNull(nio.service<borg.trikeshed.userspace.nio.file.spi.FileOperations>())
                journal = MissionJournal(files, path)
            }
            fanout.open()
            val capability = Capability.Process("bash")
            fanout.register(Workgroup("bash", Subnet.core, TraitSpace { s_[capability] })) { claim ->
                withContext(processes) { executeBashWork(claim.payload as BashWork) }
            }
            var commandStatus = 1
            var assertions: Series<String> = emptySeriesOf()
            val address = nuid(capability, Nonce.RandomBytes(), Subnet.core)
            val modelCapability = Capability.Process("model-completion")
            val modelAddress = nuid(modelCapability, Nonce.RandomBytes(), Subnet.core)
            val tasks = if (planPath == null) s_[MissionTask("command", address, payload = BashWork(arguments))]
            else {
                val files = checkNotNull(nio.service<borg.trikeshed.userspace.nio.file.spi.FileOperations>())
                val source = files.readString(planPath)
                assertions = missionAssertions(source)
                bashMissionPlan(source, address, modelAddress)
            }
            if (tasks.view.any { it.payload is ModelWork }) {
                val keys = borg.trikeshed.agent.keymux.KeyMux { bind("llm.beam.key", borg.trikeshed.agent.keymux.EnvVarSource("REFLECTION_API_KEY")) }
                val mux = ModelMux(keys) {
                    model("Beam-1.0-501B-A23B", setOf("chat", "tools"),
                        baseUrl = "https://api.reflection.ai/openai/v1", provider = "beam")
                }
                val ownedTransport = openHtxElement(nioSupervisor = nio)
                transport = ownedTransport
                val ownedReactor = MuxReactorElement()
                reactor = ownedReactor
                ownedReactor.open()
                fanout.register(Workgroup("model", Subnet.core, TraitSpace { s_[modelCapability] })) { claim ->
                    withContext(ownedTransport + ownedReactor) { executeModelWork(mux, claim.payload as ModelWork) }
                }
            }
            val output: (Any?) -> Boolean = { value ->
                if (value is ProcessResult) {
                    System.out.write(value.stdout)
                    System.err.write(value.stderr)
                    commandStatus = value.exitCode
                    value.exitCode == 0
                } else {
                    @Suppress("UNCHECKED_CAST")
                    val response = value as ConfixDoc
                    System.out.write(response.src.toArray())
                    System.out.println()
                    commandStatus = 0
                    true
                }
            }
            val outputTasks = tasks α { task -> outputTask(task, output) }
            executeMission(fanout, outputTasks, assertions = assertions, observe = { event ->
                    journal?.record(event)
                    System.err.println("mission\t${event.task}\t${event.phase}\t${event.claimId}\t${event.worker}")
                })
            return commandStatus
        } finally {
            withContext(NonCancellable) {
                try { fanout.drain(); fanout.close() }
                finally {
                    try { journal?.close() }
                    finally {
                        try { reactor?.drain(); reactor?.close() }
                        finally {
                            try { transport?.drain(); transport?.close() }
                            finally { nio.drain(); nio.close() }
                        }
                    }
                }
            }
        }
    }
}
