package borg.trikeshed.utils.kanban

import borg.trikeshed.userspace.nio.file.spi.JvmAppendWal
import java.io.File

/**
 * JVM factory for [SessionBoardStore] — the single construction site for the
 * canonical board WAL. Kills the copy-pasted
 * `SessionBoardStore(JvmAppendWal(File(forgeDir, "jules-board.wal")))` pattern.
 *
 * @param forgeDir  the forge home directory (~/.local/forge by default)
 */
fun SessionBoardStore.Companion.forForgeDir(forgeDir: File): SessionBoardStore {
    forgeDir.mkdirs()
    return SessionBoardStore(JvmAppendWal(File(forgeDir, SessionBoardStore.WAL_FILENAME)))
}
