package borg.trikeshed.kanban

import borg.trikeshed.userspace.nio.file.spi.JvmFileOperations
import java.io.File

/** JVM path boundary; WAL framing, recovery, sync and handle ownership live in commonMain. */
class JvmBoardWal(dir: File) : BoardWalPort by BoardFileWal(JvmFileOperations(), dir.path)
