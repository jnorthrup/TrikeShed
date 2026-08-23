package borg.trikeshed.forge.server

import borg.trikeshed.graal.ConfixBlackboard
import borg.trikeshed.graal.subvm.Hypervisor
import borg.trikeshed.litebike.JvmKanbanServer
import borg.trikeshed.util.JvmForgeIo
import borg.trikeshed.vm.HypervisorVmHost
import borg.trikeshed.vm.VmSupervisor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking

/**
 * The Forge host server: the litebike kanban server plus the two extension wires —
 * `/blackboard/…` ([BlackboardWire]) and `/api/vm/…` ([VmWire]) — sharing ONE blackboard, so every
 * sub-VM spawn/revoke/delegation lands where the IDE and the PWA already read facts.
 */
object KanbanServerMain {

    @JvmStatic
    fun main(args: Array<String>) {
        val parsed = JvmForgeIo.parseKanbanServerArgs(
            args = args,
            programName = "KanbanServerMain",
            usage = "Usage: KanbanServerMain [--port N] [--donor path]",
        )
        runBlocking { run(parsed.port, parsed.donor) }
    }

    suspend fun run(port: Int, donorPath: String?) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val blackboard = ConfixBlackboard.empty()
        val wire = BlackboardWire(blackboard, scope)
        val host = HypervisorVmHost(Hypervisor(blackboard = blackboard))
        VmSupervisor.install(host)   // ForgeApp.renderHtml (served at /) sees the same host the wire serves
        val vmWire = VmWire(host, scope)
        JvmKanbanServer(
            extraRoutes = listOf(vmWire::route, wire::route),
            streamingPaths = setOf(VmWire.EVENTS_PATH, "/blackboard/facts"),
        ).run(port = port, donorPath = donorPath)
    }
}
