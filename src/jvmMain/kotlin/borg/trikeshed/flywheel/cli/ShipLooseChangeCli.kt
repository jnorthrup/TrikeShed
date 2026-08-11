package borg.trikeshed.flywheel.cli

import borg.trikeshed.userspace.nio.file.spi.JvmAppendWal
import borg.trikeshed.utils.kanban.JulesBoardStore
import borg.trikeshed.jules.JulesCause
import java.io.File
import kotlinx.coroutines.runBlocking

/**
 * Queue the current uncommitted working-tree fixes as durable Jules work items.
 * Each entry names the exact files it owns so dispatch lands in a disjoint locality.
 */
fun main() {
    val forgeHome = File(System.getProperty("user.home"), ".local/forge")
    val store = JulesBoardStore(JvmAppendWal(File(forgeHome, "jules-board.wal")))
    val now = System.currentTimeMillis()

    val entries = listOf(
        Triple(
            "fix:htx-eagain-eof",
            "Fix HtxReactorElement.readChunk EAGAIN/EOF confusion",
            """
                |The Oroboros daemon's entire Jules dispatch path was dark: every TLS
                |handshake died with "remote peer closed the channel" before the server's
                |ServerHello could arrive.
                |
                |Root cause: HtxReactorElement.readChunk() on a non-blocking socket treated
                |`read() == 0` (EAGAIN, no data yet) as EOF. The first read fired
                |immediately after the ClientHello write; the server had not yet
                |responded, result was 0, and the handshake aborted. Only -1 is EOF.
                |
                |Fix: loop on result == 0 with a 10ms delay; return null only on -1.
                |File: src/commonMain/kotlin/borg/trikeshed/htx/HtxReactorElement.kt
                |Verify: an --once daemon run dispatches queued work (Dispatched > 0).
                |Gate: ./gradlew jvmMainClasses --console=plain
            """.trimMargin()
        ),
        Triple(
            "fix:jvmchannel-connect-race",
            "Fix JvmChannelOperations connect/write race + worker rejection",
            """
                |Two defects in the userspace.nio JVM channel stub broke every HTX
                |exchange under the daemon's parallel fan-out.
                |
                |(a) connect() returned 0 ("connected") while scheduling the real
                |    connect/finishConnect on a worker thread. The first TLS ClientHello
                |    write raced it and threw NotYetConnectedException, swallowed into
                |    -1 ("HTX reactor write failed"). Fix: run connect+finishConnect
                |    inline so a >= 0 return genuinely means connected.
                |
                |(b) ioWorkers used ArrayBlockingQueue(capacity=2) + AbortPolicy; under
                |    parallel dispatch, submissions were rejected mid-exchange and
                |    rejectSubmitted() failed the ops with -1. Fix: unbounded
                |    LinkedBlockingQueue; queue growth is the reactor's backpressure.
                |
                |File: src/jvmMain/kotlin/borg/trikeshed/userspace/nio/channels/spi/JvmChannelOperations.kt
                |Verify: no NotYetConnectedException, no "HTX reactor write failed" in a
                |  full --once cycle.
                |Gate: ./gradlew jvmMainClasses --console=plain
            """.trimMargin()
        ),
    )

    runBlocking {
        for ((workId, title, spec) in entries) {
            store.appendWork(workId, JulesCause.WorkQueued(
                workId = workId,
                tier = "forge",
                title = title,
                spec = spec,
                parent = null,
                score = 0.9,
                at = now,
            ))
            println("[SEED] queued $workId")
        }
        println("[SEED] done: ${entries.size} WorkQueued entries appended to jules-board.wal")
    }
}
