package borg.trikeshed.forge.server

import borg.trikeshed.litebike.JvmKanbanServer
import borg.trikeshed.util.JvmForgeIo
import kotlinx.coroutines.runBlocking

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
        // Delegates to canonical Litebike Kanban server boundary
        JvmKanbanServer.run(port, donorPath)
    }
}
