package borg.trikeshed.hook

import borg.trikeshed.forge.persistence.CausalWal
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * Delivery NUID ledger backed by the existing append-only CausalWal. Replays accepted keys at
 * construction; [acceptOnce] atomically checks then appends before returning true.
 */
class CausalHookDeliveryLedger private constructor(
    private val wal: CausalWal,
    replayed: Set<String>,
) : HookDeliveryLedger {
    private val mutex = Mutex()
    private val accepted = HashSet<String>().apply { addAll(replayed) }

    override suspend fun acceptOnce(nuid: String): Boolean = mutex.withLock {
        if (nuid in accepted) return@withLock false
        wal.append("hook-delivery/$nuid", nuid.encodeToByteArray())
        accepted.add(nuid)
        true
    }

    companion object {
        /** Call from Dispatchers.IO: replay reads the WAL file synchronously. */
        fun open(path: File): CausalHookDeliveryLedger {
            val wal = CausalWal(path)
            val seen = HashSet<String>()
            for ((key, _) in wal.replay()) if (key.startsWith("hook-delivery/")) seen.add(key.removePrefix("hook-delivery/"))
            return CausalHookDeliveryLedger(wal, seen)
        }
    }
}
