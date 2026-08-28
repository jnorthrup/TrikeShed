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
    private val prefix: String,
    replayed: Set<String>,
) : HookDeliveryLedger {
    private val mutex = Mutex()
    private val accepted = HashSet<String>().apply { addAll(replayed) }

    override suspend fun acceptOnce(nuid: String): Boolean = mutex.withLock {
        if (nuid in accepted) return@withLock false
        wal.append("$prefix$nuid", nuid.encodeToByteArray())
        accepted.add(nuid)
        true
    }

    companion object {
        /**
         * Call from Dispatchers.IO: replay reads the WAL file synchronously. The [prefix]
         * separates NUID lanes — inbound and outbound each open their own ledger so the
         * two acceptance spaces never collide.
         */
        fun open(path: File, prefix: String = "hook-delivery/"): CausalHookDeliveryLedger {
            val wal = CausalWal(path)
            val seen = HashSet<String>()
            for ((key, _) in wal.replay()) if (key.startsWith(prefix)) seen.add(key.removePrefix(prefix))
            return CausalHookDeliveryLedger(wal, prefix, seen)
        }
    }
}
