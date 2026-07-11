@file:Suppress("NonAsciiCharacters", "UNCHECKED_CAST", "NAME_SHADOWING")

package borg.trikeshed.couch

import borg.trikeshed.cursor.*
import borg.trikeshed.lib.*
import borg.trikeshed.parse.confix.*

// ─────────────────────────────────────────────────────────────────────────────
// CONFIX DOCSTORE — CouchDB-style _id/_rev document store
// ─────────────────────────────────────────────────────────────────────────────

enum class RevPolicy {
    UUID,
    TIMESTAMP,
    SEQUENTIAL,
}

data class ConfixDocStoreEntry(
    val id:  String,
    var rev: String,
    val doc: ConfixDoc,
    val role: ConfixRole = ConfixRole.OBSERVATION,
    val timestamp: Long = System.currentTimeMillis(),
)

class ConfixDocStore(
    val wal: borg.trikeshed.couch.isam.ConfixWal? = null,
    val revPolicy: RevPolicy = RevPolicy.UUID,
    val indexBy: List<String> = listOf("_id"),
) {
    private val byId = mutableMapOf<String, ConfixDocStoreEntry>()
    private val byRev = mutableMapOf<String, ConfixDocStoreEntry>()
    private var seq: Long = 0L

    operator fun get(id: String): ConfixDocStoreEntry? = byId[id]
    fun contains(id: String): Boolean = byId.containsKey(id)
    val size: Int get() = byId.size

    val entries: Series<ConfixDocStoreEntry>
        get() = byId.size j { i -> byId.values.toList()[i] }

    fun put(id: String, doc: ConfixDoc, rev: String? = null): ConfixDocStoreEntry? {
        require(id.isNotEmpty()) { "_id required" }

        val existing = byId[id]
        return if (existing == null) {
            val newRev = genRev()
            val entry = ConfixDocStoreEntry(id, newRev, doc)
            byId[id] = entry
            byRev[newRev] = entry
            seq++
            wal?.append(id, newRev, doc)
            entry
        } else {
            if (rev != null && existing.rev != rev) {
                return null // conflict
            }
            val newRev = genRev()
            val newEntry = existing.copy(rev = newRev, doc = doc, timestamp = System.currentTimeMillis())
            byRev.remove(existing.rev)
            byId[id] = newEntry
            byRev[newRev] = newEntry
            seq++
            wal?.append(id, newRev, doc)
            newEntry
        }
    }

    fun put(id: String, text: String, rev: String? = null): ConfixDocStoreEntry? =
        put(id, confixDoc(text), rev)

    fun delete(id: String, rev: String): Boolean {
        val existing = byId[id] ?: return false
        if (existing.rev != rev) return false
        byRev.remove(existing.rev)
        byId.remove(id)
        return true
    }

    fun filter(pred: (ConfixDocStoreEntry) -> Boolean): Series<ConfixDocStoreEntry> {
        val matches = byId.values.filter(pred)
        return matches.size j { i -> matches[i] }
    }

    fun byIdPrefix(prefix: String): Series<ConfixDocStoreEntry> =
        filter { it.id.startsWith(prefix) }

    fun toBlackboardEntries(): Series<BlackBoardEntry> =
        byId.size j { i ->
            val entry = byId.values.toList()[i]
            BlackBoardEntry(entry.doc, entry.role, entry.timestamp, entry.id)
        }

    fun byRole(role: ConfixRole): Series<ConfixDocStoreEntry> =
        filter { it.role == role }

    private fun genRev(): String = when (revPolicy) {
        RevPolicy.UUID -> "uuid-${seq}"
        RevPolicy.TIMESTAMP -> "${System.currentTimeMillis()}-$seq"
        RevPolicy.SEQUENTIAL -> "$seq-uuid"
    }

    override fun toString(): String =
        "ConfixDocStore(size=${byId.size}, seq=$seq)"
}

object ConfixDocStoreFactory {
    fun create(): ConfixDocStore = ConfixDocStore()
    fun createTimestamped(): ConfixDocStore = ConfixDocStore(revPolicy = RevPolicy.TIMESTAMP)
    fun createSequential(): ConfixDocStore = ConfixDocStore(revPolicy = RevPolicy.SEQUENTIAL)
}
