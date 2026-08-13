package borg.trikeshed.jules

import borg.trikeshed.userspace.nio.file.spi.JvmFileOperations
import borg.trikeshed.util.oroboros.FileCasStore
import borg.trikeshed.utils.kanban.JulesBoardStore
import borg.trikeshed.utils.kanban.forForgeDir
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Read-only, byte-exact projection of an explicitly selectable Jules artifact.
 * Freshness is supplied by the daemon's single API poller; this tool performs
 * no HTTP, repository mutation, review, or settlement operation.
 */
object JulesArtifactCli {
    @JvmStatic
    fun main(args: Array<String>) = runBlocking {
        require(args.size in 2..3 && args[0] in setOf("patch", "report")) {
            "usage: JulesArtifactCli <patch|report> <session-id> [forge-dir]"
        }
        val kind = args[0]
        val sessionId = args[1].substringAfterLast('/')
        val forgeDir = File(args.getOrNull(2) ?: defaultForgeDir())
        val store = JulesBoardStore.forForgeDir(forgeDir)
        val card = requireNotNull(withContext(Dispatchers.IO) { store.load()[sessionId] }) {
            "no WAL-observed Jules session $sessionId"
        }
        require(card.snapshot.state in TERMINAL_STATES) {
            "session $sessionId is ${card.snapshot.state}; artifact is still mutable"
        }
        val cas = FileCasStore(JvmFileOperations(), File(forgeDir, "cas").absolutePath)
        val continuity = JulesPatchContinuityStore(cas, store)
        val bytes = when (kind) {
            "patch" -> {
                val selected = selectJulesPatchForDrain(card.causes)
                    as? JulesPatchDrainSelection.Selected
                    ?: error("session $sessionId has no currently selected patch")
                continuity.bytes(selected)
            }
            else -> {
                val selected = selectJulesReportForSettlement(card.causes)
                    as? JulesReportSettlementSelection.Selected
                    ?: error("session $sessionId has no currently reviewed report")
                continuity.reportBytes(selected)
            }
        }
        withContext(Dispatchers.IO) {
            System.out.write(bytes)
            System.out.flush()
        }
    }

    private fun defaultForgeDir(): String =
        System.getenv("TRIKESHED_HOME") ?: File(System.getProperty("user.home"), ".local/forge").path

    private val TERMINAL_STATES = setOf("COMPLETED", "FINISHED", "FAILED", "CANCELLED")
}
