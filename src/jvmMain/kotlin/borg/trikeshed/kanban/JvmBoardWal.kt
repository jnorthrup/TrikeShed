package borg.trikeshed.kanban

import borg.trikeshed.couch.isam.JvmDurableAppendLog
import java.io.File

/**
 * JvmBoardWal — [BoardWalPort] over [JvmDurableAppendLog] (the belief.wal
 * substrate: CRC-framed records, torn-tail truncation on replay). Group commit
 * is the ELEMENT's job: it appends a drained batch, then calls [flush] once.
 *
 * Lives under the forge home (`.kanban/board.wal`) — never a worktree, never
 * /tmp; the WAL IS the board's state (restart proof: replay ⇒ identical board).
 */
class JvmBoardWal(dir: File) : BoardWalPort {
    private val file = File(dir, "board.wal").apply { parentFile?.mkdirs() }
    private val log = JvmDurableAppendLog(file)
    private var seq = 0L

    override fun append(record: ByteArray): Long = log.append(++seq, record)

    override fun flush() {
        log.flush()
    }

    override suspend fun replay(onRecord: suspend (Long, ByteArray) -> Unit) {
        log.replay { s, payload ->
            if (s > seq) seq = s
            onRecord(s, payload)
        }
    }
}
