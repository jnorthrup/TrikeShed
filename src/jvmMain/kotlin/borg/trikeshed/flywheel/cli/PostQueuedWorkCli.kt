package borg.trikeshed.flywheel.cli

import borg.trikeshed.htx.openHtxElement
import borg.trikeshed.jules.JulesRestClient
import borg.trikeshed.userspace.nio.file.spi.JvmAppendWal
import borg.trikeshed.utils.kanban.JulesBoardStore
import keymux.KeyMux
import java.io.File
import kotlinx.coroutines.runBlocking

/**
 * One-shot manual dispatch of a queued WorkQueued entry to Jules.
 *
 * Posts the entry via [JulesRestClient.createSession] using the SAME title
 * format as FlywheelDriver.dispatchTitle ("[work:<id>] <title>") so the
 * daemon adopts the session by title match on its next dispatch cycle and
 * writes the WorkDispatched receipt itself — the causal chain stays
 * daemon-owned; this CLI performs no WAL mutation.
 *
 * Usage: PostQueuedWorkCliKt <workId> [forgeHome]
 */
fun main(args: Array<String>) {
    val workId = args.firstOrNull { !it.startsWith("--") }
        ?: error("usage: PostQueuedWorkCli <workId> [forgeHome]")
    val forgeHome = File(args.getOrNull(1) ?: System.getenv("TRIKESHED_HOME")
        ?: File(System.getProperty("user.home"), ".local/forge").path)
    val walFile = File(forgeHome, JulesBoardStore.WAL_FILENAME)
    require(walFile.exists()) { "no WAL at $walFile" }

    val entry = JulesBoardStore(JvmAppendWal(walFile)).loadQueue()
        .firstOrNull { it.workId == workId }
        ?: error("WorkQueued $workId not found in ${walFile.path}")

    val title = "[work:${entry.workId}] ${entry.title}"
    println("[POST] $title")
    println("[POST] spec=${entry.spec.encodeToByteArray().size} bytes, tier=${entry.tier}, score=${entry.score}")

    val keyMux = KeyMux { env() }
    val client = JulesRestClient(keyMux)
    val htxElement = runBlocking { openHtxElement() }
    try {
        val sessionId = runBlocking {
            kotlinx.coroutines.withContext(htxElement) {
                client.createSession(prompt = entry.spec, title = title)
            }
        }
        println("[POST] session=$sessionId")
        println("[POST] daemon will adopt by title-match and append WorkDispatched on its next cycle")
    } finally {
        runBlocking { htxElement.close() }
    }
}
