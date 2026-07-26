package borg.trikeshed.utils.kanban

import borg.trikeshed.userspace.nio.file.spi.JvmAppendWal
import java.io.File

/**
 * JVM factory for [JulesBoardStore] — the single construction site for the
 * canonical board WAL. Kills the copy-pasted
 * `JulesBoardStore(JvmAppendWal(File(forgeDir, "jules-board.wal")))` pattern.
 *
 * @param forgeDir  the forge home directory (~/.local/forge by default)
 */
fun JulesBoardStore.Companion.forForgeDir(forgeDir: File): JulesBoardStore {
    forgeDir.mkdirs()
    return JulesBoardStore(JvmAppendWal(File(forgeDir, JulesBoardStore.WAL_FILENAME)))
}
