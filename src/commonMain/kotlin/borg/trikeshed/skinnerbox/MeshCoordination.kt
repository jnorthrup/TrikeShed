package borg.trikeshed.skinnerbox

import borg.trikeshed.job.CanonicalCbor
import borg.trikeshed.job.ContentId
import kotlin.random.Random
import kotlin.time.Clock

/** A fenced lock grant. [fence] grows with every new grant of [name] and replays with the row, so it never repeats. */
data class MeshLockGrant(val name: String, val holder: String, val fence: Long, val leaseUntilMillis: Long)

/** A semaphore answer: a permit and its fence, or the caller's place in the FIFO lobby. */
sealed class MeshSeat {
    abstract val name: String
    abstract val holder: String

    data class Granted(override val name: String, override val holder: String, val fence: Long,
        val leaseUntilMillis: Long) : MeshSeat()

    /** [position] counts from 1. [inceptionMillis] is when the earliest live waiter entered. */
    data class Waiting(override val name: String, override val holder: String, val position: Int,
        val enteredMillis: Long, val inceptionMillis: Long) : MeshSeat()
}

/** The head kept moving under every compare-and-set attempt. */
class MeshContention(val name: String) : IllegalStateException("Coordination row $name kept changing; retry")

/**
 * Hazelcast's FencedLock and ISemaphore over one namespace's rows. Each primitive is one row in [COLLECTION] that the
 * namespace owner serializes: a write names the head it read (`expectedRevision`), so a write based on a stale read is
 * refused and read again. [submit] is [MeshNode.submit], whose mutation answers only after three custody proofs, and the
 * row replays when the owner restarts, so a granted fence is never issued twice. Leases are judged by the caller's clock;
 * the fence, checked where the effect happens, is what makes a holder whose lease ran out harmless.
 */
class MeshCoordination(
    val namespace: String,
    private val submit: suspend (MeshTask) -> ByteArray,
    private val nowMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val attempts: Int = 8,
) {
    companion object {
        const val COLLECTION = "coordination"
        const val MAX_HOLDERS = 64
        const val MAX_WAITERS = 128
        const val MAX_LEASE_MILLIS = 24L * 3600 * 1000

        /** Row key of a primitive: its kind and the digest of its name, inside the persistence token grammar. */
        fun key(kind: String, name: String): String = kind + "-" + ContentId.of(name.encodeToByteArray()).hex
    }

    init {
        meshSubnet(namespace)
        require(attempts in 1..64) { "Coordination attempt bound" }
    }

    private class Head(val revision: String?, val value: Map<String, Any?>)

    /** Lock [name] for [holder] unless another holder's lease is live; the same holder renews and keeps its fence. */
    suspend fun tryLock(name: String, holder: String, leaseMillis: Long): MeshLockGrant? {
        check(name, holder, leaseMillis)
        val key = key("lock", name)
        repeat(attempts) {
            val head = read(key)
            val now = nowMillis()
            val current = head.value["holder"] as? String
            val fence = head.value["fence"] as? Long ?: 0L
            val live = current != null && (head.value["leaseUntilMs"] as? Long ?: 0L) > now
            if (live && current != holder) return null
            val granted = if (live) fence else fence + 1
            val until = now + leaseMillis
            if (write(key, head, mapOf("name" to name, "holder" to holder, "leaseUntilMs" to until, "fence" to granted)))
                return MeshLockGrant(name, holder, granted, until)
        }
        throw MeshContention(name)
    }

    /** Release [grant]; false when the lock already moved on (its lease ran out and another holder took it). */
    suspend fun unlock(grant: MeshLockGrant): Boolean {
        val key = key("lock", grant.name)
        repeat(attempts) {
            val head = read(key)
            if (head.value["holder"] != grant.holder || head.value["fence"] != grant.fence) return false
            if (write(key, head, mapOf("name" to grant.name, "holder" to null, "leaseUntilMs" to 0L, "fence" to grant.fence)))
                return true
        }
        throw MeshContention(grant.name)
    }

    /**
     * A permit of [name] (capacity [permits]) for [holder], or its place in line. Waiters are admitted in arrival order;
     * an expired lease frees its permit, and a waiter that stops asking drops out when its own lease lapses. Asking again
     * renews a permit or a place in line.
     */
    suspend fun acquire(name: String, holder: String, permits: Int, leaseMillis: Long): MeshSeat {
        check(name, holder, leaseMillis)
        require(permits in 1..MAX_HOLDERS) { "Semaphore permits must be 1 through $MAX_HOLDERS" }
        val key = key("semaphore", name)
        repeat(attempts) {
            val head = read(key)
            val now = nowMillis()
            val until = now + leaseMillis
            val fence = head.value["fence"] as? Long ?: 0L
            val holders = entries(head.value["holders"]).filter { (it["leaseUntilMs"] as? Long ?: 0L) > now }.toMutableList()
            val waiters = entries(head.value["waiters"]).filter { (it["leaseUntilMs"] as? Long ?: 0L) > now }.toMutableList()
            fun row(nextFence: Long) = mapOf("name" to name, "permits" to permits.toLong(), "fence" to nextFence,
                "holders" to holders, "waiters" to waiters)
            val held = holders.indexOfFirst { it["holder"] == holder }
            if (held >= 0) {
                val granted = holders[held]["fence"] as? Long ?: error("Semaphore holder fence")
                holders[held] = mapOf("holder" to holder, "fence" to granted, "leaseUntilMs" to until)
                if (write(key, head, row(fence))) return MeshSeat.Granted(name, holder, granted, until)
                return@repeat
            }
            val queued = waiters.indexOfFirst { it["holder"] == holder }
            val ahead = if (queued >= 0) queued else waiters.size
            if (holders.size + ahead < permits) {
                if (queued >= 0) waiters.removeAt(queued)
                holders.add(mapOf("holder" to holder, "fence" to fence + 1, "leaseUntilMs" to until))
                if (write(key, head, row(fence + 1))) return MeshSeat.Granted(name, holder, fence + 1, until)
                return@repeat
            }
            val entered = if (queued >= 0) waiters[queued]["enteredMs"] as? Long ?: now else now
            val entry = mapOf("holder" to holder, "enteredMs" to entered, "leaseUntilMs" to until)
            if (queued >= 0) waiters[queued] = entry
            else {
                if (waiters.size >= MAX_WAITERS) throw MeshOverloaded()
                waiters.add(entry)
            }
            val inception = waiters.minOf { it["enteredMs"] as? Long ?: now }
            if (write(key, head, row(fence))) return MeshSeat.Waiting(name, holder, ahead + 1, entered, inception)
        }
        throw MeshContention(name)
    }

    /** Give back [holder]'s permit or its place in line; false when it held neither. */
    suspend fun release(name: String, holder: String): Boolean {
        val key = key("semaphore", name)
        repeat(attempts) {
            val head = read(key)
            val holders = entries(head.value["holders"])
            val waiters = entries(head.value["waiters"])
            val keptHolders = holders.filter { it["holder"] != holder }
            val keptWaiters = waiters.filter { it["holder"] != holder }
            if (keptHolders.size == holders.size && keptWaiters.size == waiters.size) return false
            if (write(key, head, head.value + mapOf("holders" to keptHolders, "waiters" to keptWaiters))) return true
        }
        throw MeshContention(name)
    }

    private fun check(name: String, holder: String, leaseMillis: Long) {
        require(name.isNotEmpty() && name.length <= 1024) { "Coordination name length" }
        require(holder.isNotEmpty() && holder.length <= 256) { "Coordination holder length" }
        require(leaseMillis in 1..MAX_LEASE_MILLIS) { "Coordination lease bound" }
    }

    @Suppress("UNCHECKED_CAST")
    private fun entries(value: Any?): List<Map<String, Any?>> =
        (value as? List<*>).orEmpty().map { it as? Map<String, Any?> ?: error("Coordination entry must be an object") }

    @Suppress("UNCHECKED_CAST")
    private suspend fun read(key: String): Head = try {
        val row = MeshPersistenceCodec.decode(submit(MeshTask(namespace, "row.get",
            CanonicalCbor.encodeMap(mapOf("collection" to COLLECTION, "key" to key)))))
        val bytes = row["value"] as? ByteArray ?: error("Coordination row value")
        val value = TwinJson.parse(bytes, MeshPersistenceCodec.ROW_BYTES) as? Map<String, Any?>
            ?: error("Coordination row must be an object")
        Head(row["revision"] as? String ?: error("Coordination row revision"), value)
    } catch (absent: MeshPersistenceException) {
        if (absent.reason != MeshPersistenceError.NOT_FOUND) throw absent
        Head(null, emptyMap())
    }

    /** One compare-and-set write; false when another writer moved the head first. */
    private suspend fun write(key: String, head: Head, value: Map<String, Any?>): Boolean = try {
        val request = "c-" + Random.nextLong().toULong().toString(16) + Random.nextLong().toULong().toString(16)
        submit(MeshTask(namespace, "row.put", CanonicalCbor.encodeMap(mapOf(
            "requestId" to request, "collection" to COLLECTION, "key" to key, "expectedRevision" to head.revision,
            "value" to TwinJson.encode(value, MeshPersistenceCodec.ROW_BYTES)))))
        true
    } catch (conflict: MeshPersistenceException) {
        if (conflict.reason != MeshPersistenceError.CONFLICT) throw conflict
        false
    }
}
